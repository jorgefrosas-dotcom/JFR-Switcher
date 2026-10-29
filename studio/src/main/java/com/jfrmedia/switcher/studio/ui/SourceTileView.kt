package com.jfrmedia.switcher.studio.ui

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
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

    /** Rotación real del cuadro que reporta el celular (0/90/180/270). */
    private var rotacionCuadro = 0

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

    /**
     * Orienta la miniatura con la rotación que reporta el celular.
     *
     * Los píxeles llegan **sin orientar** (igual que en el lienzo, donde el shader aplica la
     * rotación). Si el cuadro viene acostado (90°/270°) hay que girar el visor; y para que el video
     * **llene la tarjeta sin recortarse ni ampliarse**, el visor se acuesta (alto × ancho) antes de
     * girarlo: así el cuadro queda del tamaño justo de la tarjeta.
     */
    fun fijarRotacion(grados: Int) {
        rotacionCuadro = ((grados % 360) + 360) % 360
        aplicarRotacionVisor()
    }

    private fun aplicarRotacionVisor() {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        video.rotation = rotacionCuadro.toFloat()
        video.layoutParams = if (rotacionCuadro % 180 != 0) {
            FrameLayout.LayoutParams(h, w, Gravity.CENTER)
        } else {
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        video.requestLayout()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Al cambiar el tamaño de la tarjeta (otra fila, otro formato) se recalcula el visor.
        aplicarRotacionVisor()
    }

    /** Tarjetas chicas (las 6 cámaras en una fila): letras más pequeñas y sin sobras. */
    fun modoCompacto(chico: Boolean) {
        txtName.textSize = if (chico) 9f else 11f
        txtStatus.textSize = if (chico) 8f else 10f
        txtNumber.textSize = if (chico) 9f else 12f
        txtBadge.textSize = if (chico) 9f else 11f
    }

    fun setup(index: Int, onClick: () -> Unit) {
        slotIndex = index
        txtNumber.text = (index + 1).toString()
        txtName.text = "Cámara ${index + 1}"
        txtStatus.text = "Sin conectar"
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

    fun bind(session: CameraSession, track: VideoTrack) {
        ensureRenderer()
        currentTrack?.removeSink(video)
        currentTrack = track
        track.addSink(video)
        video.visibility = View.VISIBLE
        txtHint.visibility = View.GONE
        txtName.text = session.name
        txtStatus.text = "En vivo · ${session.preset.width}×${session.preset.height}"
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
        txtStatus.text = "En vivo · esta tablet"
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
            txtStatus.text = "Toca para encender"
            setBackgroundResource(R.drawable.bg_tile)
        } else {
            txtStatus.text = "En vivo · esta tablet"
        }
    }

    fun refreshInfo(session: CameraSession?) {
        if (session == null) {
            txtStatus.text = "Sin conectar"
            return
        }
        txtName.text = session.name
        val battery = if (session.battery in 1..100) " · ${session.battery}%" else ""
        txtStatus.text = when (session.state) {
            com.jfrmedia.switcher.studio.rtc.CamState.STREAMING ->
                "${session.fps.coerceAtLeast(0)} fps${battery}"

            com.jfrmedia.switcher.studio.rtc.CamState.CONNECTING -> "Conectando…"
            com.jfrmedia.switcher.studio.rtc.CamState.CONNECTED -> "Emparejada${battery}"
            com.jfrmedia.switcher.studio.rtc.CamState.ERROR -> session.lastError.ifEmpty { "Error" }
            com.jfrmedia.switcher.studio.rtc.CamState.CLOSED -> "Desconectada"
        }
    }

    fun clearSession() {
        currentTrack?.removeSink(video)
        currentTrack = null
        video.visibility = View.INVISIBLE
        txtHint.visibility = View.VISIBLE
        txtName.text = "Cámara ${slotIndex + 1}"
        txtStatus.text = "Sin conectar"
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
