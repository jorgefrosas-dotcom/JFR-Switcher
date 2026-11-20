package com.jfrmedia.switcher.studio.tcg

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
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
) {
    val record: String get() = "$ganadas - $empatadas - $perdidas"

    override fun equals(other: Any?): Boolean {
        if (other !is JugadorTcg) return false
        return nombre == other.nombre && ganadas == other.ganadas && empatadas == other.empatadas &&
            perdidas == other.perdidas && debilitados == other.debilitados &&
            chips.contentEquals(other.chips)
    }

    override fun hashCode(): Int {
        var r = nombre.hashCode()
        r = 31 * r + ganadas
        r = 31 * r + empatadas
        r = 31 * r + perdidas
        r = 31 * r + debilitados
        r = 31 * r + chips.contentHashCode()
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

    /**
     * Dibuja el banner completo y devuelve el bitmap listo para el compositor.
     * El tamaño es fijo (1600x320) y el compositor lo estira al rectángulo del programa.
     */
    fun dibujar(estado: EstadoTcg, ancho: Int = 1600, alto: Int = 360): Bitmap {
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
        texto.textSize = 26f * u
        cv.drawText(estado.evento.uppercase(), ancho / 2f, 40f * u, texto)

        // nombres (a la izquierda del bloque) y pokebolas grandes en dos filas de 3 al lado
        texto.textAlign = Paint.Align.LEFT
        texto.color = p.nombre
        texto.textSize = 33f * u
        cv.drawText(estado.uno.nombre.uppercase(), 34f * u, 106f * u, texto)
        texto.textAlign = Paint.Align.RIGHT
        cv.drawText(estado.dos.nombre.uppercase(), ancho - 34f * u, 106f * u, texto)

        // 6 pokebolas en 2 filas de 3, a la derecha (jugador 1) y a la izquierda (jugador 2)
        val r = 22f * u
        for (i in 0 until 6) {
            val fila = i / 3
            val col = i % 3
            val xIzq = 300f * u + col * 62f * u
            pokebola(cv, xIzq, 92f * u + fila * 56f * u, r, i >= estado.uno.debilitados, p.pokebolaBorde)
            val xDer = ancho - 300f * u - col * 62f * u
            pokebola(cv, xDer, 92f * u + fila * 56f * u, r, i >= estado.dos.debilitados, p.pokebolaBorde)
        }

        // récord
        texto.textSize = 22f * u
        texto.color = p.record
        texto.textAlign = Paint.Align.LEFT
        cv.drawText("Torneo  ${estado.uno.record}", 34f * u, 214f * u, texto)
        texto.textAlign = Paint.Align.RIGHT
        cv.drawText("Torneo  ${estado.dos.record}", ancho - 34f * u, 214f * u, texto)

        // Chips Energy / Supporter / Retreat de cada jugador, debajo de sus pokebolas.
        filaChips(cv, estado.uno, estado.turno == 0, 34f * u, 240f * u, 500f * u, u, alDerecha = false, p)
        filaChips(cv, estado.dos, estado.turno == 1, ancho - 34f * u - 500f * u, 240f * u, 500f * u, u, alDerecha = true, p)

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
        val alto = 62f * u
        val paso = ancho / 3f
        val pintura = Paint(Paint.ANTI_ALIAS_FLAG)
        val texto = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            textSize = 27f * u
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
