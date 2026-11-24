package com.jfrmedia.switcher.studio.tcg

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Banda de controles del Modo Pokémon TCG, en una sola fila como el diseño aprobado:
 *
 *     [ Jugador 1 ] [ turno · resultado · herramientas ] [ Jugador 2 ]
 *
 * Cada tarjeta lleva el nombre, el récord del evento, **el mazo que está jugando**, las 6 pokebolas
 * con −/+ y los chips de la mesa. El centro lleva de quién es el turno, el botón de pasar turno, el
 * resultado por nombre y las herramientas de la partida.
 */
class PanelTcg(
    contexto: Context,
    private val escucha: Escucha,
    /** Devuelve los iconos ya bajados de un mazo (por id); vacío si no hay. */
    private val iconos: (String) -> List<Bitmap> = { emptyList() },
) {

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

        /** Abre el cuadro del QR / IP para conectar otra cámara sin salir del modo. */
        fun onConectarCamara()

        /** Abre la lista de mazos del formato para asignarle uno a ese jugador. */
        fun onElegirMazo(lado: Int)
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

        // La fila se queda solo con los dos cuadros: el centro pasa a ser la columna de al lado.
        fila.addView(
            tarjetaUno,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { rightMargin = dp(contexto, 8f) }
        )
        fila.addView(
            tarjetaDos,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        )
        vista = fila

        centro.orientation = LinearLayout.VERTICAL
        centro.background = caja(contexto, 0xFF16181E.toInt(), 0xFF2C303A.toInt(), 12f)
        centro.setPadding(dp(contexto, 8f), dp(contexto, 8f), dp(contexto, 8f), dp(contexto, 8f))
    }

    /** Columna de herramientas (turno, resultado y herramientas) para ponerla al lado del monitor. */
    val vistaHerramientas: View get() = centro

    fun refrescar(estado: EstadoTcg) {
        pintarTarjeta(tarjetaUno, estado, 0)
        pintarTarjeta(tarjetaDos, estado, 1)
        pintarComentarios()
    }

    // ------------------------------------------------------------------ comentarios

    /** Cuántos están viendo en Facebook (texto ya formateado; vacío = sin conectar). */
    private var espectadores: String = ""

    /** Últimos comentarios: (autor, texto). */
    private var comentarios: List<Pair<String, String>> = emptyList()

    /** Lo llama la pantalla cuando llegan datos de Facebook. */
    fun comentariosFacebook(espectadores: String, lista: List<Pair<String, String>>) {
        this.espectadores = espectadores
        this.comentarios = lista
        pintarComentarios()
    }

    /**
     * Recuadro de la derecha: **contador de espectadores y comentarios del stream**.
     * Ocupa el lugar donde antes estaban los botones del turno (Jorge, 24/11/2026).
     */
    private fun pintarComentarios() {
        val c = centro
        val contexto = c.context
        c.removeAllViews()
        c.orientation = LinearLayout.VERTICAL
        c.background = caja(contexto, 0xFF16181E.toInt(), 0xFF2C303A.toInt(), 12f)
        c.setPadding(dp(contexto, 10f), dp(contexto, 8f), dp(contexto, 10f), dp(contexto, 8f))

        c.addView(
            TextView(contexto).apply {
                text = "ESPECTADORES"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                setTextColor(0xFF9AA0AA.toInt())
            }
        )
        c.addView(
            TextView(contexto).apply {
                val sinDatos = espectadores.isBlank()
                text = if (sinDatos) "sin conexión" else espectadores
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (sinDatos) 14f else 34f)
                setTextColor(if (sinDatos) 0xFF6E7686.toInt() else 0xFF58C88C.toInt())
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, 0, dp(contexto, 6f))
            }
        )
        c.addView(
            TextView(contexto).apply {
                text = "COMENTARIOS"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                setTextColor(0xFF9AA0AA.toInt())
                setPadding(0, 0, 0, dp(contexto, 4f))
            }
        )
        if (comentarios.isEmpty()) {
            c.addView(
                TextView(contexto).apply {
                    text = if (espectadores.isBlank()) {
                        "Conecta Facebook en «Ajustes» para ver aquí los comentarios del stream."
                    } else {
                        "Todavía no hay comentarios."
                    }
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    setTextColor(0xFF6E7686.toInt())
                }
            )
            return
        }
        val columna = LinearLayout(contexto).apply { orientation = LinearLayout.VERTICAL }
        comentarios.forEach { par ->
            val fila = LinearLayout(contexto).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 0, 0, dp(contexto, 6f))
            }
            fila.addView(
                TextView(contexto).apply {
                    text = par.first
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    setTextColor(0xFF96BEFF.toInt())
                    typeface = Typeface.DEFAULT_BOLD
                }
            )
            fila.addView(
                TextView(contexto).apply {
                    text = par.second
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(0xFFECEEF2.toInt())
                    maxLines = 4
                }
            )
            columna.addView(fila)
        }
        c.addView(
            android.widget.ScrollView(contexto).apply { addView(columna) },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )
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

        // Mazo del jugador: icono + nombre, arriba de las pokebolas. Se toca para elegirlo de la
        // lista de los mazos más usados del formato.
        val filaMazo = LinearLayout(contexto).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(contexto, 6f), dp(contexto, 3f), dp(contexto, 6f), dp(contexto, 3f))
            background = caja(contexto, 0xFF1E222A.toInt(), 0xFF2C303A.toInt(), 10f)
            setOnClickListener { escucha.onElegirMazo(lado) }
        }
        val iconosMazo = if (jugador.mazoId.isNotBlank()) iconos(jugador.mazoId) else emptyList()
        iconosMazo.filter { !it.isRecycled }.take(2).forEach { bmp ->
            filaMazo.addView(
                ImageView(contexto).apply {
                    setImageBitmap(bmp)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                },
                LinearLayout.LayoutParams(dp(contexto, 24f), dp(contexto, 24f)).apply {
                    rightMargin = dp(contexto, 6f)
                }
            )
        }
        filaMazo.addView(
            TextView(contexto).apply {
                val sinMazo = jugador.mazo.isBlank()
                text = if (sinMazo) "＋ Elegir mazo" else jugador.mazo
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setTextColor(if (sinMazo) 0xFF9AA0AA.toInt() else 0xFFECEEF2.toInt())
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        fila.addView(
            filaMazo,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(contexto, 5f) }
        )

        // Pasar turno y «Ganó» **dentro de la tarjeta del jugador**, arriba de las pokebolas
        // (Jorge, 24/11/2026: antes estaban en la columna de la derecha, que ahora es de los
        // comentarios).
        val acciones = LinearLayout(contexto).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(contexto, 6f), 0, 0)
        }
        acciones.addView(
            boton(contexto, "Pasar turno", 0xFF23262E.toInt(), 0xFFECEEF2.toInt(), 11f) {
                escucha.onPasarTurno()
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(contexto, 4f)
            }
        )
        acciones.addView(
            boton(contexto, "Ganó " + jugador.nombre, 0xFF1A4A30.toInt(), 0xFFD6FFE8.toInt(), 11f) {
                escucha.onResultado(lado + 1)
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        fila.addView(acciones)

        // Pokebolas: − ●●●●●● +
        val premios = LinearLayout(contexto).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(contexto, 6f), 0, 0)
        }
        premios.addView(etiqueta(contexto, "Pokémon", 0xFF9AA0AA.toInt()))
        // `debilitados` cuenta las pokebolas ya gastadas, así que quitar una bola con el «−» suma
        // uno. Antes estaban al revés y había que apretar «+» para gastar (Jorge, 22/11/2026).
        premios.addView(boton(contexto, "−", 0xFF23262E.toInt(), 0xFFECEEF2.toInt()) {
            escucha.onCambiarDebilitados(lado, +1)
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
            // Poner una pokebola de vuelta (un Pokémon menos debilitado).
            escucha.onCambiarDebilitados(lado, -1)
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
        fila.addView(fichas)
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
