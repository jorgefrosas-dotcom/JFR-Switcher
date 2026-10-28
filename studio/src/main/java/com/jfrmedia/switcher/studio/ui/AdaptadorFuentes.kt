package com.jfrmedia.switcher.studio.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.jfrmedia.switcher.studio.R
import com.jfrmedia.switcher.studio.texto.Fuentes

/**
 * Lista desplegable de tipos de letra: cada nombre se muestra **con su propia letra**, así se elige
 * viendo el resultado y no a ciegas.
 */
class AdaptadorFuentes(private val ctx: Context) : BaseAdapter() {

    private val opciones = Fuentes.TODAS

    override fun getCount(): Int = opciones.size

    override fun getItem(position: Int): Any = opciones[position].first

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertir: View?, padre: ViewGroup?): View {
        val vista = convertir as? TextView ?: TextView(ctx).apply {
            setPadding(28, 22, 28, 22)
            setTextSize(16f)
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
        }
        val (nombre, cara) = opciones[position]
        vista.text = nombre
        vista.typeface = cara
        return vista
    }
}
