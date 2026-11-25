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
 * Además de ese top, la app trae **50 mazos más** dentro del APK (`assets/mazos_extra.json`): son los
 * arquetipos del Estándar que aparecen en los torneos y que el top del sitio no publica (el sitio solo
 * lista ~29). Esa lista es fija y se regenera con `herramientas\generar-mazos-extra.ps1`.
 *
 * Cuando no hay red o la página cambia, se sigue usando la última lista buena.
 */
object Mazos {

    /** Las dos páginas del top: la primera trae 25 mazos y la segunda los 4 que quedan. */
    private val URL_LISTA = listOf(
        "https://limitlesstcg.com/decks?page=1",
        "https://limitlesstcg.com/decks?page=2",
    )
    private const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0 Mobile Safari/537.36"
    private const val PREFS = "jfr_studio"
    private const val CLAVE_LISTA = "mazos_top"
    private const val CLAVE_CUANDO = "mazos_cuando"
    private const val CLAVE_ESQUEMA = "mazos_esquema"
    private const val UN_DIA_MS = 24L * 60 * 60 * 1000

    /** Mazos extra que vienen dentro del APK (ver `herramientas\generar-mazos-extra.ps1`). */
    private const val ASSET_EXTRA = "mazos_extra.json"

    /**
     * Versión de la caché de iconos. Se sube cuando cambia cómo se guardan (por ejemplo al pasar de
     * uno a dos iconos por mazo) para forzar una descarga nueva una sola vez.
     */
    private const val ESQUEMA_ICONOS = 3

    /** Iconos ya cargados en memoria (por id de mazo). */
    private val memoriaIconos = HashMap<String, List<Bitmap>>()

    /** Mazos extra leídos del APK (se leen una sola vez). */
    private var extraEnMemoria: List<Mazo>? = null

    // ------------------------------------------------------------------ mazos propios

    /**
     * Un Pokémon del catálogo de iconos (para armar mazos personalizados).
     * [slug] es el nombre del archivo en Limitless.
     */
    data class IconoCatalogo(val nombre: String, val slug: String) {
        val url: String get() = "https://r2.limitlesstcg.net/pokemon/gen9/$slug.png"
    }

    /** Mazo que armó el usuario y que no está en la lista de Limitless. */
    data class MazoCustom(val id: String, val nombre: String, val slugs: List<String>)

    private const val ASSET_ICONOS = "iconos_pokemon.json"
    private const val CLAVE_CUSTOM = "mazos_custom"

    private var catalogoEnMemoria: List<IconoCatalogo>? = null

