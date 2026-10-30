package com.jfrmedia.switcher.studio.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.jfrmedia.switcher.studio.R
import com.jfrmedia.switcher.studio.canvas.AspectFrameLayout
import com.jfrmedia.switcher.studio.rtc.CameraSession
import com.jfrmedia.switcher.studio.rtc.RtcCore
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * Tarjeta de una cámara en la parrilla de fuentes: preview en vivo,
 * número, nombre, estado y marca de capa (A/B).
 */
class SourceTileView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AspectFrameLayout(context, attrs) {

    private val video: SurfaceViewRenderer
    private val txtNumber: TextView
    private val txtBadge: TextView
    private val txtName: TextView
    private val txtStatus: TextView
    private val txtHint: TextView

    private var rendererReady = false
    private var currentTrack: VideoTrack? = null
    private var slotIndex = 0

    init {
        aspect = 16f / 9f
        LayoutInflater.from(context).inflate(R.layout.view_source_tile, this, true)
        video = findViewById(R.id.tileVideo)
        txtNumber = findViewById(R.id.tileNumber)
        txtBadge = findViewById(R.id.tileBadge)
        txtName = findViewById(R.id.tileName)
        txtStatus = findViewById(R.id.tileStatus)
        txtHint = findViewById(R.id.tileHint)
        setBackgroundResource(R.drawable.bg_tile)
        isClickable = true
        isFocusable = true
    }

    /** Ajusta la forma de la tarjeta: 16:9 en horizontal, 9:16 en vertical. */
    fun modoVertical(vertical: Boolean) {
        aspect = if (vertical) 9f / 16f else 16f / 9f
        requestLayout()
    }

    /** Forma exacta de la tarjeta (en Modo Studio vertical se aplana a 3:4 para bajar el alto). */
    fun modoAspecto(valor: Float) {
        aspect = valor
        requestLayout()
    }

    /**
     * Guarda la rotación que reporta el celular.
     *
     * La miniatura **no se gira a mano**: el `SurfaceViewRenderer` de WebRTC ya orienta el cuadro
     * (usa la rotación del `VideoFrame`) y ajusta la proporción. Girar o escalar la vista encima de
     * eso recortaba y ampliaba la imagen, que es lo que se veía mal.
     */
    fun fijarRotacion(grados: Int) {
        rotacionCuadro = ((grados % 360) + 360) % 360
        dejarVisorEnSuSitio()
    }

