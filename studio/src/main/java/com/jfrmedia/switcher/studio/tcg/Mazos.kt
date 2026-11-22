package com.jfrmedia.switcher.studio.tcg

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.jfrmedia.switcher.core.util.L
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Un mazo del top del formato: nombre, id de Limitless, iconos (URLs) y porcentaje de uso. */
data class Mazo(
    val id: String,
    val nombre: String,
    val iconos: List<String> = emptyList(),
    /** Uso en el formato, ya formateado (por ejemplo «35.65%»). */
    val uso: String = "",
) {
    val iconoPrincipal: String? get() = iconos.firstOrNull()
}

/**
 * Lista de los **mazos más usados del formato**, sacada de `limitlesstcg.com/decks`.
 *
 * Se guarda en las preferencias (`mazos_top`) con la fecha de la última descarga y se vuelve a bajar
 * **una vez al día** (al entrar al Modo Pokémon). Los iconos (los mismos sprites que usa Limitless) se
 * bajan una vez y se guardan como archivo en la carpeta privada de la app, así que se ven sin internet.
 *
 * Cuando no hay red o la página cambia, se sigue usando la última lista buena.
 */
object Mazos {

    private const val URL_LISTA = "https://limitlesstcg.com/decks?page=1"
    private const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0 Mobile Safari/537.36"
    private const val PREFS = "jfr_studio"
    private const val CLAVE_LISTA = "mazos_top"
    private const val CLAVE_CUANDO = "mazos_cuando"
    private const val CLAVE_ESQUEMA = "mazos_esquema"
    private const val UN_DIA_MS = 24L * 60 * 60 * 1000

    /**
     * Versión de la caché de iconos. Se sube cuando cambia cómo se guardan (por ejemplo al pasar de
     * uno a dos iconos por mazo) para forzar una descarga nueva una sola vez.
     */
    private const val ESQUEMA_ICONOS = 2

    /** Iconos ya cargados en memoria (por id de mazo). */
    private val memoriaIconos = HashMap<String, List<Bitmap>>()

    // ------------------------------------------------------------------ consulta

    /** Lista guardada (vacía si nunca se ha descargado). */
    fun guardados(ctx: Context): List<Mazo> = leer(ctx)

    /** ¿Toca volver a bajar la lista? (nunca descargada o con más de un día) */
    fun vieja(ctx: Context): Boolean {
        val cuando = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(CLAVE_CUANDO, 0L)
        return System.currentTimeMillis() - cuando > UN_DIA_MS
    }

