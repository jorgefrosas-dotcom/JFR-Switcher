package com.jfrmedia.switcher.studio.stream

import android.content.Context
import android.media.MediaRecorder
import android.view.Surface
import android.graphics.SurfaceTexture
import com.jfrmedia.switcher.core.util.L
import com.jfrmedia.switcher.studio.audio.AudioEq
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.audio.CustomAudioEffect
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.video.NoVideoSource
import com.pedro.library.rtmp.RtmpStream
import kotlin.math.abs

/**
 * Motor de salida del switcher sobre RootEncoder.
 *
 * Codifica **una sola vez** lo que dibuja el compositor y lo reparte a:
 *  - la transmisión RTMP/RTMPS (Facebook, YouTube, etc.),
 *  - el archivo MP4 local, si se está grabando.
 *
 * El video entra como un [Surface] que se le pide a RootEncoder
 * ([superficieDeVideo]); el compositor dibuja ahí en vez de hacerlo en su propia
 * ventana. El audio entra por el micrófono del sistema y pasa por nuestro
 * ecualizador antes de codificarse.
 */
class Emisor(
    contexto: Context,
    alEstadoInicial: (Estado, String) -> Unit
) : ConnectChecker {

    /**
     * A quién avisar de los cambios de estado.
     *
     * Es `var` a propósito: la pantalla que escucha cambia cuando Android la recrea, pero el
     * emisor sigue siendo el mismo (vive en [Emision]). Si fuera fijo, la pantalla nueva se
     * quedaría sin noticias y mostraría «Transmitir» con el directo en el aire.
     */
    @Volatile
    var alEstado: (Estado, String) -> Unit = alEstadoInicial

    /** Ancho y alto configurados (los necesita el compositor al reconectarse). */
    val anchoVideo: Int get() = ancho
    val altoVideo: Int get() = alto

    /** Superficie para que el compositor dibuje el programa, o null si no está preparado. */
    fun superficieParaCompositor(): Surface? = superficie?.let { Surface(it) }

    /** Estado de la salida, para reflejarlo en la interfaz. */
    enum class Estado { LISTO, CONECTANDO, TRANSMITIENDO, GRABANDO, ERROR, DETENIDO }

    private val microfono = MicrophoneSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
    private val flujo = RtmpStream(contexto, this, NoVideoSource(), microfono)

    /** Ecualizador que se aplica al audio antes de codificar. */
    val eq = AudioEq(44100)

    /** Volumen lineal de entrada (ganancia). */
    @Volatile
    var ganancia: Float = 1f

    /** Silencia el audio sin cortar la transmisión. */
    @Volatile
    var silencio: Boolean = false

    /** Nivel actual del audio (0..1), para el medidor. */
    @Volatile
    var nivel: Float = 0f
        private set

    var transmitiendo: Boolean = false
        private set

    /**
     * El cliente RTMP detecta que la red no aguanta el bitrate (su cola de envío crece).
     * Es la señal honesta de «red floja»: no depende de lo que pese la imagen.
     */
    val congestionada: Boolean
        get() = runCatching { flujo.getStreamClient().hasCongestion() }.getOrDefault(false)

    /**
     * Avisa cuando el MP4 de respaldo quedó cerrado, con su ruta. La pantalla lo usa para
     * publicarlo en la galería al detener el directo.
     */
    @Volatile
    var alCerrarGrabacion: ((String) -> Unit)? = null

    /** Compensa la inversión de Y de la salida de RootEncoder (SurfaceTexture). */
    var volteoVertical: Boolean = false
    var grabando: Boolean = false
        private set

    private var superficie: SurfaceTexture? = null
    private var ancho = 1280
    private var alto = 720
    private var listo = false

    init {
        microfono.setAudioEffect(object : CustomAudioEffect() {
            override fun process(pcmBuffer: ByteArray): ByteArray {
                procesarAudio(pcmBuffer)
                return pcmBuffer
            }
        })
    }

    /** Aplica silencio, ecualizador y ganancia al bloque de audio; mide el nivel. */
    private fun procesarAudio(pcm: ByteArray) {
        if (silencio) {
            java.util.Arrays.fill(pcm, 0.toByte())
            nivel = 0f
            return
        }
        val bytes = pcm.size
        if (eq.activo) {
            eq.procesar(pcm, bytes, ganancia)
        } else if (ganancia != 1f) {
            var i = 0
            while (i + 1 < bytes) {
                val lo = pcm[i].toInt() and 0xFF
                val hi = pcm[i + 1].toInt()
                var s = ((hi shl 8) or lo).toShort().toInt()
                s = (s * ganancia).toInt().coerceIn(-32768, 32767)
                pcm[i] = (s and 0xFF).toByte()
                pcm[i + 1] = ((s shr 8) and 0xFF).toByte()
                i += 2
            }
        }
        // Nivel aproximado (pico del bloque).
        var pico = 0
        var i = 0
        while (i + 1 < bytes) {
            val s = (((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort()).toInt()
            if (abs(s) > pico) pico = abs(s)
            i += 2
        }
        nivel = (pico / 32768f).coerceIn(0f, 1f)
    }

    // ------------------------------------------------------------------ video

    /**
     * Prepara los codificadores y devuelve la superficie donde el compositor debe
     * dibujar el programa. Devuelve null si el equipo no soporta la configuración.
     *
     * [vertical] le dice a RootEncoder la forma del cuadro: su cálculo de viewport
     * (`SizeCalculator.calculateViewPortEncoder`) asume entrada horizontal, así que con un
     * lienzo 9:16 y esta bandera en false encaja la imagen con barras negras arriba y abajo
     * (visto en el directo vertical del 27/10: 720×1280 con el cuadro a 720×405).
     */
    fun preparar(anchoVideo: Int, altoVideo: Int, kbps: Int, fps: Int, vertical: Boolean): Surface? {
        if (listo) return superficie?.let { Surface(it) }
        ancho = anchoVideo
        alto = altoVideo
        return try {
            val video = flujo.prepareVideo(
                width = ancho,
                height = alto,
                bitrate = kbps * 1000,
                fps = fps,
                iFrameInterval = 2
            )
            val audio = flujo.prepareAudio(
                sampleRate = 44100,
                isStereo = false,
                bitrate = 128 * 1024,
                echoCanceler = false,
                noiseSuppressor = false
            )
            if (!video || !audio) {
                L.w(TAG, "no se pudo preparar el codificador (video=$video audio=$audio)")
                alEstado(Estado.ERROR, "El equipo no soporta esta configuración")
                return null
            }
            // El SurfaceTexture solo existe cuando el GL interno está arrancado.
            val gl = flujo.getGlInterface()
            if (!gl.isRunning) gl.start()
            // Nuestro compositor ya entrega el cuadro derecho: se anula la rotación que
            // RootEncoder aplica por defecto (270°) y se compensa el volteo vertical.
            gl.setCameraOrientation(0)
            gl.setIsPortrait(vertical)
            gl.setStreamRotation(0)
            gl.setIsStreamVerticalFlip(volteoVertical)
            val st = gl.surfaceTexture
            st.setDefaultBufferSize(ancho, alto)
            superficie = st
            listo = true
            L.i(TAG, "emisor listo ${ancho}x$alto @ ${kbps} kbps")
            alEstado(Estado.LISTO, "Listo")
            Surface(st)
        } catch (t: Throwable) {
            L.e(TAG, "preparar emisor: ${t.message}", t)
            alEstado(Estado.ERROR, t.message ?: "Error al preparar")
            null
        }
    }

    // --------------------------------------------------------------- transmisión

    fun transmitir(url: String): Boolean {
        if (transmitiendo) return true
        return try {
            flujo.startStream(url)
            transmitiendo = true
            alEstado(Estado.CONECTANDO, "Conectando…")
            true
        } catch (t: Throwable) {
            L.e(TAG, "transmitir: ${t.message}", t)
            alEstado(Estado.ERROR, t.message ?: "No se pudo transmitir")
            false
        }
    }

    fun detenerTransmision() {
        if (!transmitiendo) return
        runCatching { flujo.stopStream() }
        transmitiendo = false
        // RootEncoder suelta su GL y su SurfaceTexture al parar. Si no se olvida la
        // superficie, la siguiente transmisión del mismo proceso cree que ya está lista y
        // entrega una superficie muerta: «EGL_BAD_SURFACE», cero cuadros enviados y una
        // grabación de 0 bytes (pasó de verdad el 27/10).
        soltarSuperficie()
        alEstado(if (grabando) Estado.GRABANDO else Estado.DETENIDO, "Transmisión detenida")
    }

    /** Obliga a preparar codificadores y superficie en la próxima transmisión. */
    private fun soltarSuperficie() {
        listo = false
        superficie = null
    }

    // ------------------------------------------------------------------- grabación

    fun grabar(ruta: String): Boolean {
        if (grabando) return true
        return try {
            flujo.startRecord(
            ruta,
            // Con NoVideoSource la biblioteca grabaría solo audio: se piden ambas pistas.
            tracks = com.pedro.library.base.recording.RecordController.RecordTracks.ALL,
                listener = object : com.pedro.library.base.recording.RecordController.Listener {
                    override fun onStatusChange(
                        status: com.pedro.library.base.recording.RecordController.Status
                    ) {
                        L.i(TAG, "grabación: $status")
                        if (status == com.pedro.library.base.recording.RecordController.Status.STOPPED) {
                            grabando = false
                            alCerrarGrabacion?.invoke(ruta)
                        }
                    }

                }
            )
            grabando = true
            alEstado(Estado.GRABANDO, "Grabando")
            true
        } catch (t: Throwable) {
            L.e(TAG, "grabar: ${t.message}", t)
            alEstado(Estado.ERROR, t.message ?: "No se pudo grabar")
            false
        }
    }

    fun detenerGrabacion() {
        if (!grabando) return
        runCatching { flujo.stopRecord() }
        grabando = false
        alEstado(if (transmitiendo) Estado.TRANSMITIENDO else Estado.DETENIDO, "Grabación detenida")
    }

    fun liberar() {
        runCatching { detenerTransmision() }
        runCatching { detenerGrabacion() }
        runCatching { flujo.release() }
        superficie = null
        listo = false
    }

    // -------------------------------------------------- callbacks de RootEncoder

    override fun onConnectionStarted(url: String) {
        alEstado(Estado.CONECTANDO, "Conectando…")
    }

    override fun onConnectionSuccess() {
        alEstado(Estado.TRANSMITIENDO, "En vivo")
        L.i(TAG, "transmisión conectada")
    }

    override fun onConnectionFailed(reason: String) {
        transmitiendo = false
        // Si la conexión falla con la grabación de respaldo abierta, se cierra: si no, el
        // MP4 sigue creciendo sin que la pantalla lo muestre ni pueda detenerlo.
        if (grabando) detenerGrabacion()
        soltarSuperficie()
        alEstado(Estado.ERROR, reason)
        L.w(TAG, "transmisión falló: $reason")
        // Tras un fallo el cliente queda en estado «iniciado» y el siguiente intento rebota con
        // «Stream already started». Se suelta un momento después, no en medio del próximo intento.
        Thread {
            try {
                Thread.sleep(400)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return@Thread
            }
            runCatching { flujo.stopStream() }
            L.i(TAG, "cliente de envío liberado tras el fallo")
        }.start()
    }

    override fun onDisconnect() {
        transmitiendo = false
        if (grabando) detenerGrabacion()
        soltarSuperficie()
        alEstado(Estado.DETENIDO, "Desconectado")
    }

    override fun onAuthError() {
        alEstado(Estado.ERROR, "Clave o URL rechazada")
    }

    override fun onAuthSuccess() {}

    companion object {
        private const val TAG = "emisor"
    }
}
