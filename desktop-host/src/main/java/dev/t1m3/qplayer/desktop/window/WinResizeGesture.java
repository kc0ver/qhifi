package dev.t1m3.qplayer.desktop.window;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinDef.LRESULT;
import com.sun.jna.platform.win32.WinDef.WPARAM;

import org.lwjgl.glfw.GLFWNativeWin32;

/**
 * Windows-only: reports the boundaries of a modal move/resize gesture.
 *
 * <p>While the user holds a window edge, Windows keeps pumping WM_SIZE from its
 * own modal loop. Rebuilding the Skia surface (and, for Vulkan, the whole
 * swapchain) for each of those intermediate sizes can block the render thread for
 * whole seconds on some drivers, which is what made live resize feel frozen. The
 * host instead records only the size the drag ends on and lets DWM stretch the
 * last presented frame in between.
 *
 * <p>Installed for both title-bar modes and subclassed underneath
 * {@link WinFrameless} where that is active, so the two chains never fight.
 */
final class WinResizeGesture {

    private static final int GWLP_WNDPROC = -4;
    private static final int WM_ENTERSIZEMOVE = 0x0231;
    private static final int WM_EXITSIZEMOVE = 0x0232;

    private WinFrameless.WndProc wndProc;   // strong ref: JNA callbacks must not be GC'd
    private Pointer originalWndProc;

    /** Subclass the window; returns null when the Win32 handle is unavailable. */
    static WinResizeGesture install(DesktopWindow window) {
        long hwndLong;
        try {
            hwndLong = GLFWNativeWin32.glfwGetWin32Window(window.window());
        } catch (Throwable unavailable) {
            return null;
        }
        if (hwndLong == 0L) return null;
        WinResizeGesture gesture = new WinResizeGesture();
        gesture.install(new HWND(Pointer.createConstant(hwndLong)), window);
        return gesture;
    }

    private void install(HWND hwnd, DesktopWindow window) {
        wndProc = (h, msg, wParam, lParam) -> {
            if (msg == WM_ENTERSIZEMOVE) window.onInteractiveMoveResizeStart();
            else if (msg == WM_EXITSIZEMOVE) window.onInteractiveMoveResizeEnd();
            return WinFrameless.U32.I.CallWindowProcW(originalWndProc, h, msg, wParam, lParam);
        };
        originalWndProc = WinFrameless.U32.I.SetWindowLongPtrW(hwnd, GWLP_WNDPROC, wndProc);
    }
}
