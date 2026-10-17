package dev.t1m3.qplayer.audio;

/**
 * What this device can actually do, as opposed to what the user asked for
 * ({@link HifiOutputMode}) or what is happening right now
 * ({@link AudioOutputStatus}). Probed by the host — on Android from
 * {@code AudioManager} and {@code PackageManager} — and reported to the settings
 * page so a mode that cannot work on this hardware says so instead of silently
 * doing nothing.
 */
public final class HifiCapabilities {

    /** The device also declares pro-audio features (API 30+); informational. */
    public final boolean proAudioSupported;
    /** A USB exclusive driver is available (a USB DAC is present): the userspace
     *  driver is bit-perfect by construction, so this is what the chain card's
     *  bit-perfect capability bit means now. */
    public final boolean bitPerfectSupported;
    /** A USB output route is connected right now. */
    public final boolean usbOutputPresent;
    /** The framework's output buffer depth in frames; 0 when it will not say. */
    public final int framesPerBuffer;
    /** Why a capability is missing, for the chain card; "" when nothing is. */
    public final String reason;

    public HifiCapabilities(boolean proAudioSupported,
                            boolean bitPerfectSupported, boolean usbOutputPresent,
                            int framesPerBuffer, String reason) {
        this.proAudioSupported = proAudioSupported;
        this.bitPerfectSupported = bitPerfectSupported;
        this.usbOutputPresent = usbOutputPresent;
        this.framesPerBuffer = framesPerBuffer;
        this.reason = reason != null ? reason : "";
    }
}
