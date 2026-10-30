package com.jfrmedia.switcher.studio.rtc

import android.content.Context
import com.jfrmedia.switcher.core.util.L
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * Cámara de la propia tablet como fuente de video del switcher.
 *
 * Se usa solo en modo horizontal: la tablet se queda fija en su base y no se gira.
 * Entrega un [VideoTrack] local, así que el lienzo y la tarjeta la tratan igual que
 * a una cámara remota (mismo compositor, mismo switch A/B, mismo PiP).
 */
class LocalCam {

    companion object {
        const val SLOT = 6
        /** Segunda cámara del mismo equipo (frontal) en el celular. */
        const val SLOT_FRONT = 7
        private const val TAG = "localcam"
        private const val ANCHO = 1280
        private const val ALTO = 720
        private const val FPS = 30
    }

    /** Slot de esta cámara en la parrilla (6 = trasera, 7 = frontal). */    var slot: Int = SLOT

    private var capturer: CameraVideoCapturer? = null
    private var helper: SurfaceTextureHelper? = null
    private var source: VideoSource? = null

    /** Pista local; null mientras no esté arrancada. */
    var track: VideoTrack? = null
        private set

    var encendida: Boolean = false
        private set

    /** Cámara en uso: true = frontal. */
    var frontal: Boolean = true
        private set

    val disponible: Boolean
        get() = Camera2Enumerator.isSupported(contexto)

    private var contexto: Context? = null

    /**
     * Arranca la captura local. Devuelve la pista para engancharla al lienzo y a la tarjeta.
     * [usarFrontal] elige la cámara; si no existe la pedida se usa la primera disponible.
     */
    fun arrancar(context: Context, usarFrontal: Boolean = true): VideoTrack? {
        if (encendida) return track
        contexto = context.applicationContext
        val app = contexto ?: return null

        val enumerador = Camera2Enumerator(app)
        val nombres = enumerador.deviceNames
        if (nombres.isEmpty()) {
            L.w(TAG, "la tablet no reporta cámaras")
            return null
        }
        val elegido = nombres.firstOrNull { enumerador.isFrontFacing(it) == usarFrontal }
            ?: nombres.first()
        frontal = enumerador.isFrontFacing(elegido)

        val cap = enumerador.createCapturer(elegido, null)
        if (cap == null) {
            L.w(TAG, "no se pudo crear el capturador")
            return null
        }
        val h = SurfaceTextureHelper.create("jfr-localcam", RtcCore.eglBase.eglBaseContext)
        val src = RtcCore.factory.createVideoSource(false)
        cap.initialize(h, app, src.capturerObserver)
        cap.startCapture(ANCHO, ALTO, FPS)

        capturer = cap
        helper = h
        source = src
        track = RtcCore.factory.createVideoTrack("tablet-cam", src)
        encendida = true
        L.i(TAG, "cámara de la tablet encendida (${if (frontal) "frontal" else "trasera"}) ${ANCHO}x$ALTO")
        return track
    }

    /**
     * Cambia entre cámara frontal y trasera sin rehacer la pista: se recrea solo el
     * capturador sobre la misma [VideoSource], así el lienzo y la tarjeta siguen enganchados.
     */
    fun cambiarCamara(usarFrontal: Boolean): Boolean {
        val app = contexto ?: return false
        val viejo = capturer ?: return false
        val h = helper ?: return false
        val src = source ?: return false

        val enumerador = Camera2Enumerator(app)
        val nombres = enumerador.deviceNames
        val elegido = nombres.firstOrNull { enumerador.isFrontFacing(it) == usarFrontal }
            ?: return false

        runCatching {
            viejo.stopCapture()
            viejo.dispose()
        }
        val cap = enumerador.createCapturer(elegido, null) ?: return false
        frontal = enumerador.isFrontFacing(elegido)
        cap.initialize(h, app, src.capturerObserver)
        cap.startCapture(ANCHO, ALTO, FPS)
        capturer = cap
        L.i(TAG, "cámara de la tablet cambiada a ${if (frontal) "frontal" else "trasera"}")
        return true
    }

    /**
     * Detiene la captura y libera la pista. Se usa al pasar a vertical o al cerrar.
     */
    fun detener() {
        val cap = capturer
        val h = helper
        val src = source
        capturer = null
        helper = null
        source = null
        track = null
        encendida = false
        try {
            cap?.stopCapture()
        } catch (t: Throwable) {
            L.w(TAG, "fallo al detener la captura: ${t.message}")
        }
        try {
            cap?.dispose()
            h?.dispose()
            src?.dispose()
        } catch (t: Throwable) {
            L.w(TAG, "fallo al liberar la cámara: ${t.message}")
        }
        L.i(TAG, "cámara de la tablet apagada")
    }
}
