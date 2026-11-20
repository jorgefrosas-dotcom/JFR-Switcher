package com.jfrmedia.switcher.studio.tcg

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Banda de controles del Modo Pokémon TCG.
 *
 * Se arma por código (no en XML) porque solo existe en este modo y cambia de contenido con el
 * estado: nombres, récord del evento, pokebolas, chips del turno y resultado de la partida.
 *
 * Lleva los datos de **un lado** (jugador 1 o jugador 2) en una tarjeta; el redibujado lo pide
 * la actividad cada vez que cambia el estado.
 */
class PanelTcg(contexto: Context, private val escucha: Escucha) {

    interface Escucha {
        fun onEditarNombre(lado: Int)
        fun onCambiarDebilitados(lado: Int, delta: Int)
        fun onCambiarChip(lado: Int, chip: Int)
        fun onPasarTurno()
        fun onResultado(resultado: Int)     // 1 = gana el 1 · 0 = empate · 2 = gana el 2
        fun onNuevaPartida()
        fun onReiniciarMarcador()
        fun onCambiarPaleta()
        fun onAlternarAmbiente()
    }

    val vista: View

    private val filaUno: LinearLayout
    private val filaDos: LinearLayout
    private val filaTurno: LinearLayout
    private val textoEvento: TextView

    init {
        val columna = LinearLayout(contexto).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, dp(contexto, 4f), 0, 0)
        }

        // Cabecera: evento + paleta + partida
        val cabecera = LinearLayout(contexto).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        textoEvento = TextView(contexto).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.parseColor("#FFCB05"))
        }
        cabecera.addView(textoEvento, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        cabecera.addView(boton(contexto, "Nueva partida", 0xFF23262E.toInt(), 0xFFECEEF2.toInt()) { escucha.onNuevaPartida() })
        cabecera.addView(boton(contexto, "Reiniciar", 0xFF23262E.toInt(), 0xFF9AA0AA.toInt()) { escucha.onReiniciarMarcador() })
        cabecera.addView(boton(contexto, "Ambiente", 0xFF23262E.toInt(), 0xFF9AA0AA.toInt()) { escucha.onAlternarAmbiente() })
        cabecera.addView(boton(contexto, "Banner", 0xFF23262E.toInt(), 0xFF96BEFF.toInt()) { escucha.onCambiarPaleta() })
        columna.addView(cabecera)

        filaUno = tarjeta(contexto, 0)
        filaDos = tarjeta(contexto, 1)
        columna.addView(filaUno)
        columna.addView(filaDos)

        filaTurno = LinearLayout(contexto).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(contexto, 6f), 0, 0)
        }
        columna.addView(filaTurno)

        vista = columna
    }

    /** Vuelve a pintar todo con el estado actual. */
    fun refrescar(estado: EstadoTcg) {
        textoEvento.text = "EVENTO: ${estado.evento}".uppercase()
        pintarTarjeta(filaUno, estado, 0)
        pintarTarjeta(filaDos, estado, 1)
        pintarTurno(filaTurno, estado)
    }

    // ------------------------------------------------------------------ tarjeta de jugador

    private fun tarjeta(contexto: Context, lado: Int): LinearLayout =
        LinearLayout(contexto).apply {
            orientation = LinearLayout.VERTICAL
            background = caja(contexto, 0xFF1A1C22.toInt(), 0xFF2C303A.toInt(), 12f)
            setPadding(dp(contexto, 10f), dp(contexto, 8f), dp(contexto, 10f), dp(contexto, 8f))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(contexto, 6f)
            layoutParams = lp
        }

    private fun pintarTarjeta(fila: LinearLayout, estado: EstadoTcg, lado: Int) {
        val contexto = fila.context
        val jugador = if (lado == 0) estado.uno else estado.dos
        val enTurno = estado.turno == lado
        fila.removeAllViews()
        fila.background = caja(contexto,
            0xFF1A1C22.toInt(),
            if (enTurno) 0xFFFF6B35.toInt() else 0xFF2C303A.toInt(),
            12f
        )

        // fila 1: quien es + récord
        val cabecera = LinearLayout(contexto).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val nombre = TextView(contexto).apply {
            text = (if (enTurno) "▶ " else "") + jugador.nombre
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(0xFFECEEF2.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        nombre.setOnClickListener { escucha.onEditarNombre(lado) }
        cabecera.addView(nombre, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        cabecera.addView(etiqueta(contexto, "Torneo ${jugador.record}", 0xFF96BEFF.toInt()))
        fila.addView(cabecera)

        // fila 2: pokebolas
        val premios = LinearLayout(contexto).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        premios.addView(etiqueta(contexto, "Pokémon debilitados", 0xFF9AA0AA.toInt()))
        premios.addView(boton(contexto, "−", 0xFF23262E.toInt(), 0xFFECEEF2.toInt()) {
            escucha.onCambiarDebilitados(lado, -1)
        })
        val bolas = TextView(contexto).apply {
            text = pokebolasTexto(jugador.debilitados)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(contexto, 6f), 0, dp(contexto, 6f), 0)
        }
        premios.addView(bolas, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        premios.addView(boton(contexto, "+", 0xFF23262E.toInt(), 0xFFECEEF2.toInt()) {
            escucha.onCambiarDebilitados(lado, +1)
        })
        fila.addView(premios)

        // fila 3: chips del turno
        val chips = LinearLayout(contexto).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        chips.addView(etiqueta(contexto, "Mesa", 0xFF9AA0AA.toInt()))
        val nombres = arrayOf("Energy", "Supporter", "Retreat")
        val activos = intArrayOf(0xFF58C88C.toInt(), 0xFF7A98F4.toInt(), 0xFFF2AC52.toInt())
        for (i in 0 until 3) {
            val encendido = jugador.chips[i]
            val b = TextView(contexto).apply {
                text = nombres[i]
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                setTextColor(if (encendido) 0xFF121214.toInt() else 0xFF6C809E.toInt())
                background = caja(contexto,
                    if (encendido) activos[i] else 0xFF161B28.toInt(),
                    if (encendido) 0xFFFFFFFF.toInt() else 0xFF3E4E6E.toInt(),
                    12f
                )
                setPadding(dp(contexto, 8f), dp(contexto, 3f), dp(contexto, 8f), dp(contexto, 3f))
                isEnabled = enTurno
                alpha = if (enTurno) 1f else 0.45f
                setOnClickListener { if (enTurno) escucha.onCambiarChip(lado, i) }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.leftMargin = dp(contexto, 5f)
            chips.addView(b, lp)
        }
        if (!enTurno) chips.addView(etiqueta(contexto, " esperando turno", 0xFF646A76.toInt()))
        fila.addView(chips)
    }

    private fun pintarTurno(fila: LinearLayout, estado: EstadoTcg) {
        val contexto = fila.context
        val quien = if (estado.turno == 0) estado.uno.nombre else estado.dos.nombre
        fila.removeAllViews()
        fila.addView(
            TextView(contexto).apply {
                text = "TURNO DE $quien".uppercase()
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(0xFFFF6B35.toInt())
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, 0, dp(contexto, 10f), 0)
            }
        )
        fila.addView(boton(contexto, "Pasar turno", 0xFF23262E.toInt(), 0xFFECEEF2.toInt()) { escucha.onPasarTurno() })
        fila.addView(boton(contexto, "Ganó ${estado.uno.nombre}", 0xFF1A4A30.toInt(), 0xFFD6FFE8.toInt()) {
            escucha.onResultado(1)
        })
        fila.addView(boton(contexto, "Empate", 0xFF23262E.toInt(), 0xFFECEEF2.toInt()) { escucha.onResultado(0) })
        fila.addView(boton(contexto, "Ganó ${estado.dos.nombre}", 0xFF1A4A30.toInt(), 0xFFD6FFE8.toInt()) {
            escucha.onResultado(2)
        })
    }

    // ------------------------------------------------------------------ ayudas

    private fun pokebolasTexto(debilitados: Int): String {
        val total = 6
        val vivas = (total - debilitados).coerceIn(0, total)
        return "●".repeat(vivas) + "○".repeat(total - vivas)
    }

    private fun etiqueta(contexto: Context, t: String, color: Int): TextView = TextView(contexto).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setTextColor(color)
        setPadding(0, 0, dp(contexto, 8f), 0)
    }

    private fun boton(contexto: Context, t: String, fondo: Int, texto: Int, accion: () -> Unit): TextView =
        TextView(contexto).apply {
            text = t
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(texto)
            background = caja(contexto, fondo, 0xFF343A46.toInt(), 14f)
            setPadding(dp(contexto, 10f), dp(contexto, 5f), dp(contexto, 10f), dp(contexto, 5f))
            setOnClickListener { accion() }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.leftMargin = dp(contexto, 6f)
            layoutParams = lp
        }

    private fun caja(contexto: Context, fondo: Int, borde: Int, radio: Float) = GradientDrawable().apply {
        setColor(fondo)
        cornerRadius = dp(contexto, radio).toFloat()
        setStroke(dp(contexto, 1f).toInt().coerceAtLeast(1), borde)
    }

    private fun dp(contexto: Context, v: Float): Int =
        (v * contexto.resources.displayMetrics.density).toInt()
}
