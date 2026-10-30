package com.jfrmedia.switcher.studio.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.TextView
import com.jfrmedia.switcher.studio.R
import com.jfrmedia.switcher.studio.canvas.AspectFrameLayout
import com.jfrmedia.switcher.studio.canvas.ImageItem

/**
 * Tarjeta de un overlay en la parrilla de fuentes: miniatura, nombre, marca de
 * seleccionado y botón para quitarlo. Tocar la tarjeta muestra u oculta el overlay
 * en el lienzo (switch rápido), igual que se hace con las cámaras.
 */
class OverlayTileView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AspectFrameLayout(context, attrs) {

    private val thumb: ImageView
    private val name: TextView
    private val badge: TextView
    private val off: TextView
    private val delete: TextView

    init {
        aspect = 16f / 9f
        LayoutInflater.from(context).inflate(R.layout.view_overlay_tile, this, true)
        thumb = findViewById(R.id.overlayThumb)
        name = findViewById(R.id.overlayName)
        badge = findViewById(R.id.overlayBadge)
        off = findViewById(R.id.overlayOff)
        delete = findViewById(R.id.overlayDelete)
        setBackgroundResource(R.drawable.bg_tile)
        isClickable = true
        isFocusable = true
    }

    /** Ajusta la forma de la tarjeta: 16:9 en horizontal, 9:16 en vertical. */
    fun modoVertical(vertical: Boolean) {
        aspect = if (vertical) 9f / 16f else 16f / 9f
        requestLayout()
    }

    fun bind(item: ImageItem, selected: Boolean, onTap: () -> Unit, onDelete: () -> Unit) {
        thumb.setImageBitmap(item.bitmap)
        name.text = item.label
        off.visibility = if (item.visible) android.view.View.GONE else android.view.View.VISIBLE
        thumb.alpha = if (item.visible) 1f else 0.3f
        badge.visibility = if (selected) android.view.View.VISIBLE else android.view.View.GONE
        setBackgroundResource(
            if (selected) R.drawable.bg_tile_a else R.drawable.bg_tile
        )
        setOnClickListener { onTap() }
        delete.setOnClickListener { onDelete() }
        // Visible en el lienzo = está en programa: se marca con el marco naranja.
        marcarEnPrograma(item.visible)
    }

    /** Marco naranja cuando este overlay está en programa. */
    fun marcarEnPrograma(activo: Boolean) {
        foreground = if (activo) {
            androidx.core.content.ContextCompat.getDrawable(context, R.drawable.bg_marco_programa)
        } else {
            null
        }
    }
}
