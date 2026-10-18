package dev.t1m3.qplayer.android.playback;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

import dev.t1m3.qplayer.audio.AudioBackend;
import dev.t1m3.qplayer.audio.AudioOutputDevice;
import dev.t1m3.qplayer.audio.AudioOutputStatus;
import dev.t1m3.qplayer.audio.HifiCapabilities;
import dev.t1m3.qplayer.audio.HifiOutputControl;
import dev.t1m3.qplayer.audio.HifiOutputMode;
import dev.t1m3.qplayer.i18n.I18n;
import dev.t1m3.qplayer.util.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * {@link AudioBackend} over {@code android.media.MediaPlayer}. MediaPlayer
 * decodes local files and {@code http(s)} streams natively (mp3/flac/m4a/ogg),
 * so netease CDN urls and local tracks share one path — no SPI decoders, no
 * {@code javax.sound} (which Android lacks).
 *
 * <p>Prepared asynchronously: {@link #play} kicks off {@code prepareAsync} and
 * starts on the prepared callback, honoring the requested start offset.
 *
 * <p>It also answers the read-only half of {@link HifiOutputControl}: which
 * output routes exist, what the framework is doing with the current source, and
 * whether a bit-perfect stream is even possible on this device. Playback itself
 * still goes through MediaPlayer either way — the direct-output backend that
 * would act on the four setters is a separate, later piece of work, so here they
 * only record what the user asked for.
 */
public final class AndroidAudioBackend implements AudioBackend, HifiOutputControl {

    private MediaPlayer player;
    private String source;
    private long pendingSeekMs;
    private boolean wantPlay;
    private float volume = 0.8f;
    private boolean prepared;
    private Runnable onComplete;
    private Runnable onStarted;
    private Runnable onPaused;
    private Runnable onResumed;
    private Runnable onError;

    // Audio focus: a full loss (another player, screen-off) and a transient duck
    // are both ignored; only a real interruption (a call) pauses us. See
    // onFocusChange.
    private final AudioManager audioManager;
    private final Context appContext;
    private AudioFocusRequest focusRequest;
    private boolean hasFocus;
    private boolean resumeOnGain;
    private boolean buffering;
    private String cachePendingPath;
    private String cacheCompletePath;
    private WifiManager.WifiLock wifiLock;
    private final Handler stallHandler = new Handler(Looper.getMainLooper());
    private final Runnable stallCheck = this::checkStall;

    // --- HiFi output control ----------------------------------------------
    /** Guarded by {@link #probeLock}: format of {@link #probedSource}. */
    private final Object probeLock = new Object();
    private String probedSource;
    private String probedEncoding = "";
    private int probedSampleRate;
    private int probedBitDepth;

    public AndroidAudioBackend(Context ctx) {
        appContext = ctx.getApplicationContext();
        audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
        WifiManager wifi = (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
        if (wifi != null) {
            wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "qplayer:stream");
            wifiLock.setReferenceCounted(false);
        }
    }

    @Override
    public synchronized void play(String src, long startMs) {
        play(src, Collections.emptyMap(), startMs);
    }

    @Override
    public synchronized void play(String src, Map<String, String> headers, long startMs) {
        if (src == null || src.isEmpty()) return;
        releasePlayer();
        source = src;
        pendingSeekMs = Math.max(0L, startMs);
        wantPlay = true;
        prepared = false;
        requestFocus();

        MediaPlayer mp = new MediaPlayer();
        // Network-backed tracks still need the CPU while the screen is off. Without
        // MediaPlayer's partial wake lock some OEMs suspend the streaming/decoder
        // path, report a MEDIA_ERROR_* and make PlayerController skip to the next
        // track even though the current song has not actually reached its end.
        mp.setWakeMode(appContext, PowerManager.PARTIAL_WAKE_LOCK);
        mp.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build());
        mp.setOnPreparedListener(this::onPrepared);
        mp.setOnCompletionListener(p -> onCompleted(p));
        mp.setOnErrorListener(this::onPlayerError);
        mp.setOnInfoListener((p, what, extra) -> {
            if (p != player) return false;
            if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                buffering = true;
                tryPlayCompleteCache();
            } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) {
                buffering = false;
            }
            return false;
        });
        player = mp;
        try {
            Logger.info("MediaPlayer: setDataSource + prepareAsync");
            setDataSource(mp, src, headers);
            mp.prepareAsync();
        } catch (IOException | IllegalStateException e) {
            Logger.error("MediaPlayer setDataSource failed: {}", e.getMessage());
            releasePlayer();
        }
    }

    /** A {@code content://} URI must be opened via the {@code (Context, Uri)}
     *  overload — the {@code String} overload has no calling context to resolve it
     *  and prepareAsync then fails async with extra=MEDIA_ERROR_SYSTEM (0x80000000).
     *  http(s) urls and plain file paths take the string overload. */
    private void setDataSource(MediaPlayer mp, String src, Map<String, String> headers) throws IOException {
        if (src.startsWith("content://")) {
            mp.setDataSource(appContext, Uri.parse(src));
        } else if ((src.startsWith("http://") || src.startsWith("https://"))
                && headers != null && !headers.isEmpty()) {
            mp.setDataSource(appContext, Uri.parse(src), headers);
        } else {
            mp.setDataSource(src);
        }
    }

    private synchronized void onPrepared(MediaPlayer preparedPlayer) {
        // releasePlayer() cannot prevent a callback that was already queued on the
        // media thread. Never let an old source start, publish onStarted, or apply its
        // state to the replacement MediaPlayer after a rapid track switch.
        if (player != preparedPlayer) return;
        prepared = true;
        Logger.info("MediaPlayer: prepared, duration={}ms", preparedPlayer.getDuration());
        applyVolume();
        if (pendingSeekMs > 0L) {
            preparedPlayer.seekTo(pendingSeekMs, MediaPlayer.SEEK_CLOSEST);
        }
        if (wantPlay) {
            preparedPlayer.start();
            holdWifiLock(source);
            scheduleStallCheck();
            Runnable cb = onStarted;
            if (cb != null) cb.run();
        }
    }

    private synchronized void onCompleted(MediaPlayer completedPlayer) {
        if (player != completedPlayer) return;
        Logger.info("MediaPlayer: completed");
        fire(onComplete);
    }

    private synchronized boolean onPlayerError(MediaPlayer failedPlayer, int what, int extra) {
        if (player != failedPlayer) return true;
        Logger.error("MediaPlayer error: what={} extra={}", what, extra);
        fire(onError);
        // PlayerController's error path retries stale URLs once and advances when
        // that fails. Firing completion too would race a second auto-advance against
        // that recovery and could replace the retried track.
        return true;
    }

    @Override
    public synchronized void pause() {
        wantPlay = false;
        // A user pause cancels a pending call-resume: a call that ends later must
        // not start the music again by itself.
        resumeOnGain = false;
        focusRetryAttempts = 0;
        callConfirmPending = false;
        stallHandler.removeCallbacks(focusCallRetry);
        stallHandler.removeCallbacks(callConfirm);
        stallHandler.removeCallbacks(stallCheck);
        releaseWifiLock();
        if (player != null && prepared && player.isPlaying()) {
            player.pause();
        }
    }

    @Override
    public synchronized void resume() {
        wantPlay = true;
        requestFocus();
        if (player != null && prepared) {
            try {
                if (!player.isPlaying()) player.start();
            } catch (IllegalStateException ignored) {
            }
            holdWifiLock(source);
            scheduleStallCheck();
        }
    }

    @Override
    public synchronized boolean isPlaying() {
        return player != null && prepared && player.isPlaying();
    }

    @Override
    public synchronized void seek(long ms) {
        long target = Math.max(0L, ms);
        if (player != null && prepared) {
            // SEEK_CLOSEST lands on the exact frame instead of the previous sync frame:
            // a basic seekTo undershoots by up to a keyframe interval, which made a
            // lyric tap (and the progress bar) land just before the target so the
            // PREVIOUS line highlighted.
            player.seekTo(target, MediaPlayer.SEEK_CLOSEST);
        } else {
            pendingSeekMs = target;
        }
    }

    @Override
    public synchronized long position() {
        if (player != null && prepared) {
            try {
                return player.getCurrentPosition();
            } catch (IllegalStateException e) {
                return 0L;
            }
        }
        return 0L;
    }

    @Override
    public synchronized long duration() {
        if (player != null && prepared) {
            try {
                int d = player.getDuration();
                return d > 0 ? d : 0L;
            } catch (IllegalStateException e) {
                return 0L;
            }
        }
        return 0L;
    }

    @Override
    public synchronized void setVolume(float v) {
        volume = Math.max(0f, Math.min(1f, v));
        applyVolume();
    }

    private void applyVolume() {
        if (player != null && prepared) {
            player.setVolume(volume, volume);
        }
    }

    @Override
    public synchronized void setOnStarted(Runnable callback) {
        this.onStarted = callback;
    }

    @Override
    public synchronized void setOnPaused(Runnable callback) {
        this.onPaused = callback;
    }

    @Override
    public synchronized void setOnResumed(Runnable callback) {
        this.onResumed = callback;
    }

    @Override
    public synchronized void setOnError(Runnable callback) {
        this.onError = callback;
    }

    @Override
    public synchronized void setOnComplete(Runnable callback) {
        this.onComplete = callback;
    }

    @Override
    public synchronized void bindGrowingCache(String pendingPath, String completePath, long durationMs) {
        this.cachePendingPath = pendingPath;
        this.cacheCompletePath = completePath;
    }

    @Override
    public synchronized void notifyCacheComplete() {
        tryPlayCompleteCache();
    }

    /** @return true if playback was restarted from the finished cache file. */
    private boolean tryPlayCompleteCache() {
        String path = cacheCompletePath;
        if (path == null || path.equals(source)) return false;
        java.io.File file = new java.io.File(path);
        if (!file.isFile() || file.length() == 0) return false;
        long pos = position();
        Logger.info("MediaPlayer: switching to complete cache at {}ms", pos);
        play(path, pos);
        return true;
    }

    @Override
    public synchronized void release() {
        stallHandler.removeCallbacks(stallCheck);
        releaseWifiLock();
        releasePlayer();
        abandonFocus();
    }

    // --- HifiOutputControl -------------------------------------------------

    @Override
    public List<AudioOutputDevice> outputDevices() {
        List<AudioOutputDevice> out = new ArrayList<>();
        // Automatic is always first and always available, even with no USB DAC
        // attached — it is what the picker falls back to.
        AudioDeviceInfo active = AndroidHifiProbe.activeOutput(audioManager);
        String activeId = active != null ? String.valueOf(active.getId()) : "";
        out.add(new AudioOutputDevice("", I18n.tr("settings.hifiOutputDevice.auto"),
                AndroidHifiProbe.routeTypeOf(active),
                AndroidHifiProbe.bitPerfectCapable(active)));
        for (AudioDeviceInfo device : AndroidHifiProbe.outputs(audioManager)) {
            String routeType = AndroidHifiProbe.routeTypeOf(device);
            if (routeType == null) continue;   // remote submix, HDMI, no output route at all
            String id = String.valueOf(device.getId());
            if (id.equals(activeId)) continue; // already offered as the automatic entry
            out.add(new AudioOutputDevice(id, AndroidHifiProbe.productName(device), routeType,
                    AndroidHifiProbe.bitPerfectCapable(device)));
        }
        return out;
    }

    @Override
    public AudioOutputStatus outputStatus() {
        AudioDeviceInfo active = AndroidHifiProbe.activeOutput(audioManager);
        probeSourceFormat(currentSource());
        String sourceEncoding;
        int sourceRate;
        int sourceDepth;
        synchronized (probeLock) {
            sourceEncoding = probedEncoding;
            sourceRate = probedSampleRate;
            sourceDepth = probedBitDepth;
        }
        int outputRate = AndroidHifiProbe.mixRate(audioManager);
        int outputDepth = AndroidHifiProbe.MIXER_BIT_DEPTH;
        // The USB exclusive driver is what can be bit-perfect; MediaPlayer → AudioFlinger
        // never is, but its availability still tells the chain card what this device
        // could do with the switch on.
        boolean bitPerfectSupported = !AndroidHifiProbe.usbOutputs(audioManager).isEmpty();
        // MediaPlayer → AudioFlinger always re-encodes to the framework's PCM
        // format, so the source counts as resampled whenever it had to be
        // converted in rate or in depth.
        boolean resampled = (sourceRate > 0 && sourceRate != outputRate)
                || sourceDepth > outputDepth;
        return new AudioOutputStatus(
                active != null ? String.valueOf(active.getId()) : "",
                active != null ? AndroidHifiProbe.productName(active)
                        : I18n.tr("settings.hifiOutputDevice.auto"),
                AndroidHifiProbe.routeTypeOf(active),
                sourceEncoding, sourceRate, sourceDepth,
                "PCM", outputRate, outputDepth,
                resampled, bitPerfectSupported, false,
                bitPerfectSupported ? "" : I18n.tr("settings.hifiChain.reason.usbRequired"),
                HifiOutputMode.SYSTEM, HifiOutputMode.SYSTEM,
                AudioOutputStatus.EXCLUSIVE_NONE, "", "software");
    }

    @Override
    public HifiCapabilities capabilities() {
        return AndroidHifiProbe.capabilities(appContext, audioManager);
    }

    // The setters below are inert on purpose: this backend keeps playing through
    // MediaPlayer, so acting on them is the direct-output backend's job. Recording
    // them here would be state nothing reads yet.

    @Override
    public void setHifiEnabled(boolean on) {
    }

    @Override
    public void setFollowSampleRate(boolean on) {
    }

    @Override
    public void selectOutputDevice(String deviceId) {
    }

    /** Snapshot of the source currently loaded, for the format probe. */
    private synchronized String currentSource() {
        return source;
    }

    /**
     * Read the current source's own format, cached per source. Only local files
     * and {@code content://} uris are read: probing an {@code http(s)} stream
     * would mean a second network connection for what is only a readout, and the
     * source format of a stream we did not open is genuinely unknown.
     */
    private void probeSourceFormat(String src) {
        synchronized (probeLock) {
            if (src == null || src.isEmpty()) {
                probedSource = null;
                probedEncoding = "";
                probedSampleRate = 0;
                probedBitDepth = 0;
                return;
            }
            if (src.equals(probedSource)) return;
            probedSource = src;
            probedEncoding = "";
            probedSampleRate = 0;
            probedBitDepth = 0;
            if (src.startsWith("http://") || src.startsWith("https://")) return;
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                if (src.startsWith("content://")) {
                    retriever.setDataSource(appContext, Uri.parse(src));
                } else {
                    retriever.setDataSource(src);
                }
                probedEncoding = AndroidHifiProbe.encodingLabel(
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE));
                // Rate and depth arrived with Android 12; older platforms can
                // only be told the codec, which is what an empty pair means.
                if (Build.VERSION.SDK_INT >= 31) {
                    probedSampleRate = AndroidHifiProbe.intMetadata(retriever,
                            MediaMetadataRetriever.METADATA_KEY_SAMPLERATE);
                    probedBitDepth = AndroidHifiProbe.intMetadata(retriever,
                            MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE);
                }
            } catch (Throwable e) {
                Logger.warn("hifi source probe failed: {}", e.toString());
            } finally {
                try {
                    retriever.release();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // --- Audio focus ------------------------------------------------------

    private void requestFocus() {
        if (hasFocus || audioManager == null) return;
        AudioFocusRequest req = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setOnAudioFocusChangeListener(this::onFocusChange)
                .setWillPauseWhenDucked(false)
                .build();
        focusRequest = req;
        hasFocus = audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abandonFocus() {
        stallHandler.removeCallbacks(focusCallRetry);
        callConfirmPending = false;
        stallHandler.removeCallbacks(callConfirm);
        if (audioManager != null && focusRequest != null) {
            audioManager.abandonAudioFocusRequest(focusRequest);
        }
        focusRequest = null;
        hasFocus = false;
        resumeOnGain = false;
    }

    /** After a TRANSIENT loss (a call) we wait for GAIN; OEM builds do not always
     *  send it, so poll — while we still intend to play and the phone is not in a
     *  call, resume. Capped only as hygiene: a long call is guarded by the in-call
     *  check, and a user pause cancels the poll outright. */
    private static final long FOCUS_RETRY_INTERVAL_MS = 2000L;
    private static final int FOCUS_RETRY_LIMIT = 300;
    /** How long a focus loss is given to prove itself a call before being ignored. */
    private static final long CALL_CONFIRM_MS = 600L;
    private int focusRetryAttempts;
    private boolean callConfirmPending;
    private final Runnable focusCallRetry = this::retryTransientLoss;
    private final Runnable callConfirm = this::confirmCallPause;

    private synchronized void scheduleFocusCallRetry() {
        stallHandler.removeCallbacks(focusCallRetry);
        stallHandler.postDelayed(focusCallRetry, FOCUS_RETRY_INTERVAL_MS);
    }

    private synchronized void retryTransientLoss() {
        if (!resumeOnGain || audioManager == null) return;
        if (focusRetryAttempts >= FOCUS_RETRY_LIMIT) return;
        focusRetryAttempts++;
        if (inCall()) {
            scheduleFocusCallRetry();
            return;
        }
        // Take the focus back if the ROM offers it, but resume either way: waiting
        // for a grant is what left playback stranded when the ROM refuses one.
        requestFocus();
        resumeFromFocus();
    }

    private boolean inCall() {
        try {
            int mode = audioManager.getMode();
            return mode == AudioManager.MODE_IN_CALL
                    || mode == AudioManager.MODE_IN_COMMUNICATION;
        } catch (Throwable e) {
            return false;
        }
    }

    private void resumeFromFocus() {
        resumeOnGain = false;
        wantPlay = true;
        if (player != null && prepared) {
            try {
                if (!player.isPlaying()) player.start();
            } catch (IllegalStateException ignored) {
            }
            holdWifiLock(source);
            scheduleStallCheck();
            fire(onResumed);
        }
    }

    /** Focus policy: a real interruption is the only thing that may pause us. A
     *  full LOSS (another player starting, the screen going off) and a transient
     *  duck are otherwise no-ops — nothing another app plays may stop or quiet our
     *  music. "Interruption" is decided by the phone actually being in a call, not
     *  by which event name the ROM chose. */
    private synchronized void onFocusChange(int change) {
        Logger.info("focus event {} (system)", focusEventName(change));
        switch (change) {
            case AudioManager.AUDIOFOCUS_LOSS:
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                if (inCall()) {
                    pauseForCall();
                } else {
                    // The audio mode can flip to a call a beat after the focus
                    // loss; a phantom loss must never pause us.
                    callConfirmPending = true;
                    stallHandler.removeCallbacks(callConfirm);
                    stallHandler.postDelayed(callConfirm, CALL_CONFIRM_MS);
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                // No-op: notification / navigation prompts must not quiet us.
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                callConfirmPending = false;
                stallHandler.removeCallbacks(callConfirm);
                stallHandler.removeCallbacks(focusCallRetry);
                hasFocus = true;
                // Only an interruption pause is ours to end.
                if (resumeOnGain) resumeFromFocus();
                break;
            default:
                break;
        }
    }

    /** The one confirmed interruption: a call. Pause and wait for it to end. */
    private void pauseForCall() {
        callConfirmPending = false;
        stallHandler.removeCallbacks(callConfirm);
        hasFocus = false;
        if (player != null && prepared && player.isPlaying()) {
            player.pause();
            resumeOnGain = true;
            fire(onPaused);
        }
        if (resumeOnGain) {
            focusRetryAttempts = 0;
            scheduleFocusCallRetry();
        }
    }

    /** Runs a moment after an unrecognised focus loss: only a call that has become
     *  visible by now is allowed to pause us. */
    private synchronized void confirmCallPause() {
        if (!callConfirmPending) return;
        callConfirmPending = false;
        if (!inCall() || !(player != null && prepared && player.isPlaying())) return;
        Logger.info("focus loss turned out to be a call; pausing (system)");
        pauseForCall();
    }

    private static String focusEventName(int change) {
        switch (change) {
            case AudioManager.AUDIOFOCUS_LOSS: return "LOSS";
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT: return "LOSS_TRANSIENT";
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK: return "LOSS_TRANSIENT_CAN_DUCK";
            case AudioManager.AUDIOFOCUS_GAIN: return "GAIN";
            default: return String.valueOf(change);
        }
    }

    /** Diagnostic (`adb shell am broadcast -a dev.t1m3.qplayer.DEBUG --es cmd
     *  focusdump`): the focus state this backend currently holds. */
    @Override
    public synchronized String focusState() {
        int mode = -1;
        try {
            mode = audioManager != null ? audioManager.getMode() : -1;
        } catch (Throwable ignored) {
        }
        return "system hasFocus=" + hasFocus
                + " resumeOnGain=" + resumeOnGain
                + " attempts=" + focusRetryAttempts
                + " mode=" + mode
                + " playing=" + isPlaying();
    }

    private void holdWifiLock(String src) {
        if (wifiLock == null || src == null) return;
        if (!(src.startsWith("http://") || src.startsWith("https://"))) return;
        try {
            if (!wifiLock.isHeld()) wifiLock.acquire();
        } catch (Throwable ignored) {
        }
    }

    private void releaseWifiLock() {
        if (wifiLock == null) return;
        try {
            if (wifiLock.isHeld()) wifiLock.release();
        } catch (Throwable ignored) {
        }
    }

    private void scheduleStallCheck() {
        stallHandler.removeCallbacks(stallCheck);
        stallHandler.postDelayed(stallCheck, 2500);
    }

    private synchronized void checkStall() {
        if (!wantPlay || !prepared || player == null || buffering) return;
        boolean playing;
        try {
            playing = player.isPlaying();
        } catch (IllegalStateException e) {
            playing = false;
        }
        if (!playing && hasFocus) {
            if (tryPlayCompleteCache()) {
                // switched to the finished cache file
            } else {
                Logger.warn("MediaPlayer stalled while wantPlay; restarting");
                try {
                    player.start();
                } catch (IllegalStateException ignored) {
                }
            }
        }
        if (wantPlay) scheduleStallCheck();
    }

    private static void fire(Runnable r) {
        if (r != null) r.run();
    }

    private void releasePlayer() {
        if (player != null) {
            try {
                // Detach first so callbacks queued by reset/release cannot act on a
                // subsequently assigned player through the backend's shared fields.
                player.setOnPreparedListener(null);
                player.setOnCompletionListener(null);
                player.setOnErrorListener(null);
                player.setOnInfoListener(null);
                player.reset();
                player.release();
            } catch (Throwable ignored) {
            }
            player = null;
        }
        prepared = false;
        buffering = false;
    }
}
