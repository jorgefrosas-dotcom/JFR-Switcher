package com.jfrmedia.switcher.studio.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Vúmetro de audio. Toma el nivel de quien se lo diga (`levelProvider`) y dibuja barras con caída
 * suave: en sesión es el RMS del grabador; fuera de sesión, el del micrófono que se abre para ver
 * cómo está entrando el audio antes de grabar.
 */
class AudioMeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var levelProvider: (() -> Float)? = null

    private var level = 0f
    private val bars = 16
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val update = object : Runnable {
        override fun run() {
            val target = (levelProvider?.invoke() ?: 0f).coerceIn(0f, 1f)
            level = if (target > level) target else level * 0.82f
            invalidate()
            postDelayed(this, 55)
        }
    }

    private val green = Color.parseColor("#FF35C759")
    private val yellow = Color.parseColor("#FFFFC13B")
    private val red = Color.parseColor("#FFFF5A2B")

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(update)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(update)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val gap = w * 0.035f
        val barW = (w - gap * (bars - 1)) / bars
        for (i in 0 until bars) {
            val threshold = i / bars.toFloat()
            val active = level > threshold
            val barH = h * (0.32f + 0.68f * (i / bars.toFloat()))
            paint.color = when {
                !active -> Color.parseColor("#FF2A2F38")
                i > bars * 0.82f -> red
                i > bars * 0.62f -> yellow
                else -> green
            }
            val left = i * (barW + gap)
            val top = h - barH
            canvas.drawRoundRect(left, top, left + barW, h, barW * 0.4f, barW * 0.4f, paint)
        }
    }
}
