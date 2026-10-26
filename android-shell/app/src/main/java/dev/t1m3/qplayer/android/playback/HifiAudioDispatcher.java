package dev.t1m3.qplayer.android.playback;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import dev.t1m3.qplayer.audio.AudioBackend;
import dev.t1m3.qplayer.audio.AudioOutputDevice;
import dev.t1m3.qplayer.audio.AudioOutputStatus;
import dev.t1m3.qplayer.audio.HifiCapabilities;
import dev.t1m3.qplayer.audio.HifiOutputControl;
import dev.t1m3.qplayer.audio.HifiOutputMode;
import dev.t1m3.qplayer.i18n.I18n;
import dev.t1m3.qplayer.util.Logger;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The one {@link AudioBackend} the app builds: it keeps the {@code MediaPlayer}
 * backend as the system path and adds the direct ones behind a switch, so the
 * controller, the playback service and the media session keep talking to a single
 * long-lived object while the strategy underneath changes.
 *
 * <p>Routing is decided per source, not at the moment the user flips the setting:
 * switching modes therefore never cuts the track that is playing. A mode that
 * cannot work here falls back to {@link HifiOutputMode#SYSTEM} and records why,
 * which the chain card shows — that is the only way this feature is allowed to
 * fail.
 *
 * <p>Delegation is total: every {@link AudioBackend} call goes to whichever
 * backend is currently active, and its callbacks are re-wired through here so a
 * strategy switch cannot leave the controller listening to a backend that is no
 * longer playing.
 */
public final class HifiAudioDispatcher implements AudioBackend, HifiOutputControl {

    private final Context appContext;
    private final AudioManager audioManager;
    private final AndroidAudioBackend system;

    private final Object lock = new Object();
    /** What is actually being used; the requested mode is simply
     *  {@code hifiEnabled ? EXCLUSIVE : SYSTEM}, there is no other choice. */
    private HifiOutputMode effectiveMode = HifiOutputMode.SYSTEM;
    private String fallbackReason = "";
    private String exclusiveState = AudioOutputStatus.EXCLUSIVE_NONE;
    private boolean hifiEnabled;
    private boolean followSampleRate = true;
    private int volumeMode = 0;
    /** Experimental keep-alive track; see {@link HifiOutputControl#setKeepAliveTrack}. */
    private volatile boolean keepAliveTrack;
    private String selectedDeviceId = "";

    /** The backend every call is currently routed to. */
    private volatile AudioBackend active;
    /** The direct backend, kept between tracks of the same kind so an exclusive
     *  stream is not torn down and reopened for every song. */
    private AndroidDirectAudioBackend direct;
    private int directKind = -1;
    /** True while the direct backend has actually started the current source. */
    private volatile boolean directStarted;
    /** Last request, so a failed direct mode can be replayed on the system path. */
    private volatile String lastSource = "";
    private volatile Map<String, String> lastHeaders = Collections.emptyMap();
    private volatile long lastStartMs;
    /** Modes that failed to open on this device; cleared when the route changes. */
    private volatile boolean exclusiveUnavailable;
    /** Receiver of the USB permission grant so the chain card refreshes the
     *  moment the user says yes. */
    private BroadcastReceiver usbPermissionReceiver;

    // --- callbacks, owned here and re-wired into every backend ---------------
    private Runnable onComplete;
    private Runnable onStarted;
    private Runnable onPaused;
    private Runnable onResumed;
    private Runnable onError;
    private Runnable onOutputLost;

    public HifiAudioDispatcher(Context context) {
        appContext = context.getApplicationContext();
        audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
        system = new AndroidAudioBackend(appContext);
        wire(system, false);
        active = system;
        registerRouteWatcher();
        registerUsbPermissionReceiver();
    }

    /**
     * The exclusive USB driver needs the user to grant the device once. The grant
     * itself changes nothing mid-track; it makes the next track (or replay) take
     * the driver, and refreshes the readout so the card stops saying "waiting".
     */
    private void registerUsbPermissionReceiver() {
        if (usbPermissionReceiver != null) return;
        usbPermissionReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                boolean granted = intent.getBooleanExtra(
                        android.hardware.usb.UsbManager.EXTRA_PERMISSION_GRANTED, false);
                Logger.info("usb permission grant received: {}", granted);
                if (granted) {
                    exclusiveUnavailable = false;
                }
                Runnable listener = deviceChangeListener;
                if (listener != null) listener.run();
            }
        };
        IntentFilter filter = new IntentFilter(AndroidUsbAudio.ACTION_PERMISSION);
        if (Build.VERSION.SDK_INT >= 33) {
            // EXPORTED on purpose: the result comes from the system service, not
            // from this app, and the action carries no sensitive payload.
            appContext.registerReceiver(usbPermissionReceiver, filter,
                    Context.RECEIVER_EXPORTED);
        } else {
            appContext.registerReceiver(usbPermissionReceiver, filter);
        }
    }

    private void unregisterUsbPermissionReceiver() {
        BroadcastReceiver receiver = usbPermissionReceiver;
        usbPermissionReceiver = null;
        if (receiver != null) {
            try {
                appContext.unregisterReceiver(receiver);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * A USB DAC appearing or disappearing changes both what is possible and where
     * the sound is going: capabilities are re-probed. A direct mode whose device
     * just went away is paused by the removal callback, not replayed on the phone
     * speaker — so this only re-probes and moves the NEXT user-initiated play onto
     * whatever route is left.
     */
    private void registerRouteWatcher() {
        if (audioManager == null) return;
        routeHandler = new Handler(Looper.getMainLooper());
        routeCallback = new AudioDeviceCallback() {
            @Override
            public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
                onRouteChanged();
            }

            @Override
            public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
                onOutputRemoved(removedDevices);
            }
        };
        audioManager.registerAudioDeviceCallback(routeCallback, routeHandler);
    }

    /** An output route was pulled. If it was the one the framework was routing to,
     *  confirm the loss before pausing: a ROM that re-evaluates its device set (an
     *  audio-policy pass on screen-off, for instance) reports removed+added in the
     *  same instant, and pausing on that would strand playback silent until the
     *  user pressed play again. Only a route that is still gone is a real removal. */
    private void onOutputRemoved(AudioDeviceInfo[] removedDevices) {
        int lost = removedActiveOutputRank(removedDevices);
        if (lost < Integer.MAX_VALUE) {
            Logger.info("output route removed while playing: {}; confirming",
                    describe(removedDevices));
            pendingLostRank = lost;
            routeHandler.removeCallbacks(routeLossConfirm);
            routeHandler.postDelayed(routeLossConfirm, ROUTE_LOSS_CONFIRM_MS);
        }
        onRouteChanged();
    }

    /** The rank of the output that was in use and has just been removed, or
     *  {@link Integer#MAX_VALUE} when the removed devices were not the route. The
     *  mixer picks USB over Bluetooth over the wired jack over the built-ins, so a
     *  removed device that outranks whatever is still connected must have been the
     *  routed one. */
    private int removedActiveOutputRank(AudioDeviceInfo[] removed) {
        if (removed == null || removed.length == 0) return Integer.MAX_VALUE;
        int nowRank = outputRank(AndroidHifiProbe.activeOutput(audioManager));
        int lost = Integer.MAX_VALUE;
        for (AudioDeviceInfo device : removed) {
            if (device == null || !device.isSink()) continue;
            int rank = outputRank(device);
            if (rank < nowRank && rank < lost) lost = rank;
        }
        return lost;
    }

    /** Runs a moment after a removal: pause only if the route really stayed away.
     *  An output at least as good as the lost one being connected again means this
     *  was a re-enumeration (or a re-plug) and playback continues untouched. */
    private void confirmRouteLoss() {
        int lost = pendingLostRank;
        pendingLostRank = Integer.MAX_VALUE;
        if (lost == Integer.MAX_VALUE) return;
        for (AudioDeviceInfo device : AndroidHifiProbe.outputs(audioManager)) {
            if (outputRank(device) <= lost) return;
        }
        // The route really is gone: point the next play at whatever is left, and
        // pause the current one rather than let the speaker take the stream over.
        Logger.info("output route (rank {}) is really gone", lost);
        synchronized (lock) {
            if (active == direct && direct != null) {
                markFallback(I18n.tr("settings.hifiChain.reason.deviceGone"));
            }
        }
        if (!isPlaying()) return;
        try {
            active.pause();
        } catch (Throwable ignored) {
        }
        fire(onOutputLost);
    }

    /** Finer than the picker's {@link AndroidHifiProbe#routeRank}: the wired jack
     *  outranks the built-in speaker, so an unplugged headphone is distinguishable
     *  from the speaker the framework fell back to. */
    private static int outputRank(AudioDeviceInfo device) {
        if (device == null) return Integer.MAX_VALUE;
        switch (device.getType()) {
            case AudioDeviceInfo.TYPE_USB_DEVICE:
            case AudioDeviceInfo.TYPE_USB_HEADSET:
            case AudioDeviceInfo.TYPE_USB_ACCESSORY:
                return 0;
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
            case AudioDeviceInfo.TYPE_BLE_SPEAKER:
                return 1;
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                return 2;
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE:
                return 3;
            default:
                return 4;
        }
    }

    private static String describe(AudioDeviceInfo[] devices) {
        if (devices == null) return "";
        StringBuilder sb = new StringBuilder();
        for (AudioDeviceInfo device : devices) {
            if (device == null || !device.isSink()) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(device.getType()).append(':').append(AndroidHifiProbe.productName(device));
        }
        return sb.toString();
    }

    /** Re-probe what the routes can do after a change. A route that merely appeared
     *  never touches what is playing; a route that disappeared is decided by
     *  {@link #confirmRouteLoss}, which is why this stays side-effect free. */
    private void onRouteChanged() {
        synchronized (lock) {
            // A different set of routes is a different set of capabilities: a mode
            // that failed on the old device deserves a fresh attempt.
            exclusiveUnavailable = false;
        }
        Runnable listener = deviceChangeListener;
        if (listener != null) listener.run();
    }

    private Handler routeHandler;
    private AudioDeviceCallback routeCallback;
    /** How long a removal is given to prove itself a re-plug before it counts. */
    private static final long ROUTE_LOSS_CONFIRM_MS = 450L;
    /** Rank of the output a pending removal is waiting on; MAX_VALUE = none. */
    private int pendingLostRank = Integer.MAX_VALUE;
    private final Runnable routeLossConfirm = this::confirmRouteLoss;
    private volatile Runnable deviceChangeListener;

    /** Host hook: told whenever the output routes change, so the chain readout can
     *  be re-probed without the user having to touch a setting. */
    public void setDeviceChangeListener(Runnable listener) {
        deviceChangeListener = listener;
    }

    /** Stop watching output routes; the host calls this when it releases the
     *  backend for good. */
    public void stopWatchingRoutes() {
        AudioDeviceCallback callback = routeCallback;
        routeCallback = null;
        pendingLostRank = Integer.MAX_VALUE;
        if (routeHandler != null) routeHandler.removeCallbacks(routeLossConfirm);
        if (audioManager != null && callback != null) {
            try {
                audioManager.unregisterAudioDeviceCallback(callback);
            } catch (Throwable ignored) {
            }
        }
    }

    /** Point a backend's callbacks at this dispatcher's, tagging its errors as
     *  direct-path failures when it is one of the direct backends. */
    private void wire(AudioBackend backend, boolean isDirect) {
        backend.setOnComplete(() -> fire(onComplete));
        backend.setOnStarted(() -> {
            if (isDirect) directPathStarted();
            fire(onStarted);
        });
        backend.setOnPaused(() -> fire(onPaused));
        backend.setOnResumed(() -> fire(onResumed));
        backend.setOnError(() -> {
            if (isDirect) handleDirectFailure();
            else fire(onError);
        });
    }

    private void directPathStarted() {
        directStarted = true;
        String note = direct != null ? direct.routingNote() : "";
        synchronized (lock) {
            effectiveMode = hifiEnabled ? HifiOutputMode.EXCLUSIVE : HifiOutputMode.SYSTEM;
            exclusiveState = direct != null ? direct.exclusiveState()
                    : AudioOutputStatus.EXCLUSIVE_NONE;
            fallbackReason = "";
        }
        // Whether the device really granted exclusivity is the whole point of this
        // mode and is otherwise invisible: a shared stream sounds exactly like the
        // system path.
        Logger.info("hifi direct started: kind={} exclusive={} rate={} note={}",
                directKind, direct != null ? direct.exclusiveState() : "none",
                direct != null ? direct.outputRate() : 0, note);
    }

    /**
     * A direct backend failed. Before the source ever started that means the mode
     * itself could not be opened (no bit-perfect attribute, device busy, no
     * exclusive stream available) — the track must still play, so the same request
     * is replayed on the system path with the reason kept for the card. After it
     * started, the failure is a real playback error and belongs to the controller.
     */
    private void handleDirectFailure() {
        AndroidDirectAudioBackend failed = direct;
        String reason = failed != null ? failed.lastError() : "";
        if (failed != null && failed.kind() == AndroidDirectAudioBackend.KIND_USB_EXCLUSIVE
                && AndroidUsbAudio.findAudioDevice(appContext) == null) {
            // The DAC is really gone: its writes now go nowhere, so pause instead
            // of replaying the track out of the phone's speaker. (The device
            // callback waits a moment to be sure; this is the authoritative check
            // for the one case where a gone route is knowable right now.)
            Logger.warn("usb dac disappeared ({}); pausing instead of falling back", reason);
            fire(onOutputLost);
            return;
        }
        if (directStarted) {
            long position = failed != null ? failed.position() : lastStartMs;
            Logger.warn("direct mode failed mid-track ({}); continuing on the system path", reason);
            synchronized (lock) {
                markFallback(reason.isEmpty()
                        ? I18n.tr("settings.hifiChain.reason.directFailed") : reason);
            }
            switchTo(system);
            playOn(system, lastSource, lastHeaders, position);
            return;
        }
        if (failed != null && failed.kind() == AndroidDirectAudioBackend.KIND_USB_EXCLUSIVE
                && failed.exclusiveState().equals(AudioOutputStatus.EXCLUSIVE_NONE)) {
            // The claim was refused (device busy / kernel driver busy): do not
            // hammer it for every track until the device or route changes.
            exclusiveUnavailable = true;
        }
        Logger.warn("direct mode could not open ({}); falling back to the system path", reason);
        synchronized (lock) {
            markFallback(reason.isEmpty()
                    ? I18n.tr("settings.hifiChain.reason.directFailed") : reason);
        }
        switchTo(system);
        playOn(system, lastSource, lastHeaders, lastStartMs);
    }

    /** Record why the requested mode is not being used. Caller holds `lock`. */
    private void markFallback(String reason) {
        effectiveMode = HifiOutputMode.SYSTEM;
        exclusiveState = AudioOutputStatus.EXCLUSIVE_NONE;
        fallbackReason = reason != null ? reason : "";
    }

    private void switchTo(AudioBackend backend) {
        if (active == backend) return;
        AudioBackend previous = active;
        if (previous != null && previous != backend) {
            try {
                previous.pause();
            } catch (Throwable ignored) {
            }
        }
        active = backend;
    }

    private void playOn(AudioBackend backend, String source, Map<String, String> headers,
                        long startMs) {
        if (source == null || source.isEmpty()) return;
        active = backend;
        backend.play(source, headers, startMs);
    }

    /** Pick the backend for the current switch state. */
    private AudioBackend chooseBackend() {
        if (!hifiEnabled) {
            synchronized (lock) {
                effectiveMode = HifiOutputMode.SYSTEM;
                exclusiveState = AudioOutputStatus.EXCLUSIVE_NONE;
                fallbackReason = "";
            }
            Logger.info("hifi route: exclusive off -> system(MediaPlayer)");
            return system;
        }
        // Exclusive output is the app's own USB driver: no AAudio, no mixer, no
        // OEM policy — but it needs the device handed over, so this branch is
        // USB-state-driven rather than capability-driven.
        AudioBackend chosen = system;
        String why;
        UsbDevice usbDevice = AndroidUsbAudio.findAudioDevice(appContext);
        if (usbDevice == null) {
            synchronized (lock) {
                markFallback(I18n.tr("settings.hifiChain.reason.usbRequired"));
            }
            why = "no usb audio device";
        } else if (!AndroidUsbAudio.hasPermission(appContext, usbDevice)) {
            // Ask once; this track plays on the system path, the next one (or a
            // replay) takes the driver once the user has allowed it.
            AndroidUsbAudio.requestPermission(appContext, usbDevice);
            synchronized (lock) {
                markFallback(I18n.tr("settings.hifiChain.reason.usbPermission"));
            }
            why = "waiting for the usb grant";
        } else if (exclusiveUnavailable) {
            synchronized (lock) {
                markFallback(I18n.tr("settings.hifiChain.reason.usbBusy"));
            }
            why = "exclusive already failed here";
        } else {
            chosen = direct(AndroidDirectAudioBackend.KIND_USB_EXCLUSIVE);
            why = "usb userspace driver";
        }
        // The routing decision is otherwise invisible: without this line a fallback
        // looks exactly like "the setting did nothing".
        Logger.info("hifi route: exclusive={} usb={} exclusiveFailed={} -> {} ({})",
                hifiEnabled, !AndroidHifiProbe.usbOutputs(audioManager).isEmpty(),
                exclusiveUnavailable,
                chosen == system ? "system(MediaPlayer)" : "direct kind=" + directKind, why);
        return chosen;
    }

    /** The direct backend of {@code kind}, reused across tracks. */
    private AndroidDirectAudioBackend direct(int kind) {
        AndroidDirectAudioBackend previous = null;
        AndroidDirectAudioBackend current;
        synchronized (lock) {
            if (direct == null || directKind != kind) {
                previous = direct;
                direct = new AndroidDirectAudioBackend(appContext, audioManager, kind);
                wire(direct, true);
                directKind = kind;
            }
            direct.setSelectedDeviceId(selectedDeviceId);
            direct.setFollowSampleRate(followSampleRate);
            direct.setVolumeMode(volumeMode);
            direct.setKeepAliveTrack(keepAliveTrack);
            current = direct;
        }
        if (previous != null) {
            // Released outside the lock: tearing a stream down waits for its decode
            // thread, and holding this lock that long would stall the controller.
            try {
                previous.release();
            } catch (Throwable ignored) {
            }
        }
        return current;
    }

    // --- AudioBackend ------------------------------------------------------

    @Override
    public void play(String source, long startMs) {
        play(source, Collections.<String, String>emptyMap(), startMs);
    }

    @Override
    public void play(String source, Map<String, String> headers, long startMs) {
        if (source == null || source.isEmpty()) return;
        lastSource = source;
        lastHeaders = headers != null ? headers : Collections.<String, String>emptyMap();
        lastStartMs = Math.max(0L, startMs);
        directStarted = false;
        AudioBackend target = chooseBackend();
        switchTo(target);
        target.play(source, lastHeaders, lastStartMs);
    }

    @Override
    public void pause() {
        active.pause();
    }

    @Override
    public void resume() {
        active.resume();
    }

    @Override
    public boolean isPlaying() {
        return active.isPlaying();
    }

    @Override
    public void seek(long ms) {
        active.seek(ms);
    }

    @Override
    public long position() {
        return active.position();
    }

    @Override
    public long duration() {
        return active.duration();
    }

    @Override
    public void setVolume(float volume) {
        active.setVolume(volume);
    }

    @Override
    public void setOnComplete(Runnable callback) {
        onComplete = callback;
    }

    @Override
    public void setOnStarted(Runnable callback) {
        onStarted = callback;
    }

    @Override
    public void setOnPaused(Runnable callback) {
        onPaused = callback;
    }

    @Override
    public void setOnResumed(Runnable callback) {
        onResumed = callback;
    }

    @Override
    public void setOnOutputLost(Runnable callback) {
        onOutputLost = callback;
    }

    @Override
    public void setOnError(Runnable callback) {
        onError = callback;
    }

    @Override
    public void bindGrowingCache(String pendingPath, String completePath, long durationMs) {
        active.bindGrowingCache(pendingPath, completePath, durationMs);
    }

    @Override
    public void notifyCacheComplete() {
        active.notifyCacheComplete();
    }

    @Override
    public void release() {
        stopWatchingRoutes();
        unregisterUsbPermissionReceiver();
        try {
            system.release();
        } catch (Throwable ignored) {
        }
        AndroidDirectAudioBackend directBackend = direct;
        direct = null;
        if (directBackend != null) {
            try {
                directBackend.release();
            } catch (Throwable ignored) {
            }
        }
        active = system;
        directKind = -1;
    }

    // --- HifiOutputControl -------------------------------------------------

    @Override
    public List<AudioOutputDevice> outputDevices() {
        return system.outputDevices();
    }

    @Override
    public boolean sourceFormatPending() {
        AndroidDirectAudioBackend directBackend = direct;
        if (active == directBackend && directBackend != null) {
            // The exclusive path reads the source's format while it opens the
            // stream, so there is never a separate read to report.
            return false;
        }
        return system.sourceFormatPending();
    }

    @Override
    public AudioOutputStatus outputStatus() {
        AndroidDirectAudioBackend directBackend = direct;
        AudioOutputStatus base = (active == directBackend && directBackend != null)
                ? directBackend.directStatus()
                : system.outputStatus();
        HifiOutputMode requested;
        HifiOutputMode effective;
        String exclusive;
        String fallback;
        synchronized (lock) {
            requested = hifiEnabled ? HifiOutputMode.EXCLUSIVE : HifiOutputMode.SYSTEM;
            effective = effectiveMode;
            exclusive = exclusiveState;
            fallback = fallbackReason;
        }
        // The USB driver is bit-perfect by construction while it is really
        // streaming: the bits that leave the app are the bits the DAC gets.
        boolean bitPerfectActive = effective == HifiOutputMode.EXCLUSIVE
                && AudioOutputStatus.EXCLUSIVE_FULL.equals(exclusive)
                && !base.resampled;
        AudioOutputStatus status = AudioOutputStatus.withRouting(base, requested,
                effective, exclusive, bitPerfectActive, fallback);
        return status;
    }

    @Override
    public HifiCapabilities capabilities() {
        return system.capabilities();
    }

    @Override
    public void setHifiEnabled(boolean on) {
        synchronized (lock) {
            hifiEnabled = on;
            effectiveMode = on ? HifiOutputMode.EXCLUSIVE : HifiOutputMode.SYSTEM;
            if (on) exclusiveUnavailable = false;
        }
    }

    /**
     * True while the USB driver really holds the DAC and is streaming. Only then
     * does the volume belong to us: the stream the framework's own volume keys
     * adjust is the one it mixes, and that is deliberately not this one — so a
     * host that wants the keys to work must route them here instead.
     */
    public boolean ownsOutputVolume() {
        AndroidDirectAudioBackend backend;
        boolean enabled;
        synchronized (lock) {
            enabled = hifiEnabled;
            backend = direct;
        }
        return enabled && backend != null && active == backend
                && AudioOutputStatus.EXCLUSIVE_FULL.equals(backend.exclusiveState());
    }

    /** The USB audio class in use, 1 or 2; 0 while no exclusive stream owns the
     *  output (framework fallback, paused/closed sink, no USB DAC). */
    @Override
    public int uacVersion() {
        AndroidDirectAudioBackend backend = direct;
        if (backend == null) return 0;
        synchronized (lock) {
            if (active != backend) return 0;
        }
        return backend.uacVersion();
    }

    @Override
    public void setFollowSampleRate(boolean on) {
        followSampleRate = on;
        AndroidDirectAudioBackend directBackend = direct;
        if (directBackend != null) directBackend.setFollowSampleRate(on);
    }

    /** Debug hook (`adb shell am broadcast -a dev.t1m3.qplayer.DEBUG --es cmd
     *  volprobe`): sweep the DAC's own volume down a ladder of dB values with a
     *  readback after each, logging where the device clamps or refuses. Blocking
     *  — callers must stay off the main thread. */
    public static void runVolumeProbe() {
        try {
            UacOutput.volProbe();
        } catch (Throwable e) {
            Logger.warn("volprobe failed: {}", e.toString());
        }
    }

    @Override
    public void setVolumeMode(int mode) {
        synchronized (lock) {
            volumeMode = mode;
        }
        AndroidDirectAudioBackend directBackend = direct;
        if (directBackend != null) directBackend.setVolumeMode(mode);
    }

    /** What the volume slider drives right now ("hardware"/"software"/""),
     *  re-read with the chain status so the volume readout labels itself. */
    public String volumeKind() {
        if (!hifiEnabled) return "";
        AndroidDirectAudioBackend directBackend = direct;
        return directBackend != null && active == directBackend
                ? directBackend.volumeKind() : "";
    }

    /** Live: true only while the exclusive stream is up and the slider drives a
     *  digital gain on it — that is the level the perceptual volume curve was
     *  built for. A hardware level is already a dB mapping of its own, so it is
     *  exempt; the framework path (MediaPlayer) and a fallback keep the host's own
     *  plain linear app volume, which the framework mixes on top of its stream
     *  volume. */
    @Override
    public boolean softwareVolumeActive() {
        if (!hifiEnabled) return false;
        AndroidDirectAudioBackend directBackend = direct;
        if (directBackend == null || active != directBackend) return false;
        return !directBackend.hardwareVolumeActive();
    }

    /** Same question as {@link #ownsOutputVolume()}: while the app's own stream is
     *  the one really playing, the slider drives the exclusive route's own level
     *  rather than the framework's. */
    @Override
    public boolean exclusiveLevelActive() {
        return ownsOutputVolume();
    }

    /** The focus state of whichever backend is actually playing, for the
     *  {@code focusdump} debug command. */
    @Override
    public String focusState() {
        AndroidDirectAudioBackend directBackend = direct;
        if (hifiEnabled && directBackend != null && active == directBackend) {
            return directBackend.focusState();
        }
        return system.focusState();
    }

    @Override
    public void setKeepAliveTrack(boolean on) {
        keepAliveTrack = on;
        AndroidDirectAudioBackend directBackend = direct;
        if (directBackend != null) directBackend.setKeepAliveTrack(on);
    }

    @Override
    public void selectOutputDevice(String deviceId) {
        String id = deviceId != null ? deviceId : "";
        synchronized (lock) {
            selectedDeviceId = id;
            AndroidDirectAudioBackend directBackend = direct;
            if (directBackend != null) directBackend.setSelectedDeviceId(id);
            // A different route is a different set of capabilities: a mode that
            // failed on the old device deserves a fresh attempt on the new one.
            exclusiveUnavailable = false;
        }
    }

    private void fire(Runnable callback) {
        if (callback != null) callback.run();
    }
}
