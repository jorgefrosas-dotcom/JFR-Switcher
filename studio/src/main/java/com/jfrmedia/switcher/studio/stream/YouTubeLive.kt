package com.jfrmedia.switcher.studio.stream

import android.content.Context
import android.net.Uri
import com.jfrmedia.switcher.core.util.L
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import org.json.JSONObject
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * YouTube en vivo: login de Google y creación de la transmisión.
 *
 * Es el equivalente de [FacebookLive] para YouTube. La diferencia es que YouTube **sí** obliga a
 * identificarse: no hay token que pegar cómodamente, así que se usa el login de Google con
 * Authorization Code + PKCE (AppAuth). La contraseña nunca pasa por la app: el login ocurre en el
 * navegador y solo vuelve un código.
 *
 * Después de estar dentro, la app:
 *  1. crea un `liveStream` (recibe la dirección de emisión y la clave),
 *  2. crea un `liveBroadcast` atado a ese stream con **arranque y corte automáticos**,
 *  3. al detener, cierra la transmisión (`transition` a `complete`).
 */
object YouTubeLive {

    /** ID de cliente OAuth de tipo Android (no es un secreto: los de Android no llevan). */
    const val CLIENTE_ID = "299565880934-0g0n4o4ffcdlpssa5mb6pn98ka20fdb6.apps.googleusercontent.com"

    private const val ESQUEMA = "com.googleusercontent.apps.299565880934-0g0n4o4ffcdlpssa5mb6pn98ka20fdb6"
    private const val PREFS = "youtube_auth"
    private const val CLAVE_ESTADO = "auth_state"
    private const val API = "https://www.googleapis.com/youtube/v3"

    /** Permiso para crear transmisiones en tu canal. */
    private const val PERMISO = "https://www.googleapis.com/auth/youtube"

    val REDIRECCION: Uri = Uri.parse("$ESQUEMA:/oauth2redirect")

    /** Un directo creado en YouTube: su id y la dirección con la clave. */
    data class DirectoFbYt(val id: String, val url: String)

    fun servicio(contexto: Context): AuthorizationService = AuthorizationService(contexto)

    fun configuracion(): AuthorizationServiceConfiguration = AuthorizationServiceConfiguration(
        Uri.parse("https://accounts.google.com/o/oauth2/v2/auth"),
        Uri.parse("https://oauth2.googleapis.com/token")
    )

    /** Lo que hay que lanzar para que el usuario entre con su cuenta de Google. */
    fun peticion(): AuthorizationRequest = AuthorizationRequest.Builder(
        configuracion(),
        CLIENTE_ID,
        ResponseTypeValues.CODE,
        REDIRECCION
    )
        .setScope(PERMISO)
        // «offline» para recibir refresh token; «consent» fuerza a que Google lo entregue.
        .setPrompt("consent")
        .setAdditionalParameters(mapOf("access_type" to "offline"))
        .build()

    fun estado(contexto: Context): AuthState? = runCatching {
        val texto = contexto.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(CLAVE_ESTADO, null) ?: return null
        AuthState.jsonDeserialize(texto)
    }.getOrNull()

    fun guardar(contexto: Context, valor: AuthState) {
        contexto.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(CLAVE_ESTADO, valor.jsonSerializeString())
            .apply()
    }

    /** Hay sesión guardada (aunque el permiso pueda necesitar renovarse). */
    fun conectado(contexto: Context): Boolean =
        estado(contexto)?.isAuthorized == true

    fun salir(contexto: Context) {
        contexto.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(CLAVE_ESTADO)
            .apply()
    }

    /** Cómo se llama la cuenta conectada, si se puede leer (para mostrarlo en el cuadro). */
    fun nombreCanal(contexto: Context, alListo: (String?) -> Unit) {
        conToken(contexto, alListo = { token ->
            val json = runCatching {
                pedir("$API/channels?part=snippet&mine=true&access_token=$token")
            }.getOrNull()
            val nombre = json?.let {
                JSONObject(it).optJSONArray("items")?.optJSONObject(0)
                    ?.optJSONObject("snippet")?.optString("title", "")
            }
            alListo(nombre?.takeIf { it.isNotBlank() })
        }) { mensaje ->
            L.w("yt", "canal: $mensaje")
            alListo(null)
        }
    }

    /**
     * Ejecuta [accion] con un token válido (lo renueva solo si venció).
     * [alError] se llama si no hay sesión o Google no da token.
     */
    private fun conToken(
        contexto: Context,
        alListo: (String) -> Unit,
        alError: (String) -> Unit
    ) {
        val valor = estado(contexto)
        if (valor == null || !valor.isAuthorized) {
            alError("No hay sesión de YouTube: toca «Conectar con YouTube»")
            return
        }
        val servicio = servicio(contexto)
        valor.performActionWithFreshTokens(servicio) { token, _, excepcion ->
            if (token.isNullOrBlank() || excepcion != null) {
                alError("Google no entregó permiso: ${excepcion?.message ?: "sin token"}")
            } else {
                guardar(contexto, valor)
                alListo(token)
            }
        }
    }

