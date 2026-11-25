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

    /**
     * Id del video en vivo **al aire ahora**.
     *
     * Busca primero en la Página configurada y luego en la Página dueña del token (`/me`), porque es
     * fácil tener dos Páginas y transmitir en la que no está cargada en la app (pasó el 25/11/2026:
     * el token era de «Mundo 9» y la app consultaba «Producciones D'Angel Film»).
     * Ojo: si el directo se creó en el **perfil personal**, la API con token de Página no lo ve.
     */
    fun videoEnVivo(pageId: String, token: String): String? {
        var reciente = ""
        for (pagina in listOfNotNull(pageId.takeIf { it.isNotBlank() }, "me")) {
            val json = runCatching {
                bajar("$BASE/$pagina/live_videos?fields=id,status&limit=5&access_token=$token")
            }.getOrNull() ?: continue
            val datos = runCatching { JSONObject(json).optJSONArray("data") }.getOrNull() ?: continue
            for (i in 0 until datos.length()) {
                val o = datos.optJSONObject(i) ?: continue
                val id = o.optString("id", "")
                if (id.isBlank()) continue
                if (o.optString("status", "").equals("LIVE", ignoreCase = true)) return id
                if (reciente.isBlank()) reciente = id
            }
        }
        // Sin ninguno al aire se devuelve el más reciente: sirve para probar el token.
        return reciente.takeIf { it.isNotBlank() }
    }

    /**
     * El **id del directo** viene dentro de la propia clave del Live Producer:
     * `FB-<idVideo>-0-<secreto>`. Con ese id no hay que adivinar cuál video está al aire, que es lo
     * que fallaba cuando el directo estaba en otra Página o en el perfil (Jorge, 25/11/2026).
     */
    fun videoDeLaClave(clave: String): String? {
        val partes = clave.trim().split('-')
        if (partes.size < 2) return null
        if (!partes[0].equals("FB", ignoreCase = true)) return null
        return partes[1].takeIf { it.isNotBlank() && it.all { c -> c.isDigit() } }
    }

    /** Espectadores conectados ahora; `null` si Facebook no lo da. */
    fun espectadores(videoId: String, token: String): Int? = runCatching {
        val json = bajar("$BASE/$videoId?fields=live_views&access_token=$token")
        JSONObject(json).optInt("live_views", -1).takeIf { it >= 0 }
    }.getOrNull()

    /**
     * Escucha los comentarios **en tiempo real** por Server-Sent Events
     * (`streaming-graph.facebook.com/{id}/live_comments`).
     *
     * Es **una sola conexión abierta**, no una consulta cada tantos segundos: por eso no gasta el cupo
     * de la aplicación (el `(#4)` que nos tumbó las consultas). Bloqueante: se llama desde su propio
     * hilo y vuelve cuando la conexión se corta; el llamador decide si reconecta.
     */
    fun escucharComentarios(videoId: String, token: String, alLlegar: (ComentarioFb) -> Unit) {
        val url = "https://streaming-graph.facebook.com/$videoId/live_comments?access_token=$token"
        val conexion = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            // Es un chorro continuo: puede pasar mucho rato sin mandar nada entre comentario y ping.
            readTimeout = 0
            setRequestProperty("Accept", "text/event-stream")
        }
        ultimaConexion = conexion
        try {
            if (conexion.responseCode !in 200..299) {
                val texto = conexion.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                throw IllegalStateException("HTTP ${conexion.responseCode} ${texto.take(200)}")
            }
            conexion.inputStream.bufferedReader().use { lector ->
                val pendiente = StringBuilder()
                while (true) {
                    val linea = lector.readLine() ?: break
                    when {
                        linea.startsWith("data:") -> {
                            val resto = linea.removePrefix("data:").trim()
                            if (resto.startsWith("{")) pendiente.append(resto)
                        }
                        // Línea en blanco = fin del evento: ahí sí se procesa lo acumulado.
                        linea.isBlank() && pendiente.isNotEmpty() -> {
                            runCatching { parsearComentario(pendiente.toString()) }
                                .getOrNull()?.let { alLlegar(it) }
                            pendiente.setLength(0)
                        }
                        // «: ping» y demás líneas de control se ignoran.
                    }
                }
            }
        } finally {
            ultimaConexion = null
            runCatching { conexion.disconnect() }
        }
    }

    /** Conexión abierta de comentarios, para poder cortarla desde fuera al terminar el directo. */
    @Volatile
    private var ultimaConexion: HttpURLConnection? = null

    /** Corta la escucha en tiempo real (el hilo que la corre recibe la excepción y sale). */
    fun cortarEscucha() {
        runCatching { ultimaConexion?.disconnect() }
        ultimaConexion = null
    }

    private fun parsearComentario(json: String): ComentarioFb? {
        val o = JSONObject(json)
        val texto = o.optString("message", "").trim()
        if (texto.isEmpty()) return null
        val autor = o.optJSONObject("from")?.optString("name", "")?.takeIf { it.isNotBlank() }
            ?: "Alguien"
        return ComentarioFb(autor, texto)
    }

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
