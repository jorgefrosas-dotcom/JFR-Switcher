package com.jfrmedia.switcher.studio.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.log10

/**
 * Monitor de entrada de audio estilo consola.
 *
 * Dibuja los últimos segundos del pico de entrada como barras finas sobre una rejilla en dB,
 * con zonas verde / naranja / rojo (rojo = rozando el recorte), envolvente suavizada, línea de
 * pico retenido y un LED **CLIP** que se enciende cuando la entrada llega al máximo.
 *
 * El nivel se pide 40 veces por segundo con [proveedorPico]; sin sesión de audio el proveedor
 * devuelve 0 y el monitor se queda en «Sin señal».
 */
class AudioWaveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** Pico actual (0..1) del micrófono. Se consulta 40 veces por segundo. */
    var proveedorPico: (() -> Float)? = null

    /** 240 muestras × 25 ms = 6 s de historia. */
    private val historia = FloatArray(240)
    private var indice = 0

    private var picoRetenido = 0f
    private var picoDesdeMs = 0L
    private var clipHastaMs = 0L

    private val pintura = Paint(Paint.ANTI_ALIAS_FLAG)
    private val etiqueta = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 8.5f * resources.displayMetrics.scaledDensity
        typeface = android.graphics.Typeface.MONOSPACE
    }
    private val trazo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.1f * resources.displayMetrics.density
    }
    private val relleno = RectF()

    private val verde = Color.parseColor("#FF35C759")
    private val naranja = Color.parseColor("#FFFFB020")
    private val rojo = Color.parseColor("#FFFF4D3D")
    private val gris = Color.parseColor("#FF6B7280")
    private val tenue = Color.parseColor("#FF2B313B")
    private val fondo = Color.parseColor("#FF0E1116")
    private val borde = Color.parseColor("#FF232A33")

    private val tarea = object : Runnable {
        override fun run() {
            val pico = (proveedorPico?.invoke() ?: 0f).coerceIn(0f, 1f)
            historia[indice] = pico
            indice = (indice + 1) % historia.size
            val ahora = System.currentTimeMillis()
            if (pico >= picoRetenido || ahora - picoDesdeMs > 1800) {
                picoRetenido = pico
                picoDesdeMs = ahora
            }
            // El LED de recorte se queda encendido un momento para que se vea aunque el pico
            // haya sido muy corto.
            if (pico >= 0.985f) clipHastaMs = ahora + 2500
            invalidate()
            postDelayed(this, 25)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(tarea)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tarea)
        super.onDetachedFromWindow()
    }

    private fun db(pico: Float): Float = 20f * log10(pico.coerceAtLeast(0.0001f)).coerceIn(-60f, 0f)

    private fun colorDe(db: Float): Int = when {
        db >= -3f -> rojo
        db >= -12f -> naranja
        else -> verde
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val anchoTotal = width.toFloat()
        val alto = height.toFloat()
        if (anchoTotal <= 0f || alto <= 0f) return

        val r = 7f * density
        // Cuerpo del monitor.
        pintura.style = Paint.Style.FILL
        pintura.color = fondo
        canvas.drawRoundRect(RectF(0f, 0f, anchoTotal, alto), r, r, pintura)
        trazo.color = borde
        canvas.drawRoundRect(RectF(0.5f, 0.5f, anchoTotal - 0.5f, alto - 0.5f), r, r, trazo)

        // Canaleta derecha para la escala. En columnas estrechas (vertical) se encoge para no
        // comerse el dibujo de la onda.
        val canaleta = minOf(32f * density, anchoTotal * 0.28f)
        val margen = 4f * density
        val x0 = margen
        val x1 = (anchoTotal - canaleta).coerceAtLeast(x0 + 10f)
        val y0 = margen + 2f * density
        val y1 = alto - margen - 2f * density
        val altoOnda = (y1 - y0)
        // Demasiado bajo para dibujar la escala con sentido (pasa al cambiar de vista): solo fondo.
        if (altoOnda < 16f * density) return
        val centro = y0 + altoOnda / 2f

        fun yDeDb(db: Float): Float = y0 + ((-db / 60f).coerceIn(0f, 1f)) * altoOnda

        // Fondo con degradado por nivel (rojo arriba, verde abajo), muy sutil.
        pintura.style = Paint.Style.FILL
        pintura.shader = LinearGradient(
            0f, y0, 0f, y1,
            intArrayOf(
                withAlpha(rojo, 22), withAlpha(naranja, 18), withAlpha(naranja, 12),
                withAlpha(verde, 10), withAlpha(verde, 4)
            ),
            floatArrayOf(0f, 0.06f, 0.2f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(x0, y0, x1, y1, pintura)
        pintura.shader = null

        // Rejilla cada 6 dB y etiquetas cada 12.
        var marca = 0
        while (marca >= -60) {
            val y = yDeDb(marca.toFloat())
            val mayor = marca % 12 == 0
            pintura.color = withAlpha(Color.WHITE, if (mayor) 26 else 12)
            pintura.style = Paint.Style.FILL
            canvas.drawRect(x0, y, x1, y + 1f, pintura)
            if (mayor) {
                etiqueta.color = gris
                etiqueta.textAlign = Paint.Align.RIGHT
                // El rango puede quedar del revés si el panel está muy bajo (p. ej. durante un
                // cambio de vista): se usa un máximo válido para no reventar al dibujar.
                val baseMin = y0 + etiqueta.textSize
                val baseMax = maxOf(baseMin, y1)
                canvas.drawText(
                    if (marca == 0) "0" else "$marca",
                    anchoTotal - margen,
                    (y + etiqueta.textSize / 3f).coerceIn(baseMin, baseMax),
                    etiqueta
                )
            }
            marca -= 6
        }
        etiqueta.textAlign = Paint.Align.LEFT

        // Barras finas con color por ALTURA: el cuerpo es verde y solo la punta que pasa de
        // −12 dB se pinta naranja, y la que pasa de −3, roja (como en un medidor de consola).
        val n = historia.size
        val paso = (x1 - x0) / n
        // En columnas estrechas se dibuja una de cada N muestras: las barras no se pegan.
        val salto = maxOf(1, (1.6f / paso).toInt())
        val anchoBarra = (paso * salto * 0.55f).coerceAtLeast(1f)
        val haySenal = historia.any { it > 0.002f }
        val medio = altoOnda / 2f
        fun distancia(db: Float): Float = ((1f + db / 60f).coerceIn(0f, 1f)) * medio
        val d12 = distancia(-12f)
        val d3 = distancia(-3f)
        for (i in 0 until n step salto) {
            val pico = historia[(indice + i) % n]
            if (pico <= 0.0004f) continue
            val d = distancia(db(pico))
            val x = x0 + i * paso
            // Se pinta de la banda más alta a la más baja: cada una tapa el centro de la anterior
            // y deja a la vista solo el tramo que le corresponde.
            if (d > d3) {
                pintura.color = withAlpha(rojo, 235)
                canvas.drawRect(x, centro - d, x + anchoBarra, centro + d, pintura)
            }
            if (d > d12) {
                val hasta = minOf(d, d3)
                pintura.color = withAlpha(naranja, 235)
                canvas.drawRect(x, centro - hasta, x + anchoBarra, centro + hasta, pintura)
            }
            val verdeHasta = minOf(d, d12)
            pintura.color = withAlpha(verde, 225)
            canvas.drawRect(x, centro - verdeHasta, x + anchoBarra, centro + verdeHasta, pintura)
        }

        // Envolvente suavizada (media corta) sobre las barras: la lectura "de consola".
        if (haySenal) {
            var suma = 0f
            val ventana = 8
            for (i in n - ventana until n) {
                suma += historia[((indice + i) % n + n) % n]
            }
            val suave = suma / ventana
            val dbSuave = db(suave)
            val ySuave = yDeDb(dbSuave)
            trazo.color = withAlpha(Color.WHITE, 150)
            trazo.strokeWidth = 1.2f * density
            canvas.drawLine(x0, ySuave, x1, ySuave, trazo)
        }

        // Pico retenido con su placa.
        if (haySenal) {
            val dbPico = db(picoRetenido)
            val yPico = yDeDb(dbPico)
            pintura.style = Paint.Style.FILL
            pintura.color = withAlpha(colorDe(dbPico), 235)
            canvas.drawRect(x0, yPico - 0.6f * density, x1, yPico + 0.6f * density, pintura)

            val textoPico = String.format(java.util.Locale.US, "PICO %+.1f dB", dbPico)
            etiqueta.color = colorDe(dbPico)
            val anchoTexto = etiqueta.measureText(textoPico)
            relleno.set(
                x0 + 3f * density, y0 + 3f * density,
                x0 + 3f * density + anchoTexto + 8f * density, y0 + 3f * density + 13f * density
            )
            pintura.color = withAlpha(Color.BLACK, 150)
            canvas.drawRoundRect(relleno, 3f * density, 3f * density, pintura)
            canvas.drawText(textoPico, relleno.left + 4f * density, relleno.bottom - 3.5f * density, etiqueta)
        }

        // LED de CLIP a la derecha.
        val clip = System.currentTimeMillis() < clipHastaMs
        val anchoLed = 40f * density
        relleno.set(
            x1 - anchoLed - 3f * density, y0 + 3f * density,
            x1 - 3f * density, y0 + 3f * density + 13f * density
        )
        pintura.color = if (clip) rojo else withAlpha(tenue, 220)
        canvas.drawRoundRect(relleno, 3f * density, 3f * density, pintura)
        etiqueta.color = if (clip) Color.BLACK else gris
        etiqueta.textAlign = Paint.Align.CENTER
        canvas.drawText("CLIP", relleno.centerX(), relleno.bottom - 3.5f * density, etiqueta)
        etiqueta.textAlign = Paint.Align.LEFT

        if (!haySenal) {
            etiqueta.color = gris
            val mensaje = "Sin señal"
            val anchoTexto = etiqueta.measureText(mensaje)
            canvas.drawText(mensaje, (x1 - anchoTexto) / 2f, centro + etiqueta.textSize / 2f, etiqueta)
        }
    }

    private val density: Float get() = resources.displayMetrics.density
}