    /** ¿La caché de iconos es de una versión anterior? (hay que volver a bajarlos) */
    fun esquemaViejo(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(CLAVE_ESQUEMA, 0) < ESQUEMA_ICONOS

    /** Cuándo se bajó por última vez (para mostrarlo en el selector). */
    fun cuando(ctx: Context): Long =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(CLAVE_CUANDO, 0L)

    fun porId(ctx: Context, id: String): Mazo? = leer(ctx).firstOrNull { it.id == id }

    /** Iconos ya bajados de un mazo (0, 1 o 2). Se cachean en memoria. */
    fun iconos(ctx: Context, mazoId: String): List<Bitmap> {
        if (mazoId.isBlank()) return emptyList()
        memoriaIconos[mazoId]?.let { guardados ->
            if (guardados.isNotEmpty() && guardados.none { it.isRecycled }) return guardados
        }
        val lista = ArrayList<Bitmap>(2)
        for (n in 1..2) {
            val archivo = archivoIcono(ctx, mazoId, n)
            if (!archivo.exists()) continue
            val bmp = runCatching { BitmapFactory.decodeFile(archivo.absolutePath) }.getOrNull() ?: continue
            lista.add(bmp)
        }
        memoriaIconos[mazoId] = lista
        return lista
    }

    /** El primer icono del mazo (para donde solo cabe uno). */
    fun icono(ctx: Context, mazoId: String): Bitmap? = iconos(ctx, mazoId).firstOrNull()

    // ------------------------------------------------------------------ descarga

    /**
     * Baja la lista y los iconos que falten. **Bloqueante**: se llama desde un hilo aparte.
     * Devuelve la lista que quedó (la nueva, o la guardada si algo falló).
     */
    fun actualizar(ctx: Context): List<Mazo> {
        val nueva = runCatching { descargarLista() }.getOrElse { t ->
            L.w("mazos", "no se pudo bajar la lista: ${t.message}")
            return leer(ctx)
        }
        if (nueva.isEmpty()) {
            L.w("mazos", "la lista vino vacía: se queda la anterior")
            return leer(ctx)
        }
        guardar(ctx, nueva)
        // Los iconos se bajan después de guardar: si alguno falla, la lista ya quedó.
        // Se guardan hasta dos por mazo (algunos llevan dos Pokémon y se muestran los dos).
        nueva.forEach { mazo ->
            mazo.iconos.take(2).forEachIndexed { indice, url ->
                val archivo = archivoIcono(ctx, mazo.id, indice + 1)
                if (archivo.exists() && archivo.length() > 0) return@forEachIndexed
                runCatching { descargarIcono(url, archivo) }
                    .onFailure { L.w("mazos", "icono de ${mazo.nombre}: ${it.message}") }
            }
        }
        memoriaIconos.clear()
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(CLAVE_ESQUEMA, ESQUEMA_ICONOS)
            .apply()
        L.i("mazos", "lista actualizada: ${nueva.size} mazos (primero: ${nueva.first().nombre})")
        return nueva
    }

    private fun descargarLista(): List<Mazo> {
        val html = bajarTexto(URL_LISTA)
        val filas = Regex("<tr>(.*?)</tr>", RegexOption.DOT_MATCHES_ALL).findAll(html)
        val lista = ArrayList<Mazo>()
        for (fila in filas) {
            val trozo = fila.groupValues[1]
            val enlace = Regex("<a href=\"/decks/(\\d+)\">(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
                .find(trozo) ?: continue
            val id = enlace.groupValues[1]
            val nombre = limpiarTexto(enlace.groupValues[2])
            if (id.isBlank() || nombre.isBlank()) continue
            val iconos = Regex("src=\"(https://r2\\.limitlesstcg\\.net/pokemon/[^\"]+)\"")
                .findAll(trozo).map { it.groupValues[1] }.toList()
            val uso = Regex("(\\d+(?:\\.\\d+)?%)\"").find(trozo)?.groupValues?.get(1)
                ?: Regex("(\\d+(?:\\.\\d+)?%)").find(trozo)?.groupValues?.get(1).orEmpty()
            lista.add(Mazo(id = id, nombre = nombre, iconos = iconos, uso = uso))
        }
        return lista
    }

    /** Quita etiquetas y espacios de más, y decodifica las entidades que usa la página. */
    private fun limpiarTexto(bruto: String): String = bruto
        .replace(Regex("<[^>]+>"), "")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&quot;", "\"")
        .replace("&amp;", "&")
        .replace("&nbsp;", " ")
        .replace("&aacute;", "á")
        .replace("&eacute;", "é")
        .replace("&iacute;", "í")
        .replace("&oacute;", "ó")
        .replace("&uacute;", "ú")
        .replace("&ntilde;", "ñ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun bajarTexto(url: String): String {
        val conexion = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "text/html")
            instanceFollowRedirects = true
        }
        try {
            if (conexion.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${conexion.responseCode}")
            }
            return conexion.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            runCatching { conexion.disconnect() }
        }
    }

    private fun descargarIcono(url: String, destino: File) {
        destino.parentFile?.mkdirs()
        val conexion = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", UA)
        }
        try {
            if (conexion.responseCode !in 200..299) throw IllegalStateException("HTTP ${conexion.responseCode}")
            val bytes = conexion.inputStream.use { it.readBytes() }
            if (bytes.isEmpty()) return
            // Se escribe a un temporal y luego se renombra: si se corta, no queda un PNG a medias.
            val temporal = File(destino.parentFile, destino.name + ".tmp")
            temporal.writeBytes(bytes)
            if (destino.exists()) destino.delete()
            temporal.renameTo(destino)
        } finally {
            runCatching { conexion.disconnect() }
        }
    }

    // ------------------------------------------------------------------ guardado

    private fun archivoIcono(ctx: Context, mazoId: String, numero: Int): File =
        File(File(ctx.filesDir, "mazos"), "mazo_${mazoId}_$numero.png")

    private fun leer(ctx: Context): List<Mazo> = runCatching {
        val texto = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(CLAVE_LISTA, null) ?: return emptyList()
        val arr = JSONArray(texto)
        val lista = ArrayList<Mazo>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val iconos = ArrayList<String>()
            o.optJSONArray("iconos")?.let { ic ->
                for (j in 0 until ic.length()) ic.optString(j).takeIf { it.isNotBlank() }?.let(iconos::add)
            }
            lista.add(
                Mazo(
                    id = o.optString("id", ""),
                    nombre = o.optString("nombre", ""),
                    iconos = iconos,
                    uso = o.optString("uso", ""),
                )
            )
        }
        lista.filter { it.id.isNotBlank() && it.nombre.isNotBlank() }
    }.getOrElse {
        L.w("mazos", "no se pudo leer la lista guardada: ${it.message}")
        emptyList()
    }

    private fun guardar(ctx: Context, lista: List<Mazo>) {
        val arr = JSONArray()
        lista.forEach { mazo ->
            arr.put(
                JSONObject()
                    .put("id", mazo.id)
                    .put("nombre", mazo.nombre)
                    .put("uso", mazo.uso)
                    .put("iconos", JSONArray().apply { mazo.iconos.forEach { put(it) } })
            )
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(CLAVE_LISTA, arr.toString())
            .putLong(CLAVE_CUANDO, System.currentTimeMillis())
            .apply()
    }
}
