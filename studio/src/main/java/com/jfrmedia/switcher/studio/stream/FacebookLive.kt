package com.jfrmedia.switcher.studio.stream

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Un comentario del stream en Facebook. */
data class ComentarioFb(val autor: String, val texto: String)

/**
 * Lo que Facebook publica del stream en vivo: **cuántos están viendo** y los **comentarios**.
 *
 * Todo va por la Graph API con el **token de la Página** que se pega en «Ajustes». El token se queda
 * en las preferencias de la tablet, nunca en los archivos de memoria (Jorge, 24/11/2026).
 *
 * Ojo: el conteo de espectadores vive en el campo `live_views`, que Facebook ha movido de lugar con
 * los años. Si la API no lo devuelve, se muestra «sin conexión» pero los comentarios siguen saliendo.
 */
object FacebookLive {

    private const val BASE = "https://graph.facebook.com/v21.0"

    /**
     * De **quién** es el token: devuelve (id, nombre) de `/me`.
     *
     * Es el dato que desambigua todo: si el nombre es la Página, es un **token de Página** (el que
     * hace falta); si es la persona, es un token de usuario y por eso la Página responde «#200».
     */
    fun quienSoy(token: String): Pair<String, String>? = runCatching {
        val json = bajar("$BASE/me?fields=id,name&access_token=$token")
        val o = JSONObject(json)
        val id = o.optString("id", "")
        val nombre = o.optString("name", "")
        if (id.isBlank()) null else id to nombre
    }.getOrNull()

    /** Id del video en vivo de la página (el que está al aire ahora). */    fun videoEnVivo(pageId: String, token: String): String? {
        val json = bajar("$BASE/$pageId/live_videos?fields=id,status&limit=5&access_token=$token")
        val datos = JSONObject(json).optJSONArray("data") ?: return null
        var reciente = ""
        for (i in 0 until datos.length()) {
            val o = datos.optJSONObject(i) ?: continue
            val id = o.optString("id", "")
            if (id.isBlank()) continue
            if (reciente.isBlank()) reciente = id
            if (o.optString("status", "").equals("LIVE", ignoreCase = true)) return id
        }
        // Sin ninguno al aire se devuelve el más reciente: sirve para probar el token.
        return reciente.takeIf { it.isNotBlank() }
    }

    /** Espectadores conectados ahora; `null` si Facebook no lo da. */
    fun espectadores(videoId: String, token: String): Int? = runCatching {
        val json = bajar("$BASE/$videoId?fields=live_views&access_token=$token")
        JSONObject(json).optInt("live_views", -1).takeIf { it >= 0 }
    }.getOrNull()

    /**
     * Cuando la lista no trae el autor, se pregunta el comentario **por su id**: a veces ese camino sí
     * devuelve `from`. Devuelve el nombre o null.
     */
    fun autorDeComentario(comentarioId: String, token: String): String? = runCatching {
        val json = bajar("$BASE/$comentarioId?fields=from{name,id}&access_token=$token")
        val o = JSONObject(json)
        val nombre = o.optJSONObject("from")?.optString("name", "").orEmpty()
        if (nombre.isBlank()) {
            com.jfrmedia.switcher.core.util.L.w("fb", "por id tampoco hay autor: $o")
            null
        } else {
            nombre
        }
    }.getOrNull()

    /** Últimos comentarios, del más nuevo al más viejo. */
    fun comentarios(videoId: String, token: String, limite: Int = 4): List<ComentarioFb> {
        val url = "$BASE/$videoId/comments?live_filter=no_filter&order=reverse_chronological" +
            "&limit=$limite&fields=from{name,id},message&access_token=$token"
        val json = bajar(url)
        val datos = JSONObject(json).optJSONArray("data") ?: return emptyList()
        val salida = ArrayList<ComentarioFb>(datos.length())
        for (i in 0 until datos.length()) {
            val o = datos.optJSONObject(i) ?: continue
            val texto = o.optString("message", "").trim()
            if (texto.isEmpty()) continue
            // Se usa el autor solo si Facebook lo manda: preguntarlo por id multiplicaba las
            // llamadas y disparaba el límite de la aplicación (Jorge, 24/11/2026).
            val autor = o.optJSONObject("from")?.optString("name", "")?.takeIf { it.isNotBlank() }
                ?: o.optString("from_name", "").takeIf { it.isNotBlank() }
                ?: "Alguien"
            salida.add(ComentarioFb(autor, texto))
        }
        return salida
    }

    private fun bajar(url: String): String {
        val conexion = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
        }
        try {
            val codigo = conexion.responseCode
            val flujo = if (codigo in 200..299) conexion.inputStream else conexion.errorStream
            val texto = flujo?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            if (codigo !in 200..299) {
                // El error de Facebook trae el motivo en JSON; se recorta para que quepa en el aviso.
                throw IllegalStateException("HTTP $codigo ${texto.take(220)}")
            }
            return texto
        } finally {
            runCatching { conexion.disconnect() }
        }
    }
}
