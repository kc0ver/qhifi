package dev.t1m3.qplayer.android.playback;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.os.Build;

import dev.t1m3.qplayer.audio.AudioOutputDevice;
import dev.t1m3.qplayer.audio.HifiCapabilities;
import dev.t1m3.qplayer.i18n.I18n;

import java.util.ArrayList;
import java.util.List;

/**
 * Every question the HiFi feature asks the platform, in one place: which routes
 * exist, which one the framework is using, whether a route can do bit-perfect,
 * and what the framework's own output format is. Shared by the system backend
 * (which reports the chain in {@code SYSTEM} mode) and the dispatcher (which
 * needs the same knowledge to pick a route for the direct modes).
 *
 * <p>Static and Android-only by construction; nothing here keeps state, so it is
 * safe to call from any thread.
 */
final class AndroidHifiProbe {

    private AndroidHifiProbe() {}

    /** {@link AudioOutputDevice#ROUTE_USB} / ROUTE_BLUETOOTH / ROUTE_BUILTIN, or
     *  null for a route the picker does not offer (HDMI, remote submix, ...). */
    static String routeTypeOf(AudioDeviceInfo device) {
        if (device == null) return AudioOutputDevice.ROUTE_BUILTIN;
        switch (device.getType()) {
            case AudioDeviceInfo.TYPE_USB_DEVICE:
            case AudioDeviceInfo.TYPE_USB_HEADSET:
            case AudioDeviceInfo.TYPE_USB_ACCESSORY:
                return AudioOutputDevice.ROUTE_USB;
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                return AudioOutputDevice.ROUTE_BLUETOOTH;
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE:
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                return AudioOutputDevice.ROUTE_BUILTIN;
            default:
                return null;
        }
    }

    static int routeRank(AudioDeviceInfo device) {
        String type = routeTypeOf(device);
        if (AudioOutputDevice.ROUTE_USB.equals(type)) return 0;
        if (AudioOutputDevice.ROUTE_BLUETOOTH.equals(type)) return 1;
        if (AudioOutputDevice.ROUTE_BUILTIN.equals(type)) return 2;
        return 3;
    }

    static String productName(AudioDeviceInfo device) {
        CharSequence name = device != null ? device.getProductName() : null;
        String value = name != null ? name.toString().trim() : "";
        return value.isEmpty() ? I18n.tr("settings.hifiOutputDevice.auto") : value;
    }

    /** The route the framework is actually using right now. Playback still runs
     *  through the system path unless a direct mode is active, so the device the
     *  user picked in the settings has nothing to do with it — reporting the pick
     *  here would claim an output that is not happening. Only connected routes are
     *  considered, and the mixer routes USB over Bluetooth over the built-ins, so
     *  that same priority order answers "where does this come out". */
    static AudioDeviceInfo activeOutput(AudioManager audioManager) {
        if (audioManager == null) return null;
        AudioDeviceInfo best = null;
        int bestRank = Integer.MAX_VALUE;
        for (AudioDeviceInfo device : outputs(audioManager)) {
            int rank = routeRank(device);
            if (rank < bestRank) {
                bestRank = rank;
                best = device;
            }
        }
        return best;
    }

    static List<AudioDeviceInfo> outputs(AudioManager audioManager) {
        List<AudioDeviceInfo> out = new ArrayList<>();
        if (audioManager == null) return out;
        AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        if (devices == null) return out;
        for (AudioDeviceInfo device : devices) {
            if (device != null && device.isSink()) out.add(device);
        }
        return out;
    }

    /** Connected USB sink routes; every direct mode aims at one of these. */
    static List<AudioDeviceInfo> usbOutputs(AudioManager audioManager) {
        List<AudioDeviceInfo> out = new ArrayList<>();
        for (AudioDeviceInfo device : outputs(audioManager)) {
            if (AudioOutputDevice.ROUTE_USB.equals(routeTypeOf(device))) out.add(device);
        }
        return out;
    }

    static AudioDeviceInfo usbOutput(AudioManager audioManager) {
        List<AudioDeviceInfo> usb = usbOutputs(audioManager);
        return usb.isEmpty() ? null : usb.get(0);
    }

