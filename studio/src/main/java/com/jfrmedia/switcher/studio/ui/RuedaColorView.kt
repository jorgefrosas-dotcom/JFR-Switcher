package com.jfrmedia.switcher.studio.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Rueda de color (tono y saturación) con marcador arrastrable.
 *
 * El brillo va aparte, en una barra; aquí solo se elige el "color puro" de la rueda.
 * Avisa cada vez que cambia con [alCambiarColor].
 */
class RuedaColorView(context: Context) : View(context) {

    var alCambiarColor: ((Int) -> Unit)? = null

    /** Brillo 0..1 (lo fija la barra de abajo). */
    var brillo: Float = 1f
        set(valor) {
            field = valor.coerceIn(0f, 1f)
            invalidate()
            alCambiarColor?.invoke(colorActual())
        }

    private var tono = 0f          // 0..360
    private var saturacion = 0f    // 0..1
    private var rueda: Bitmap? = null
    private var diametro = 0

    private val pincelMarcador = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.WHITE
    }
    private val pincelSombra = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = Color.argb(140, 0, 0, 0)
    }

    /** Color elegido ahora mismo. */
    fun colorActual(): Int = Color.HSVToColor(floatArrayOf(tono, saturacion, brillo))

    /** Fija el color de partida (por ejemplo al abrir el selector). */
    fun fijarColor(color: Int) {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        tono = hsv[0]
        saturacion = hsv[1]
        brillo = if (hsv[2] < 0.15f) 0.15f else hsv[2]
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val lado = minOf(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
        setMeasuredDimension(lado, lado)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        diametro = minOf(w, h)
        rueda = crearRueda(diametro)
    }

    /** Genera la rueda de una vez: se reutiliza en cada dibujo. */
    private fun crearRueda(lado: Int): Bitmap {
        val bmp = Bitmap.createBitmap(lado, lado, Bitmap.Config.ARGB_8888)
        val radio = lado / 2f
        val centro = lado / 2f
        for (y in 0 until lado) {
            for (x in 0 until lado) {
                val dx = x - centro
                val dy = y - centro
                val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                if (dist > radio) {
                    bmp.setPixel(x, y, Color.TRANSPARENT)
                } else {
                    var angulo = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                    if (angulo < 0) angulo += 360f
                    val sat = (dist / radio).coerceIn(0f, 1f)
                    bmp.setPixel(x, y, Color.HSVToColor(floatArrayOf(angulo, sat, 1f)))
                }
            }
        }
        return bmp
    }

    override fun onDraw(canvas: Canvas) {
        rueda?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        val centro = diametro / 2f
        val radio = diametro / 2f
        val ang = Math.toRadians(tono.toDouble())
        val x = centro + (cos(ang) * saturacion * radio).toFloat()
        val y = centro + (sin(ang) * saturacion * radio).toFloat()
        canvas.drawCircle(x, y, 9f, pincelSombra)
        canvas.drawCircle(x, y, 9f, pincelMarcador)
        // Vista previa del color con el brillo aplicado, en la esquina.
        val muestra = RectF(8f, diametro - 34f, 40f, diametro - 8f)
        val relleno = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorActual() }
        canvas.drawRoundRect(muestra, 8f, 8f, relleno)
        pincelMarcador.strokeWidth = 1.5f
        canvas.drawRoundRect(muestra, 8f, 8f, pincelMarcador)
        pincelMarcador.strokeWidth = 3f
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val centro = diametro / 2f
                val radio = diametro / 2f
                val dx = event.x - centro
                val dy = event.y - centro
                var angulo = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                if (angulo < 0) angulo += 360f
                tono = angulo
                saturacion = (hypot(dx.toDouble(), dy.toDouble()).toFloat() / radio).coerceIn(0f, 1f)
                invalidate()
                alCambiarColor?.invoke(colorActual())
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
