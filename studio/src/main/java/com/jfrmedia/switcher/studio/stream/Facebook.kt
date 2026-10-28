package com.jfrmedia.switcher.studio.stream

import com.jfrmedia.switcher.core.util.L
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Acceso a la API de Graph de Facebook para **iniciar el directo sin pasar por el
 * Live Producer**.
 *
 * Flujo:
 *  1. El usuario entra con Facebook (OAuth implícito en un WebView) y se obtiene un token
 *     de usuario con permisos de páginas.
 *  2. Con ese token se listan las páginas que administra y se guarda el **token de página**.
 *  3. Al transmitir se crea un `live_videos` con `status=LIVE_NOW`: Facebook devuelve la URL
 *     RTMPS ya con la clave incluida y el directo queda publicado en ese momento.
 *  4. Al detener se cierra con `end_live_video=true`.
 */
object Facebook {

    private const val VERSION = "v21.0"
    private const val BASE = "https://graph.facebook.com/$VERSION"
    private const val REDIRECT = "https://www.facebook.com/connect/login_success.html"

    const val PERMISOS =
        "pages_show_list,pages_read_engagement,pages_manage_posts,publish_video"

    /** Página de Facebook que administra el usuario. */
    data class Pagina(val id: String, val nombre: String, val token: String)

    /** Directo creado y ya publicado. */
    data class Directo(val id: String, val urlRtmp: String)

    /** URL del diálogo de autorización (flujo implícito, sin secreto de app). */
    fun urlLogin(appId: String): String {
        val redirect = URLEncoder.encode(REDIRECT, "UTF-8")
        val scope = URLEncoder.encode(PERMISOS, "UTF-8")
        return "https://www.facebook.com/$VERSION/dialog/oauth" +
            "?client_id=$appId&redirect_uri=$redirect&response_type=token&scope=$scope"
    }

    /** Extrae el token del fragmento con el que Facebook redirige tras el login. */
    fun extraerToken(url: String): String? {
        if (!url.contains("access_token=")) return null
        val fragmento = url.substringAfter("access_token=", "")
        return fragmento.substringBefore("&").takeIf { it.isNotBlank() }
    }

    /** Páginas que administra el usuario, con su token propio. */
    fun paginas(tokenUsuario: String): List<Pagina> {
        val url = "$BASE/me/accounts?fields=id,name,access_token&limit=100" +
            "&access_token=${URLEncoder.encode(tokenUsuario, "UTF-8")}"
        val json = pedir(url, null) ?: return emptyList()
        val lista = ArrayList<Pagina>()
        runCatching {
            val datos = JSONObject(json).optJSONArray("data") ?: return emptyList()
            for (i in 0 until datos.length()) {
                val p = datos.getJSONObject(i)
                val id = p.optString("id")
                val nombre = p.optString("name")
                val token = p.optString("access_token")
                if (id.isNotBlank() && token.isNotBlank()) lista.add(Pagina(id, nombre, token))
            }
        }.onFailure { L.w(TAG, "páginas: ${it.message}") }
        return lista
    }

    /** Crea el directo ya publicado y devuelve su id y la URL RTMPS con clave incluida. */
    fun crearDirecto(pageId: String, pageToken: String, titulo: String): Directo? {
        val url = "$BASE/$pageId/live_videos" +
            "?status=LIVE_NOW" +
            "&title=${URLEncoder.encode(titulo, "UTF-8")}" +
            "&access_token=${URLEncoder.encode(pageToken, "UTF-8")}"
        val json = pedir(url, "") ?: return null
        return runCatching {
            val o = JSONObject(json)
            val id = o.optString("id")
            val rtmp = o.optString("secure_stream_url").ifBlank { o.optString("stream_url") }
            if (id.isBlank() || rtmp.isBlank()) {
                L.w(TAG, "respuesta sin id o url: $json")
                null
            } else {
                L.i(TAG, "directo creado $id")
                Directo(id, rtmp)
            }
        }.onFailure { L.w(TAG, "crear directo: ${it.message}") }.getOrNull()
    }

    /** Cierra el directo (equivale a «finalizar transmisión»). */
    fun terminarDirecto(videoId: String, pageToken: String): Boolean {
        val url = "$BASE/$videoId?end_live_video=true" +
            "&access_token=${URLEncoder.encode(pageToken, "UTF-8")}"
        return pedir(url, "") != null
    }

    /** Petición simple; [cuerpo] no nulo la vuelve POST. */
    private fun pedir(url: String, cuerpo: String?): String? {
        var conexion: HttpURLConnection? = null
        return try {
            conexion = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = if (cuerpo == null) "GET" else "POST"
                connectTimeout = 15000
                readTimeout = 15000
                if (cuerpo != null) {
                    doOutput = true
                    setFixedLengthStreamingMode(0)
                }
            }
            val codigo = conexion.responseCode
            val flujo = if (codigo in 200..299) conexion.inputStream else conexion.errorStream
            val texto = BufferedReader(InputStreamReader(flujo, "UTF-8")).use { it.readText() }
            if (codigo !in 200..299) {
                L.w(TAG, "Graph $codigo: $texto")
                null
            } else {
                texto
            }
        } catch (t: Throwable) {
            L.w(TAG, "Graph: ${t.message}")
            null
        } finally {
            conexion?.disconnect()
        }
    }

    private const val TAG = "facebook"
}
