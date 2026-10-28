package com.jfrmedia.switcher.studio.canvas

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import kotlin.math.roundToInt

/**
 * Contenedor que fuerza la relación de aspecto del lienzo (16:9 por defecto),
 * de modo que lo que se ve en pantalla coincide exactamente con lo que se graba.
 */
open class AspectFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : FrameLayout(context, attrs, defStyle) {

    var aspect: Float = 16f / 9f
        set(value) {
            field = value
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val wMode = MeasureSpec.getMode(widthMeasureSpec)
        val hMode = MeasureSpec.getMode(heightMeasureSpec)
        val wSize = MeasureSpec.getSize(widthMeasureSpec)
        val hSize = MeasureSpec.getSize(heightMeasureSpec)

        val w: Int
        val h: Int
        if (wMode != MeasureSpec.UNSPECIFIED && hMode != MeasureSpec.UNSPECIFIED) {
            if (wSize / aspect <= hSize) {
                w = wSize
                h = (wSize / aspect).roundToInt()
            } else {
                h = hSize
                w = (hSize * aspect).roundToInt()
            }
        } else if (wMode != MeasureSpec.UNSPECIFIED) {
            w = wSize
            h = (wSize / aspect).roundToInt()
        } else {
            h = hSize
            w = (hSize * aspect).roundToInt()
        }
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        )
    }
}
