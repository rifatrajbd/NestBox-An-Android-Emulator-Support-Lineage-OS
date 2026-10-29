package io.twoyi;

import android.view.MotionEvent;
import android.view.Surface;

/**
 * JNI bridge to Twoyi's libtwoyi.so (Classic mode). The library registers these natives on this
 * exact class name in JNI_OnLoad, so the package and method signatures must not change.
 * ClassicEngine loads the library; this class does not load it by itself.
 */
public final class Renderer {
    private Renderer() {
    }

    /** Starts input, the GPU renderer and the guest's ./init on the first call; re-attaches the window later. */
    public static native void init(Surface surface, String loader, float xdpi, float ydpi, int fps);

    public static native void resetWindow(Surface surface, int top, int left, int width, int height);

    public static native void removeWindow(Surface surface);

    public static native void handleTouch(MotionEvent event);

    public static native void sendKeycode(int keycode);
}
