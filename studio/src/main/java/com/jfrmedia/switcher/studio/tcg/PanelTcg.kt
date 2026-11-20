package com.jfrmedia.switcher.studio.tcg

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Banda de controles del Modo Pokémon TCG, en una sola fila como el diseño aprobado:
 *
 *     [ Jugador 1 ] [ turno · resultado · herramientas ] [ Jugador 2 ]
 *
 * Cada tarjeta lleva el nombre, el récord del evento, las 6 pokebolas con −/+ y los chips de la
 * mesa. El centro lleva de quién es el turno, el botón de pasar turno, el resultado por nombre y
 * las herramientas de la partida.
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
        fun onAbrirEvento()
    }

    val vista: View

    private val tarjetaUno: LinearLayout
    private val tarjetaDos: LinearLayout
    private val centro: LinearLayout

    init {
        val fila = LinearLayout(contexto).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(0, dp(contexto, 6f), 0, 0)
        }
        tarjetaUno = LinearLayout(contexto)
        centro = LinearLayout(contexto)
        tarjetaDos = LinearLayout(contexto)

        fila.addView(
            tarjetaUno,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(contexto, 8f) }
        )
        fila.addView(
            centro,
            LinearLayout.LayoutParams(dp(contexto, 330f), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = dp(contexto, 8f)
            }
        )
        fila.addView(
            tarjetaDos,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        vista = fila
    }

    fun refrescar(estado: EstadoTcg) {
        pintarTarjeta(tarjetaUno, estado, 0)
        pintarTarjeta(tarjetaDos, estado, 1)
        pintarCentro(centro, estado)
    }

    // ------------------------------------------------------------------ tarjetas

    private fun pintarTarjeta(fila: LinearLayout, estado: EstadoTcg, lado: Int) {
        val contexto = fila.context
        val jugador = if (lado == 0) estado.uno else estado.dos
        val enTurno = estado.turno == lado
        fila.removeAllViews()
        fila.orientation = LinearLayout.VERTICAL
        fila.background = caja(contexto, 0xFF1A1C22.toInt(), if (enTurno) 0xFFFF6B35.toInt() else 0xFF2C303A.toInt(), 12f)
        fila.setPadding(dp(contexto, 12f), dp(contexto, 8f), dp(contexto, 12f), dp(contexto, 10f))

        // Nombre + récord del evento
        val cabecera = LinearLayout(contexto).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        cabecera.addView(
            TextView(contexto).apply {
                text = (if (enTurno) "▶ " else "") + jugador.nombre
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(0xFFECEEF2.toInt())
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, dp(contexto, 10f), 0)
                setOnClickListener { escucha.onEditarNombre(lado) }
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        cabecera.addView(
            TextView(contexto).apply {
                text = "Torneo ${jugador.record}"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(0xFF96BEFF.toInt())
            }
        )
        fila.addView(cabecera)

        // Pokebolas: − ●●●●●● +
        val premios = LinearLayout(contexto).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(contexto, 6f), 0, 0)
        }
        premios.addView(etiqueta(contexto, "Pokémon", 0xFF9AA0AA.toInt()))
        premios.addView(boton(contexto, "−", 0xFF23262E.toInt(), 0xFFECEEF2.toInt()) {
            escucha.onCambiarDebilitados(lado, -1)
        })
        premios.addView(
            // Pokebolas dibujadas (iconos), no círculos de texto.
            android.widget.ImageView(contexto).apply {
                setImageBitmap(Banners.dibujarPokebolas(jugador.debilitados, 420, dp(contexto, 30f)))
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            },
            LinearLayout.LayoutParams(0, dp(contexto, 30f), 1f)
        )
        premios.addView(boton(contexto, "+", 0xFF23262E.toInt(), 0xFFECEEF2.toInt()) {
            escucha.onCambiarDebilitados(lado, +1)
        })
        fila.addView(premios)

        // Chips de la mesa
        val fichas = LinearLayout(contexto).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(contexto, 6f), 0, 0)
        }
        fichas.addView(etiqueta(contexto, "Mesa", 0xFF9AA0AA.toInt()))
        val nombres = arrayOf("Energy", "Supporter", "Retreat")
        val activos = intArrayOf(0xFF58C88C.toInt(), 0xFF7A98F4.toInt(), 0xFFF2AC52.toInt())
        for (i in 0 until 3) {
            val encendido = jugador.chips[i]
            fichas.addView(
                TextView(contexto).apply {
                    text = nombres[i]
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
                    setTextColor(if (encendido) 0xFF121214.toInt() else 0xFF6C809E.toInt())
                    background = caja(
                        contexto,
                        if (encendido) activos[i] else 0xFF161B28.toInt(),
                        if (encendido) 0xFFFFFFFF.toInt() else 0xFF3E4E6E.toInt(),
                        11f
                    )
                    setPadding(dp(contexto, 7f), dp(contexto, 3f), dp(contexto, 7f), dp(contexto, 3f))
                    alpha = if (enTurno) 1f else 0.45f
                    setOnClickListener { if (enTurno) escucha.onCambiarChip(lado, i) }
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { leftMargin = dp(contexto, 5f) }
            )
        }
        if (!enTurno) fichas.addView(etiqueta(contexto, " espera", 0xFF646A76.toInt()))
        fila.addView(fichas)
    }

    // ------------------------------------------------------------------ centro

    private fun pintarCentro(fila: LinearLayout, estado: EstadoTcg) {
        val contexto = fila.context
        val quien = if (estado.turno == 0) estado.uno.nombre else estado.dos.nombre
        fila.removeAllViews()
        fila.orientation = LinearLayout.VERTICAL
        fila.background = caja(contexto, 0xFF16181E.toInt(), 0xFF2C303A.toInt(), 12f)
        fila.setPadding(dp(contexto, 10f), dp(contexto, 8f), dp(contexto, 10f), dp(contexto, 8f))

        // Turno y el botón de pasar, en la misma fila (ahorra alto para el monitor).
        val turnoFila = LinearLayout(contexto).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.CENTER_HORIZONTAL
        }
        turnoFila.addView(
            TextView(contexto).apply {
                text = "TURNO DE $quien".uppercase()
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(0xFFFF6B35.toInt())
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, dp(contexto, 8f), 0)
            }
        )
        turnoFila.addView(boton(contexto, "Pasar turno", 0xFF23262E.toInt(), 0xFFECEEF2.toInt(), 11f) { escucha.onPasarTurno() })
        fila.addView(turnoFila)

        val resultado = LinearLayout(contexto).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(contexto, 6f), 0, 0)
        }
        resultado.addView(boton(contexto, "Ganó ${estado.uno.nombre}", 0xFF1A4A30.toInt(), 0xFFD6FFE8.toInt(), 10f) {
            escucha.onResultado(1)
        })
        resultado.addView(boton(contexto, "Empate", 0xFF23262E.toInt(), 0xFFECEEF2.toInt(), 10f) {
            escucha.onResultado(0)
        })
        resultado.addView(boton(contexto, "Ganó ${estado.dos.nombre}", 0xFF1A4A30.toInt(), 0xFFD6FFE8.toInt(), 10f) {
            escucha.onResultado(2)
        })
        fila.addView(resultado)

        val herramientas = LinearLayout(contexto).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(contexto, 6f), 0, 0)
        }
        herramientas.addView(boton(contexto, "Evento", 0xFF23262E.toInt(), 0xFFFFCB05.toInt(), 10f) { escucha.onAbrirEvento() })
        herramientas.addView(boton(contexto, "Nueva", 0xFF23262E.toInt(), 0xFFECEEF2.toInt(), 10f) { escucha.onNuevaPartida() })
        herramientas.addView(boton(contexto, "Reiniciar", 0xFF23262E.toInt(), 0xFF9AA0AA.toInt(), 10f) { escucha.onReiniciarMarcador() })
        herramientas.addView(boton(contexto, "Ambiente", 0xFF23262E.toInt(), 0xFF9AA0AA.toInt(), 10f) { escucha.onAlternarAmbiente() })
        herramientas.addView(boton(contexto, "Banner", 0xFF23262E.toInt(), 0xFF96BEFF.toInt(), 10f) { escucha.onCambiarPaleta() })
        fila.addView(herramientas)
    }

    // ------------------------------------------------------------------ ayudas

    private fun etiqueta(contexto: Context, t: String, color: Int): TextView = TextView(contexto).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setTextColor(color)
        setPadding(0, 0, dp(contexto, 6f), 0)
    }

    private fun boton(
        contexto: Context,
        t: String,
        fondo: Int,
        texto: Int,
        tam: Float = 12f,
        accion: () -> Unit,
    ): TextView = TextView(contexto).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, tam)
        setTextColor(texto)
        background = caja(contexto, fondo, 0xFF343A46.toInt(), 13f)
        setPadding(dp(contexto, 8f), dp(contexto, 4f), dp(contexto, 8f), dp(contexto, 4f))
        setOnClickListener { accion() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = dp(contexto, 5f) }
    }

    private fun caja(contexto: Context, fondo: Int, borde: Int, radio: Float) = GradientDrawable().apply {
        setColor(fondo)
        cornerRadius = dp(contexto, radio).toFloat()
        setStroke(dp(contexto, 1f).toInt().coerceAtLeast(1), borde)
    }

    private fun dp(contexto: Context, v: Float): Int =
        (v * contexto.resources.displayMetrics.density).toInt()
}
