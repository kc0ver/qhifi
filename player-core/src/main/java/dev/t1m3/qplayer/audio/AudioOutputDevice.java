package dev.t1m3.qplayer.audio;

/**
 * One selectable audio output route, as reported by a host that implements
 * {@link HifiOutputControl}. Flat and public-fielded so the QML bridge can read
 * it directly — same convention as the other row value objects.
 *
 * <p>The empty {@link #id} is reserved for "auto (system default)": the host
 * always offers it first, and {@link HifiOutputControl#selectOutputDevice} treats
 * it as "stop forcing a device" rather than as an unknown id.
 */
public final class AudioOutputDevice {

    /** {@code DeviceInfo.getId()} as a string, or "" for the automatic route. */
    public final String id;
    /** Product name, e.g. "iBasso DC03". */
    public final String name;
    /** {@link #ROUTE_USB} / {@link #ROUTE_BUILTIN} / {@link #ROUTE_BLUETOOTH}. */
    public final String routeType;
    /** Whether this route can carry a bit-perfect stream on this device. */
    public final boolean supportsBitPerfect;

    public static final String ROUTE_USB = "usb";
    public static final String ROUTE_BUILTIN = "builtin";
    public static final String ROUTE_BLUETOOTH = "bluetooth";

    public AudioOutputDevice(String id, String name, String routeType, boolean supportsBitPerfect) {
        this.id = id != null ? id : "";
        this.name = name != null ? name : "";
        this.routeType = routeType != null ? routeType : ROUTE_BUILTIN;
        this.supportsBitPerfect = supportsBitPerfect;
    }
}
