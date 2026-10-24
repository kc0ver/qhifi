package dev.t1m3.qplayer.audio;

import java.util.List;

/**
 * Optional capability of an {@link AudioBackend}: enumerate output routes and
 * report what the audio chain is currently doing, and (where the platform can)
 * steer playback onto a chosen route with a bit-perfect stream.
 *
 * <p>Deliberately kept out of {@link AudioBackend} itself so the desktop
 * contract stays unchanged — desktop backends simply do not implement this, and
 * the controller treats that as "HiFi output unsupported" via {@code instanceof}.
 */
public interface HifiOutputControl {

    /**
     * Every route the user can pick, automatic first (see
     * {@link AudioOutputDevice}). Never empty on a host that implements this:
     * the automatic entry alone is always available.
     */
    List<AudioOutputDevice> outputDevices();

    /** Snapshot of the current chain; called off the render thread. */
    AudioOutputStatus outputStatus();

    /** What this device can do, whatever mode the user picked; called off the
     *  render thread. Carries the capability bits the chain card shows, so a mode
     *  that cannot work here says so instead of silently doing nothing. */
    HifiCapabilities capabilities();

    /** Turn the direct path on or off. When on, the backend uses the app's own
     *  USB exclusive driver from the next source on; when it cannot, it must not
     *  fail loudly: fall back to {@link HifiOutputMode#SYSTEM} and record why in
     *  the status. */
    void setHifiEnabled(boolean on);

    /** Experimental: hold a silent framework AudioTrack for as long as the
     *  exclusive stream plays. The exclusive path bypasses the framework entirely
     *  (no AudioTrack exists under this app's uid), and ROMs that judge an app by
     *  the platform's own audio bookkeeping may suspend such a process in the
     *  background — the session still says "playing" while the output goes quiet.
     *  A no-op on hosts with no exclusive path. */
    default void setKeepAliveTrack(boolean on) {
    }

    /** Output at the source's own rate instead of the framework's mix rate. */
    void setFollowSampleRate(boolean on);

    /** Live answer to "does the app's volume slider drive a digital gain right
     *  now — the gain the perceptual volume curve was built for?". A hardware
     *  level is already a dB mapping of its own, so curving it again would double
     *  the steepness, and the framework path keeps the host's plain linear app
     *  volume instead of our curve. Hosts whose slider is always digital return
     *  true; the Android dispatcher answers true only while its exclusive stream
     *  is up with the slider on the digital end. Default true so older hosts and
     *  test doubles keep compiling. */
    default boolean softwareVolumeActive() {
        return true;
    }

    /** Live answer to "is the app's volume slider driving the exclusive stream
     *  right now?". The exclusive stream keeps its own level, separate from the
     *  framework's: one slider would otherwise hand the level set on the USB path
     *  straight to the mixed path (and back) on every switch. False wherever no
     *  exclusive stream owns the output — the framework route, a fallback, and
     *  desktop all fall back to the host's own app volume. Default false so older
     *  hosts and test doubles keep compiling. */
    default boolean exclusiveLevelActive() {
        return false;
    }

    /** Diagnostic for the {@code focusdump} debug command: the audio-focus state
     *  the active backend currently holds (focus, pending call resume, call mode).
     *  Empty on hosts with no audio focus. */
    default String focusState() {
        return "";
    }

    /** Audio class of the USB stream that is open right now: 1 (UAC1), 2 (UAC2),
     *  or 0 when no exclusive stream is up. Default 0 so hosts without a USB
     *  driver (desktop, test doubles) need no change. */
    default int uacVersion() {
        return 0;
    }

    /** Which end the app's volume slider drives on an exclusive stream:
     *  0 = hardware (the DAC's own control, samples locked at full scale),
     *  1 = software (the DAC held at max, digital attenuation). A no-op where
     *  the host has no exclusive driver. Default empty so older hosts and test
     *  doubles keep compiling. */
    default void setVolumeMode(int mode) {
    }

    /** Force playback onto {@code deviceId}; "" means automatic. */
    void selectOutputDevice(String deviceId);
}
