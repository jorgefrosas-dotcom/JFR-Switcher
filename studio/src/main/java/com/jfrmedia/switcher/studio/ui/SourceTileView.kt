package com.jfrmedia.switcher.studio.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
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

    /**
     * Gira la miniatura según la rotación que reporta el celular. Si el cuadro llega
     * acostado (90/270) se rota la vista y se escala para que llene la tarjeta.
     */
    fun fijarRotacion(grados: Int) {
        video.rotation = 0f // los píxeles ya llegan orientados
        val w = width.toFloat()
        val h = height.toFloat()
        val escala = if ((grados % 180) != 0 && w > 0f && h > 0f) maxOf(w / h, h / w) else 1f
        video.scaleX = escala
        video.scaleY = escala
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
