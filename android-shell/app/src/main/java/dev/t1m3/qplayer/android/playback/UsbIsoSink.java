package dev.t1m3.qplayer.android.playback;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;

import dev.t1m3.qplayer.i18n.I18n;
import dev.t1m3.qplayer.util.Logger;

import java.nio.ByteBuffer;

/**
 * {@link PcmSink} over the native {@link UacOutput} — the EXCLUSIVE mode. This
 * one really holds the device: the UAC interfaces are claimed from usbfs, the
 * kernel's own driver is detached and AudioFlinger never sees the stream.
 *
 * <p>Consequences reported honestly to the caller: {@link #exclusive()} is only
 * true once the claim succeeded, the samples are never scaled in software
 * ({@link #softwareVolume()} is false), so {@link #setVolume} drives the DAC's own
 * hardware volume control instead, and the DAC runs at the source's own rate
 * because that is the entire point of the mode.
 */
final class UsbIsoSink implements PcmSink {

    private final Context appContext;
    private UsbDeviceConnection connection;
    private UsbDevice device;

    private int rate;
    private int channels;
    private boolean floatPcm;
    /** The source's bit depth, for the driver's alt-setting choice. */
    private int sourceDepth = 16;
    /** 0 = the app drives the DAC's own control (the app's exclusive level is
     *  written onto it), 1 = the DAC's control is never written and the app
     *  attenuates digitally. Software is also the fallback whenever the DAC
     *  answers no volume control, whatever the mode asks for. */
    private int volumeMode = 0;
    private float lastVolume = 0.8f;
    private long framesWritten;
    private volatile String lastError = "";
    private boolean opened;

    UsbIsoSink(Context context) {
        this.appContext = context.getApplicationContext();
    }

    /** The chain readout asks for the depth of the thing being played. */
    void setSourceDepth(int depth) {
        sourceDepth = depth > 0 ? Math.min(depth, 32) : 16;
    }

    /** 0 = hardware-first (the app is the one writer of the DAC's control),
     *  1 = software-first (the DAC's control is never touched; its buttons act
     *  as a pre-amp and the app attenuates digitally). Re-applies the last
     *  level under the new mode at once. */
    void setVolumeMode(int mode) {
        int wanted = mode == 1 ? 1 : 0;
        if (wanted == volumeMode) return;
        volumeMode = wanted;
        applyVolume(lastVolume);
    }

    private boolean hardwareUsable() {
        try {
            return opened && UacOutput.hasHardwareVolume();
        } catch (Throwable e) {
            return false;
        }
    }

    /** True when the current mode really drives the DAC's own control. */
    boolean volumeModeIsHardware() {
        return volumeMode == 0 && hardwareUsable();
    }

    /** True once the opened device answered a hardware volume control probe. */
    boolean hasHardwareVolume() {
        return hardwareUsable();
    }

    /** Hand the DAC's own control back before the route is released. A level the
     *  app wrote while driving that control would otherwise stay on the device and
     *  keep attenuating whatever plays next through the framework path, which
     *  mixes at full scale and expects no hidden hardware trim underneath. Nothing
     *  to hand back in software mode, which never wrote the control, nor on a DAC
     *  that answers none. The stream is stopped first: the control endpoint
     *  measurably drops requests while the isochronous stream runs, so a write
     *  issued before that would simply be lost. */
    void releaseHardwareControl() {
        if (!opened || volumeMode != 0 || !hardwareUsable()) return;
        try {
            UacOutput.pause();
        } catch (Throwable ignored) {
        }
        writeHardwareNow(1f);
    }

    /** Route {@code volume} per the current mode. Hardware mode: the app is the
     *  one writer of the DAC's own control. The
     *  two writers cannot both own one control — NetEase solves the same
     *  problem by never writing hardware volume at all — so software mode
     *  deliberately leaves the DAC's control untouched: its buttons stay the
     *  only hardware writer (a pre-amp), and the app attenuates digitally on
     *  top. No shared control, no fight. */
    private void applyVolume(float volume) {
        lastVolume = Math.max(0f, Math.min(1f, volume));
        if (volumeMode == 0 && hardwareUsable()) {
            scheduleHardwareWrite(lastVolume);
            return;
        }
        try { UacOutput.setSoftwareVolume(lastVolume); } catch (Throwable ignored) { }
    }

    /**
     * Hardware writes are throttled hard. The DAC's control endpoint measurably
     * drops requests while the isochronous stream runs, and a slider drag (or
     * the playback fade, which ticks every few dozen milliseconds) fires far
     * more writes per second than it tolerates — past some burst rate the DAC
     * stops answering and the output goes silent. So: at most one write per
     * window, with the newest level always written last as a trailing edge, so
     * a fast drag collapses into the first and final levels.
     */
    private final android.os.Handler volumeWriter =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private long lastHardwareWriteMs;
    private float trailingVolume = Float.NaN;
    private boolean trailingScheduled;

