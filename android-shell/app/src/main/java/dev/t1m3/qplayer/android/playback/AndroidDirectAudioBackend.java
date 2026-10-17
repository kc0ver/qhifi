package dev.t1m3.qplayer.android.playback;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
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
    private static final long DRAIN_TIMEOUT_MS = 3000L;
    private static final int MAX_RESTARTS_PER_SOURCE = 3;
    private static final long OPEN_PROBE_TIMEOUT_MS = 1500L;

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
    }

    @Override
    public void pause() {
        synchronized (lock) {
            desiredPlaying = false;
        }
        watchdogHandler.removeCallbacks(stallCheck);
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
            // Same behaviour as the system backend: once the download finished, the
            // rest of the track comes from the complete file rather than from a
            // network stream that may be about to expire. Moving the source here as
            // well keeps later snapshots pointed at the file that is playing now.
            pendingReopen = true;
            pendingReopenFromMs = position();
            pendingReopenSource = path;
            requestedSource = path;
        }
    }

    @Override
    public void release() {
        watchdogHandler.removeCallbacks(stallCheck);
        watchdogHandler.removeCallbacks(focusResumeCheck);
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
                    // switch that used to be heard as a stutter.
                    if (pending.serial != serial && pending.reopenFromMs < 0L
                            && sink.isOpen()) {
                        sink.flush();
                    }
                    closeMedia(codec, extractor);
                    codec = null;
                    extractor = null;
                    inputDone = false;
                    outputDone = false;
                }
                if (extractor == null) {
                    long startMs = pending.seekMs >= 0L ? pending.seekMs
                            : pending.reopenFromMs >= 0L ? pending.reopenFromMs : position();
                    PcmSource opened = openSource(pending.source, pending.headers, startMs);
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
                    fire(onStarted);
                }
                if (outputDone) {
                    long finished = pending.serial;
                    drainSink();
                    closeMedia(codec, extractor);
                    codec = null;
                    extractor = null;
                    serial = -1L;
                    finishSource(finished);
                    // The source is over but nothing new has been requested yet:
                    // wait rather than reopening it, which would replay the track.
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

    /** Wait until a newer request arrives, so a finished or failed source does not
     *  spin and does not replay itself. */
    private void awaitNewSerial(long serial) {
        watchdogHandler.removeCallbacks(stallCheck);
        releaseWakeLock();
        releaseWifiLock();
        sleepQuietly(100L);
        while (true) {
            synchronized (lock) {
                if (closed || wantedSerial != serial) return;
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
    private PcmSource openSource(String src, Map<String, String> headers, long startMs) {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            setDataSource(extractor, src, headers);
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
            MediaFormat out = awaitOutputFormat(codec);
            synchronized (lock) {
                sourceEncoding = AndroidHifiProbe.encodingLabel(mime);
                sourceRate = srcRate;
                sourceBitDepth = srcDepth;
                durationMs = durationUs > 0L ? durationUs / 1000L : 0L;
                readPcmFormat(out, srcRate, srcChannels);
            }
            if (!openSink()) {
                synchronized (lock) {
                    lastError = sink.lastError();
                    opened = false;
                }
                closeMedia(codec, extractor);
                return null;
            }
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
    private MediaFormat awaitOutputFormat(MediaCodec codec) {
        long deadline = System.currentTimeMillis() + OPEN_PROBE_TIMEOUT_MS;
        MediaCodec.BufferInfo probe = new MediaCodec.BufferInfo();
        while (System.currentTimeMillis() < deadline) {
            int index = codec.dequeueOutputBuffer(probe, 50_000L);
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
    private boolean openSink() {
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
                positionBaseFrames = sink.framesWritten();
                adoptedVolume01 = volume / 100f;
                openGen++;
            }
            sink.setVolume(volume / 100f);
            return true;
        }
        if (sink.isOpen()) sink.close();   // format change: rebuild for the new one
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
        // itself. Hardware mode ADOPTS whatever level the DAC's buttons left it
        // at (readable only before the isochronous stream starts); when that
        // fails, open at a safe 30% instead of the stored app level. Software
        // mode pins the hardware at max, so its first write is capped at 40%.
        float reported;
        if (sink instanceof UsbIsoSink
                && ((UsbIsoSink) sink).volumeModeIsHardware()) {
            float adopted = ((UsbIsoSink) sink).adoptedVolume();
            if (adopted >= 0) {
                reported = adopted;
                Logger.info("hifi open volume: adopted device level {}%",
                        Math.round(adopted * 100));
            } else {
                reported = 0.30f;
                sink.setVolume(reported);
                Logger.info("hifi open volume: device level unreadable, safe 30%");
            }
        } else {
            reported = Math.min(volume / 100f, 0.40f);
            sink.setVolume(reported);
        }
        synchronized (lock) {
            volumePercent = Math.round(reported * 100f);
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
        if (info.size > 0) {
            ByteBuffer buffer = codec.getOutputBuffer(index);
            if (buffer != null) {
                buffer.position(info.offset);
                buffer.limit(info.offset + info.size);
                if (sink.write(buffer, info.size) < 0) {
                    throw new IllegalStateException("direct sink write failed: " + sink.lastError());
                }
            }
        }
        codec.releaseOutputBuffer(index, false);
        return eos;
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

    /** Let the sink play out what it still holds before the track is called over. */
    private void drainSink() {
        long deadline = System.currentTimeMillis() + DRAIN_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline && sink.bufferedFrames() > 0) {
            sleepQuietly(20L);
        }
    }

    /** The source ended: report completion once, unless a newer request has
     *  already replaced this one. */
    private void finishSource(long serial) {
        synchronized (lock) {
            if (closed || wantedSerial != serial) return;
            desiredPlaying = false;
            opened = false;
            positionBaseMs = durationMs;
            positionBaseFrames = sink.framesWritten();
        }
        watchdogHandler.removeCallbacks(stallCheck);
        releaseWifiLock();
        releaseWakeLock();
        sink.pause();
        fire(onComplete);
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
        watchdogHandler.removeCallbacks(focusResumeCheck);
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

    /** Scheduled after a full focus LOSS: OEM builds (ColorOS among them) send
     *  LOSS when the screen turns off and never send GAIN back, which used to
     *  strand background playback silent until the app was reopened. Re-check
     *  shortly after: if nothing else is actually making sound, the loss was
     *  phantom and the intent to play stands — take focus again and resume. A
     *  real handover (another player, a video) leaves the music stream active
     *  and we stay paused. Transient losses (calls) wait for GAIN as usual. */
    private final Runnable focusResumeCheck = new Runnable() {
        @Override public void run() {
            reassertFocusResume();
        }
    };

    private void reassertFocusResume() {
        if (audioManager == null) return;
        boolean want;
        synchronized (lock) {
            want = resumeOnGain && !closed;
        }
        if (!want || audioManager.isMusicActive()) return;
        requestFocus();
        if (!hasFocus) return;
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

    private void onFocusChange(int change) {
        switch (change) {
            case AudioManager.AUDIOFOCUS_LOSS:
                // Full loss: pause now, then re-check (see reassertFocusResume).
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
                    watchdogHandler.removeCallbacks(focusResumeCheck);
                    watchdogHandler.postDelayed(focusResumeCheck, 1500L);
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                // A real interruption (call, navigation prompt): wait for GAIN.
                hasFocus = false;
                if (isPlaying()) {
                    resumeOnGain = true;
                    synchronized (lock) {
                        desiredPlaying = false;
                    }
                    sink.pause();
                    fire(onPaused);
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK: {
                float ducked;
                synchronized (lock) {
                    ducked = volumePercent / 100f * 0.3f;
                }
                sink.setVolume(ducked);
                break;
            }
            case AudioManager.AUDIOFOCUS_GAIN: {
                watchdogHandler.removeCallbacks(focusResumeCheck);
                hasFocus = true;
                boolean resume;
                float volume;
                synchronized (lock) {
                    volume = volumePercent / 100f;
                    resume = resumeOnGain || desiredPlaying;
                    resumeOnGain = false;
                    desiredPlaying = true;
                }
                sink.setVolume(volume);
                if (resume && opened) {
                    sink.play();
                    holdWifiLock();
                    acquireWakeLock();
                    fire(onResumed);
                }
                break;
            }
            default:
                break;
        }
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
