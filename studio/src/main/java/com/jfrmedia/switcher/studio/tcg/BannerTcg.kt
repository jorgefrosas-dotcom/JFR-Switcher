package com.jfrmedia.switcher.studio.tcg

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface

/**
 * Estado del modo Pokémon TCG: es lo que se dibuja en el banner y lo que cambia con los botones.
 * Es inmutable a propósito: cada cambio produce un estado nuevo y el banner se vuelve a pintar.
 */
data class JugadorTcg(
    val nombre: String = "Jugador",
    val ganadas: Int = 0,
    val empatadas: Int = 0,
    val perdidas: Int = 0,
    /** Pokebolas apagadas = Pokémon debilitados (0 a 6). */
    val debilitados: Int = 0,
    /** Energy, Supporter, Retreat (solo del jugador en turno). */
    val chips: BooleanArray = booleanArrayOf(true, true, true),
    /** Nombre del mazo que está jugando (de la lista del formato; vacío = sin asignar). */
    val mazo: String = "",
    /** Id del mazo en Limitless, para encontrar su icono. */
    val mazoId: String = "",
) {
    val record: String get() = "$ganadas - $empatadas - $perdidas"

    override fun equals(other: Any?): Boolean {
        if (other !is JugadorTcg) return false
        return nombre == other.nombre && ganadas == other.ganadas && empatadas == other.empatadas &&
            perdidas == other.perdidas && debilitados == other.debilitados &&
            chips.contentEquals(other.chips) && mazo == other.mazo && mazoId == other.mazoId
    }

    override fun hashCode(): Int {
        var r = nombre.hashCode()
        r = 31 * r + ganadas
        r = 31 * r + empatadas
        r = 31 * r + perdidas
        r = 31 * r + debilitados
        r = 31 * r + chips.contentHashCode()
        r = 31 * r + mazo.hashCode()
        r = 31 * r + mazoId.hashCode()
        return r
    }
}

data class JugadorEvento(
    val nombre: String,
    val ganadas: Int = 0,
    val empatadas: Int = 0,
    val perdidas: Int = 0,
) {
    val record: String get() = "$ganadas - $empatadas - $perdidas"
}

data class EstadoTcg(
    val evento: String = "LIGA JFR",
    /** Jugadores inscritos en el evento: de aquí salen los récords. */
    val roster: List<JugadorEvento> = emptyList(),
    val uno: JugadorTcg = JugadorTcg("Jugador 1"),
    val dos: JugadorTcg = JugadorTcg("Jugador 2"),
    val serieUno: Int = 0,
    val serieDos: Int = 0,
    /** 0 = le toca al jugador 1, 1 = al jugador 2. */
    val turno: Int = 0,
    /** Paleta del banner, 1 a 5. */
    val paleta: Int = 1,
    /** true cuando la cámara general ocupa el preview (el botón cambia a «Cambiar a mesa»). */
    val generalActivo: Boolean = false,
)

/** Colores de cada una de las cinco propuestas de banner. */
data class PaletaBanner(
    val fondo: Int,
    val borde: Int,
    val franja: Int,
    val franjaTexto: Int,
    val nombre: Int,
    val record: Int,
    val marcadorFondo: Int,
    val marcadorBorde: Int?,
    val marcador: Int,
    val dosPuntos: Int,
    val pokebolaBorde: Int,
)

object Banners {

    private fun c(v: Long) = (0xFF000000L or v).toInt()

    val PALETAS: List<PaletaBanner> = listOf(
        // 1 · Azul torneo
        PaletaBanner(c(0x1B3A6B), c(0x0E2044), c(0x102446), c(0x96BEFF), c(0xFFFFFF),
            c(0x96BEFF), c(0x0B1830), null, c(0xFFFFFF), c(0x8CAADC), c(0x121214)),
        // 2 · Nocturno
        PaletaBanner(c(0x13151B), c(0x343A46), c(0x1C202A), c(0x78E2F0), c(0xF0F2F6),
            c(0x78E2F0), c(0x0A0B0F), c(0x3C4454), c(0x78E2F0), c(0x464E5E), c(0x3C4250)),
        // 3 · Pokéball rojo
        PaletaBanner(c(0x96221C), c(0x540F0C), c(0x681612), c(0xFFD6C8), c(0xFFFFFF),
            c(0xFFD6C8), c(0x540E0C), c(0xFFFFFF), c(0xFFFFFF), c(0xFFC8B4), c(0x280A08)),
        // 4 · Claro
        PaletaBanner(c(0xF2F4F8), c(0xC4CAD6), c(0xE0E4EC), c(0x343E54), c(0x161A22),
            c(0x3A5CB4), c(0xFFFFFF), c(0xB4BCCA), c(0x161A22), c(0x788090), c(0x464A54)),
        // 5 · Esmeralda
        PaletaBanner(c(0x0E3B2E), c(0x08221A), c(0x0A2A21), c(0xE7C14B), c(0xFAFAF6),
            c(0xE7C14B), c(0x081E17), c(0xE7C14B), c(0xE7C14B), c(0xB4A05A), c(0x061812)),
    )

