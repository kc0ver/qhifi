package dev.t1m3.qplayer.audio;

/**
 * How the output should be produced. Only two choices remain: the system path,
 * or the app's own userspace USB exclusive driver. The exact mode is not a
 * user-facing dropdown any more — it is decided per source by whether the "USB
 * exclusive output" switch is on and by whether a USB DAC is usable — so the
 * enum here only labels the two possible outcomes.
 */
public enum HifiOutputMode {

    /** The system path (MediaPlayer → AudioFlinger). Default, and the fallback
     *  the exclusive driver lands on when it cannot be used. */
    SYSTEM,
    /** The app's own userspace USB driver: the UAC interfaces are claimed from
     *  usbfs and the PCM goes straight to the DAC. */
    EXCLUSIVE;

    /** Persisted index → mode; anything out of range falls back to {@link #SYSTEM}. */
    public static HifiOutputMode fromId(int id) {
        HifiOutputMode[] values = values();
        return (id >= 0 && id < values.length) ? values[id] : SYSTEM;
    }

    /** Persisted index. */
    public int id() {
        return ordinal();
    }
}
