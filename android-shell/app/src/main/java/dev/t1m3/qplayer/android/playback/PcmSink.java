package dev.t1m3.qplayer.android.playback;

import java.nio.ByteBuffer;

/**
 * Where decoded PCM goes. The decode loop in
 * {@link AndroidDirectAudioBackend} is written once against this interface; the
 * only implementation is the native USB driver ({@link UsbIsoSink}), which backs
 * the single direct output mode.
 *
 * <p>Frames are counted by the sink itself rather than read back from the
 * device: a seek or a reopen rebases the caller's position on
 * {@link #framesWritten()}, and a device counter that resets (or wraps) on those
 * boundaries would make that arithmetic wrong.
 */
interface PcmSink {

    /** True between a successful {@link #open} and {@link #close}. Lets the
     *  decode loop reuse an open sink across sources instead of rebuilding the
     *  device stream for every track. */
    default boolean isOpen() { return false; }

    /**
     * Open the device for a stream of {@code rate} Hz, {@code channels} channels
     * of interleaved PCM ({@code floatPcm} selects {@code ENCODING_PCM_FLOAT}
     * over 16-bit). {@code deviceId} is the route to aim at, or -1 for whatever
     * the framework picks.
     *
     * @return false when the device cannot serve this request — the caller then
     *         falls back to the system path rather than playing nothing.
     */
    boolean open(int rate, int channels, boolean floatPcm, int deviceId);

    /** Write {@code bytes} from {@code src}, blocking until the device has room.
     *  @return bytes consumed, or -1 when the stream failed (see {@link #lastError()}). */
    int write(ByteBuffer src, int bytes);

    void play();

    void pause();

    /** Drop everything queued but not yet played (a seek). */
    void flush();

    /** Linear gain 0..1, or a no-op on a sink the framework will not let us
     *  scale (see {@link #softwareVolume()}). */
    void setVolume(float volume);

    /** Frames handed to the device since {@link #open}; monotonic across flushes. */
    long framesWritten();

    /** Frames written but not yet consumed by the device — how much is still
     *  queued when the source runs out. */
    long bufferedFrames();

    /** True when this sink has the device to itself. */
    boolean exclusive();

    /** False for a sink whose output level the framework controls (an exclusive
     *  stream has no software volume). */
    boolean softwareVolume();

    int sampleRate();

    int channels();

    boolean floatPcm();

    /** Why the last open/write failed; "" when fine. */
    String lastError();

    void close();
}