    fun paleta(numero: Int): PaletaBanner = PALETAS[(numero - 1).coerceIn(0, PALETAS.lastIndex)]

    // ------------------------------------------------------------------ fondo del modo

    /** Mezcla dos colores: `f` = 0 deja [a] y `f` = 1 deja [b]. */
    private fun mezclar(a: Int, b: Int, f: Float): Int {
        val t = f.coerceIn(0f, 1f)
        return Color.argb(
            (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t).toInt(),
            (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt()
        )
    }

    /**
     * Fondo del Modo Pokémon: **degradado con manchas difusas del color del banner**.
     *
     * Se usa detrás de la composición para que el programa no sea un vacío negro. Todo sale de la
     * paleta elegida, así que al cambiar el banner (botón «Banner» o Ajustes) el fondo se vuelve a
     * pintar con los colores nuevos (Jorge, 26/11/2026).
     *
     * Las manchas son degradados radiales de varias paradas: como el borde se va a transparente,
     * quedan con el borde difuso, sin necesidad de desenfoque real (que en Android no existe en
     * Canvas) y sin coste por cuadro: es un mapa de bits fijo.
     *
     * @param numeroPaleta 1..5, el mismo número que usa [paleta].
     */
    fun fondoVivo(
        numeroPaleta: Int,
        ancho: Int = 540,
        alto: Int = 960,
    ): Bitmap {
        val p = paleta(numeroPaleta)
        // ¿El banner es claro? Entonces el fondo también tiene que serlo, o el texto del banner
        // (oscuro en la paleta «Claro») quedaría ilegible contra un fondo negro.
        val claro = (0.299f * Color.red(p.fondo) + 0.587f * Color.green(p.fondo) +
            0.114f * Color.blue(p.fondo)) / 255f > 0.6f
        val bmp = Bitmap.createBitmap(ancho, alto, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val w = ancho.toFloat()
        val h = alto.toFloat()

        // 1) Degradado base en diagonal, derivado del color del banner: arriba más claro, abajo más
        //    oscuro (así el banner que va abajo y su texto se leen sin pelear con el fondo).
        val arriba = mezclar(p.fondo, Color.WHITE, if (claro) 0.30f else 0.16f)
        val abajo = mezclar(p.fondo, Color.BLACK, if (claro) 0.22f else 0.62f)
        val base = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, w * 0.55f, h,
                arriba, abajo, Shader.TileMode.CLAMP
            )
        }
        cv.drawRect(0f, 0f, w, h, base)

        // 2) Manchas. Cada una es un círculo con degradado radial (centro con color, borde
        //    transparente). Se dibujan estiradas para que no parezcan círculos perfectos.
        //    El alfa es bajo a propósito: el fondo acompaña, no compite con el área de juego.
        data class Mancha(
            val x: Float,
            val y: Float,
            val radio: Float,
            val estirado: Float,
            val color: Int,
            val alfa: Float,
        )

        val acento = p.franjaTexto
        val manchas = listOf(
            Mancha(0.16f, 0.08f, 0.58f, 1.30f, acento, 0.46f),
            Mancha(0.90f, 0.26f, 0.46f, 1.10f, acento, 0.26f),
            Mancha(0.62f, 0.50f, 0.60f, 0.85f, p.franja, if (claro) 0.60f else 0.80f),
            Mancha(0.10f, 0.66f, 0.50f, 1.20f, acento, 0.22f),
            Mancha(0.82f, 0.84f, 0.46f, 1.00f, acento, 0.18f),
        )
        val pincel = Paint(Paint.ANTI_ALIAS_FLAG)
        manchas.forEach { m ->
            val r = m.radio * minOf(w, h)
            if (r <= 1f) return@forEach
            val centro = Color.argb((m.alfa * 255).toInt(), Color.red(m.color), Color.green(m.color), Color.blue(m.color))
            val medio = Color.argb((m.alfa * 255 * 0.55f).toInt(), Color.red(m.color), Color.green(m.color), Color.blue(m.color))
            val fuera = Color.argb(0, Color.red(m.color), Color.green(m.color), Color.blue(m.color))
            pincel.shader = RadialGradient(
                0f, 0f, r, intArrayOf(centro, medio, fuera), floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            cv.save()
            cv.translate(m.x * w, m.y * h)
            cv.scale(m.estirado, 1f / m.estirado)
            cv.drawCircle(0f, 0f, r, pincel)
            cv.restore()
        }
        pincel.shader = null
        return bmp
    }

    /**
     * Dibuja el banner completo y devuelve el bitmap listo para el compositor.
     * El tamaño es fijo (1600x320) y el compositor lo estira al rectángulo del programa.
     */
    fun dibujar(
        estado: EstadoTcg,
        ancho: Int = 1600,
        alto: Int = 360,
        iconos: Map<String, List<Bitmap>> = emptyMap(),
    ): Bitmap {
        val p = paleta(estado.paleta)
        val bmp = Bitmap.createBitmap(ancho, alto, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val u = alto / 360f                      // escala de todo el dibujo
        val radio = 26f * u

        val pintura = Paint(Paint.ANTI_ALIAS_FLAG)
        // fondo
        pintura.color = p.fondo
        cv.drawRoundRect(RectF(2f, 2f, ancho - 2f, alto - 2f), radio, radio, pintura)
        // borde
        pintura.style = Paint.Style.STROKE
        pintura.strokeWidth = 6f * u
        pintura.color = p.borde
        cv.drawRoundRect(RectF(3f, 3f, ancho - 3f, alto - 3f), radio, radio, pintura)
        pintura.style = Paint.Style.FILL

        // franja del evento
        val franja = RectF(0f, 0f, ancho.toFloat(), 60f * u)
        pintura.color = p.franja
        cv.save()
        cv.clipRect(franja)
        cv.drawRoundRect(RectF(2f, 2f, ancho - 2f, alto - 2f), radio, radio, pintura)
        cv.restore()

        val texto = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        texto.color = p.franjaTexto
        texto.textSize = 30f * u
        cv.drawText(estado.evento.uppercase(), ancho / 2f, 42f * u, texto)

        // nombres (arriba), después el mazo y el récord. Los chips ya no van aquí: ahora van dentro
        // de la cámara de cada jugador, así que este lado queda libre para las pokebolas grandes.
        texto.textAlign = Paint.Align.LEFT
        texto.color = p.nombre
        texto.textSize = 46f * u
        cv.drawText(estado.uno.nombre.uppercase(), 34f * u, 128f * u, texto)
        texto.textAlign = Paint.Align.RIGHT
        cv.drawText(estado.dos.nombre.uppercase(), ancho - 34f * u, 128f * u, texto)

        // 6 pokebolas grandes en **2 columnas × 3 filas**, pegadas al marcador: la del jugador 1 a la
        // izquierda del 0:0 y la del jugador 2 a la derecha (Jorge, 23/11/2026).
        val r = 36f * u
        val pasoY = 88f * u
        val pasoX = 84f * u
        val yPokebolas = 120f * u
        for (i in 0 until 6) {
            val fila = i / 2
            val col = i % 2
            val xIzq = 508f * u + col * pasoX
            pokebola(cv, xIzq, yPokebolas + fila * pasoY, r, i >= estado.uno.debilitados, p.pokebolaBorde)
            val xDer = 1092f * u - col * pasoX
            pokebola(cv, xDer, yPokebolas + fila * pasoY, r, i >= estado.dos.debilitados, p.pokebolaBorde)
        }

        // récord
        texto.textSize = 40f * u
        texto.color = p.record
        texto.textAlign = Paint.Align.LEFT
        cv.drawText("Torneo  ${estado.uno.record}", 34f * u, 252f * u, texto)
        texto.textAlign = Paint.Align.RIGHT
        cv.drawText("Torneo  ${estado.dos.record}", ancho - 34f * u, 252f * u, texto)

        // Mazo de cada jugador (icono + nombre), entre el nombre y el récord.
        dibujarMazo(cv, estado.uno, 34f * u, 168f * u, u, alDerecha = false, iconos, p)
        dibujarMazo(cv, estado.dos, ancho - 34f * u, 168f * u, u, alDerecha = true, iconos, p)

        // marcador central
        val caja = RectF(ancho / 2f - 160f * u, 70f * u, ancho / 2f + 160f * u, 300f * u)
        pintura.color = p.marcadorFondo
        cv.drawRoundRect(caja, 18f * u, 18f * u, pintura)
        p.marcadorBorde?.let {
            pintura.style = Paint.Style.STROKE
            pintura.strokeWidth = 3f * u
            pintura.color = it
            cv.drawRoundRect(caja, 18f * u, 18f * u, pintura)
            pintura.style = Paint.Style.FILL
        }
        texto.color = p.marcador
        texto.textSize = 92f * u
        texto.textAlign = Paint.Align.CENTER
        cv.drawText("${estado.serieUno}", ancho / 2f - 78f * u, 206f * u, texto)
        cv.drawText("${estado.serieDos}", ancho / 2f + 78f * u, 206f * u, texto)
        texto.textSize = 66f * u
        texto.color = p.dosPuntos
        cv.drawText(":", ancho / 2f, 202f * u, texto)

        return bmp
    }

    /**
     * Chips Energy / Supporter / Retreat de un jugador, para dibujarlos **dentro del video**
     * debajo de su cámara. Si no es su turno, van apagados.
     */
    fun dibujarChips(jugador: JugadorTcg, enTurno: Boolean, ancho: Int = 760, alto: Int = 150): Bitmap {
        val bmp = Bitmap.createBitmap(ancho, alto, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val u = alto / 150f
        val nombres = listOf("ENERGY", "SUPPORTER", "RETREAT")
        val colores = listOf(Color.rgb(88, 200, 140), Color.rgb(122, 152, 244), Color.rgb(242, 172, 82))
        val pintura = Paint(Paint.ANTI_ALIAS_FLAG)
        val texto = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            textSize = 44f * u
        }
        var x = 4f * u
        for (i in 0 until 3) {
            val activo = enTurno && jugador.chips[i]
            val w = ancho / 3f - 8f * u
            val caja = RectF(x, 8f * u, x + w, alto - 8f * u)
            pintura.color = if (activo) colores[i] else if (enTurno) Color.rgb(22, 27, 40) else Color.rgb(22, 24, 30)
            cv.drawRoundRect(caja, 20f * u, 20f * u, pintura)
            pintura.style = Paint.Style.STROKE
            pintura.strokeWidth = 3f * u
            pintura.color = if (activo) Color.WHITE else if (enTurno) Color.rgb(62, 78, 110) else Color.rgb(48, 52, 62)
            cv.drawRoundRect(caja, 20f * u, 20f * u, pintura)
            pintura.style = Paint.Style.FILL
            texto.color = if (activo) Color.rgb(16, 16, 18) else if (enTurno) Color.rgb(120, 136, 160) else Color.rgb(78, 84, 96)
            cv.drawText(nombres[i], caja.centerX(), caja.centerY() + 12f * u, texto)
            x += w + 8f * u
        }
        return bmp
    }
    /**
     * Un chip (Energy / Supporter / Retreat) para dibujarlo **dentro de la cámara del jugador**.
     * Apagado = ya se usó: se pinta translúcido, pero se sigue viendo su punta.
     */
    /**
     * Base de **todos** los chips: la misma forma, el mismo borde y el mismo texto centrado.
     *
     * Una sola función para que el cronómetro y los chips de Energy/Supporter/Retreat se vean
     * exactamente iguales: la única diferencia entre ellos es el tamaño y el texto
     * (Jorge, 25/11/2026).
     */
    private fun pildora(
        ancho: Int,
        alto: Int,
        relleno: Int,
        borde: Int,
        texto: String,
        colorTexto: Int,
        tamanoTexto: Float,
        tipo: Typeface,
        sombra: Boolean = false,
    ): Bitmap {
        val bmp = Bitmap.createBitmap(ancho, alto, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        // Se toma 120 como referencia de alto, igual para los dos tamaños.
        val u = alto / 120f
        val pintura = Paint(Paint.ANTI_ALIAS_FLAG)
        // Radio sobre el alto REAL de la caja: si se pide más de la mitad, Android recorta la curva
        // y los extremos salen picudos.
        val caja = RectF(3f * u, 3f * u, ancho - 3f * u, alto - 3f * u)
        val radio = caja.height() / 2f
        pintura.color = relleno
        cv.drawRoundRect(caja, radio, radio, pintura)
        pintura.style = Paint.Style.STROKE
        pintura.strokeWidth = 5f * u
        pintura.color = borde
        cv.drawRoundRect(caja, radio, radio, pintura)
        pintura.style = Paint.Style.FILL
        val letra = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tipo
            textAlign = Paint.Align.CENTER
            textSize = tamanoTexto
            color = colorTexto
            if (sombra) setShadowLayer(6f * u, 0f, 2f * u, 0xAA000000.toInt())
        }
        // Centrado vertical real, sin números mágicos por chip.
        val linea = alto / 2f - (letra.descent() + letra.ascent()) / 2f
        cv.drawText(texto, ancho / 2f, linea, letra)
        return bmp
    }

    /** Chip de la mesa (Energy, Supporter, Retreat): píldora con el texto centrado. */
    fun dibujarChip(
        nombre: String,
        color: Int,
        activo: Boolean,
        ancho: Int = 520,
        alto: Int = 120,
    ): Bitmap = pildora(
        ancho = ancho,
        alto = alto,
        relleno = if (activo) color else Color.argb(150, 26, 30, 40),
        borde = if (activo) Color.WHITE else Color.argb(150, 74, 80, 94),
        texto = nombre,
        colorTexto = if (activo) Color.rgb(16, 16, 18) else Color.argb(170, 158, 166, 180),
        tamanoTexto = alto / 120f * 52f,
        tipo = Typeface.create(Typeface.DEFAULT, Typeface.BOLD),
    )

    /**
     * Cronómetro de la partida: **la misma píldora de los chips de la mesa**, del color del banner
     * elegido y con los números grandes en amarillo (Jorge, 25/11/2026).
     *
     * La proporción es 520:120, igual que los chips, para que los extremos se vean idénticos.
     */
    fun dibujarCronometro(
        texto: String,
        numeroPaleta: Int,
        ancho: Int = 1040,
        alto: Int = 240,
    ): Bitmap = pildora(
        ancho = ancho,
        alto = alto,
        relleno = paleta(numeroPaleta).fondo,
        borde = Color.WHITE,
        texto = texto,
        colorTexto = 0xFFFFD21F.toInt(),
        tamanoTexto = alto / 120f * 54f,
        tipo = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD),
        sombra = true,
    )

    /** Las 6 pokebolas de un jugador para la banda de control (encendidas = premios vivos). */
    fun dibujarPokebolas(debilitados: Int, ancho: Int = 420, alto: Int = 64): Bitmap {
        val bmp = Bitmap.createBitmap(ancho, alto, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val r = alto / 2f - 3f
        val paso = (ancho - 6f) / 6f
        val vivas = (6 - debilitados).coerceIn(0, 6)
        for (i in 0 until 6) {
            pokebola(cv, 3f + paso * i + paso / 2f, alto / 2f, r, i < vivas, Color.rgb(18, 18, 20))
        }
        return bmp
    }
    /**
     * Mazo del jugador dentro del banner: el icono (o los dos, si el mazo lleva dos Pokémon) y el
     * nombre del mazo, debajo del nombre del jugador. Si el nombre no cabe, se corta con «…» para no
     * invadir las pokebolas.
     */
    private fun dibujarMazo(
        cv: Canvas,
        jugador: JugadorTcg,
        x: Float,
        y: Float,
        u: Float,
        alDerecha: Boolean,
        iconos: Map<String, List<Bitmap>>,
        p: PaletaBanner,
    ) {
        val nombreMazo = jugador.mazo.trim()
        if (nombreMazo.isEmpty()) return
        val dibujos = iconos[jugador.mazoId].orEmpty().filter { !it.isRecycled }.take(2)
        val lado = 48f * u
        val entreIconos = 6f * u
        val separacion = 10f * u
        val anchoIconos =
            if (dibujos.isEmpty()) 0f else lado * dibujos.size + entreIconos * (dibujos.size - 1) + separacion
        // Las pokebolas grandes empiezan en 472u: el nombre del mazo no puede pasar de ahí.
        val topeTexto = 430f * u - anchoIconos
        val pintura = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 34f * u
            color = p.franjaTexto
            textAlign = if (alDerecha) Paint.Align.RIGHT else Paint.Align.LEFT
        }
        var texto = nombreMazo
        while (texto.length > 1 && pintura.measureText(texto) > topeTexto) {
            texto = texto.dropLast(2) + "…"
        }
        val xTexto = if (alDerecha) x - anchoIconos else x + anchoIconos
        cv.drawText(texto, xTexto, y + lado * 0.78f, pintura)
        // Los iconos, del lado del borde (izquierda para el jugador 1, derecha para el 2).
        val filtro = Paint(Paint.FILTER_BITMAP_FLAG)
        var cursor = if (alDerecha) x - lado else x
        dibujos.forEach { icono ->
            cv.drawBitmap(icono, null, RectF(cursor, y, cursor + lado, y + lado), filtro)
            cursor += if (alDerecha) -(lado + entreIconos) else lado + entreIconos
        }
    }

    /** Tres chips de un jugador dentro del banner (rellenos si están disponibles). */
    private fun filaChips(
        cv: Canvas,
        jugador: JugadorTcg,
        enTurno: Boolean,
        x0: Float,
        y0: Float,
        ancho: Float,
        u: Float,
        alDerecha: Boolean,
        p: PaletaBanner,
    ) {
        val nombres = listOf("ENERGY", "SUPPORTER", "RETREAT")
        val colores = listOf(Color.rgb(88, 200, 140), Color.rgb(122, 152, 244), Color.rgb(242, 172, 82))
        val alto = 64f * u
        val paso = ancho / 3f
        val pintura = Paint(Paint.ANTI_ALIAS_FLAG)
        val texto = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            textSize = 28f * u
        }
        for (i in 0 until 3) {
            val disponible = enTurno && jugador.chips[i]
            val w = paso - 8f * u
            val x = if (alDerecha) x0 + paso * i else x0 + paso * i
            val caja = RectF(x, y0, x + w, y0 + alto)
            pintura.color = if (disponible) colores[i] else Color.argb(70, 40, 46, 58)
            cv.drawRoundRect(caja, alto / 2f, alto / 2f, pintura)
            pintura.style = Paint.Style.STROKE
            pintura.strokeWidth = 2f * u
            pintura.color = if (disponible) Color.WHITE else Color.argb(90, 120, 132, 150)
            cv.drawRoundRect(caja, alto / 2f, alto / 2f, pintura)
            pintura.style = Paint.Style.FILL
            texto.color = if (disponible) Color.rgb(16, 16, 18) else Color.argb(120, 210, 216, 224)
            cv.drawText(nombres[i], caja.centerX(), caja.centerY() + 7f * u, texto)
        }
    }
    private fun pokebola(cv: Canvas, cx: Float, cy: Float, r: Float, encendida: Boolean, borde: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val arriba = if (encendida) Color.rgb(227, 53, 13) else Color.rgb(58, 59, 64)
        val abajo = if (encendida) Color.rgb(243, 243, 245) else Color.rgb(32, 33, 37)
        p.color = abajo
        cv.drawCircle(cx, cy, r, p)
        cv.save()
        cv.clipRect(cx - r, cy - r, cx + r, cy)
        p.color = arriba
        cv.drawCircle(cx, cy, r, p)
        cv.restore()
        p.color = borde
        cv.drawRect(cx - r, cy - r * 0.14f, cx + r, cy + r * 0.14f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = r * 0.22f
        cv.drawCircle(cx, cy, r, p)
        p.style = Paint.Style.FILL
        p.color = if (encendida) Color.rgb(243, 243, 245) else Color.rgb(52, 53, 58)
        cv.drawCircle(cx, cy, r * 0.34f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = r * 0.16f
        p.color = borde
        cv.drawCircle(cx, cy, r * 0.34f, p)
    }
}
