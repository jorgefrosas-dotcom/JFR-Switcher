package com.jfrmedia.switcher.studio.canvas

import android.graphics.Bitmap
import android.graphics.RectF
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReferenceArray

/** Elemento del lienzo: cámara, imagen u (más adelante) texto. */
sealed class CanvasItem(val id: Long) {
    /** Rectángulo en coordenadas normalizadas 0..1 del lienzo. */
    val rect = RectF(0f, 0f, 1f, 1f)

    @Volatile var opacity: Float = 1f
    @Volatile var visible: Boolean = true
    @Volatile var radiusDp: Float = 0f

    val width: Float get() = rect.width()
    val height: Float get() = rect.height()
}

/** Cámara en el lienzo (slot 0..5, y 6 = cámara de la tablet). */
class CamItem(id: Long, val slot: Int) : CanvasItem(id) {
    var label: String = "Cámara ${slot + 1}"

    /**
     * Zoom óptico simulado: 1 = plano completo tal cual llega del celular.
     * Se aplica recortando el cuadro de la fuente (no deforma), con [panX]/[panY]
     * para mover el encuadre dentro de ese recorte.
     */
    var zoom: Float = 1f

    /** Desplazamiento del encuadre, -1..1 (0 = centrado). */
    var panX: Float = 0f
    var panY: Float = 0f

    // --- Ajustes de imagen por cámara (no afectan el encuadre) ---
    /** Brillo, -1..1 (0 = normal). */
    var luz: Float = 0f

    /** Contraste, -1..1 (0 = normal). */
    var contraste: Float = 0f

    /** Balance de blancos (verde ↔ magenta), -1..1. */
    var wb: Float = 0f


    /** Saturación de color, -1..1 (0 = normal). */
    var color: Float = 0f

    /** Viñeta: oscurece las orillas, 0..1. */
    var vineta: Float = 0f

    /** Posterización: reduce la cantidad de niveles de color, 0..1. */
    var poster: Float = 0f

    fun resetImagen() {
        luz = 0f
        contraste = 0f
        wb = 0f
        color = 0f
        vineta = 0f
        poster = 0f
    }

    fun resetZoom() {
        zoom = 1f
        panX = 0f
        panY = 0f
    }
}

/**
 * Medio que se reproduce encima del programa: un video pregrabado o una imagen fija
 * (intro, cuenta regresiva, despedida, "volvemos pronto", publicidad).
 */
class MediaItem(
    id: Long,
    val uri: String,
    val video: Boolean,
    var label: String = if (video) "Video" else "Imagen"
) : CanvasItem(id) {
    /** Mientras está visible tapa al programa; al terminar (o al tocarlo) se quita. */
    var reproduciendo: Boolean = false
}

/** Imagen (logo, banner, mensaje). */
class ImageItem(
    id: Long,
    val bitmap: Bitmap,
    var label: String = "Imagen",
    /** URI original, para poder guardarla en una plantilla y volver a cargarla. */
    val uri: String? = null
) : CanvasItem(id)

/**
 * Escena del programa: lista ordenada de atrás hacia adelante.
 * Máximo 2 cámaras simultáneas; imágenes sin límite razonable.
 *
 * [aspect] cambia entre horizontal (16:9) y vertical (9:16) según la plantilla.
 */
class Scene(aspectInicial: Float = 16f / 9f) {

    @Volatile
    var aspect: Float = aspectInicial

    val items = CopyOnWriteArrayList<CanvasItem>()

    @Volatile
    var selectedId: Long? = null

    @Volatile
    var revision: Long = 0
        private set

    fun touch() {
        revision++
    }

    fun cams(): List<CamItem> = items.filterIsInstance<CamItem>()

    fun camAt(slot: Int): CamItem? = items.filterIsInstance<CamItem>().firstOrNull { it.slot == slot }

    fun images(): List<ImageItem> = items.filterIsInstance<ImageItem>()

    val maxCams: Int get() = 2

    fun canAddCam(slot: Int): Boolean = camAt(slot) == null && cams().size < maxCams

    fun addCam(slot: Int): CamItem? {
        if (!canAddCam(slot)) return null
        val item = CamItem(nextId(), slot)
        item.rect.set(0f, 0f, 1f, 1f)
        items.add(item)
        selectedId = item.id
        touch()
        return item
    }

    fun addImage(
        bitmap: Bitmap,
        label: String,
        uri: String? = null,
        rect: RectF? = null
    ): ImageItem {
        val item = ImageItem(nextId(), bitmap, label, uri)
        if (rect != null) {
            item.rect.set(rect)
        } else {
            // Relación de aspecto real de la imagen: alto normalizado =
            // ancho * aspecto del lienzo / aspecto de la imagen.
            val aspectoImagen = bitmap.width.toFloat() / bitmap.height.toFloat()
            val ancho = if (aspectoImagen >= 3f) 0.72f else 0.42f
            val alto = (ancho * aspect / aspectoImagen).coerceAtMost(0.9f)
            val x = (1f - ancho) / 2f
            // Los gráficos anchos (lower thirds, banners) nacen abajo, que es donde se usan.
            val y = if (aspectoImagen >= 3f) 0.92f - alto else (1f - alto) / 2f
            item.rect.set(x, y, x + ancho, y + alto)
        }
        items.add(item)
        selectedId = item.id
        touch()
        return item
    }

    /** Rectángulo preajustado para la capa B (picture in picture). */
    fun placePip(item: CanvasItem, corner: Int, scale: Float = 0.34f) {
        val w = scale
        val h = w * aspect
        val margin = 0.035f
        item.rect.set(
            if (corner == 0 || corner == 2) margin else 1f - margin - w,
            if (corner == 0 || corner == 1) margin else 1f - margin - h,
            if (corner == 0 || corner == 2) margin + w else 1f - margin,
            if (corner == 0 || corner == 1) margin + h else 1f - margin
        )
        touch()
    }

    fun remove(item: CanvasItem) {
        items.remove(item)
        if (selectedId == item.id) selectedId = null
        touch()
    }

    fun bringToFront(item: CanvasItem) {
        if (items.remove(item)) {
            items.add(item)
            touch()
        }
    }

    fun sendToBack(item: CanvasItem) {
        if (items.remove(item)) {
            items.add(0, item)
            touch()
        }
    }

    fun selected(): CanvasItem? = items.firstOrNull { it.id == selectedId }

    private var idSeq = 1L
    /** Agrega cualquier elemento (cámara, imagen u medio de video). */
    fun agregar(item: CanvasItem) {
        items.add(item)
        touch()
    }

    fun nextId(): Long = idSeq++
}

/**
 * Puente entre la pista WebRTC y el render: guarda solo el último cuadro por cámara
 * (si se acumulan, la latencia crece) y avisa al hilo de dibujo.
 */
class FrameBridge(private val slot: Int, private val view: CompositorView) : VideoSink {

    private val pending = AtomicReferenceArray<VideoFrame>(1)

    override fun onFrame(frame: VideoFrame) {
        // Se retiene el cuadro y se suelta en el hilo GL después de subirlo a textura.
        frame.retain()
        val old = pending.getAndSet(0, frame)
        old?.release()
        view.requestRender()
    }

    /** Llamado en el hilo GL. Devuelve el cuadro pendiente o null (se suelta fuera). */
    fun take(): VideoFrame? = pending.getAndSet(0, null)

    fun attach(track: VideoTrack?) {
        track?.addSink(this)
    }

    fun detach(track: VideoTrack?) {
        track?.removeSink(this)
        pending.getAndSet(0, null)?.release()
    }
}
