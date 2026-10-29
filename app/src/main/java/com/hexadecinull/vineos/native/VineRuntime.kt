package com.hexadecinull.vineos.`native`

import android.view.Surface

/**
 * JNI bridge to Wayondroid's libvine_runtime.so (Modern mode display). Its exported JNI symbols
 * name this exact package and object, so neither may change. Source: Wayondroid, GPL-3.0.
 */
object VineRuntime {
    val isLoaded: Boolean = try {
        System.loadLibrary("vine_runtime")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    }

    external fun getRuntimeVersion(): String
    external fun hostSupportsAArch32(): Boolean
    external fun probeHost(): String
    external fun verifyQemu(path: String): Boolean
    external fun openDisplay(vinefbPath: String): Long
    external fun closeDisplay(handle: Long)
    external fun attachSurface(handle: Long, surface: Surface?)
    external fun detachSurface(handle: Long)
    external fun setDisplayFrameRate(handle: Long, refreshHz: Int)
    external fun startRendering(handle: Long): Boolean
    external fun stopRendering(handle: Long)
    external fun displayInfo(handle: Long): LongArray
}
