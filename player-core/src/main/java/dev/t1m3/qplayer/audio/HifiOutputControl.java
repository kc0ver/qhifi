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

    /** Output at the source's own rate instead of the framework's mix rate. */
    void setFollowSampleRate(boolean on);

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
