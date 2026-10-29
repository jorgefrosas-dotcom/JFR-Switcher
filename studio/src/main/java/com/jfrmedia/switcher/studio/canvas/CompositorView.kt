package com.jfrmedia.switcher.studio.canvas

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaPlayer
import android.net.Uri
import android.graphics.SurfaceTexture
import android.graphics.RectF
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.jfrmedia.switcher.core.util.L
import com.jfrmedia.switcher.studio.texto.Entradas
import org.webrtc.VideoTrack
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Vista del lienzo del programa.
 *
 * Dibuja en OpenGL ES 2 la escena (hasta 2 cámaras + imágenes), permite arrastrar y
 * escalar cada elemento con el dedo y, además, puede renderizar el mismo contenido
 * hacia la superficie de entrada del codificador para grabar en MP4.
 */
class CompositorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    interface Listener {
        fun onSelectionChanged(item: CanvasItem?)
        fun onSceneChanged()
    }

    /**
     * Escena que dibuja esta vista.
     *
     * Es `var` a propósito: en Modo Studio hay **dos** compositores (preview y programa) y al
     * «Transicionar» se intercambian las escenas entre los dos.
     */
    var scene: Scene = Scene()

    @Volatile
    var listener: Listener? = null

    private val egl = EglCore()
    private val lock = Object()
    private var dirty = true
    @Volatile private var running = false
    private var renderThread: Thread? = null
    private var displaySurface: EGLSurface? = null

    /** La ventana no está visible (app en segundo plano): se dibuja solo en la salida. */
    @Volatile private var pantallaOculta = false

    /** Hay que rehacer la superficie de pantalla (el sistema le dio una nueva a la ventana). */
    @Volatile private var necesitaSuperficie = false

    private val bridges = arrayOfNulls<FrameBridge>(7)
    private val textures = arrayOfNulls<SourceTexture>(7)
    private val imageTextures = HashMap<Long, ImageTexture>()

    private var yuv: YuvProgram? = null
    private var rgba: RgbaProgram? = null
    private var solid: SolidProgram? = null

    private val mvp = FloatArray(16)
    private val density = resources.displayMetrics.density

    // ---- salida para grabación ----
    @Volatile private var pendingRecordSurface: Surface? = null
    private var recordSurface: EGLSurface? = null
    private var recW = 1920
    private var recH = 1080

    /** true cuando la salida de grabación es la del emisor (RTMP), que invierte la Y. */
    var salidaInvertida = false
    @Volatile private var recBaseNs = 0L

    @Volatile
    var isRecording = false
        private set

    private var frameCounter = 0
    private var recordSwaps = 0
    private var accumDisplayNs = 0L
    private var accumRecordNs = 0L

    init {
        holder.addCallback(this)
        holder.setFormat(android.graphics.PixelFormat.OPAQUE)
        keepScreenOn = true
    }

    // ------------------------------------------------------------------ ciclo de vida

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (running) {
            // El hilo quedó vivo dibujando solo en la salida: se recupera la pantalla.
            pantallaOculta = false
            necesitaSuperficie = true
            requestRender()
            return
        }
        startRender()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        requestRender()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // Con una transmisión o grabación en curso el dibujo NO se detiene: la app puede quedar
        // en segundo plano (ir al Live Producer, revisar algo) y el video debe seguir saliendo.
        // Antes se apagaba el hilo y el directo se quedaba congelado.
        if (isRecording && recordSurface != null) {
            pantallaOculta = true
            L.i("gl", "pantalla oculta: se sigue dibujando en la salida")
            return
        }
        stopRender()
    }

    private fun startRender() {
        if (running) return
        running = true
        renderThread = Thread({ renderLoop() }, "compositor-gl").also { it.start() }
    }

    private fun stopRender() {
        running = false
        synchronized(lock) { lock.notifyAll() }
        val t = renderThread
        renderThread = null
        if (t != null) {
            try {
                t.join(1500)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        releaseGl()
    }

    // ------------------------------------------------------------------ bucle de dibujo

    private fun renderLoop() {
        try {
            if (!egl.init()) {
                L.e("gl", "no se pudo inicializar EGL")
                running = false
                return
            }
            val s = holder.surface
            if (!s.isValid) {
                running = false
                return
            }
            displaySurface = egl.createSurface(s)
            egl.makeCurrent(displaySurface!!)
            yuv = YuvProgram()
            rgba = RgbaProgram()
            solid = SolidProgram()
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            L.i("gl", "compositor listo")

            var nextFrameNs = 0L
            val periodNs = 33_333_333L
            while (running) {
                if (isRecording) {
                    // Planificador de 30 fps: se duerme lo que falta para el próximo cuadro.
                    // No se mide desde el inicio del cuadro anterior porque los swapBuffers
                    // bloquean (vsync del display + cola del codificador) y el video salía a 16 fps.
                    val now = System.nanoTime()
                    if (nextFrameNs == 0L) nextFrameNs = now
                    if (now < nextFrameNs) {
                        val ms = (nextFrameNs - now) / 1_000_000
                        if (ms > 0) {
                            try {
                                Thread.sleep(ms)
                            } catch (e: InterruptedException) {
                                Thread.currentThread().interrupt()
                            }
                        }
                    }
                    nextFrameNs += periodNs
                    if (nextFrameNs < System.nanoTime() - periodNs) {
                        nextFrameNs = System.nanoTime() + periodNs
                    }
                } else {
                    nextFrameNs = 0L
                    // La animación de transición necesita cuadros seguidos.
                    if (animItem != null) {
                        synchronized(lock) { dirty = true }
                    }
                    synchronized(lock) {
                        if (!dirty) {
                            try {
                                lock.wait(400)
                            } catch (e: InterruptedException) {
                                Thread.currentThread().interrupt()
                            }
                        }
                        dirty = false
                    }
                }
                if (!running) break

                try {
                    if (pedirVideo) prepararVideo()
                    val pending = pendingRecordSurface
                    if (pending != null) {
                        pendingRecordSurface = null
                        egl.releaseSurface(recordSurface)
                        recordSurface = egl.createSurface(pending)
                        egl.makeCurrent(recordSurface!!)
                        egl.setSwapInterval(0)
                        isRecording = true
                        L.i("gl", "salida de grabación ${recW}x$recH")
                    }

                    // Al volver del segundo plano la ventana trae una superficie nueva.
                    if (necesitaSuperficie) {
                        necesitaSuperficie = false
                        runCatching { egl.releaseSurface(displaySurface) }
                        displaySurface = null
                        val st = holder.surface
                        if (st != null && st.isValid) {
                            displaySurface = runCatching { egl.createSurface(st) }.getOrNull()
                            displaySurface?.let { egl.makeCurrent(it) }
                        }
                    }

                    val t0 = System.nanoTime()
                    val ds = if (pantallaOculta) null else displaySurface
                    if (ds != null && width > 0 && height > 0) {
                        egl.makeCurrent(ds)
                        pumpFrames()
                        GLES20.glViewport(0, 0, width, height)
                        drawScene(width, height, withSelection = true)
                        egl.swap(ds, 0)
                    }
                    val t1 = System.nanoTime()

                    val rs = recordSurface
                    // La salida del emisor usa Y hacia arriba: se invierte solo para ese destino.
                    Mvp.invertirY = salidaInvertida
                    if (rs != null) {
                        egl.makeCurrent(rs)
                        pumpFrames()
                        GLES20.glViewport(0, 0, recW, recH)
                        drawScene(recW, recH, withSelection = false)
                        val pts = System.nanoTime() - recBaseNs
                        if (egl.swap(rs, pts)) recordSwaps++
                        Mvp.invertirY = false
                    }
                    val t2 = System.nanoTime()
                    if (isRecording) {
                        frameCounter++
                        accumDisplayNs += (t1 - t0)
                        accumRecordNs += (t2 - t1)
                        if (frameCounter % 150 == 0) {
                            L.i(
                                "gl",
                                "ritmo: $frameCounter dibujos · $recordSwaps enviados al codificador" +
                                    " · pantalla ${accumDisplayNs / 150 / 1_000_000} ms" +
                                    " · grabación ${accumRecordNs / 150 / 1_000_000} ms"
                            )
                            accumDisplayNs = 0
                            accumRecordNs = 0
                        }
                    }
                } catch (t: Throwable) {
                    L.e("gl", "error al dibujar: ${t.message}", t)
                }
            }
        } catch (t: Throwable) {
            L.e("gl", "bucle de render terminado: ${t.message}", t)
        } finally {
            isRecording = false
            running = false
            releaseGl()
        }
    }

    private fun pumpFrames() {
        for (i in 0 until 7) {
            val b = bridges[i] ?: continue
            val f = b.take() ?: continue
            try {
                val tex = textures[i] ?: SourceTexture().also { textures[i] = it }
                tex.upload(f)
            } catch (t: Throwable) {
                L.e("gl", "fallo al subir cuadro $i", t)
            } finally {
                f.release()
            }
        }
    }

    // ---- transiciones de entrada ----

    /** 12 transiciones preestablecidas para los cambios de cámara y la entrada de medios. */
    enum class Transicion(val id: String, val label: String) {
        CORTE("corte", "Corte directo"),
        FUNDIDO("fundido", "Desvanecer"),
        DESLIZ_IZQ("desliz_izq", "Deslizar desde la izquierda"),
        DESLIZ_DER("desliz_der", "Deslizar desde la derecha"),
        DESLIZ_ARRIBA("desliz_arriba", "Deslizar desde arriba"),
        DESLIZ_ABAJO("desliz_abajo", "Deslizar desde abajo"),
        WIPE_IZQ("wipe_izq", "Barrido hacia la derecha"),
        WIPE_DER("wipe_der", "Barrido hacia la izquierda"),
        WIPE_ARRIBA("wipe_arriba", "Barrido hacia abajo"),
        WIPE_ABAJO("wipe_abajo", "Barrido hacia arriba"),
        ZOOM_IN("zoom_in", "Acercar (zoom in)"),
        ZOOM_OUT("zoom_out", "Alejar (zoom out)")
    }

    @Volatile var transicion: Transicion = Transicion.FUNDIDO
    @Volatile var transicionMs: Int = 420

    private var animItem: CanvasItem? = null
    private var animTipo: Transicion = Transicion.CORTE
    private var animInicioNs = 0L
    private val animRect = RectF()
    private var animAlpha = 1f

    // ---- velo del cambio preview → programa (MODO STUDIO) ----
    /** Cuadro congelado del programa que se está retirando; null si no hay transición en curso. */
    @Volatile
    private var veloBitmap: Bitmap? = null
    private var veloTexture: ImageTexture? = null
    @Volatile
    private var veloInicioNs = 0L
    @Volatile
    private var veloTipo: Transicion = Transicion.FUNDIDO
    @Volatile
    private var veloDurMs = 420

    /**
     * Muestra [bitmap] a cuadro completo (el programa que sale) y lo va retirando con la
     * transición elegida, para que el cambio preview → programa se vea, no que salte de golpe.
     */
    fun animarVelo(bitmap: Bitmap, tipo: Transicion = transicion, duracionMs: Int = transicionMs) {
        if (tipo == Transicion.CORTE || duracionMs <= 0) {
            veloBitmap = null
            requestRender()
            return
        }
        veloBitmap = bitmap
        veloTipo = tipo
        veloDurMs = duracionMs.coerceAtLeast(1)
        veloInicioNs = System.nanoTime()
        requestRender()
    }

    /** true mientras el velo se está retirando (para pedir cuadros). */
    val veloActivo: Boolean get() = veloBitmap != null

    private fun dibujarVelo(outW: Int, outH: Int) {
        val bmp = veloBitmap ?: return
        val dur = veloDurMs / 1000f
        var t = ((System.nanoTime() - veloInicioNs) / 1_000_000_000f / dur).coerceIn(0f, 1f)
        t = t * t * (3f - 2f * t)
        if (t >= 1f) {
            veloBitmap = null
            return
        }
        val tex = veloTexture?.takeIf { it.bitmap === bmp }
            ?: ImageTexture(bmp).also { veloTexture = it }
        if (!tex.ensure()) return

        val r = RectF(0f, 0f, 1f, 1f)
        var alfa = 1f
        // Se retira por donde la entrada entra: si la transición hace entrar de un lado, el
        // cuadro que sale se va por el lado contrario.
        when (veloTipo) {
            Transicion.FUNDIDO -> alfa = 1f - t
            Transicion.DESLIZ_IZQ -> r.offset(t * 2f, 0f)
            Transicion.DESLIZ_DER -> r.offset(-t * 2f, 0f)
            Transicion.DESLIZ_ARRIBA -> r.offset(0f, t * 2f)
            Transicion.DESLIZ_ABAJO -> r.offset(0f, -t * 2f)
            Transicion.WIPE_IZQ -> r.set(0f, 0f, 1f - t, 1f)
            Transicion.WIPE_DER -> r.set(t, 0f, 1f, 1f)
            Transicion.WIPE_ARRIBA -> r.set(0f, 0f, 1f, 1f - t)
            Transicion.WIPE_ABAJO -> r.set(0f, t, 1f, 1f)
            Transicion.ZOOM_IN -> {
                val e = 1f + 0.5f * t
                r.set(0.5f - e / 2f, 0.5f - e / 2f, 0.5f + e / 2f, 0.5f + e / 2f)
                alfa = 1f - t
            }
            Transicion.ZOOM_OUT -> {
                val e = 1f - 0.35f * t
                r.set(0.5f - e / 2f, 0.5f - e / 2f, 0.5f + e / 2f, 0.5f + e / 2f)
                alfa = 1f - t
            }
            Transicion.CORTE -> return
        }
        if (alfa <= 0.01f || r.width() <= 0.002f || r.height() <= 0.002f) return
        Mvp.forRect(mvp, r.left, r.top, r.right, r.bottom)
        rgba?.draw(
            mvp = mvp,
            textureId = tex.id,
            opacity = alfa,
            radiusPx = 0f,
            halfW = r.width() * outW / 2f,
            halfH = r.height() * outH / 2f
        )
    }

    /** Arranca la animación de entrada del elemento indicado. */
    fun animarEntrada(item: CanvasItem, tipo: Transicion = transicion, duracionMs: Int = transicionMs) {
        if (tipo == Transicion.CORTE || duracionMs <= 0) {
            animItem = null
            requestRender()
            return
        }
        animItem = item
        animTipo = tipo
        animInicioNs = System.nanoTime()
        animRect.set(item.rect)
        animAlpha = 1f
        requestRender()
    }

    /** Rectángulo a dibujar: el animado si el elemento está en transición. */
    /** Calcula el rectángulo y la opacidad del elemento para el progreso [t]. */
    private fun aplicarTransicion(base: RectF, tipo: Transicion, t: Float) {        val w = base.width()
        val h = base.height()
        val cx = base.centerX()
        val cy = base.centerY()
        animAlpha = 1f
        animRect.set(base)
        when (tipo) {
            Transicion.CORTE -> return
            Transicion.FUNDIDO -> animAlpha = t
            Transicion.DESLIZ_IZQ -> animRect.offset(-(1f - t) * (w + 1f), 0f)
            Transicion.DESLIZ_DER -> animRect.offset((1f - t) * (w + 1f), 0f)
            Transicion.DESLIZ_ARRIBA -> animRect.offset(0f, -(1f - t) * (h + 1f))
            Transicion.DESLIZ_ABAJO -> animRect.offset(0f, (1f - t) * (h + 1f))
            Transicion.WIPE_IZQ, Transicion.WIPE_DER,
            Transicion.WIPE_ARRIBA, Transicion.WIPE_ABAJO -> animRect.set(base)

            Transicion.ZOOM_IN -> {
                val e = 1f + 0.35f * (1f - t)
                val nw = w * e
                val nh = h * e
                animRect.set(cx - nw / 2f, cy - nh / 2f, cx + nw / 2f, cy + nh / 2f)
                animAlpha = t
            }

            Transicion.ZOOM_OUT -> {
                val e = 1f - 0.25f * (1f - t)
                val nw = w * e
                val nh = h * e
                animRect.set(cx - nw / 2f, cy - nh / 2f, cx + nw / 2f, cy + nh / 2f)
                animAlpha = t
            }
        }
    }

    private fun rectPara(item: CanvasItem): RectF =
        if (item === animItem) animRect else item.rect

    private fun drawScene(outW: Int, outH: Int, withSelection: Boolean) {
        // Si el video que estaba al aire ya no está en la escena (se quitó del programa, se eliminó
        // de MEDIOS o se cambió de escenario), se corta: su audio no puede seguir sonando.
        videoItem?.let { item -> if (!scene.items.contains(item)) terminarVideo() }

        GLES20.glClearColor(0.047f, 0.055f, 0.067f, 1f) // #0C0E11
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        // Progreso de la transición en curso (0..1 con suavizado).
        val enTransicion = animItem
        var cortina = -1f
        if (enTransicion != null) {
            val dur = (transicionMs.coerceAtLeast(1)) / 1000f
            var t = ((System.nanoTime() - animInicioNs) / 1_000_000_000f / dur).coerceIn(0f, 1f)
            t = t * t * (3f - 2f * t) // suavizado
            aplicarTransicion(enTransicion.rect, animTipo, t)
            if (t >= 1f) {
                animItem = null
            } else if (animTipo == Transicion.WIPE_IZQ || animTipo == Transicion.WIPE_DER ||
                animTipo == Transicion.WIPE_ARRIBA || animTipo == Transicion.WIPE_ABAJO
            ) {
                cortina = t
            }
        }

        for (item in scene.items) {
            if (!item.visible || item.opacity <= 0.01f) continue
            if (item === enTransicion && animAlpha <= 0.01f) continue
            when (item) {
                is CamItem -> drawCam(item, outW, outH)
                is ImageItem -> drawImage(item, outW, outH)
                is MediaItem -> drawVideo(item, outW, outH)
            }
        }

        // Cortina del barrido: tapa la parte del cuadro que todavía no se revela.
        if (cortina in 0f..0.999f) {
            val r = RectF(0f, 0f, 1f, 1f)
            when (animTipo) {
                Transicion.WIPE_IZQ -> r.set(cortina, 0f, 1f, 1f)
                Transicion.WIPE_DER -> r.set(0f, 0f, 1f - cortina, 1f)
                Transicion.WIPE_ARRIBA -> r.set(0f, cortina, 1f, 1f)
                Transicion.WIPE_ABAJO -> r.set(0f, 0f, 1f, 1f - cortina)
                else -> {}
            }
            Mvp.forRect(mvp, r.left, r.top, r.right, r.bottom)
            solid?.draw(mvp, 0.047f, 0.055f, 0.067f, 1f)
        }

        if (withSelection) {
            scene.selected()?.let { drawOutline(it, outW, outH) }
        }
        // El velo del cambio de programa va encima de todo (menos del marco de selección).
        dibujarVelo(outW, outH)
    }

    private fun drawCam(item: CamItem, outW: Int, outH: Int) {
        val tex = textures[item.slot]
        if (tex == null || !tex.hasData) {
            drawEmpty(item, outW, outH)
            return
        }
        val r = rectPara(item)
        val alpha = item.opacity * (if (item === animItem) animAlpha else 1f)
        Mvp.forRect(mvp, r.left, r.top, r.right, r.bottom)

        val srcAspect = if (tex.rotation % 180 == 0) {
            tex.texW.toFloat() / tex.texH.toFloat()
        } else {
            tex.texH.toFloat() / tex.texW.toFloat()
        }
        val itemAspect = (r.width() * scene.aspect) / r.height()

        var sx = 1f
        var sy = 1f
        var ox = 0f
        var oy = 0f
        if (srcAspect > itemAspect) {
            sx = itemAspect / srcAspect
            ox = (1f - sx) / 2f
        } else {
            sy = srcAspect / itemAspect
            oy = (1f - sy) / 2f
        }
        // Zoom de la cámara: se recorta más (sin deformar) y se mueve el encuadre.
        val z = item.zoom.coerceIn(1f, 5f)
        if (z > 1.001f) {
            sx /= z
            sy /= z
            val slackX = (1f - sx).coerceAtLeast(0f)
            val slackY = (1f - sy).coerceAtLeast(0f)
            ox = (slackX / 2f * (1f + item.panX)).coerceIn(0f, slackX)
            oy = (slackY / 2f * (1f + item.panY)).coerceIn(0f, slackY)
        }
        // El shader aplica el recorte DESPUÉS del giro, así que en cuadros de 90/270
        // hay que intercambiar los ejes: si no, la imagen se aplasta en vez de recortarse.
        if (tex.rotation % 180 != 0) {
            val s = sx
            sx = sy
            sy = s
            val o = ox
            ox = oy
            oy = o
        }

        yuv?.draw(
            texture = tex,
            mvp = mvp,
            uvOffX = ox, uvOffY = oy,
            uvScaleX = sx, uvScaleY = sy,
            mirror = false,
            opacity = alpha,
            radiusPx = item.radiusDp * density * (outH / 1080f).coerceAtLeast(0.35f),
            halfW = r.width() * outW / 2f,
            halfH = r.height() * outH / 2f,
            luz = item.luz,
            contraste = item.contraste,
            wb = item.wb,
            color = item.color,
            vineta = item.vineta,
            poster = item.poster
        )
    }

    // ---- reproducción de video (intros, cuentas regresivas, publicidad) ----

    /** Se crea en el hilo de GL (necesita contexto EGL activo). */
    private var oes: OesProgram? = null
    private var videoTexId = 0
    private var videoSurfaceTexture: SurfaceTexture? = null
    private var videoReproductor: MediaPlayer? = null
    private val videoMatriz = FloatArray(16)
    @Volatile private var videoListo = false
    @Volatile private var pedirVideo = false
    private var videoItem: MediaItem? = null
    private var videoAlTerminar: (() -> Unit)? = null

    /** Reproduce [item] encima del programa. [alTerminar] se llama al acabar o al cortarlo. */
    fun reproducirVideo(item: MediaItem, alTerminar: () -> Unit) {
        videoItem = item
        videoAlTerminar = alTerminar
        videoListo = false
        pedirVideo = true
        item.reproduciendo = true
        requestRender()
    }

    /** Corta el video en curso (por ejemplo si el operador toca el medio otra vez). */
    fun detenerVideo() {
        if (videoItem == null) return
        terminarVideo()
    }

    val hayVideo: Boolean get() = videoItem != null

    /**
     * Si el sistema pausó el video (algunos equipos lo hacen al minimizar la app), lo reanuda.
     *
     * Se llama cada segundo mientras hay grabación o transmisión: así el video no se queda
     * congelado cuando el switcher pasa a segundo plano.
     */
    fun reanudarMediosSiHaceFalta() {
        val mp = videoReproductor ?: return
        val item = videoItem ?: return
        // Si el video ya no está en la escena (se quitó del programa o se eliminó de MEDIOS), se
        // corta aquí mismo: si no, seguiría sonando aunque ya no se vea.
        if (!scene.items.contains(item)) {
            terminarVideo()
            return
        }
        if (!videoListo || !item.reproduciendo) return
        runCatching {
            if (!mp.isPlaying) {
                mp.start()
                L.i("gl", "video reanudado (estaba pausado en segundo plano)")
            }
        }
    }

    /** Prepara textura y MediaPlayer. Debe correr en el hilo de GL. */
    private fun prepararVideo() {
        pedirVideo = false
        val item = videoItem ?: return
        if (oes == null) oes = OesProgram()
        if (videoTexId == 0) {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            videoTexId = ids[0]
            GLES20.glBindTexture(0x8D65, videoTexId)
            GLES20.glTexParameteri(0x8D65, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(0x8D65, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glBindTexture(0x8D65, 0)
        }
        videoSurfaceTexture?.release()
        val st = SurfaceTexture(videoTexId)
        st.setOnFrameAvailableListener { requestRender() }
        videoSurfaceTexture = st
        post {
            runCatching {
                videoReproductor?.release()
                val mp = MediaPlayer()
                mp.setSurface(Surface(st))
                mp.setDataSource(context, Uri.parse(item.uri))
                mp.setOnPreparedListener { reproductor ->
                    videoListo = true
                    reproductor.start()
                    requestRender()
                }
                mp.setOnCompletionListener { terminarVideo() }
                mp.setOnErrorListener { _, _, _ ->
                    terminarVideo()
                    true
                }
                mp.prepareAsync()
                videoReproductor = mp
            }.onFailure {
                L.w("gl", "no se pudo reproducir el video: ${'$'}{it.message}")
                terminarVideo()
            }
        }
    }

    private fun terminarVideo() {
        val item = videoItem
        val aviso = videoAlTerminar
        videoItem = null
        videoAlTerminar = null
        videoListo = false
        item?.reproduciendo = false
        post {
            runCatching {
                videoReproductor?.stop()
                videoReproductor?.release()
            }
            videoReproductor = null
            aviso?.invoke()
        }
        requestRender()
    }

    /** Dibuja el cuadro de video actual con recorte tipo cover. */
    private fun drawVideo(item: MediaItem, outW: Int, outH: Int) {
        val st = videoSurfaceTexture
        if (!videoListo || st == null || videoTexId == 0) return
        try {
            st.updateTexImage()
            st.getTransformMatrix(videoMatriz)
        } catch (t: Throwable) {
            return
        }
        val mp = videoReproductor
        val vw = mp?.videoWidth ?: 0
        val vh = mp?.videoHeight ?: 0
        val srcAspect = if (vw > 0 && vh > 0) vw.toFloat() / vh else 16f / 9f
        val r = rectPara(item)
        Mvp.forRect(mvp, r.left, r.top, r.right, r.bottom)
        // Encuadre: se estira el cuadro al rectángulo del item (cover por transformación
        // de la matriz de la SurfaceTexture).
        val dstAspect = (r.width() * scene.aspect) / r.height()
        val sx: Float
        val sy: Float
        if (srcAspect > dstAspect) {
            sx = dstAspect / srcAspect
            sy = 1f
        } else {
            sx = 1f
            sy = srcAspect / dstAspect
        }
        val m = FloatArray(16)
        android.opengl.Matrix.setIdentityM(m, 0)
        android.opengl.Matrix.scaleM(m, 0, sx, sy, 1f)
        android.opengl.Matrix.translateM(m, 0, (1f - sx) / 2f / sx, (1f - sy) / 2f / sy, 0f)
        val combinada = FloatArray(16)
        android.opengl.Matrix.multiplyMM(combinada, 0, videoMatriz, 0, m, 0)
        oes?.draw(mvp, videoTexId, combinada, item.opacity * (if (item === animItem) animAlpha else 1f))
    }

    private fun drawImage(item: ImageItem, outW: Int, outH: Int) {
        val tex = imageTextures.getOrPut(item.id) { ImageTexture(item.bitmap) }
        if (!tex.ensure()) return
        var r = rectPara(item)
        var alpha = item.opacity * (if (item === animItem) animAlpha else 1f)

        // Entrada y salida de los textos. Si está saliendo, manda la salida.
        if (item is TextoItem && (item.animSalida > 0L || item.animInicio > 0L)) {
            val saliendo = item.animSalida > 0L
            val desde = if (saliendo) item.animSalida else item.animInicio
            val transcurrido = (System.nanoTime() - desde) / 1_000_000f
            val p = transcurrido / item.animDuracionMs.coerceAtLeast(1)
            if (p >= 1f && !saliendo) {
                item.animInicio = 0L
            } else {
                val e = if (saliendo) {
                    Entradas.calcularSalida(item.animacion, p)
                } else {
                    Entradas.calcular(item.animacion, p)
                }
                alpha *= e.alfa
                val w = r.width()
                val h = r.height()
                val cx = r.centerX()
                val cy = r.centerY()
                val nw = w * e.escala
                val nh = h * e.escala
                val movido = RectF(cx - nw / 2f, cy - nh / 2f, cx + nw / 2f, cy + nh / 2f)
                movido.offset(e.desplazamientoX * w, e.desplazamientoY * h)
                r = movido
            }
        }

        if (alpha <= 0.01f) return
        Mvp.forRect(mvp, r.left, r.top, r.right, r.bottom)
        rgba?.draw(
            mvp = mvp,
            textureId = tex.id,
            opacity = alpha,
            radiusPx = item.radiusDp * density * (outH / 1080f).coerceAtLeast(0.35f),
            halfW = r.width() * outW / 2f,
            halfH = r.height() * outH / 2f
        )
    }

    private fun drawEmpty(item: CamItem, outW: Int, outH: Int) {
        val r = item.rect
        Mvp.forRect(mvp, r.left, r.top, r.right, r.bottom)
        solid?.draw(mvp, 0.12f, 0.13f, 0.16f, 0.55f)
        drawBorder(r, outW, outH, 0.22f, 0.24f, 0.28f, 1f, 1.5f)
    }

    private fun drawOutline(item: CanvasItem, outW: Int, outH: Int) {
        drawBorder(item.rect, outW, outH, 1f, 0.35f, 0.17f, 1f, 2.5f)
        // Esquinas
        val t = 10f * density / outW
        val th = 10f * density / outH
        val r = item.rect
        solid?.draw(normalized(r.left, r.top, r.left + t, r.top + th), 1f, 0.35f, 0.17f, 1f)
        solid?.draw(normalized(r.right - t, r.top, r.right, r.top + th), 1f, 0.35f, 0.17f, 1f)
        solid?.draw(normalized(r.left, r.bottom - th, r.left + t, r.bottom), 1f, 0.35f, 0.17f, 1f)
        solid?.draw(normalized(r.right - t, r.bottom - th, r.right, r.bottom), 1f, 0.35f, 0.17f, 1f)
    }

    private fun drawBorder(
        r: RectF, outW: Int, outH: Int,
        cr: Float, cg: Float, cb: Float, ca: Float, px: Float
    ) {
        val nx = px * density / outW
        val ny = px * density / outH
        solid?.draw(normalized(r.left, r.top, r.right, r.top + ny), cr, cg, cb, ca)
        solid?.draw(normalized(r.left, r.bottom - ny, r.right, r.bottom), cr, cg, cb, ca)
        solid?.draw(normalized(r.left, r.top, r.left + nx, r.bottom), cr, cg, cb, ca)
        solid?.draw(normalized(r.right - nx, r.top, r.right, r.bottom), cr, cg, cb, ca)
    }

    private fun normalized(l: Float, t: Float, rr: Float, b: Float): FloatArray {
        Mvp.forRect(mvp, l, t, rr, b)
        return mvp
    }

    // ------------------------------------------------------------------ API pública

    fun requestRender() {
        synchronized(lock) {
            dirty = true
            lock.notifyAll()
        }
    }

    fun attachTrack(slot: Int, track: VideoTrack) {
        bridge(slot).attach(track)
        requestRender()
    }

    fun detachTrack(slot: Int, track: VideoTrack?) {
        bridges[slot]?.detach(track)
        requestRender()
    }

    private fun bridge(slot: Int): FrameBridge =
        bridges[slot] ?: FrameBridge(slot, this).also { bridges[slot] = it }

    fun hasVideo(slot: Int): Boolean = textures.getOrNull(slot)?.hasData == true

    /** Preparar la salida de grabación; el hilo GL crea el EGLSurface. */
    fun beginRecordingOutput(surface: Surface, width: Int, height: Int, baseTimeNs: Long) {
        recW = width
        recH = height
        recBaseNs = baseTimeNs
        pendingRecordSurface = surface
        requestRender()
    }

    fun endRecordingOutput() {
        isRecording = false
        pendingRecordSurface = null
        requestRender()
    }

    private fun releaseGl() {
        runCatching { egl.releaseSurface(recordSurface) }
        recordSurface = null
        runCatching { egl.releaseSurface(displaySurface) }
        displaySurface = null
        runCatching {
            textures.forEach { it?.release() }
        }
        runCatching { imageTextures.values.forEach { it.release() } }
        runCatching { yuv?.release() }
        runCatching { rgba?.release() }
        runCatching { solid?.release() }
        runCatching { egl.release() }
    }

    // ------------------------------------------------------------------ gestos

    /**
     * false en el monitor de PROGRAMA del Modo Studio: ahí solo se mira, se compone y se edita
     * en el preview. Así no se mueve nada que ya está al aire.
     */
    var editable: Boolean = true

    private var touchItem: CanvasItem? = null
    private var scaleMode = false
    private var zoomMode = false
    private var startZoom = 1f
    private var startPanX = 0f
    private var startPanY = 0f
    private val startRect = RectF()
    private var downX = 0f
    private var downY = 0f
    private var startDist = 1f
    private var startMidX = 0f
    private var startMidY = 0f
    private var didMove = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!editable) return false
        if (width == 0 || height == 0) return false
        val x = event.x / width
        val y = event.y / height
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hit = hitTest(x, y)
                // Las cámaras solo se seleccionan: no se arrastran ni se escalan.
                scene.selectedId = hit?.id
                listener?.onSelectionChanged(hit)
                touchItem = hit
                if (hit != null) {
                    startRect.set(hit.rect)
                    downX = x
                    downY = y
                    didMove = false
                }
                requestRender()
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    startDist = distance(event)
                    startMidX = midX(event) / width
                    startMidY = midY(event) / height
                    val cam = touchItem as? CamItem
                    if (cam != null) {
                        // Dos dedos sobre una cámara = zoom de la fuente.
                        zoomMode = true
                        scaleMode = false
                        startZoom = cam.zoom
                        startPanX = cam.panX
                        startPanY = cam.panY
                    } else {
                        zoomMode = false
                        scaleMode = true
                        touchItem?.let { startRect.set(it.rect) }
                    }
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val item = touchItem ?: return true
                if (zoomMode && event.pointerCount >= 2) {
                    val cam = item as? CamItem ?: return true
                    val d = distance(event)
                    if (startDist > 1f && d > 1f) {
                        val factor = d / startDist
                        cam.zoom = (startZoom * factor).coerceIn(1f, 5f)
                        // Arrastre del punto medio = mover el encuadre dentro del recorte.
                        if (cam.zoom > 1.001f) {
                            val mx = midX(event) / width
                            val my = midY(event) / height
                            cam.panX = (startPanX + (mx - startMidX) * 4f).coerceIn(-1f, 1f)
                            cam.panY = (startPanY + (my - startMidY) * 4f).coerceIn(-1f, 1f)
                        } else {
                            cam.panX = 0f
                            cam.panY = 0f
                        }
                        didMove = true
                        requestRender()
                    }
                } else if (scaleMode && event.pointerCount >= 2) {
                    val d = distance(event)
                    if (startDist > 1f && d > 1f) {
                        val factor = (d / startDist).coerceIn(0.2f, 6f)
                        val w = (startRect.width() * factor).coerceIn(0.06f, 1.6f)
                        val h = if (item is ImageItem) {
                            w * scene.aspect * (item.bitmap.height.toFloat() / item.bitmap.width)
                        } else {
                            (startRect.height() * factor).coerceIn(0.06f, 1.6f)
                        }
                        val cx = startRect.centerX()
                        val cy = startRect.centerY()
                        item.rect.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
                        didMove = true
                        requestRender()
                    }
                } else {
                    val dx = x - downX
                    val dy = y - downY
                    // El video no se mueve ni se redimensiona con el dedo: su marco lo
                    // define el modo (al aire, PiP o pantalla partida). Solo los overlays
                    // se arrastran.
                    if (item is CamItem) return true
                    if (abs(dx) > 0.004f || abs(dy) > 0.004f) didMove = true
                    val nx = clampX(startRect.left + dx, item.rect.width())
                    val ny = clampY(startRect.top + dy, item.rect.height())
                    item.rect.set(nx, ny, nx + item.rect.width(), ny + item.rect.height())
                    requestRender()
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (didMove) {
                    scene.touch()
                    listener?.onSceneChanged()
                }
                touchItem = null
                scaleMode = false
                zoomMode = false
                requestRender()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun hitTest(x: Float, y: Float): CanvasItem? {
        val items = scene.items
        for (i in items.size - 1 downTo 0) {
            val it = items[i]
            if (!it.visible) continue
            if (it.rect.contains(x, y)) return it
        }
        return null
    }

    private fun clampX(v: Float, w: Float): Float = v.coerceIn(0.02f - w * 0.6f, 0.98f - w * 0.4f)
    private fun clampY(v: Float, h: Float): Float = v.coerceIn(0.02f - h * 0.6f, 0.98f - h * 0.4f)

    private fun distance(e: MotionEvent): Float =
        hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0))

    private fun midX(e: MotionEvent): Float = (e.getX(0) + e.getX(1)) / 2f
    private fun midY(e: MotionEvent): Float = (e.getY(0) + e.getY(1)) / 2f

    /** Recalcula preajustes PiP (esquinas) para la capa superior. */
    fun applyPip(corner: Int, camSlot: Int) {
        val cam = scene.camAt(camSlot) ?: return
        scene.bringToFront(cam)
        scene.placePip(cam, corner)
        scene.selectedId = cam.id
        requestRender()
        listener?.onSceneChanged()
    }

    fun swapLayers() {
        val cams = scene.cams()
        if (cams.size < 2) return
        val a = cams[0]
        val b = cams[1]
        val tmp = RectF(a.rect)
        a.rect.set(b.rect)
        b.rect.set(tmp)
        scene.sendToBack(a)
        scene.bringToFront(b)
        requestRender()
        listener?.onSceneChanged()
    }

    fun fullScreen(slot: Int) {
        val cam = scene.camAt(slot) ?: return
        cam.rect.set(0f, 0f, 1f, 1f)
        scene.bringToFront(cam)
        scene.touch()
        requestRender()
        listener?.onSceneChanged()
    }

    val selection: CanvasItem? get() = scene.selected()

    /** Picar imagen al centro de la capa de cámara que está al frente. */
    fun centerItem(item: CanvasItem) {
        val w = item.rect.width()
        val h = item.rect.height()
        item.rect.set((1f - w) / 2f, (1f - h) / 2f, (1f + w) / 2f, (1f + h) / 2f)
        scene.touch()
        requestRender()
    }

    fun resizeItem(item: CanvasItem, scale: Float) {
        // El video no cambia de tamaño a mano: su marco lo define el modo del lienzo.
        if (item is CamItem) return
        val cx = item.rect.centerX()
        val cy = item.rect.centerY()
        val w = min(1.6f, max(0.06f, item.rect.width() * scale))
        val h = if (item is ImageItem) {
            w * scene.aspect * (item.bitmap.height.toFloat() / item.bitmap.width)
        } else {
            min(1.6f, max(0.06f, item.rect.height() * scale))
        }
        item.rect.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        scene.touch()
        requestRender()
    }
}
