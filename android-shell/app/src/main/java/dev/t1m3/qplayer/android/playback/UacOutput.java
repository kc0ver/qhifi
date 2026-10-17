package dev.t1m3.qplayer.android.playback;

import java.nio.ByteBuffer;

/**
 * The native USB userspace driver (see {@code src/main/cpp/uac_output.cpp}). One
 * stream at a time, which matches how it is used: a single exclusive sink for
 * the single track that is playing.
 *
 * <p>The library is optional: a build without it (or a loader that refuses it)
 * leaves {@link #LOADED} false, and {@link UsbIsoSink} then reports why it
 * cannot open, so the dispatcher falls back instead of crashing.
 */
final class UacOutput {

    static final boolean LOADED = loadLibrary();

    private static boolean loadLibrary() {
        try {
            System.loadLibrary("qplayer_uac");
            return true;
        } catch (Throwable e) {
            dev.t1m3.qplayer.util.Logger.warn("UAC library unavailable: {}", e.toString());
            return false;
        }
    }

    private UacOutput() {}

    /** Open the DAC for a stream of {@code rate}/{@code channels}, preferring an
     *  alternate setting of {@code preferDepth} bits. {@code fd} comes from
     *  {@link android.hardware.usb.UsbDeviceConnection#getFileDescriptor()}.
     *  @return false with {@link #lastError()} set when the device refuses. */
    static native boolean open(int fd, int rate, int channels, boolean floatPcm,
                               int preferDepth);

    static native void start();

    static native void pause();

    /** Drop everything queued but not yet played (a seek). */
    static native void flush();

    /** Apply the app's 0..1 volume to the DAC's own hardware volume control. A
     *  no-op on a device whose Feature Unit has none; the samples themselves are
     *  never scaled. */
    static native void setVolume(float volume);

    /** Force the level onto the DAC's own control (hardware-volume mode). */
    static native void setHardwareVolume(float volume);

    /** Force the level onto a digital gain over the samples (software mode). */
    static native void setSoftwareVolume(float volume);

    /** True once a hardware volume control was found on the opened device. */
    static native boolean hasHardwareVolume();

    /** The DAC's own level at open, mapped to the app's 0..1 with the same
     *  curve the writes use, or -1 when it could not be read. Readable only
     *  before the isochronous stream starts. */
    static native float initialVolume();

    /** Diagnostic: log every channel's reported range, then sweep the volume
     *  down a ladder of dB values with a readback after each. Blocking — run it
     *  off the main thread. */
    static native void volProbe();

    /** Blocking write of {@code bytes} from {@code src} (a direct buffer, at its
     *  current position). @return bytes consumed, or -1 on failure. */
    static native int write(ByteBuffer src, int bytes);

    /** Frames queued in the ring and in flight, i.e. how much is still audible
     *  after the source runs dry. */
    static native long framesQueued();

    /** True once the interfaces are claimed and the stream configured — this
     *  driver really does hold the device to itself. */
    static native boolean exclusive();

    static native int sampleRate();

    static native int channels();

    static native int bitDepth();

    static native boolean floatPcm();

    /** Error token (see UsbIsoSink for the mapping) or "" when fine. */
    static native String lastError();

    /** Release claims and tear the stream down. The fd itself stays open — the
     *  Java side owns the connection. */
    static native void close();
}
