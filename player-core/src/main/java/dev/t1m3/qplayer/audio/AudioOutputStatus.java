package dev.t1m3.qplayer.audio;

/**
 * Immutable snapshot of what the audio chain is doing right now: where the sound
 * goes, what the source is, what the framework negotiated for it, and whether
 * anything was lost on the way. Produced by a host's {@link HifiOutputControl}
 * and published to the UI as read-only text; never crosses into QML as an object
 * (the controller copies it into plain String/Boolean properties).
 *
 * <p>A zero/false formatter on any field means "not known", not "zero hertz":
 * a streaming source whose header has not been inspected leaves the source
 * fields at 0 and the UI simply shows the fields it does have.
 */
public final class AudioOutputStatus {

    /** How exclusive the current output really is. */
    public static final String EXCLUSIVE_NONE = "none";
    /** The device is held alone, but the stream is not the source's own samples
     *  (the platform still converts, or the sink was opened shared). */
    public static final String EXCLUSIVE_PARTIAL = "partial";
    /** Device held alone with a stream the framework does not resample. */
    public static final String EXCLUSIVE_FULL = "full";

    // --- where it goes ---
    public final String deviceId;
    public final String deviceName;
    public final String routeType;

    // --- what went in ---
    public final String sourceEncoding;
    public final int sourceSampleRate;
    public final int sourceBitDepth;

    // --- what comes out ---
    public final String outputEncoding;
    public final int outputSampleRate;
    public final int outputBitDepth;

    /** True when the framework had to change the rate or the depth to output it. */
    public final boolean resampled;
    public final boolean bitPerfectSupported;
    public final boolean bitPerfectActive;
    /** Human-readable explanation when something is unavailable; "" when fine. */
    public final String reason;

    // --- routing (filled in by whoever actually dispatches, see withRouting) ---
    /** What the user asked for. */
    public final HifiOutputMode requestedMode;
    /** What is really being used; differs from {@link #requestedMode} when the
     *  requested mode could not be used on this device/source. */
    public final HifiOutputMode effectiveMode;
    /** {@link #EXCLUSIVE_NONE} / {@link #EXCLUSIVE_PARTIAL} / {@link #EXCLUSIVE_FULL}. */
    public final String exclusiveState;
    /** Why the requested mode was not used; "" when it was. */
    public final String fallbackReason;
    /** Which end the app's volume slider actually controls: "hardware" (the
     *  DAC's own control, samples untouched), "software" (a digital gain), or ""
     *  when the chain does not say. The volume readout labels itself with this. */
    public final String volumeKind;

    public AudioOutputStatus(String deviceId, String deviceName, String routeType,
                             String sourceEncoding, int sourceSampleRate, int sourceBitDepth,
                             String outputEncoding, int outputSampleRate, int outputBitDepth,
                             boolean resampled, boolean bitPerfectSupported, boolean bitPerfectActive,
                             String reason) {
        this(deviceId, deviceName, routeType,
                sourceEncoding, sourceSampleRate, sourceBitDepth,
                outputEncoding, outputSampleRate, outputBitDepth,
                resampled, bitPerfectSupported, bitPerfectActive, reason,
                HifiOutputMode.SYSTEM, HifiOutputMode.SYSTEM, EXCLUSIVE_NONE, "");
    }

    public AudioOutputStatus(String deviceId, String deviceName, String routeType,
                             String sourceEncoding, int sourceSampleRate, int sourceBitDepth,
                             String outputEncoding, int outputSampleRate, int outputBitDepth,
                             boolean resampled, boolean bitPerfectSupported, boolean bitPerfectActive,
                             String reason, HifiOutputMode requestedMode,
                             HifiOutputMode effectiveMode, String exclusiveState,
                             String fallbackReason) {
        this(deviceId, deviceName, routeType,
                sourceEncoding, sourceSampleRate, sourceBitDepth,
                outputEncoding, outputSampleRate, outputBitDepth,
                resampled, bitPerfectSupported, bitPerfectActive, reason,
                requestedMode, effectiveMode, exclusiveState, fallbackReason, "");
    }

    public AudioOutputStatus(String deviceId, String deviceName, String routeType,
                             String sourceEncoding, int sourceSampleRate, int sourceBitDepth,
                             String outputEncoding, int outputSampleRate, int outputBitDepth,
                             boolean resampled, boolean bitPerfectSupported, boolean bitPerfectActive,
                             String reason, HifiOutputMode requestedMode,
                             HifiOutputMode effectiveMode, String exclusiveState,
                             String fallbackReason, String volumeKind) {
        this.deviceId = deviceId != null ? deviceId : "";
        this.deviceName = deviceName != null ? deviceName : "";
        this.routeType = routeType != null ? routeType : AudioOutputDevice.ROUTE_BUILTIN;
        this.sourceEncoding = sourceEncoding != null ? sourceEncoding : "";
        this.sourceSampleRate = sourceSampleRate;
        this.sourceBitDepth = sourceBitDepth;
        this.outputEncoding = outputEncoding != null ? outputEncoding : "";
        this.outputSampleRate = outputSampleRate;
        this.outputBitDepth = outputBitDepth;
        this.resampled = resampled;
        this.bitPerfectSupported = bitPerfectSupported;
        this.bitPerfectActive = bitPerfectActive;
        this.reason = reason != null ? reason : "";
        this.requestedMode = requestedMode != null ? requestedMode : HifiOutputMode.SYSTEM;
        this.effectiveMode = effectiveMode != null ? effectiveMode : HifiOutputMode.SYSTEM;
        this.exclusiveState = exclusiveState != null ? exclusiveState : EXCLUSIVE_NONE;
        this.fallbackReason = fallbackReason != null ? fallbackReason : "";
        this.volumeKind = volumeKind != null ? volumeKind : "";
    }

    /** Same measurements, re-labelled with who actually did the dispatching: the
     *  backend that played a source does not know which mode the user picked,
     *  whether the dispatcher had to fall back, or whether the stream really came
     *  out bit-perfect, so the dispatcher layers that on top of whatever the
     *  backends reported. */
    public static AudioOutputStatus withRouting(AudioOutputStatus base, HifiOutputMode requested,
                                                HifiOutputMode effective, String exclusiveState,
                                                boolean bitPerfectActive, String fallbackReason) {
        if (base == null) {
            return new AudioOutputStatus("", "", AudioOutputDevice.ROUTE_BUILTIN,
                    "", 0, 0, "", 0, 0, false, false, bitPerfectActive, "",
                    requested, effective, exclusiveState, fallbackReason, "");
        }
        return new AudioOutputStatus(base.deviceId, base.deviceName, base.routeType,
                base.sourceEncoding, base.sourceSampleRate, base.sourceBitDepth,
                base.outputEncoding, base.outputSampleRate, base.outputBitDepth,
                base.resampled, base.bitPerfectSupported, bitPerfectActive, base.reason,
                requested, effective, exclusiveState, fallbackReason, base.volumeKind);
    }
}
