package com.jfrmedia.switcher.studio.canvas

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.view.Surface

/**
 * EGL mínimo y explícito (sin EglBase de WebRTC) para poder:
 *  - dibujar en la pantalla,
 *  - dibujar el mismo contenido en la superficie de entrada del codificador H.264
 *    con marca de tiempo real por cuadro (sin ella, el MP4 sale con tiempos rotos).
 */
class EglCore {

    lateinit var display: EGLDisplay
        private set
    lateinit var context: EGLContext
        private set
    private lateinit var config: EGLConfig

    var initialized = false
        private set

    fun init(): Boolean {
        if (initialized) return true
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return false
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) return false

        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE
        )
        val num = IntArray(1)
        val configs = arrayOfNulls<EGLConfig>(1)
        if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) || num[0] == 0) return false
        config = configs[0] ?: return false

        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        if (context == EGL14.EGL_NO_CONTEXT) return false

        initialized = true
        return true
    }

    fun createSurface(surface: Surface): EGLSurface {
        val attribs = intArrayOf(EGL14.EGL_NONE)
        return EGL14.eglCreateWindowSurface(display, config, surface, attribs, 0)
    }

    fun makeCurrent(surface: EGLSurface): Boolean =
        EGL14.eglMakeCurrent(display, surface, surface, context)

    fun makeNothingCurrent() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
    }

    /**
     * 0 = no sincronizar con el reloj del sistema. En la superficie del codificador evita
     * que el driver descarte cuadros al ritmo del compositor del sistema.
     */
    fun setSwapInterval(interval: Int) {
        EGL14.eglSwapInterval(display, interval)
    }

    fun swap(surface: EGLSurface, presentationTimeNs: Long): Boolean {
        if (presentationTimeNs > 0) {
            EGLExt.eglPresentationTimeANDROID(display, surface, presentationTimeNs)
        }
        val ok = EGL14.eglSwapBuffers(display, surface)
        if (!ok) {
            val err = EGL14.eglGetError()
            if (err != lastSwapError) {
                lastSwapError = err
                android.util.Log.w("JFR", "[egl] swapBuffers falló, error 0x${Integer.toHexString(err)}")
            }
        }
        return ok
    }

    private var lastSwapError = 0

    fun releaseSurface(surface: EGLSurface?) {
        if (surface != null && surface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(display, surface)
        }
    }

    fun release() {
        if (!initialized) return
        makeNothingCurrent()
        EGL14.eglDestroyContext(display, context)
        EGL14.eglTerminate(display)
        initialized = false
    }
}