    private fun dejarVisorEnSuSitio() {
        if (width <= 0 || height <= 0) return
        // Sin giro ni escala propios: mandan el renderizador y el tamaño de la tarjeta.
        if (video.rotation != 0f) video.rotation = 0f
        if (video.scaleX != 1f) video.scaleX = 1f
        if (video.scaleY != 1f) video.scaleY = 1f
        val lp = video.layoutParams
        if (lp != null &&
            (lp.width != ViewGroup.LayoutParams.MATCH_PARENT ||
                lp.height != ViewGroup.LayoutParams.MATCH_PARENT)
        ) {
            video.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            video.requestLayout()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Al cambiar el tamaño de la tarjeta (otra fila, otro formato) se deja otra vez en su sitio.
        dejarVisorEnSuSitio()
    }

    /** Rotación real del cuadro que reporta el celular (0/90/180/270). */
    private var rotacionCuadro = 0

    /** Tarjetas chicas (las 6 cámaras en una fila): letras más pequeñas y sin sobras. */
    fun modoCompacto(chico: Boolean) {
        txtName.textSize = if (chico) 9f else 11f
        txtStatus.textSize = if (chico) 8f else 10f
        txtNumber.textSize = if (chico) 9f else 12f
        txtBadge.textSize = if (chico) 9f else 11f
    }

    /**
     * Tarjetas angostas (celular en vertical, 2 por fila): el nombre tiene que caber completo y el
     * estado pasa a ser un **punto de color** (verde conectada / gris desconectada), porque el
     * texto «Sin conectar» no entra (Jorge, 29/10).
     */
    fun modoMuyCompacto() {
        txtName.textSize = 8f
        txtStatus.textSize = 7f
        txtNumber.textSize = 9f
        txtBadge.textSize = 8f
        txtName.maxLines = 1
        txtStatus.maxLines = 1
        estadoComoIcono = true
        pintarEstado()
    }

    /** El estado se dibuja como punto de color en vez de texto. */
    private var estadoComoIcono = false
    private var conectada = false

    /** Punto verde si está conectada, gris si no. */
    private fun pintarEstado() {
        if (!estadoComoIcono) return
        txtStatus.text = ""
        txtStatus.setBackgroundResource(
            if (conectada) R.drawable.bg_dot_green else R.drawable.bg_dot_gray
        )
        val lado = (10 * resources.displayMetrics.density).toInt()
        txtStatus.layoutParams = (txtStatus.layoutParams as? ViewGroup.LayoutParams)?.apply {
            width = lado
            height = lado
        }
        txtStatus.visibility = View.VISIBLE
    }

    /** Cambia el texto del estado y, si toca, el punto de color. */
    private fun estado(texto: String, conectadaAhora: Boolean) {
        conectada = conectadaAhora
        txtStatus.text = texto
        pintarEstado()
    }

    fun setup(index: Int, onClick: () -> Unit) {
        slotIndex = index
        txtNumber.text = (index + 1).toString()
        txtName.text = "Cámara ${index + 1}"
        estado("Sin conectar", false)
        ensureRenderer()
        setOnClickListener { onClick() }
    }

    private fun ensureRenderer() {
        if (rendererReady) return
        video.init(RtcCore.eglBase.eglBaseContext, null)
        video.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        video.setEnableHardwareScaler(true)
        video.setMirror(false)
        rendererReady = true
    }

    /**
     * Muestra u oculta el video de la tarjeta.
     *
     * Se usa al desplazar el panel de fuentes: la capa del `SurfaceView` no se recorta con el
     * scroll y se dibujaba encima del monitor. Al ocultarla hay que **borrar el cuadro**, porque si
     * no la última imagen se queda pintada y parece que la cámara está congelada.
     */
    fun mostrarVideoSiSeVe(seVe: Boolean) {
        if (currentTrack == null) return
        if (seVe) {
            if (video.visibility != View.VISIBLE) video.visibility = View.VISIBLE
        } else {
            runCatching { video.clearImage() }
            if (video.visibility != View.INVISIBLE) video.visibility = View.INVISIBLE
        }
    }

    fun bind(session: CameraSession, track: VideoTrack) {
        ensureRenderer()
        currentTrack?.removeSink(video)
        currentTrack = track
        track.addSink(video)
        video.visibility = View.VISIBLE
        txtHint.visibility = View.GONE
        txtName.text = session.name
        estado("En vivo · ${session.preset.width}×${session.preset.height}", true)
        refreshBorder()
    }

    /**
     * Fuente local (cámara de la propia tablet): no hay sesión remota, solo la pista.
     */
    fun bindLocal(track: VideoTrack, nombre: String) {
        ensureRenderer()
        currentTrack?.removeSink(video)
        currentTrack = track
        track.addSink(video)
        video.visibility = View.VISIBLE
        txtHint.visibility = View.GONE
        txtName.text = nombre
        estado("En vivo · esta tablet", true)
        refreshBorder()
    }

    /** Estado de la fuente local sin sesión: encendida o apagada. */
    fun setEstadoLocal(encendida: Boolean, nombre: String = "Tablet") {
        txtName.text = nombre
        txtNumber.text = "T"
        if (!encendida) {
            currentTrack?.removeSink(video)
            currentTrack = null
            video.visibility = View.INVISIBLE
            txtHint.visibility = View.VISIBLE
            estado("Toca para encender", false)
            setBackgroundResource(R.drawable.bg_tile)
        } else {
            estado("En vivo · esta tablet", true)
        }
    }

    fun refreshInfo(session: CameraSession?) {
        if (session == null) {
            estado("Sin conectar", false)
            return
        }
        txtName.text = session.name
        val battery = if (session.battery in 1..100) " · ${session.battery}%" else ""
        val conectadaAhora = session.state == com.jfrmedia.switcher.studio.rtc.CamState.STREAMING ||
            session.state == com.jfrmedia.switcher.studio.rtc.CamState.CONNECTED
        val texto = when (session.state) {
            com.jfrmedia.switcher.studio.rtc.CamState.STREAMING ->
                "${session.fps.coerceAtLeast(0)} fps${battery}"

            com.jfrmedia.switcher.studio.rtc.CamState.CONNECTING -> "Conectando…"
            com.jfrmedia.switcher.studio.rtc.CamState.CONNECTED -> "Emparejada${battery}"
            com.jfrmedia.switcher.studio.rtc.CamState.ERROR -> session.lastError.ifEmpty { "Error" }
            com.jfrmedia.switcher.studio.rtc.CamState.CLOSED -> "Desconectada"
        }
        estado(texto, conectadaAhora)
    }

    fun clearSession() {
        currentTrack?.removeSink(video)
        currentTrack = null
        video.visibility = View.INVISIBLE
        txtHint.visibility = View.VISIBLE
        txtName.text = "Cámara ${slotIndex + 1}"
        estado("Sin conectar", false)
        txtBadge.visibility = View.GONE
        setBackgroundResource(R.drawable.bg_tile)
    }

    fun setLayerMark(layer: Char?) {
        if (layer == null) {
            txtBadge.visibility = View.GONE
        } else {
            txtBadge.visibility = View.VISIBLE
            txtBadge.text = layer.toString()
        }
        refreshBorder()
    }

    /**
     * Marco naranja cuando esta fuente es la que está **en programa** (al aire).
     * Se dibuja como `foreground`, así convive con el fondo de capa A/B.
     */
    fun marcarEnPrograma(activo: Boolean) {
        foreground = if (activo) {
            androidx.core.content.ContextCompat.getDrawable(context, R.drawable.bg_marco_programa)
        } else {
            null
        }
    }

    private fun refreshBorder() {
        // El marcador de capa se colorea por capa: A naranja, B azul.
        if (txtBadge.visibility != View.VISIBLE) {
            setBackgroundResource(R.drawable.bg_tile)
            return
        }
        setBackgroundResource(
            if (txtBadge.text.toString() == "A") R.drawable.bg_tile_a else R.drawable.bg_tile_b
        )
    }

    fun release() {
        currentTrack?.removeSink(video)
        currentTrack = null
        runCatching { video.release() }
        rendererReady = false
    }
}
