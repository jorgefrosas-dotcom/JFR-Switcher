package com.jfrmedia.switcher.studio.texto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.sin

/** Una animación de entrada disponible para los textos. */
data class EntradaTexto(val clave: String, val nombre: String)

/** Lo que se guarda en la biblioteca: el texto y cómo se ve. */
data class TextoGuardado(
    val id: String,
    val texto: String,
    val fuente: String,
    val color: Int,
    val animacion: String,
    val tamano: Float
) {
    fun aJson(): String = JSONObject()
        .put("id", id)
        .put("texto", texto)
        .put("fuente", fuente)
        .put("color", color)
        .put("animacion", animacion)
        .put("tamano", tamano.toDouble())
        .toString()

    companion object {
        fun deJson(t: String): TextoGuardado? = runCatching {
            val o = JSONObject(t)
            TextoGuardado(
                id = o.optString("id"),
                texto = o.optString("texto"),
                fuente = o.optString("fuente", "Sans negrita"),
                color = o.optInt("color", Color.WHITE),
                animacion = o.optString("animacion", "fundido"),
                tamano = o.optDouble("tamano", 1.0).toFloat()
            ).takeIf { it.id.isNotEmpty() && it.texto.isNotEmpty() }
        }.getOrNull()
    }
}

/** Tipografías que puede elegir el usuario (nombre visible → Typeface). */
object Fuentes {
    val TODAS: List<Pair<String, Typeface>> = listOf(
        "Sans" to Typeface.SANS_SERIF,
        "Sans negrita" to Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD),
        "Serif" to Typeface.SERIF,
        "Serif negrita" to Typeface.create(Typeface.SERIF, Typeface.BOLD),
        "Monoespaciada" to Typeface.MONOSPACE,
        "Cursiva" to Typeface.create("cursive", Typeface.NORMAL),
        "Compacta" to Typeface.create("sans-serif-condensed", Typeface.BOLD),
        "Elegante" to Typeface.create("serif", Typeface.ITALIC)
    )

    fun de(nombre: String): Typeface =
        TODAS.firstOrNull { it.first == nombre }?.second ?: Typeface.SANS_SERIF
}

/** Las 10 entradas de texto, en el orden en que se muestran. */
object Entradas {
    val TODAS = listOf(
        EntradaTexto("fundido", "Fundido"),
        EntradaTexto("subir", "Subir"),
        EntradaTexto("bajar", "Bajar"),
        EntradaTexto("izquierda", "Desde la izquierda"),
        EntradaTexto("derecha", "Desde la derecha"),
        EntradaTexto("zoom", "Zoom"),
        EntradaTexto("pop", "Pop"),
        EntradaTexto("rebote", "Rebote"),
        EntradaTexto("deslizar", "Deslizar con fundido"),
        EntradaTexto("caida", "Caída con rebote")
    )

    fun nombre(clave: String): String = TODAS.firstOrNull { it.clave == clave }?.nombre ?: "Fundido"

    /** Estado de la entrada en el momento [p] (0 = acaba de aparecer, 1 = ya está quieto). */
    data class Estado(
        val desplazamientoX: Float = 0f,
        val desplazamientoY: Float = 0f,
        val alfa: Float = 1f,
        val escala: Float = 1f
    )

    fun calcular(clave: String, p: Float): Estado {
        val t = p.coerceIn(0f, 1f)
        // Suavizado estándar (ease-out): entra rápido y frena.
        val suave = 1f - (1f - t) * (1f - t)
        return when (clave) {
            "subir" -> Estado(desplazamientoY = (1f - suave) * 0.35f, alfa = t)
            "bajar" -> Estado(desplazamientoY = -(1f - suave) * 0.35f, alfa = t)
            "izquierda" -> Estado(desplazamientoX = -(1f - suave) * 0.6f, alfa = t)
            "derecha" -> Estado(desplazamientoX = (1f - suave) * 0.6f, alfa = t)
            "zoom" -> Estado(alfa = t, escala = 0.55f + 0.45f * suave)
            "pop" -> {
                // Se pasa un poco de tamaño y vuelve: da sensación de "golpe".
                val exceso = 0.14f * sin(t * Math.PI.toFloat())
                Estado(alfa = t, escala = 0.70f + 0.30f * suave + exceso)
            }
            "rebote" -> {
                // Cae y rebota dos veces, perdiendo fuerza.
                val rebote = abs(sin(t * Math.PI.toFloat() * 2f)) * (1f - t) * 0.22f
                Estado(desplazamientoY = -(1f - t) * 0.18f - rebote, alfa = t)
            }
            "deslizar" -> Estado(desplazamientoX = (1f - suave) * 0.5f, alfa = t * t)
            "caida" -> {
                val caida = (1f - suave) * 0.75f
                val rebote = if (t > 0.7f) abs(sin((t - 0.7f) * 6.28f / 0.3f)) * (1f - t) * 0.12f else 0f
                Estado(desplazamientoY = -(caida - rebote), alfa = t)
            }
            else -> Estado(alfa = t) // fundido
        }
    }
}