    static AudioDeviceInfo deviceById(AudioManager audioManager, String id) {
        if (id == null || id.isEmpty()) return null;
        for (AudioDeviceInfo device : outputs(audioManager)) {
            if (id.equals(String.valueOf(device.getId()))) return device;
        }
        return null;
    }

    /** The device a direct mode should aim at: the one the user picked when it is
     *  still connected, else the connected USB route, else the framework's own. */
    static AudioDeviceInfo targetDevice(AudioManager audioManager, String selectedDeviceId) {
        AudioDeviceInfo selected = deviceById(audioManager, selectedDeviceId);
        if (selected != null && AudioOutputDevice.ROUTE_USB.equals(routeTypeOf(selected))) {
            return selected;
        }
        AudioDeviceInfo usb = usbOutput(audioManager);
        return usb != null ? usb : activeOutput(audioManager);
    }

    /** Whether a route can carry a bit-perfect stream: only a USB route can,
     *  because the app's userspace driver is what makes the output bit-perfect,
     *  and it drives USB DACs. */
    static boolean bitPerfectCapable(AudioDeviceInfo device) {
        return AudioOutputDevice.ROUTE_USB.equals(routeTypeOf(device));
    }

    static int intProperty(AudioManager audioManager, String key) {
        if (audioManager == null) return 0;
        try {
            String value = audioManager.getProperty(key);
            if (value != null && !value.trim().isEmpty()) {
                int parsed = Integer.parseInt(value.trim());
                if (parsed > 0) return parsed;
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /** The framework's own mix rate, i.e. what it feeds the HAL on the system
     *  path (a USB HAL may convert again after that). */
    static int mixRate(AudioManager audioManager) {
        int rate = intProperty(audioManager, AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE);
        return rate > 0 ? rate : 48000;
    }

    /** Depth of the framework's own PCM stream on the system path: MediaPlayer
     *  decodes into AudioFlinger, which mixes 16-bit PCM, so a deeper source is
     *  truncated there before the HAL ever sees it. */
    static final int MIXER_BIT_DEPTH = 16;

    static HifiCapabilities capabilities(Context context, AudioManager audioManager) {
        boolean usbPresent = !usbOutputs(audioManager).isEmpty();
        int framesPerBuffer = intProperty(audioManager, AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER);
        PackageManager pm = context != null ? context.getPackageManager() : null;
        boolean proAudio = Build.VERSION.SDK_INT >= 30 && pm != null
                && pm.hasSystemFeature(PackageManager.FEATURE_AUDIO_PRO);
        // The userspace USB driver is bit-perfect by construction, so a connected
        // USB DAC is the whole capability — not a ROM-declared mixer attribute.
        boolean bitPerfect = usbPresent;
        // Only the "nothing to play through" case needs saying here: a mode this
        // device cannot do at all is reported by the chain card's capability bits,
        // and a mode that failed to open is reported by the dispatcher.
        String reason = usbPresent ? "" : I18n.tr("settings.hifiChain.reason.usbRequired");
        return new HifiCapabilities(proAudio, bitPerfect, usbPresent,
                framesPerBuffer, reason);
    }

    static int intMetadata(MediaMetadataRetriever retriever, int key) {
        try {
            String value = retriever.extractMetadata(key);
            if (value == null) return 0;
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** Codec name as the chain card should spell it, e.g. FLAC. */
    static String encodingLabel(String mime) {
        if (mime == null) return "";
        switch (mime.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "audio/flac":
            case "audio/x-flac":
                return "FLAC";
            case "audio/mpeg":
                return "MP3";
            case "audio/mp4":
            case "audio/mp4a-latm":
            case "audio/aac":
                return "AAC";
            case "audio/ogg":
            case "audio/vorbis":
            case "audio/opus":
                return "OGG";
            case "audio/raw":
            case "audio/wav":
            case "audio/x-wav":
            case "audio/x-pcm":
                return "WAV";
            default:
                return "";
        }
    }
}
