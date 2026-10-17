package dev.t1m3.qplayer.android.playback;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * The UsbManager plumbing the USB driver needs: find the connected DAC, ask the
 * user for it once (the system remembers the grant per device), and hand the
 * connection's fd to the native driver.
 */
public final class AndroidUsbAudio {

    /** Broadcast action of the permission result; also what the request targets. */
    static final String ACTION_PERMISSION =
            "com.kc0ver.qhifi.USB_PERMISSION";

    /** UAC class-level subclass codes; android.hardware.usb.UsbConstants only
     *  defines the top-level USB_CLASS_AUDIO, so the subclasses are spelled out. */
    private static final int SUBCLASS_AUDIOCONTROL = 1;
    private static final int SUBCLASS_AUDIOSTREAMING = 2;

    private AndroidUsbAudio() {}

    private static UsbManager manager(Context context) {
        return (UsbManager) context.getSystemService(Context.USB_SERVICE);
    }

    /** Every connected device that exposes a real UAC audio endpoint, in list
     *  order. A composite device that merely carries a class-1 interface (a HID
     *  gadget, a hub with an audio child) is not a DAC and is left out. */
    public static List<UsbDevice> listAudioDevices(Context context) {
        List<UsbDevice> out = new ArrayList<>();
        UsbManager usb = manager(context);
        if (usb == null) return out;
        HashMap<String, UsbDevice> devices = usb.getDeviceList();
        if (devices == null) return out;
        for (UsbDevice device : devices.values()) {
            if (device != null && hasStreamingInterface(device)) out.add(device);
        }
        return out;
    }

    /** The device the driver should claim: prefer one that also exposes an
     *  AudioControl interface (a fully UAC-compliant DAC), else the first with a
     *  streaming endpoint. Multiple DACs are not distinguished — the common case
     *  is one DAC per phone. */
    static UsbDevice findAudioDevice(Context context) {
        UsbDevice fallback = null;
        for (UsbDevice device : listAudioDevices(context)) {
            if (hasControlInterface(device)) return device;
            if (fallback == null) fallback = device;
        }
        return fallback;
    }

    /** A real UAC endpoint: bInterfaceClass == AUDIO and bInterfaceSubClass ==
     *  AUDIOSTREAMING (1/2). */
    private static boolean hasStreamingInterface(UsbDevice device) {
        return hasAudioInterface(device, SUBCLASS_AUDIOSTREAMING);
    }

    /** The UAC control interface: class AUDIO, subclass AUDIOCONTROL (1/1). */
    private static boolean hasControlInterface(UsbDevice device) {
        return hasAudioInterface(device, SUBCLASS_AUDIOCONTROL);
    }

    private static boolean hasAudioInterface(UsbDevice device, int subClass) {
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);
            if (iface != null
                    && iface.getInterfaceClass() == UsbConstants.USB_CLASS_AUDIO
                    && iface.getInterfaceSubclass() == subClass) {
                return true;
            }
        }
        return false;
    }

    /** A real name for the picker, falling back through manufacturer → device
     *  path → VID:PID when the descriptor carries no string. */
    public static String displayName(UsbDevice device) {
        if (device == null) return "";
        String name = device.getProductName();
        if (name == null || name.trim().isEmpty()) name = device.getManufacturerName();
        if (name == null || name.trim().isEmpty()) name = device.getDeviceName();
        if (name == null || name.trim().isEmpty()) {
            name = String.format(java.util.Locale.ROOT, "USB %04x:%04x",
                    device.getVendorId(), device.getProductId());
        }
        return name.trim();
    }

    static boolean hasPermission(Context context, UsbDevice device) {
        UsbManager usb = manager(context);
        return usb != null && device != null && usb.hasPermission(device);
    }

    /** Pop the system dialog asking for this device. The result lands on
     *  {@link #ACTION_PERMISSION}; nothing else happens this round — the next
     *  track (or replay) uses the driver once granted. */
    static void requestPermission(Context context, UsbDevice device) {
        UsbManager usb = manager(context);
        if (usb == null || device == null) return;
        Intent intent = new Intent(ACTION_PERMISSION).setPackage(context.getPackageName());
        int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
        PendingIntent pending = PendingIntent.getBroadcast(context, 0, intent, flags);
        try {
            usb.requestPermission(device, pending);
        } catch (Throwable e) {
            dev.t1m3.qplayer.util.Logger.warn("usb permission request failed: {}", e.toString());
        }
    }

    /** Open the device for the native driver. The caller owns the connection and
     *  must close it after {@link UacOutput#close()}. */
    static UsbDeviceConnection openConnection(Context context, UsbDevice device) {
        UsbManager usb = manager(context);
        if (usb == null || device == null) return null;
        try {
            return usb.openDevice(device);
        } catch (Throwable e) {
            dev.t1m3.qplayer.util.Logger.warn("usb open failed: {}", e.toString());
            return null;
        }
    }
}