/**
 * Textos del programa: dibujo del texto a un mapa de bits y guardado en la biblioteca
 * (un archivo JSON por texto dentro de `files/biblioteca/textos`).
 */
object Textos {

    private const val ANCHO_BITMAP = 1100
    private const val MARGEN = 34f

    /** Dibuja el texto en un PNG con fondo transparente, listo para usarse como overlay. */
    fun rasterizar(t: TextoGuardado): Bitmap {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Fuentes.de(t.fuente)
            textSize = 62f * t.tamano.coerceIn(0.5f, 2.5f)
            color = t.color
            // Sombra suave: el texto se lee sobre cualquier imagen.
            setShadowLayer(9f, 0f, 4f, Color.argb(170, 0, 0, 0))
        }
        val lineas = envolver(t.texto, paint, ANCHO_BITMAP - MARGEN * 2f)
        val metricas = paint.fontMetrics
        val altoLinea = (metricas.descent - metricas.ascent) * 1.12f
        val anchoTexto = lineas.maxOfOrNull { paint.measureText(it) }?.coerceAtLeast(1f) ?: 1f
        val ancho = (anchoTexto + MARGEN * 2f).toInt().coerceAtMost(ANCHO_BITMAP)
        val alto = (altoLinea * lineas.size + MARGEN * 2f).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(ancho, alto, Bitmap.Config.ARGB_8888)
        val lienzo = Canvas(bmp)
        var y = MARGEN - metricas.ascent
        for (linea in lineas) {
            lienzo.drawText(linea, MARGEN, y, paint)
            y += altoLinea
        }
        return bmp
    }

    /** Reparte el texto en líneas que quepan en [anchoMax], respetando los saltos de línea. */
    private fun envolver(texto: String, paint: Paint, anchoMax: Float): List<String> {
        val salida = ArrayList<String>()
        for (parrafo in texto.replace("\r", "").split('\n')) {
            if (parrafo.isBlank()) {
                salida.add(" ")
                continue
            }
            var linea = StringBuilder()
            for (palabra in parrafo.trim().split(' ')) {
                val prueba = if (linea.isEmpty()) palabra else "$linea $palabra"
                if (paint.measureText(prueba) <= anchoMax || linea.isEmpty()) {
                    linea = StringBuilder(prueba)
                } else {
                    salida.add(linea.toString())
                    linea = StringBuilder(palabra)
                }
            }
            if (linea.isNotEmpty()) salida.add(linea.toString())
        }
        return if (salida.isEmpty()) listOf(" ") else salida
    }

    // ------------------------------------------------------------------ biblioteca

    private fun carpeta(ctx: Context): File =
        File(ctx.filesDir, "biblioteca/textos").apply { mkdirs() }

    /** Todos los textos guardados, del más nuevo al más viejo. */
    fun listar(ctx: Context): List<TextoGuardado> =
        carpeta(ctx).listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() }
            ?.mapNotNull { f -> runCatching { TextoGuardado.deJson(f.readText()) }.getOrNull() }
            ?: emptyList()

    fun guardar(ctx: Context, t: TextoGuardado): Boolean = runCatching {
        File(carpeta(ctx), "${t.id}.json").writeText(t.aJson())
        true
    }.getOrDefault(false)

    fun borrar(ctx: Context, id: String): Boolean =
        File(carpeta(ctx), "$id.json").delete()

    fun nuevoId(): String = "t" + System.currentTimeMillis()
}