    private void scheduleHardwareWrite(float volume) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastHardwareWriteMs >= 150L) {
            writeHardwareNow(volume);
            return;
        }
        trailingVolume = volume;
        if (trailingScheduled) return;
        trailingScheduled = true;
        volumeWriter.postDelayed(() -> {
            trailingScheduled = false;
            float value = trailingVolume;
            trailingVolume = Float.NaN;
            if (!Float.isNaN(value) && opened) writeHardwareNow(value);
        }, 160L);
    }

    private void writeHardwareNow(float volume) {
        lastHardwareWriteMs = android.os.SystemClock.elapsedRealtime();
        try {
            UacOutput.setHardwareVolume(volume);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public boolean open(int sampleRate, int channelCount, boolean wantFloatPcm, int deviceId) {
        close();
        lastError = "";
        rate = Math.max(8000, sampleRate);
        channels = channelCount >= 2 ? 2 : 1;
        floatPcm = wantFloatPcm;
        if (!UacOutput.LOADED) {
            lastError = I18n.tr("settings.hifiChain.reason.usbOpenFailed");
            return false;
        }
        device = AndroidUsbAudio.findAudioDevice(appContext);
        if (device == null) {
            lastError = I18n.tr("settings.hifiChain.reason.usbRequired");
            return false;
        }
        if (!AndroidUsbAudio.hasPermission(appContext, device)) {
            // The dispatcher normally asks before getting here; this is the guard
            // for the race where the grant was revoked between the two.
            AndroidUsbAudio.requestPermission(appContext, device);
            lastError = I18n.tr("settings.hifiChain.reason.usbPermission");
            return false;
        }
        connection = AndroidUsbAudio.openConnection(appContext, device);
        if (connection == null) {
            lastError = I18n.tr("settings.hifiChain.reason.usbOpenFailed");
            return false;
        }
        int fd = connection.getFileDescriptor();
        if (fd < 0
                || !UacOutput.open(fd, rate, channels, wantFloatPcm, sourceDepth)) {
            lastError = mapError(UacOutput.lastError());
            Logger.warn("usb exclusive open failed: {}", lastError);
            close();
            return false;
        }
        // The driver may have accepted a different container than requested; the
        // readout must show what the DAC actually runs at.
        rate = UacOutput.sampleRate();
        floatPcm = UacOutput.floatPcm();
        opened = true;
        framesWritten = 0L;
        return true;
    }

    /** Native error tokens → localized card text; anything else is a raw system
     *  message that is still better than silence. */
    private static String mapError(String token) {
        if (token == null || token.isEmpty()) {
            return I18n.tr("settings.hifiChain.reason.usbOpenFailed");
        }
        if ("err:busy".equals(token) || "err:claim".equals(token)) {
            return I18n.tr("settings.hifiChain.reason.usbBusy");
        }
        if ("err:rate".equals(token) || "err:noalt".equals(token)) {
            return I18n.tr("settings.hifiChain.reason.usbRateUnsupported");
        }
        if ("err:noaudio".equals(token) || "err:desc".equals(token) || "err:fd".equals(token)) {
            return I18n.tr("settings.hifiChain.reason.usbOpenFailed");
        }
        if ("err:usbLost".equals(token)) {
            return I18n.tr("settings.hifiChain.reason.deviceGone");
        }
        return token;
    }

    @Override
    public int write(ByteBuffer src, int bytes) {
        if (!opened || bytes <= 0) return 0;
        int written = UacOutput.write(src, bytes);
        if (written < 0) {
            lastError = mapError(UacOutput.lastError());
            Logger.warn("usb exclusive write failed: {}", lastError);
            // The wire is dead (URB submission refused): close so the next open
            // re-claims the current DAC instead of reusing this fd.
            close();
            return -1;
        }
        framesWritten += written / bytesPerSourceFrame();
        return written;
    }

    private int bytesPerSourceFrame() {
        return channels * (floatPcm ? 4 : 2);
    }

    @Override
    public void play() {
        if (opened) UacOutput.start();
    }

    @Override
    public void pause() {
        if (opened) UacOutput.pause();
    }

    @Override
    public void flush() {
        if (opened) UacOutput.flush();
    }

    @Override
    public void setVolume(float volume) {
        // The bits leave unaltered in hardware mode — that is what an exclusive
        // stream is — so the level goes onto the DAC's own control. Where the
        // mode (or the DAC) asks for software, the gain is applied on the way
        // into the ring; without any of this the stream would sit at full scale.
        if (opened) applyVolume(volume);
    }

    @Override
    public long framesWritten() {
        return framesWritten;
    }

    @Override
    public long bufferedFrames() {
        if (!opened) return 0;
        try {
            return UacOutput.framesQueued();
        } catch (Throwable e) {
            return 0;
        }
    }

    @Override
    public boolean isOpen() {
        return opened;
    }

    @Override
    public boolean exclusive() {
        // Real claim state, not a request flag.
        return opened && UacOutput.exclusive();
    }

    @Override
    public boolean softwareVolume() {
        return false;
    }

    @Override
    public int sampleRate() {
        return rate;
    }

    /** True only while the same enumerated device this sink was opened on is
     *  still present. A swapped/replugged DAC must never inherit the old fd and
     *  claim: the reused sink would keep reporting the previous DAC's UAC
     *  version and feed a dead endpoint while the decoder ran at full speed. */
    boolean matchesCurrentDevice() {
        if (!opened) return false;
        UsbDevice now = AndroidUsbAudio.findAudioDevice(appContext);
        return device != null && now != null
                && device.getDeviceName().equals(now.getDeviceName());
    }

    /** Audio class of the open stream: 1, 2, or 0 when nothing is open. */
    int uacVersion() {
        return opened ? UacOutput.uacVersion() : 0;
    }

    @Override
    public int channels() {
        return channels;
    }

    @Override
    public boolean floatPcm() {
        return floatPcm;
    }

    /** The container the DAC actually plays, for the chain card. */
    int bitDepth() {
        return opened ? UacOutput.bitDepth() : 0;
    }

    @Override
    public String lastError() {
        return lastError;
    }

    @Override
    public void close() {
        if (opened) {
            try {
                UacOutput.close();
            } catch (Throwable ignored) {
            }
        }
        if (connection != null) {
            try {
                connection.close();
            } catch (Throwable ignored) {
            }
        }
        connection = null;
        device = null;
        opened = false;
    }
}