    /** Todos los Pokémon con icono disponible, ordenados por nombre (se lee del APK una vez). */
    fun catalogo(ctx: Context): List<IconoCatalogo> {
        catalogoEnMemoria?.let { return it }
        val lista = runCatching {
            val texto = ctx.assets.open(ASSET_ICONOS).use { it.readBytes().toString(Charsets.UTF_8) }
            val arr = JSONArray(texto)
            val salida = ArrayList<IconoCatalogo>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val nombre = o.optString("n", "")
                val slug = o.optString("s", "")
                if (nombre.isNotBlank() && slug.isNotBlank()) salida.add(IconoCatalogo(nombre, slug))
            }
            salida
        }.getOrElse { t ->
            L.w("mazos", "no se pudo leer el catálogo de iconos: ${t.message}")
            emptyList()
        }
        catalogoEnMemoria = lista
        return lista
    }

    /** Los mazos propios guardados en las preferencias. */
    fun personalizados(ctx: Context): List<MazoCustom> = runCatching {
        val texto = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(CLAVE_CUSTOM, null) ?: return emptyList()
        val arr = JSONArray(texto)
        val salida = ArrayList<MazoCustom>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id", "")
            val nombre = o.optString("nombre", "")
            if (id.isBlank() || nombre.isBlank()) continue
            val slugs = ArrayList<String>()
            o.optJSONArray("slugs")?.let { a ->
                for (j in 0 until a.length()) a.optString(j).takeIf { it.isNotBlank() }?.let(slugs::add)
            }
            salida.add(MazoCustom(id, nombre, slugs))
        }
        salida
    }.getOrElse { t ->
        L.w("mazos", "no se pudieron leer los mazos propios: ${t.message}")
        emptyList()
    }

    /**
     * Guarda (o reemplaza) un mazo propio y **baja sus iconos** a la caché, para que después se
     * dibujen igual que los de Limitless. Bloqueante: se llama desde un hilo.
     */
    fun guardarPersonalizado(ctx: Context, mazo: MazoCustom) {
        val actuales = personalizados(ctx).toMutableList()
        actuales.removeAll { it.id == mazo.id }
        actuales.add(0, mazo)
        val arr = JSONArray()
        actuales.forEach { m ->
            arr.put(
                JSONObject()
                    .put("id", m.id)
                    .put("nombre", m.nombre)
                    .put("slugs", JSONArray(m.slugs))
            )
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(CLAVE_CUSTOM, arr.toString())
            .apply()
        // Los iconos, a la misma caché que usan los mazos de Limitless.
        mazo.slugs.take(2).forEachIndexed { indice, slug ->
            val archivo = archivoIcono(ctx, mazo.id, indice + 1)
            runCatching {
                descargarIcono("https://r2.limitlesstcg.net/pokemon/gen9/$slug.png", archivo)
            }.onFailure { L.w("mazos", "icono propio ${mazo.nombre}: ${it.message}") }
        }
        memoriaIconos.remove(mazo.id)
    }

    /** Borra un mazo propio y sus iconos guardados. */
    fun borrarPersonalizado(ctx: Context, id: String) {
        val actuales = personalizados(ctx).filterNot { it.id == id }
        val arr = JSONArray()
        actuales.forEach { m ->
            arr.put(
                JSONObject()
                    .put("id", m.id)
                    .put("nombre", m.nombre)
                    .put("slugs", JSONArray(m.slugs))
            )
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(CLAVE_CUSTOM, arr.toString())
            .apply()
        for (n in 1..2) runCatching { archivoIcono(ctx, id, n).delete() }
        memoriaIconos.remove(id)
    }

    /** Los mazos propios como mazos comunes, listos para la lista y el banner. */
    fun personalizadosComoMazos(ctx: Context): List<Mazo> = personalizados(ctx).map { m ->
        Mazo(
            id = m.id,
            nombre = m.nombre,
            iconos = m.slugs.map { "https://r2.limitlesstcg.net/pokemon/gen9/$it.png" },
            uso = "",
        )
    }

    /** Carpeta donde se guardan las miniaturas del catálogo (una sola vez por Pokémon). */
    private fun archivoCatalogo(ctx: Context, slug: String): File =
        File(File(ctx.filesDir, "iconos_catalogo"), "$slug.png")

    /**
     * Miniatura del catálogo para el buscador. Devuelve la que ya está guardada, o **null** si
     * todavía no se ha bajado (así el diálogo no se congela). Bloqueante para el hilo que baja.
     */
    fun miniaturaGuardada(ctx: Context, slug: String): Bitmap? {
        val archivo = archivoCatalogo(ctx, slug)
        if (!archivo.exists() || archivo.length() == 0L) return null
        return runCatching { BitmapFactory.decodeFile(archivo.absolutePath) }.getOrNull()
    }

    /** Baja la miniatura de un Pokémon del catálogo (si no estaba). Bloqueante. */
    fun bajarMiniatura(ctx: Context, slug: String): Bitmap? {
        miniaturaGuardada(ctx, slug)?.let { return it }
        val archivo = archivoCatalogo(ctx, slug)
        runCatching {
            descargarIcono("https://r2.limitlesstcg.net/pokemon/gen9/$slug.png", archivo)
        }.onFailure { L.w("mazos", "miniatura $slug: ${it.message}") }
        return miniaturaGuardada(ctx, slug)
    }

    // ------------------------------------------------------------------ consulta

    /** Lista completa: el top descargado del sitio, los 50 extra de torneo y los mazos propios. */
    fun guardados(ctx: Context): List<Mazo> =
        personalizadosComoMazos(ctx) + unir(leer(ctx), extras(ctx))

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

    fun porId(ctx: Context, id: String): Mazo? = guardados(ctx).firstOrNull { it.id == id }

    /** Los mazos extra que vienen dentro del APK (leídos una sola vez). */
    fun extras(ctx: Context): List<Mazo> {
        extraEnMemoria?.let { return it }
        val lista = runCatching {
            val texto = ctx.assets.open(ASSET_EXTRA).use { it.readBytes().toString(Charsets.UTF_8) }
            desdeJson(texto)
        }.getOrElse { t ->
            L.w("mazos", "no se pudieron leer los mazos extra: ${t.message}")
            emptyList()
        }
        extraEnMemoria = lista
        return lista
    }

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
     * Devuelve la lista que quedó (la nueva más los extra, o la guardada si algo falló).
     */
    fun actualizar(ctx: Context): List<Mazo> {
        val nueva = runCatching { descargarLista() }.getOrElse { t ->
            L.w("mazos", "no se pudo bajar la lista: ${t.message}")
            null
        }
        if (nueva != null && nueva.isNotEmpty()) {
            guardar(ctx, nueva)
        } else {
            L.w("mazos", "la lista vino vacía: se queda la anterior")
        }
        // Los iconos se bajan después de guardar: si alguno falla, la lista ya quedó.
        // Se guardan hasta dos por mazo (algunos llevan dos Pokémon y se muestran los dos).
        // Incluye los mazos extra, que también tienen iconos que bajar (una sola vez).
        val todos = guardados(ctx)
        todos.forEach { mazo ->
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
        L.i("mazos", "lista actualizada: ${todos.size} mazos (top ${leer(ctx).size} + extra)")
        return todos
    }

    /**
     * Pega el top descargado y los extra del APK, sin repetir: si el top del sitio ya trae un mazo con
     * el mismo id o el mismo nombre, el extra se descarta (el del sitio tiene el % de uso).
     */
    private fun unir(top: List<Mazo>, extra: List<Mazo>): List<Mazo> {
        val ids = HashSet<String>()
        val nombres = HashSet<String>()
        val salida = ArrayList<Mazo>(top.size + extra.size)
        for (m in top) {
            if (!ids.add(m.id)) continue
            nombres.add(normalizar(m.nombre))
            salida.add(m)
        }
        for (m in extra) {
            if (ids.contains(m.id)) continue
            if (!nombres.add(normalizar(m.nombre))) continue
            ids.add(m.id)
            salida.add(m)
        }
        return salida
    }

    /** Para comparar nombres: sin «ex» final, sin acentos y sin signos. */
    private fun normalizar(nombre: String): String = nombre
        .lowercase()
        .replace(Regex("\\bex\\b"), "")
        .replace(Regex("[^a-z0-9]"), "")

    private fun descargarLista(): List<Mazo> {
        // La página 1 trae 25 mazos y la 2 los 4 que faltan. Si alguna falla, se usa lo que llegó.
        val lista = ArrayList<Mazo>()
        var fallos = 0
        for (url in URL_LISTA) {
            val html = runCatching { bajarTexto(url) }.getOrElse { t ->
                fallos++
                L.w("mazos", "no se pudo bajar $url: ${t.message}")
                continue
            }
            val filas = Regex("<tr>(.*?)</tr>", RegexOption.DOT_MATCHES_ALL).findAll(html)
            for (fila in filas) {
                val trozo = fila.groupValues[1]
                val enlace = Regex("<a href=\"/decks/(\\d+)\">(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
                    .find(trozo) ?: continue
                val id = enlace.groupValues[1]
                val nombre = limpiarTexto(enlace.groupValues[2])
                if (id.isBlank() || nombre.isBlank()) continue
                if (lista.any { it.id == id }) continue
                val iconos = Regex("src=\"(https://r2\\.limitlesstcg\\.net/pokemon/[^\"]+)\"")
                    .findAll(trozo).map { it.groupValues[1] }.toList()
                val uso = Regex("(\\d+(?:\\.\\d+)?%)\"").find(trozo)?.groupValues?.get(1)
                    ?: Regex("(\\d+(?:\\.\\d+)?%)").find(trozo)?.groupValues?.get(1).orEmpty()
                lista.add(Mazo(id = id, nombre = nombre, iconos = iconos, uso = uso))
            }
        }
        if (lista.isEmpty() && fallos > 0) throw IllegalStateException("no se pudo bajar ninguna página")
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
        desdeJson(texto)
    }.getOrElse {
        L.w("mazos", "no se pudo leer la lista guardada: ${it.message}")
        emptyList()
    }

    /** Convierte el JSON de la lista (el guardado o el asset de los extra) en mazos. */
    private fun desdeJson(texto: String): List<Mazo> {
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
        return lista.filter { it.id.isNotBlank() && it.nombre.isNotBlank() }
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
