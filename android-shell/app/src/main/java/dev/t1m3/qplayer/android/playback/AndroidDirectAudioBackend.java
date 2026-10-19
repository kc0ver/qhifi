package dev.t1m3.qplayer.android.playback;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

import dev.t1m3.qplayer.audio.AudioBackend;
import dev.t1m3.qplayer.audio.AudioOutputStatus;
import dev.t1m3.qplayer.audio.HifiOutputMode;
import dev.t1m3.qplayer.i18n.I18n;
import dev.t1m3.qplayer.util.Logger;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Map;

/**
 * The direct output path: this backend opens the source itself
 * ({@code MediaExtractor} + {@code MediaCodec}), decodes it to PCM and writes
 * that into the app's own userspace USB driver ({@link UsbIsoSink}), which
 * claims the UAC interfaces from usbfs and sends the PCM straight to the DAC.
 *
 * <p>It deliberately carries the same robustness the {@code MediaPlayer} backend
 * has, because it replaces that backend for the whole duration of a track: audio
 * focus (including the OEM loss-on-screen-off behaviour), a Wi-Fi lock for
 * network sources, a wake lock so decoding continues with the screen off, a
 * watchdog that can break a stuck write, and the mid-track switch to the finished
 * cache file.
 *
 * <p>Everything it can fail at ends in {@code onError}, which the controller
 * treats exactly like a {@code MediaPlayer} error — the dispatcher then falls
 * back to the system path. Nothing here plays silence without saying so.
 */
final class AndroidDirectAudioBackend implements AudioBackend {

    /** The userspace USB driver (see {@link UsbIsoSink}): the UAC interfaces are
     *  claimed from usbfs and the PCM goes straight to the DAC. */
    static final int KIND_USB_EXCLUSIVE = 2;

    private static final long STALL_CHECK_MS = 2500L;
    private static final long STALL_TIMEOUT_MS = 3000L;
    private static final int MAX_RESTARTS_PER_SOURCE = 3;
    private static final long OPEN_PROBE_TIMEOUT_MS = 1500L;
    /** How far before a reopen's seam the cache file is seeked, so the decoder
     *  is already producing audio by the time it reaches it (the first samples
     *  are trimmed away, see {@link #drainOutput}). */
    private static final long REOPEN_SEEK_BACK_MS = 300L;

    private final Context appContext;
    private final AudioManager audioManager;
    private final WifiManager.WifiLock wifiLock;
    private final PowerManager.WakeLock wakeLock;
    private final Handler watchdogHandler = new Handler(Looper.getMainLooper());
    private final Runnable stallCheck = this::checkStall;
    private final PcmSink sink;
    private final int kind;

    /** Route to aim at, from the settings picker; "" means automatic. */
    private volatile String selectedDeviceId = "";
    /** "Follow the source's own rate" (the default). Kept as plumbing for the
     *  settings switch; the USB driver always runs at the source's rate, so on
     *  this backend it has no effect on the sink. */
    private volatile boolean followSampleRate = true;

    // --- request state: written by callers, read by the decoder under `lock` ---
    private final Object lock = new Object();
    private String requestedSource = "";
    private Map<String, String> requestedHeaders = Collections.emptyMap();
    private long wantedSerial;
    private boolean desiredPlaying;
    private long pendingSeekMs = -1L;
    /** PTS the cache-file reopen's decoder must be measured against: a seek lands
     *  on the sync sample at or BEFORE the target, so whatever comes first may be
     *  audio the ring already played. -1 when nothing is armed. */
    private long reopenTargetUs = -1L;
    private boolean pendingReopen;
    private long pendingReopenFromMs;
    private String pendingReopenSource = "";
    private boolean closed;

    // --- readouts, also under `lock` ---
    private long positionBaseMs;
    private long positionBaseFrames;
    private long durationMs;
    private boolean opened;
    private String outputEncoding = "";
    private int outputRate;
    private int outputChannels;
    private int outputBitDepth;
    private int sourceRate;
    private int sourceBitDepth;
    private String sourceEncoding = "";
    private boolean exclusive;
    private String lastError = "";

    /** Volume bookkeeping for the adoption handshake: {@code adoptedVolume01} is
     *  what the DAC actually plays at after the latest open (its own level, or a
     *  safe fallback), and {@code openGen} increments per open so the dispatcher
     *  reports it to the UI exactly once. */
    private int openGen;
    private float adoptedVolume01 = -1f;

    // --- watchdog state, main thread only ---
    private long lastProgressFrames = -1L;
    private long lastProgressAtMs;
    private int restarts;

    // --- callbacks ---
    private Runnable onComplete;
    private Runnable onStarted;
    private Runnable onPaused;
    private Runnable onResumed;
    private Runnable onError;

    private volatile Thread decodeThread;
    private volatile boolean decodeRunning;
    private String cacheCompletePath;
    private boolean playingStarted;
    /** Whether the loop has already told the sink to play for this source. */
    private boolean sinkPlaying;
    /** Set when a track reached its own end: the ring still holds its tail, so the
     *  next source must NOT flush it — the tail is the gapless hand-off. */
    private boolean tailCarriesOver;
    private int volumePercent = 80;
    private AudioFocusRequest focusRequest;
    private boolean hasFocus;
    private boolean resumeOnGain;