    /**
     * Crea el stream y la transmisión en YouTube. Bloqueante: se llama desde un hilo aparte.
     *
     * La transmisión queda con **arranque automático**: en cuanto la app empieza a mandar video,
     * YouTube la publica sola. El id que se devuelve sirve para cerrarla al terminar.
     */
    fun crearDirecto(
        contexto: Context,
        titulo: String,
        descripcion: String,
        alListo: (DirectoFbYt) -> Unit,
        alError: (String) -> Unit
    ) {
        conToken(contexto, alListo = { token ->
            try {
                val cuerpoStream = JSONObject()
                    .put("snippet", JSONObject().put("title", "JFR Media"))
                    .put(
                        "cdn",
                        JSONObject()
                            .put("ingestionType", "rtmp")
                            .put("resolution", "variable")
                            .put("frameRate", "variable")
                    )
                val stream = JSONObject(
                    enviar(
                        "$API/liveStreams?part=snippet,cdn", token, cuerpoStream.toString()
                    )
                )
                val idStream = stream.optString("id", "")
                val info = stream.optJSONObject("cdn")?.optJSONObject("ingestionInfo")
                val direccion = info?.optString("ingestionAddress", "").orEmpty()
                val clave = info?.optString("streamName", "").orEmpty()
                if (idStream.isBlank() || direccion.isBlank() || clave.isBlank()) {
                    alError("YouTube no devolvió la dirección de emisión")
                    return@conToken
                }
                val cuerpoEmision = JSONObject()
                    .put(
                        "snippet",
                        JSONObject()
                            .put("title", titulo.ifBlank { "JFR Media" })
                            .put("description", descripcion)
                    )
                    .put("status", JSONObject().put("privacyStatus", "public"))
                    .put(
                        "contentDetails",
                        JSONObject()
                            .put("enableAutoStart", true)
                            .put("enableAutoStop", true)
                            .put("boundStreamId", idStream)
                            .put(
                                "monitorStream",
                                JSONObject().put("enableMonitorStream", false)
                            )
                    )
                val emision = JSONObject(
                    enviar(
                        "$API/liveBroadcasts?part=snippet,status,contentDetails",
                        token,
                        cuerpoEmision.toString()
                    )
                )
                val idEmision = emision.optString("id", "")
                if (idEmision.isBlank()) {
                    alError("YouTube no devolvió la transmisión")
                    return@conToken
                }
                L.i("yt", "transmisión $idEmision creada (stream $idStream)")
                alListo(DirectoFbYt(idEmision, "$direccion/$clave"))
            } catch (t: Throwable) {
                alError(t.message ?: "no se pudo crear la transmisión")
            }
        }, alError = alError)
    }

    /** Cierra la transmisión en YouTube. Bloqueante: se llama desde un hilo aparte. */
    fun terminarDirecto(contexto: Context, emisionId: String): Boolean = runCatching {
        var cerrado = false
        conToken(contexto, alListo = { token ->
            pedir(
                "$API/liveBroadcasts/transition?broadcastStatus=complete&part=status" +
                    "&id=$emisionId&access_token=$token",
                metodo = "POST"
            )
            cerrado = true
            L.i("yt", "transmisión $emisionId cerrada")
        }, alError = { mensaje -> L.w("yt", "cerrar: $mensaje") })
        cerrado
    }.getOrDefault(false)

    // ------------------------------------------------------------------ HTTP

    private fun pedir(url: String, metodo: String = "GET"): String {
        val conexion = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = metodo
            connectTimeout = 12_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            if (metodo == "POST") setFixedLengthStreamingMode(0)
        }
        try {
            val codigo = conexion.responseCode
            val flujo = if (codigo in 200..299) conexion.inputStream else conexion.errorStream
            val texto = flujo?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            if (codigo !in 200..299) throw IllegalStateException("HTTP $codigo ${texto.take(200)}")
            return texto
        } finally {
            runCatching { conexion.disconnect() }
        }
    }

    private fun enviar(url: String, token: String, cuerpo: String): String {
        val conexion = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 12_000
            readTimeout = 25_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            val datos = cuerpo.toByteArray(Charsets.UTF_8)
            conexion.setFixedLengthStreamingMode(datos.size)
            conexion.outputStream.use { salida: OutputStream -> salida.write(datos) }
            val codigo = conexion.responseCode
            val flujo = if (codigo in 200..299) conexion.inputStream else conexion.errorStream
            val texto = flujo?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            if (codigo !in 200..299) throw IllegalStateException("HTTP $codigo ${texto.take(300)}")
            return texto
        } finally {
            runCatching { conexion.disconnect() }
        }
    }
}