    AndroidDirectAudioBackend(Context context, AudioManager audioManager, int kind) {
        this.appContext = context.getApplicationContext();
        this.audioManager = audioManager;
        this.kind = kind;
        this.sink = new UsbIsoSink(appContext);
        WifiManager wifi = (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
        WifiManager.WifiLock wifiLockRef = wifi != null
                ? wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "qplayer:direct") : null;
        if (wifiLockRef != null) wifiLockRef.setReferenceCounted(false);
        wifiLock = wifiLockRef;
        PowerManager power = (PowerManager) appContext.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wake = power != null
                ? power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "qplayer:direct") : null;
        if (wake != null) wake.setReferenceCounted(false);
        wakeLock = wake;
    }

    void setSelectedDeviceId(String deviceId) {
        selectedDeviceId = deviceId != null ? deviceId : "";
    }

    void setFollowSampleRate(boolean on) {
        followSampleRate = on;
    }

    /** 0 = drive the DAC's own control (samples at full scale), 1 = hold the DAC
     *  at max and attenuate digitally. The sink applies it to the live stream. */
    void setVolumeMode(int mode) {
        if (sink instanceof UsbIsoSink) {
            ((UsbIsoSink) sink).setVolumeMode(mode);
            float volume;
            synchronized (lock) {
                volume = volumePercent / 100f;
            }
            sink.setVolume(volume);
        }
    }

    /** What the app's volume slider controls right now, for the volume readout:
     *  "hardware" / "software" while an exclusive stream is up, "" otherwise. */
    String volumeKind() {
        if (!(sink instanceof UsbIsoSink) || !sink.exclusive()) return "";
        return ((UsbIsoSink) sink).hasHardwareVolume()
                && ((UsbIsoSink) sink).volumeModeIsHardware() ? "hardware" : "software";
    }

    /** Live: true while the slider really drives the DAC's own control on the open
     *  stream (so the perceptual volume curve must NOT be applied on top — that
     *  level is already a dB mapping). False everywhere else. */
    boolean hardwareVolumeActive() {
        return sink instanceof UsbIsoSink && sink.exclusive()
                && ((UsbIsoSink) sink).hasHardwareVolume()
                && ((UsbIsoSink) sink).volumeModeIsHardware();
    }

    /** Generation of the last sink open; the dispatcher compares this to what it
     *  has already reported, so the adopted level reaches the UI exactly once. */
    int openGen() {
        synchronized (lock) {
            return openGen;
        }
    }

    /** The level the DAC actually plays at after the latest open, 0..1; -1 when
     *  nothing was adopted yet. */
    float adoptedVolume01() {
        synchronized (lock) {
            return adoptedVolume01;
        }
    }

    // --- AudioBackend ------------------------------------------------------

    @Override
    public void play(String src, long startMs) {
        play(src, Collections.<String, String>emptyMap(), startMs);
    }

    @Override
    public void play(String src, Map<String, String> headers, long startMs) {
        if (src == null || src.isEmpty()) return;
        synchronized (lock) {
            requestedSource = src;
            requestedHeaders = headers != null ? headers : Collections.<String, String>emptyMap();
            pendingSeekMs = Math.max(0L, startMs);
            pendingReopen = false;
            desiredPlaying = true;
            wantedSerial++;
            // A new source is a new time base whether or not the decoder has
            // switched over yet: the UI must not keep showing the old track's
            // position while the new one opens.
            positionBaseMs = Math.max(0L, startMs);
            positionBaseFrames = sink.framesWritten();
            durationMs = 0L;
            outputEncoding = "";
            outputRate = 0;
            outputChannels = 0;
            outputBitDepth = 0;
            sourceRate = 0;
            sourceBitDepth = 0;
            sourceEncoding = "";
            exclusive = false;
            lastError = "";
        }
        restarts = 0;
        requestFocus();
        startDecoder();
        startKeepAliveTrack();
    }

    // --- silent keep-alive track (experiment) -------------------------------

    /** Experimental, off by default: hold a silent framework AudioTrack for as
     *  long as this exclusive stream plays. This backend bypasses AudioFlinger
     *  entirely — nothing about our playback exists in the platform's audio
     *  bookkeeping — and some ROMs suspend a process they cannot see playing
     *  audio, which shows up as "session says playing, output is silent" after the
     *  app goes to the background. A zero-filled track at volume 0 keeps us on the
     *  platform's own player list (the position a framework-playing app is always
     *  in), without making a sound. */
    private volatile boolean keepAliveEnabled;
    /** A silent track is believed to be up right now. */
    private volatile boolean keepAliveUp;
    /** Bumped to retire a running keep-alive loop (and never let it clear the
     *  bookkeeping of a newer one). */
    private volatile int keepAliveGeneration;
    private volatile Thread keepAliveThread;

    void setKeepAliveTrack(boolean on) {
        keepAliveEnabled = on;
        if (!on) {
            stopKeepAliveTrack();
            return;
        }
        boolean playing;
        synchronized (lock) {
            playing = desiredPlaying && !closed;
        }
        if (playing) startKeepAliveTrack();
    }

    private void startKeepAliveTrack() {
        if (!keepAliveEnabled) return;
        final int generation;
        synchronized (lock) {
            if (keepAliveUp || closed) return;
            keepAliveUp = true;
            generation = ++keepAliveGeneration;
        }
        Thread thread = new Thread(() -> keepAliveLoop(generation), "qplayer-keepalive");
        thread.setDaemon(true);
        keepAliveThread = thread;
        thread.start();
    }

    private void stopKeepAliveTrack() {
        synchronized (lock) {
            if (!keepAliveUp) return;
            keepAliveUp = false;
            keepAliveGeneration++;
        }
        Thread thread = keepAliveThread;
        keepAliveThread = null;
        if (thread != null) thread.interrupt();
    }

    private void keepAliveLoop(int generation) {
        try {
            int attempt = 0;
            while (!closed && keepAliveGeneration == generation) {
                attempt++;
                if (runKeepAliveTrack(generation, attempt)) return;
                if (closed || keepAliveGeneration != generation) return;
                // The track died (a write error, or the system stopped it). Rebuild
                // after a short back-off: this track's whole job is to stay present
                // in the platform's bookkeeping for hours of background playback,
                // so one transient failure must not end that protection for the
                // rest of the session — the ROM freeze comes back the moment it is
                // gone, and the symptom (silent until the screen wakes) is exactly
                // what a long run reported.
                Logger.warn("keepalive: silent track died (attempt {}); rebuilding", attempt);
                sleepQuietly(Math.min(5000L, 1000L * attempt));
            }
        } finally {
            synchronized (lock) {
                // Only the current generation owns the flag; a newer keep-alive
                // that started while this one was tearing down must not be cleared.
                if (keepAliveGeneration == generation) keepAliveUp = false;
            }
            Logger.info("keepalive: silent track down");
        }
    }

    /** Runs one silent track until it is stopped on purpose.
     *  @return true when the loop should end (the keep-alive was switched off, or
     *  the backend is closing); false when the track died and wants a rebuild. */
    private boolean runKeepAliveTrack(int generation, int attempt) {
        AudioTrack track = null;
        try {
            final int rate = 48000;
            int minBytes = AudioTrack.getMinBufferSize(rate,
                    AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            // A roomy buffer (>=1s): the feeder below writes ~200ms of silence per
            // pass, so the buffer never becomes the reason a write blocks.
            int bufferBytes = Math.max(minBytes > 0 ? minBytes : 8192,
                    rate * 2 /*stereo*/ * 2 /*bytes*/);
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build())
                    .setBufferSizeInBytes(bufferBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            // Deliberately NO setPreferredDevice: selecting a device for a media
            // track lands in AudioPolicyManager::setOutputDevice(force=true) and
            // moves the media strategy's output for EVERY client — pinning this
            // track to the speaker sent other apps' audio there too. Left alone,
            // the track follows the system's own routing (the USB DAC, the device
            // the policy prefers), which is exactly where it must stay: the DAC is
            // held by us, so the attempt fails and nobody hears anything.
            track.setVolume(0f);
            track.play();
            Logger.info("keepalive: silent track up ({}Hz {}B, following system routing{})",
                    rate, bufferBytes, attempt > 1 ? ", rebuild #" + (attempt - 1) : "");
            byte[] zeros = new byte[rate * 2 /*stereo*/ * 2 /*bytes*/ / 5 /*200ms*/];
            long lastLog = System.currentTimeMillis();
            int idlePasses = 0;
            while (!closed && keepAliveGeneration == generation) {
                // Non-blocking on purpose: a blocking write on an output that stops
                // consuming would hang this loop forever, and then the protection
                // this track provides would be silently gone with nobody noticing.
                // Non-blocking + a nap keeps the feed at roughly realtime and keeps
                // the health checks below running no matter what the output does.
                int written = track.write(zeros, 0, zeros.length, AudioTrack.WRITE_NON_BLOCKING);
                if (written < 0) {
                    Logger.warn("keepalive: write failed: {}", written);
                    return false;
                }
                if (written < zeros.length) idlePasses++;
                sleepQuietly(200L);
                if (System.currentTimeMillis() - lastLog >= 15000L) {
                    lastLog = System.currentTimeMillis();
                    int state = track.getPlayState();
                    Logger.info("keepalive: alive playState={} routed={} otherMusicActive={} fullPasses={}",
                            state, routedDeviceName(track),
                            audioManager != null && audioManager.isMusicActive(), idlePasses);
                    idlePasses = 0;
                    // A stream track we keep feeding must stay PLAYING; if the system
                    // stopped it (output revoked, HAL reconfigured), it no longer
                    // counts for anything and has to be rebuilt.
                    if (state == AudioTrack.PLAYSTATE_STOPPED) {
                        Logger.warn("keepalive: track was stopped by the system");
                        return false;
                    }
                }
            }
            return true;
        } catch (Throwable e) {
            Logger.warn("keepalive: track failed: {}", e.toString());
            return false;
        } finally {
            if (track != null) {
                try {
                    track.stop();
                } catch (Throwable ignored) {
                }
                try {
                    track.release();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** The device this silent track actually ended up on, for the heartbeat: it is
     *  a diagnostic, so an unreadable answer is reported as a dash rather than
     *  failing the keep-alive. */
    private static String routedDeviceName(AudioTrack track) {
        try {
            AudioDeviceInfo device = track.getRoutedDevice();
            return device != null ? AndroidHifiProbe.productName(device) : "-";
        } catch (Throwable e) {
            return "-";
        }
    }

    @Override
    public void pause() {
        synchronized (lock) {
            desiredPlaying = false;
            // A user pause cancels a pending call-resume: a call that ends later
            // must not start the music again by itself.
            resumeOnGain = false;
        }
        focusRetryAttempts = 0;
        callConfirmPending = false;
        watchdogHandler.removeCallbacks(focusCallRetry);
        watchdogHandler.removeCallbacks(callConfirm);
        watchdogHandler.removeCallbacks(stallCheck);
        stopKeepAliveTrack();
        releaseWifiLock();
        releaseWakeLock();
        sink.pause();
        fire(onPaused);
    }

    @Override
    public void resume() {
        synchronized (lock) {
            desiredPlaying = true;
        }
        requestFocus();
        if (opened) sink.play();
        holdWifiLock();
        acquireWakeLock();
        scheduleStallCheck();
        startKeepAliveTrack();
        fire(onResumed);
    }

    @Override
    public boolean isPlaying() {
        synchronized (lock) {
            return desiredPlaying && opened;
        }
    }

    @Override
    public void seek(long ms) {
        long target = Math.max(0L, ms);
        synchronized (lock) {
            pendingSeekMs = target;
            // Report the target at once; the decoder drops what was queued for the
            // old position and repositions the extractor on its next pass.
            positionBaseMs = target;
            positionBaseFrames = sink.framesWritten();
        }
    }

    @Override
    public long position() {
        synchronized (lock) {
            long frames = sink.framesWritten() - positionBaseFrames;
            if (frames < 0) frames = 0;
            int rate = outputRate > 0 ? outputRate : 48000;
            long ms = positionBaseMs + frames * 1000L / rate;
            if (durationMs > 0 && ms > durationMs) return durationMs;
            return ms;
        }
    }

    @Override
    public long duration() {
        synchronized (lock) {
            return durationMs;
        }
    }

    /** Ring depth right now (queued + in-flight), in ms — diagnostics only. */
    private long bufferedMs() {
        synchronized (lock) {
            int rate = outputRate > 0 ? outputRate : sink.sampleRate();
            if (rate <= 0) return 0L;
            return sink.bufferedFrames() * 1000L / rate;
        }
    }

    /** Diagnostics only: one consistent snapshot of the position bookkeeping. */
    private String positionDebug() {
        synchronized (lock) {
            return "pos=" + position() + "ms base=" + positionBaseMs + "ms/"
                    + positionBaseFrames + "f rate=" + outputRate
                    + " written=" + sink.framesWritten() + "f ringMs=" + bufferedMs();
        }
    }

    @Override
    public void setVolume(float volume) {
        int percent = Math.round(Math.max(0f, Math.min(1f, volume)) * 100f);
        synchronized (lock) {
            volumePercent = percent;
        }
        sink.setVolume(percent / 100f);
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
    public void setOnError(Runnable callback) {
        onError = callback;
    }

    @Override
    public void bindGrowingCache(String pendingPath, String completePath, long durationMs) {
        synchronized (lock) {
            cacheCompletePath = completePath;
            if (durationMs > 0 && this.durationMs <= 0) this.durationMs = durationMs;
        }
    }

    @Override
    public void notifyCacheComplete() {
        String path;
        synchronized (lock) {
            path = cacheCompletePath;
            if (path == null || path.equals(requestedSource)) return;
            // Never swap sources mid-playback. The switch only buys the last few
            // seconds of the track off the network instead of the finished file,
            // and it costs an audible join: the HTTP and file paths prime their
            // decoders differently, so the PCM the file hands over is not the PCM
            // the ring already holds — heard as a short repeat at the join. The
            // finished file is picked up by the next play (playAt prefers it) and
            // by a reopen while paused, so nothing is lost by leaving it alone.
            if (desiredPlaying && !closed) {
                Logger.info("direct: cache complete, keeping the stream");
                return;
            }
            pendingReopen = true;
            pendingReopenFromMs = position();
            pendingReopenSource = path;
            requestedSource = path;
        }
    }

    @Override
    public void release() {
        watchdogHandler.removeCallbacks(stallCheck);
        watchdogHandler.removeCallbacks(focusCallRetry);
        watchdogHandler.removeCallbacks(callConfirm);
        stopKeepAliveTrack();
        releaseWifiLock();
        releaseWakeLock();
        Thread thread;
        synchronized (lock) {
            closed = true;
            desiredPlaying = false;
        }
        thread = decodeThread;
        decodeThread = null;
        if (thread != null) {
            // Closing the sink first is what unblocks a decoder sitting in a write.
            sink.close();
            try {
                thread.join(1500L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        sink.close();
        abandonFocus();
        synchronized (lock) {
            opened = false;
            decodeRunning = false;
        }
    }

    // --- readout for the dispatcher ---------------------------------------

    /** Snapshot of what this backend is doing. The mode fields stay at their
     *  defaults: the dispatcher knows the requested mode and whether it had to
     *  fall back, this backend does not. */
    AudioOutputStatus directStatus() {
        AudioDeviceInfo device = AndroidHifiProbe.targetDevice(audioManager, selectedDeviceId);
        String sourceEnc;
        int srcRate;
        int srcDepth;
        String outEnc;
        int outRate;
        int outDepth;
        synchronized (lock) {
            sourceEnc = sourceEncoding;
            srcRate = sourceRate;
            srcDepth = sourceBitDepth;
            outEnc = outputEncoding;
            outRate = outputRate;
            outDepth = outputBitDepth;
        }
        boolean resampled = (srcRate > 0 && outRate > 0 && srcRate != outRate)
                || (srcDepth > 0 && outDepth > 0 && srcDepth > outDepth);
        // The USB driver streams unaltered bits by construction, so it is
        // bit-perfect wherever it can open the DAC at all.
        boolean bitPerfectSupported = kind == KIND_USB_EXCLUSIVE;
        return new AudioOutputStatus(
                device != null ? String.valueOf(device.getId()) : "",
                device != null ? AndroidHifiProbe.productName(device)
                        : I18n.tr("settings.hifiOutputDevice.auto"),
                AndroidHifiProbe.routeTypeOf(device),
                sourceEnc, srcRate, srcDepth,
                outEnc, outRate, outDepth,
                resampled, bitPerfectSupported, false, routingNote(),
                HifiOutputMode.SYSTEM, HifiOutputMode.SYSTEM,
                AudioOutputStatus.EXCLUSIVE_NONE, "", volumeKind());
    }

    /** How exclusive this backend's output really is. The USB claim either
     *  succeeded or it did not: nothing between us and the DAC can demote the
     *  stream, so there is no "partial grant". */
    String exclusiveState() {
        return sink.exclusive() ? AudioOutputStatus.EXCLUSIVE_FULL
                : AudioOutputStatus.EXCLUSIVE_NONE;
    }

    /** Why the output is not what the mode asked for, for the chain card: "" when
     *  it is. Failures that kept the source from starting are reported through
     *  the dispatcher's fallback reason instead, which is the more specific
     *  thing to say. */
    String routingNote() {
        return "";
    }

    /** Why the last open/write failed; "" when fine. */
    String lastError() {
        synchronized (lock) {
            return lastError;
        }
    }

    /** The rate the sink was actually opened at, for the diagnostic log. */
    int outputRate() {
        synchronized (lock) {
            return outputRate;
        }
    }

    // --- decoder thread ----------------------------------------------------

    private void startDecoder() {
        synchronized (lock) {
            if (decodeRunning || closed) return;
            decodeRunning = true;
        }
        Thread thread = new Thread(this::decodeLoop, "qplayer-direct-decode");
        thread.setDaemon(true);
        decodeThread = thread;
        thread.start();
    }

    /**
     * One decode pipeline for one source at a time. It re-reads the request state
     * between buffers, so a track switch, a seek, a pause or a cache switch takes
     * effect within a buffer instead of only at the end of a source.
     *
     * <p>{@link #snapshot()} consumes the one-shot flags (seek, reopen), which is
     * why every branch below sees a request exactly once.
     */
    private void decodeLoop() {
        // A background app's threads are shared out aggressively; a decoder that
        // loses that race starves the ring and the DAC goes quiet. Ask for the
        // audio priority — the same defense the USB feeder thread already has, and
        // a no-op where the kernel refuses it.
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
        } catch (Throwable ignored) {
        }
        MediaExtractor extractor = null;
        MediaCodec codec = null;
        final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long serial = -1L;
        boolean inputDone = false;
        boolean outputDone = false;
        try {
            while (true) {
                Pending pending = snapshot();
                if (pending == null) return;                      // released
                if (pending.source.isEmpty()) {
                    sleepQuietly(50L);
                    continue;
                }
                if (pending.serial != serial || pending.reopenFromMs >= 0L) {
                    // A genuinely new source starts from silence: drop the old
                    // track's tail so the ring cannot leak it under the new one.
                    // A reopen (the cache-file switch) keeps the ring — its
                    // buffered audio is the same position, and it plays on while
                    // the decoder re-primes, which is exactly what hides the
                    // switch that used to be heard as a stutter. A track that
                    // ended on its own keeps its tail too: that tail IS the
                    // hand-off into this source.
                    if (pending.serial != serial && pending.reopenFromMs < 0L
                            && sink.isOpen() && !tailCarriesOver) {
                        sink.flush();
                    }
                    tailCarriesOver = false;
                    closeMedia(codec, extractor);
                    codec = null;
                    extractor = null;
                    inputDone = false;
                    outputDone = false;
                }
                if (extractor == null) {
                    boolean isReopen = pending.reopenFromMs >= 0L;
                    // The seam a reopen has to land on is the ring's head — the audio the
                    // cache file continues from. A plain seekTo lands on a sync sample on
                    // either side of it, which came out as a short skip at the switch, so
                    // seek slightly before the seam and trim the leading samples up to it.
                    long seamMs = pending.seekMs >= 0L ? pending.seekMs : position();
                    long startMs = isReopen
                            ? Math.max(0L, seamMs - REOPEN_SEEK_BACK_MS) : seamMs;
                    if (isReopen) Logger.info("direct reopen begin: {}", positionDebug());
                    long openT0 = System.currentTimeMillis();
                    PcmSource opened = openSource(pending.source, pending.headers, startMs,
                            isReopen);
                    if (opened == null) {
                        fireError(sink.lastError().isEmpty()
                                ? "cannot open source" : sink.lastError());
                        awaitNewSerial(pending.serial);
                        serial = -1L;
                        continue;
                    }
                    extractor = opened.extractor;
                    codec = opened.codec;
                    serial = pending.serial;
                    Logger.info("direct open: {}ms reopen={} ringMs={} {}",
                            System.currentTimeMillis() - openT0,
                            isReopen, bufferedMs(),
                            pending.source.startsWith("http") ? "net" : "local");
                    Logger.info("direct open done: {} reopen={}", positionDebug(), isReopen);
                    if (isReopen) reopenTargetUs = seamMs * 1000L;
                    // A reopen (the cache-file switch) is the SAME source: it
                    // must not re-fire onStarted — with the fade enabled that
                    // restarts the ramp and dips the volume mid-track — nor
                    // re-arm the sink. Only a genuinely new source resets.
                    if (pending.reopenFromMs < 0L) {
                        playingStarted = false;
                        // A fresh sink is stopped by definition.
                        sinkPlaying = false;
                    }
                    continue;
                }
                if (!pending.playing) {
                    // Paused: keep the pipeline where it is and let the sink hold
                    // what it already has. Once the track's buffer fills the decoder
                    // simply blocks in write, which is the intended shape of
                    // "paused" here — the watchdog is not armed while paused.
                    if (sinkPlaying) {
                        sink.pause();
                        sinkPlaying = false;
                    }
                    sleepQuietly(20L);
                    continue;
                }
                if (pending.seekMs >= 0L) {
                    applySeek(extractor, codec, pending.seekMs);
                    inputDone = false;
                    outputDone = false;
                }
                if (!sinkPlaying) {
                    // Also how a resume that did not come through resume() (e.g. the
                    // watchdog's rebuild) gets its sink going again.
                    sink.play();
                    sinkPlaying = true;
                }
                if (!playingStarted) {
                    playingStarted = true;
                    acquireWakeLock();
                    holdWifiLock();
                    scheduleStallCheck();
                    Logger.info("direct start: ringMs={}", bufferedMs());
                    fire(onStarted);
                }
                if (outputDone) {
                    long finished = pending.serial;
                    closeMedia(codec, extractor);
                    codec = null;
                    extractor = null;
                    serial = -1L;
                    // The source is over but nothing new has been requested yet:
                    // wait rather than reopening it, which would replay the track.
                    // The sink is deliberately left running — what is still in its
                    // ring is this track's real tail, and the next source's PCM is
                    // appended right behind it, which is what removes the silence
                    // between two consecutive same-format tracks.
                    tailCarriesOver = finishSourceKeepingTail(finished);
                    awaitNewSerial(finished);
                    continue;
                }
                if (!inputDone) {
                    inputDone = feedInput(extractor, codec);
                }
                outputDone = drainOutput(codec, info);
            }
        } catch (Throwable e) {
            Logger.error("direct decode failed: {}", e.toString());
            synchronized (lock) {
                lastError = e.toString();
            }
            fire(onError);
        } finally {
            closeMedia(codec, extractor);
            sink.close();
            stopKeepAliveTrack();
            releaseWakeLock();
            releaseWifiLock();
            synchronized (lock) {
                decodeRunning = false;
                opened = false;
            }
        }
    }

    /** A consistent view of what the decoder should be doing right now, with the
     *  one-shot seek/reopen flags already consumed. */
    private Pending snapshot() {
        Pending pending = new Pending();
        synchronized (lock) {
            if (closed) return null;
            pending.serial = wantedSerial;
            pending.source = pendingReopen ? pendingReopenSource : requestedSource;
            pending.reopenFromMs = pendingReopen ? pendingReopenFromMs : -1L;
            pending.playing = desiredPlaying;
            pending.seekMs = pendingSeekMs;
            pendingSeekMs = -1L;
            pendingReopen = false;
            pendingReopenSource = "";
            pending.headers = requestedHeaders;
        }
        return pending;
    }

    private static final class Pending {
        long serial;
        String source = "";
        Map<String, String> headers = Collections.emptyMap();
        boolean playing;
        long seekMs = -1L;
        long reopenFromMs = -1L;
    }

    /** How long the wake/wifi locks (and the silent keep-alive track) outlive a
     *  finished source while the next one is on its way. Generous on purpose: an
     *  online track's hand-off includes a network resolve, and the moment the
     *  process goes idle at a track boundary is exactly when a ROM freezes a
     *  background app — a 3s window left playback silent until the screen came
     *  back on. 30s still covers the "queue really ended" case without holding a
     *  wake lock indefinitely. */
    private static final long HANDOFF_HOLD_MS = 30_000L;

    /** Wait until a newer request arrives, so a finished or failed source does not
     *  spin and does not replay itself. The wake/wifi locks (and the silent
     *  keep-alive track) are held for {@link #HANDOFF_HOLD_MS} first — the hand-off
     *  to the next source normally takes milliseconds, but it may include a network
     *  resolve, and the idle moment right after it must not become a freeze window. */
    private void awaitNewSerial(long serial) {
        watchdogHandler.removeCallbacks(stallCheck);
        long releaseAt = System.currentTimeMillis() + HANDOFF_HOLD_MS;
        boolean released = false;
        sleepQuietly(100L);
        while (true) {
            synchronized (lock) {
                if (closed || wantedSerial != serial) return;
            }
            if (!released && System.currentTimeMillis() >= releaseAt) {
                released = true;
                releaseWakeLock();
                releaseWifiLock();
                // Nothing followed the finished source: the session really is over,
                // so the silent keep-alive track goes down with the locks.
                stopKeepAliveTrack();
            }
            sleepQuietly(100L);
        }
    }

    private static final class PcmSource {
        MediaExtractor extractor;
        MediaCodec codec;
    }

    /**
     * Open the container and the decoder, and work out the PCM format it will
     * produce. The sink is built from what the decoder reports, not from what the
     * source claims, so a decoder that quietly resamples is reported truthfully.
     */
    private PcmSource openSource(String src, Map<String, String> headers, long startMs,
                                 boolean reopen) {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        long tOpen0 = System.currentTimeMillis();
        long tDs, tDepth, tCodec, tProbe;
        try {
            setDataSource(extractor, src, headers);
            tDs = System.currentTimeMillis();
            int track = selectAudioTrack(extractor);
            if (track < 0) {
                Logger.warn("direct: no audio track in {}", src);
                extractor.release();
                synchronized (lock) {
                    lastError = "no audio track";
                }
                return null;
            }
            extractor.selectTrack(track);
            MediaFormat format = extractor.getTrackFormat(track);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime == null) {
                extractor.release();
                synchronized (lock) {
                    lastError = "no track mime";
                }
                return null;
            }
            long durationUs = format.containsKey(MediaFormat.KEY_DURATION)
                    ? format.getLong(MediaFormat.KEY_DURATION) : 0L;
            int srcRate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 0;
            int srcChannels = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
            int srcDepth = probeBitDepth(src);
            tDepth = System.currentTimeMillis();
            // Ask for float PCM where the decoder will give it: that keeps a 24-bit
            // source's extra bits instead of truncating them. The USB path is the
            // one exception — a 16-bit source can then pass through unaltered, and
            // asking for float would force a conversion right back.
            boolean wantFloat = !(kind == KIND_USB_EXCLUSIVE && srcDepth > 0 && srcDepth <= 16);
            if (wantFloat) {
                try {
                    format.setInteger(MediaFormat.KEY_PCM_ENCODING,
                            AudioFormat.ENCODING_PCM_FLOAT);
                } catch (Throwable ignored) {
                }
            }
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();
            if (startMs > 0L) {
                extractor.seekTo(startMs * 1000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
            }
            tCodec = System.currentTimeMillis();
            MediaFormat out = awaitOutputFormat(extractor, codec);
            tProbe = System.currentTimeMillis();
            synchronized (lock) {
                sourceEncoding = AndroidHifiProbe.encodingLabel(mime);
                sourceRate = srcRate;
                sourceBitDepth = srcDepth;
                durationMs = durationUs > 0L ? durationUs / 1000L : 0L;
                readPcmFormat(out, srcRate, srcChannels);
            }
            if (!openSink(reopen)) {
                synchronized (lock) {
                    lastError = sink.lastError();
                    opened = false;
                }
                closeMedia(codec, extractor);
                return null;
            }
            Logger.info("direct open parts: ds={}ms depth={}ms codec={}ms probe={}ms sink={}ms {}",
                    tDs - tOpen0, tDepth - tDs, tCodec - tDepth, tProbe - tCodec,
                    System.currentTimeMillis() - tProbe,
                    src.startsWith("http") ? "net" : "local");
            PcmSource openedSource = new PcmSource();
            openedSource.extractor = extractor;
            openedSource.codec = codec;
            return openedSource;
        } catch (Throwable e) {
            Logger.error("direct source open failed: {}", e.toString());
            synchronized (lock) {
                lastError = e.toString();
            }
            closeMedia(codec, extractor);
            return null;
        }
    }

    /** The decoder's own format only appears after it has produced a buffer. */
    private MediaFormat awaitOutputFormat(MediaExtractor extractor, MediaCodec codec) {
        // A decoder emits nothing until input has been queued: polling
        // dequeueOutputBuffer alone (as this used to) could therefore never
        // succeed, and every source open — local file or stream alike — paid the
        // whole deadline before the sink was even opened. That is why the ring
        // was still empty when playback started, and why the 800 ms of buffered
        // audio could not cover the mid-track cache-file switch. Feed a buffer
        // and the format arrives in milliseconds; the deadline is only a backstop
        // for a decoder that genuinely refuses to produce one.
        long deadline = System.currentTimeMillis() + OPEN_PROBE_TIMEOUT_MS;
        MediaCodec.BufferInfo probe = new MediaCodec.BufferInfo();
        while (System.currentTimeMillis() < deadline) {
            int in = codec.dequeueInputBuffer(0L);
            if (in >= 0) {
                ByteBuffer input = codec.getInputBuffer(in);
                int size = input != null ? extractor.readSampleData(input, 0) : -1;
                if (size < 0) {
                    codec.queueInputBuffer(in, 0, 0, 0L,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                } else {
                    codec.queueInputBuffer(in, 0, size, extractor.getSampleTime(), 0);
                    extractor.advance();
                }
            }
            int index = codec.dequeueOutputBuffer(probe, 10_000L);
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                return codec.getOutputFormat();
            }
            if (index >= 0) {
                // Nothing has been written yet, so this buffer is safe to drop: it
                // belongs to the head of the file the sink is being positioned past.
                codec.releaseOutputBuffer(index, false);
                return codec.getOutputFormat();
            }
        }
        return null;
    }

    private void readPcmFormat(MediaFormat out, int srcRate, int srcChannels) {
        int rate = srcRate;
        int channels = srcChannels;
        boolean floatPcm = false;
        if (out != null) {
            if (out.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                rate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            }
            if (out.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            }
            if (out.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                floatPcm = out.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        == AudioFormat.ENCODING_PCM_FLOAT;
            }
        }
        outputRate = rate > 0 ? rate : 48000;
        outputChannels = channels > 0 ? channels : 2;
        outputEncoding = floatPcm ? "PCM float" : "PCM";
        outputBitDepth = floatPcm ? 32 : 16;
    }

    /** Open the sink for the format the decoder reported. The USB driver ignores
     *  framework routing entirely — it finds its own DAC and always runs at the
     *  source's rate, which is the entire point of an exclusive stream. */
    private boolean openSink(boolean reopen) {
        int decodedRate;
        int channels;
        boolean floatPcm;
        int volume;
        synchronized (lock) {
            decodedRate = outputRate > 0 ? outputRate : 48000;
            channels = outputChannels > 0 ? outputChannels : 2;
            floatPcm = "PCM float".equals(outputEncoding);
            volume = volumePercent;
        }
        if (sink instanceof UsbIsoSink) {
            int depth;
            synchronized (lock) {
                depth = sourceBitDepth;
            }
            ((UsbIsoSink) sink).setSourceDepth(depth);
        }
        // The sink stays open across sources: when the last source left it open
        // at the very format this one needs, keep it — re-opening would re-claim
        // the DAC from usbfs (the audible gap between tracks) and throw away the
        // buffered audio that bridges a mid-track cache-file switch.
        if (sink.isOpen()
                && sink.sampleRate() == decodedRate
                && sink.channels() == channels
                && sink.floatPcm() == floatPcm) {
            synchronized (lock) {
                opened = true;
                exclusive = sink.exclusive();
                // play() cleared these; the reused sink's actuals are what the
                // chain card and the position math must see.
                outputRate = sink.sampleRate();
                outputEncoding = "PCM";
                outputBitDepth = sink instanceof UsbIsoSink
                        ? ((UsbIsoSink) sink).bitDepth() : 0;
                // Re-base the frame counter only for a genuinely new source, where
                // play() re-based the position pair to match. A reopen (the mid-track
                // cache-file switch) is the SAME source: the ring and the counter both
                // carry on, so re-basing the frames alone snapped the reported position
                // back to positionBaseMs — the bar jumping from 0:03 to 0:00.
                if (!reopen) positionBaseFrames = sink.framesWritten();
                // Nothing is adopted from a REUSED sink: its level has not changed,
                // and the value used here is the app's own level (a digital gain in
                // software mode, which is NOT the same scale as a slider position).
                // Reporting it made the volume slider jump to the gain value on
                // every track switch that reopened nothing.
                adoptedVolume01 = -1f;
                openGen++;
            }
            sink.setVolume(volume / 100f);
            return true;
        }
        if (sink.isOpen()) {
            Logger.info("direct sink rebuild: {}Hz/ch{} -> {}Hz/ch{}",
                    sink.sampleRate(), sink.channels(), decodedRate, channels);
            sink.close();   // format change: rebuild for the new one
        }
        if (!sink.open(decodedRate, channels, floatPcm, -1)) return false;
        synchronized (lock) {
            opened = true;
            exclusive = sink.exclusive();
            // What the driver actually got the DAC to run at is what the chain
            // card must report (same rate; the container may differ).
            outputRate = sink.sampleRate();
            outputEncoding = "PCM";
            outputBitDepth = sink instanceof UsbIsoSink
                    ? ((UsbIsoSink) sink).bitDepth() : 0;
            positionBaseFrames = sink.framesWritten();
        }
        // Open-time volume: switching to exclusive must never change loudness by
        // itself. Hardware mode ADOPTS whatever level the DAC's buttons left it at
        // (readable only before the isochronous stream starts); when that read
        // fails, hold the level the app last asked for rather than inventing one.
        // Software mode never writes the DAC at all and owns the level entirely
        // through the digital gain, so its value is re-asserted unchanged.
        float reported = -1f;   // nothing adopted unless a hardware level was read
        if (sink instanceof UsbIsoSink
                && ((UsbIsoSink) sink).volumeModeIsHardware()) {
            float adopted = ((UsbIsoSink) sink).adoptedVolume();
            if (adopted >= 0) {
                reported = adopted;
                Logger.info("hifi open volume: adopted device level {}%",
                        Math.round(adopted * 100));
            } else {
                float keep = volume / 100f;
                sink.setVolume(keep);
                Logger.info("hifi open volume: device level unreadable, keeping app level {}%",
                        Math.round(keep * 100));
            }
        } else {
            // The digital gain the app pushed; not clamped — a clamp here is what
            // quietly overrode the user's setting on an open.
            sink.setVolume(volume / 100f);
        }
        synchronized (lock) {
            if (reported >= 0) volumePercent = Math.round(reported * 100f);
            adoptedVolume01 = reported;
            openGen++;
        }
        return true;
    }

    /** Bit depth of the source, for the chain card only: neither the extractor nor
     *  the decoder reliably reports it, so a local file is asked directly. */
    private int probeBitDepth(String src) {
        if (Build.VERSION.SDK_INT < 31) return 0;
        if (src.startsWith("http://") || src.startsWith("https://")) return 0;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            if (src.startsWith("content://")) {
                retriever.setDataSource(appContext, Uri.parse(src));
            } else {
                retriever.setDataSource(src);
            }
            return AndroidHifiProbe.intMetadata(retriever,
                    MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE);
        } catch (Throwable ignored) {
            return 0;
        } finally {
            try {
                retriever.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private void setDataSource(MediaExtractor extractor, String src,
                              Map<String, String> headers) throws java.io.IOException {
        if (src.startsWith("content://")) {
            extractor.setDataSource(appContext, Uri.parse(src), headers);
        } else if ((src.startsWith("http://") || src.startsWith("https://"))
                && headers != null && !headers.isEmpty()) {
            extractor.setDataSource(src, headers);
        } else {
            extractor.setDataSource(src);
        }
    }

    private static int selectAudioTrack(MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) return i;
        }
        return -1;
    }

    /** Feed the decoder from the extractor. @return true once the end of the
     *  stream has been signalled into the codec. */
    private boolean feedInput(MediaExtractor extractor, MediaCodec codec) {
        int index = codec.dequeueInputBuffer(0L);
        if (index < 0) return false;
        ByteBuffer buffer = codec.getInputBuffer(index);
        if (buffer == null) return false;
        int size = extractor.readSampleData(buffer, 0);
        if (size < 0) {
            codec.queueInputBuffer(index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            return true;
        }
        codec.queueInputBuffer(index, 0, size, extractor.getSampleTime(), 0);
        extractor.advance();
        return false;
    }

    /** Drain decoded PCM into the sink. @return true at the codec's end of stream. */
    private boolean drainOutput(MediaCodec codec, MediaCodec.BufferInfo info) {
        int index = codec.dequeueOutputBuffer(info, 0L);
        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            synchronized (lock) {
                readPcmFormat(codec.getOutputFormat(), outputRate, outputChannels);
            }
            return false;
        }
        if (index < 0) return false;
        boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
        int offset = info.offset;
        int size = info.size;
        if (reopenTargetUs >= 0L && size > 0) {
            // A reopen's first buffers are the seek's own lead-in. The seam is the
            // ring's head, and everything before it has already been played out of
            // the ring, so it is dropped — whole buffers, or trimmed off the head of
            // the one that straddles the seam, keeping the join sample exact.
            long seam = reopenTargetUs;
            int bpf = sourceFrameBytes();
            int rate = outputRate;
            long pts = info.presentationTimeUs;
            long durUs = bpf > 0 && rate > 0 && size >= bpf
                    ? (size / bpf) * 1_000_000L / rate : 0L;
            if (durUs > 0L && pts + durUs <= seam) {
                codec.releaseOutputBuffer(index, false);
                return false;
            }
            if (bpf > 0 && rate > 0 && pts < seam) {
                int skip = (int) Math.min(size, ((seam - pts) * rate / 1_000_000L) * bpf);
                Logger.info("direct reopen seam: trimmed {}B of {}B (pts={}us seam={}us)",
                        skip, size, pts, seam);
                offset += skip;
                size -= skip;
            } else {
                Logger.info("direct reopen seam: pts={}us seam={}us", pts, seam);
            }
            reopenTargetUs = -1L;
        }
        if (size > 0) {
            ByteBuffer buffer = codec.getOutputBuffer(index);
            if (buffer != null) {
                buffer.position(offset);
                buffer.limit(offset + size);
                if (sink.write(buffer, size) < 0) {
                    throw new IllegalStateException("direct sink write failed: " + sink.lastError());
                }
            }
        }
        codec.releaseOutputBuffer(index, false);
        return eos;
    }

    /** Bytes one source frame occupies in the PCM handed to the sink. */
    private int sourceFrameBytes() {
        synchronized (lock) {
            int channels = outputChannels > 0 ? outputChannels : 2;
            return channels * ("PCM float".equals(outputEncoding) ? 4 : 2);
        }
    }

    /** Re-position both halves of the pipeline: flush the decoder, seek the
     *  extractor onto the sync sample at or before the target, drop what the sink
     *  had queued, and rebase the reported position on the new frame count. */
    private void applySeek(MediaExtractor extractor, MediaCodec codec, long targetMs) {
        try {
            codec.flush();
            extractor.seekTo(Math.max(0L, targetMs) * 1000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
            sink.flush();
            synchronized (lock) {
                positionBaseMs = Math.max(0L, targetMs);
                positionBaseFrames = sink.framesWritten();
            }
            sink.play();
        } catch (Throwable e) {
            Logger.warn("direct seek failed: {}", e.toString());
        }
    }

    /** The source ended: report completion once, unless a newer request has
     *  already replaced this one. The sink is deliberately NOT paused: the ring
     *  still holds this track's tail, and letting it play on is what makes the
     *  hand-off to the next source gapless (the ring also buys the controller
     *  the time to hand that source over). The wake/wifi locks are deliberately
     *  kept for now for the same reason — letting the process go idle at a track
     *  boundary is exactly the window a ROM uses to freeze background playback;
     *  {@link #awaitNewSerial} releases them if nothing follows.
     *  @return true when completion was reported and the tail is live. */
    private boolean finishSourceKeepingTail(long serial) {
        synchronized (lock) {
            if (closed || wantedSerial != serial) return false;
            desiredPlaying = false;
            opened = false;
            positionBaseMs = durationMs;
            positionBaseFrames = sink.framesWritten();
        }
        watchdogHandler.removeCallbacks(stallCheck);
        fire(onComplete);
        return true;
    }

    private void scheduleStallCheck() {
        watchdogHandler.removeCallbacks(stallCheck);
        watchdogHandler.postDelayed(stallCheck, STALL_CHECK_MS);
    }

    /**
     * The watchdog runs on the main thread precisely because the decoder may be
     * blocked inside a sink write: nothing on the decode thread can notice that
     * the device stopped consuming. Dropping the sink's buffer unblocks that write
     * and the reopen flag makes the decoder rebuild the pipeline where it was.
     */
    private void checkStall() {
        boolean armed;
        synchronized (lock) {
            armed = desiredPlaying && opened && !closed;
        }
        if (!armed) {
            watchdogHandler.removeCallbacks(stallCheck);
            return;
        }
        long frames = sink.framesWritten();
        long now = System.currentTimeMillis();
        if (frames != lastProgressFrames) {
            lastProgressFrames = frames;
            lastProgressAtMs = now;
        } else if (now - lastProgressAtMs >= STALL_TIMEOUT_MS) {
            if (restarts >= MAX_RESTARTS_PER_SOURCE) {
                Logger.warn("direct sink stalled repeatedly; reporting an error");
                fireError("sink stalled");
                return;
            }
            restarts++;
            lastProgressAtMs = now;
            lastProgressFrames = frames;
            Logger.warn("direct sink stalled; rebuilding at the current position");
            sink.flush();
            synchronized (lock) {
                pendingReopen = true;
                pendingReopenFromMs = position();
                pendingReopenSource = requestedSource;
                wantedSerial++;
            }
        }
        scheduleStallCheck();
    }

    private void fireError(String message) {
        synchronized (lock) {
            lastError = message != null ? message : "";
            desiredPlaying = false;
            opened = false;
        }
        watchdogHandler.removeCallbacks(stallCheck);
        releaseWifiLock();
        releaseWakeLock();
        sink.close();
        fire(onError);
        // A sink that just failed is no use to the rest of this source; the next
        // play() (or the dispatcher's fallback) builds a new one.
    }

    private void holdWifiLock() {
        String src;
        synchronized (lock) {
            src = requestedSource;
        }
        if (wifiLock == null || src == null || src.isEmpty()) return;
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

    private void acquireWakeLock() {
        if (wakeLock == null) return;
        try {
            if (!wakeLock.isHeld()) wakeLock.acquire();
        } catch (Throwable ignored) {
        }
    }

    private void releaseWakeLock() {
        if (wakeLock == null) return;
        try {
            if (wakeLock.isHeld()) wakeLock.release();
        } catch (Throwable ignored) {
        }
    }

    // --- audio focus -------------------------------------------------------

    private void requestFocus() {
        if (hasFocus || audioManager == null) return;
        AudioFocusRequest request = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setOnAudioFocusChangeListener(this::onFocusChange)
                .setWillPauseWhenDucked(false)
                .build();
        focusRequest = request;
        hasFocus = audioManager.requestAudioFocus(request)
                == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abandonFocus() {
        watchdogHandler.removeCallbacks(focusCallRetry);
        callConfirmPending = false;
        watchdogHandler.removeCallbacks(callConfirm);
        if (audioManager != null && focusRequest != null) {
            try {
                audioManager.abandonAudioFocusRequest(focusRequest);
            } catch (Throwable ignored) {
            }
        }
        focusRequest = null;
        hasFocus = false;
        resumeOnGain = false;
    }

    /** After a TRANSIENT loss (a call) we wait for GAIN. OEM builds do not always
     *  send it back, so poll instead: while we still intend to play and the phone
     *  is not actually in a call, resume. Capped only as hygiene — a long call is
     *  guarded by the in-call check, and a user pause cancels the poll outright. */
    private static final long FOCUS_RETRY_INTERVAL_MS = 2000L;
    private static final int FOCUS_RETRY_LIMIT = 300;
    /** How long a focus loss is given to prove itself a call before being ignored. */
    private static final long CALL_CONFIRM_MS = 600L;
    private int focusRetryAttempts;
    private boolean callConfirmPending;

    private final Runnable focusCallRetry = this::retryTransientLoss;
    private final Runnable callConfirm = this::confirmCallPause;

    private void retryTransientLoss() {
        if (audioManager == null) return;
        boolean want;
        synchronized (lock) {
            want = resumeOnGain && !closed;
        }
        if (!want) return;
        if (focusRetryAttempts >= FOCUS_RETRY_LIMIT) return;
        focusRetryAttempts++;
        if (inCall()) {
            // Still on the call: the GAIN that ends it is the real trigger; this
            // poll is only the net for builds that forget to send it.
            scheduleFocusCallRetry();
            return;
        }
        // Take the focus back if the ROM offers it, but resume either way: this
        // stream does not need the grant to make sound, and waiting for one is
        // what left playback stranded silent when the ROM refuses it to a
        // background app (ColorOS does exactly that).
        requestFocus();
        resumeFromFocus();
    }

    private void scheduleFocusCallRetry() {
        watchdogHandler.removeCallbacks(focusCallRetry);
        watchdogHandler.postDelayed(focusCallRetry, FOCUS_RETRY_INTERVAL_MS);
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
        float volume;
        synchronized (lock) {
            volume = volumePercent / 100f;
            resumeOnGain = false;
            desiredPlaying = true;
        }
        sink.setVolume(volume);
        if (opened) {
            sink.play();
            holdWifiLock();
            acquireWakeLock();
            fire(onResumed);
        }
    }

    /** Focus policy: a real interruption is the only thing that may pause us.
     *  A full LOSS (another player starting, the screen going off) and a transient
     *  duck are otherwise ignored — nothing another app plays may stop or quiet our
     *  stream. "Interruption" is decided by the phone actually being in a call, not
     *  by which event name the ROM chose (they disagree): a call must pause, a
     *  notification or an OEM background transition must not. */
    private void onFocusChange(int change) {
        Logger.info("focus event {} (direct)", focusEventName(change));
        switch (change) {
            case AudioManager.AUDIOFOCUS_LOSS:
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                if (inCall()) {
                    pauseForCall();
                } else {
                    // The audio mode can flip to a call a beat after the focus
                    // loss, so give it a moment before concluding this was not an
                    // interruption. A phantom loss must never pause us: a silent
                    // two-second recovery is worse than never pausing at all.
                    callConfirmPending = true;
                    watchdogHandler.removeCallbacks(callConfirm);
                    watchdogHandler.postDelayed(callConfirm, CALL_CONFIRM_MS);
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                // No-op: a notification / navigation prompt must not quiet us.
                break;
            case AudioManager.AUDIOFOCUS_GAIN: {
                callConfirmPending = false;
                watchdogHandler.removeCallbacks(callConfirm);
                watchdogHandler.removeCallbacks(focusCallRetry);
                hasFocus = true;
                boolean resume;
                synchronized (lock) {
                    resume = resumeOnGain;
                }
                // Only an interruption pause is ours to end; every other loss is
                // ignored, so an unexpected GAIN must not resume anything.
                if (resume) resumeFromFocus();
                break;
            }
            default:
                break;
        }
    }

    /** The one confirmed interruption: a call. Pause and wait for it to end. */
    private void pauseForCall() {
        callConfirmPending = false;
        watchdogHandler.removeCallbacks(callConfirm);
        hasFocus = false;
        if (isPlaying()) {
            resumeOnGain = true;
            synchronized (lock) {
                desiredPlaying = false;
            }
            sink.pause();
            fire(onPaused);
        }
        if (resumeOnGain) {
            focusRetryAttempts = 0;
            scheduleFocusCallRetry();
        }
    }

    /** Runs a moment after an unrecognised focus loss: only a call that has become
     *  visible by now is allowed to pause us. */
    private void confirmCallPause() {
        if (!callConfirmPending) return;
        callConfirmPending = false;
        if (!inCall() || !isPlaying()) return;
        Logger.info("focus loss turned out to be a call; pausing (direct)");
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
    String focusState() {
        int mode = -1;
        try {
            mode = audioManager != null ? audioManager.getMode() : -1;
        } catch (Throwable ignored) {
        }
        return "direct hasFocus=" + hasFocus
                + " resumeOnGain=" + resumeOnGain
                + " attempts=" + focusRetryAttempts
                + " mode=" + mode
                + " playing=" + isPlaying();
    }

    // --- helpers -----------------------------------------------------------

    private void closeMedia(MediaCodec codec, MediaExtractor extractor) {
        // The sink is deliberately NOT closed here: the USB stream survives
        // across tracks (and across a mid-track cache-file switch), so a new
        // source neither re-claims the DAC — which cost the audible gap between
        // songs — nor drops what the ring still holds, which is what bridges the
        // decoder's re-prime during a cache switch. Format changes and release
        // are the places that close it.
        if (codec != null) {
            try {
                codec.stop();
                codec.release();
            } catch (Throwable ignored) {
            }
        }
        if (extractor != null) {
            try {
                extractor.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private void fire(Runnable callback) {
        if (callback != null) callback.run();
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Kind this backend was built for, for the dispatcher's readout. */
    int kind() {
        return kind;
    }
}
