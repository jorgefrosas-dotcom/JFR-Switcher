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

    /**
     * ID de cliente OAuth de tipo **iOS** (no es un secreto: los de app nativa no llevan).
     *
     * Se usa uno de tipo iOS y no de Android porque Android solo sirve para el SDK de Google
     * Sign-In; el flujo de navegador con regreso por `com.googleusercontent.apps.<id>` es el que
     * Google admite en los clientes de app nativa, y con el de Android respondía
     * «400: invalid_request» (Jorge, 25/11/2026).
     */
    const val CLIENTE_ID = "299565880934-5eq17m5n7t7qqe03ua8j6u6rdnlme8go.apps.googleusercontent.com"

    private const val ESQUEMA = "com.googleusercontent.apps.299565880934-5eq17m5n7t7qqe03ua8j6u6rdnlme8go"
    private const val PREFS = "youtube_auth"
    private const val CLAVE_ESTADO = "auth_state"
    private const val API = "https://www.googleapis.com/youtube/v3"

    /** Permiso para crear transmisiones en tu canal. */
    private const val PERMISO = "https://www.googleapis.com/auth/youtube"

    val REDIRECCION: Uri = Uri.parse("$ESQUEMA:/oauth2redirect")

    /** Un directo creado en YouTube: su id, la dirección con la clave y el chat (si ya existe). */
    data class DirectoFbYt(val id: String, val url: String, val chatId: String = "")

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
        // «offline» para recibir refresh token. «select_account» obliga a Google a mostrar el
        // selector de cuenta (sin eso usa la sesión del navegador y no deja cambiar al canal);
        // «consent» fuerza a que entregue el refresh token.
        .setPrompt("select_account consent")
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
            // La consulta va en su propio hilo: si se hace desde el hilo de la interfaz, Android
            // lanza NetworkOnMainThreadException y la app se queda creyendo que no hay canal
            // (visto el 25/11/2026, y solo se detectó al registrar el error).
            Thread {
                val crudo = runCatching {
                    pedir("$API/channels?part=snippet&mine=true&access_token=$token")
                }.onFailure { L.w("yt", "canal: ${it.javaClass.simpleName} ${it.message}") }
                    .getOrNull()
                L.i("yt", "canal crudo: ${crudo?.take(260) ?: "(sin respuesta)"}")
                val nombre = crudo?.let {
                    JSONObject(it).optJSONArray("items")?.optJSONObject(0)
                        ?.optJSONObject("snippet")?.optString("title", "")
                }
                alListo(nombre?.takeIf { it.isNotBlank() })
            }.apply { isDaemon = true }.start()
        }) { mensaje ->
            L.w("yt", "canal: $mensaje")
            alListo(null)
        }
    }

    /**
     * ¿La cuenta conectada tiene un canal de YouTube?
     *
     * Es distinto de «estar conectado»: se puede autorizar con una cuenta personal que **no tiene
     * canal**, y entonces YouTube responde `youtubeSignupRequired` al crear el directo. Para
     * transmitir hay que elegir la **cuenta de marca** en la pantalla de Google (Jorge, 25/11/2026).
     */
    fun tieneCanal(contexto: Context, alListo: (Boolean) -> Unit) {
        conToken(contexto, alListo = { token ->
            val json = runCatching {
                pedir("$API/channels?part=id&mine=true&access_token=$token")
            }.getOrNull()
            val cuantos = json?.let {
                JSONObject(it).optJSONArray("items")?.length() ?: 0
            } ?: 0
            alListo(cuantos > 0)
        }) { alListo(false) }
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
                            // YouTube obliga a mandar la hora de inicio aunque el arranque sea
                            // automático: con `enableAutoStart` empieza en cuanto llega el video.
                            .put("scheduledStartTime", horaUtc())
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
                // El chat puede no venir todavía; se pide después si falta.
                val chat = emision.optJSONObject("snippet")?.optString("liveChatId", "").orEmpty()
                L.i("yt", "transmisión $idEmision creada (stream $idStream, chat ${chat.ifBlank { "pendiente" }})")
                alListo(DirectoFbYt(idEmision, "$direccion/$clave", chat))
            } catch (t: Throwable) {
                val bruto = t.message ?: "no se pudo crear la transmisión"
                // Este error es el más confuso: la cuenta autorizó pero no tiene canal.
                val claro = if (bruto.contains("youtubeSignupRequired")) {
                    "esa cuenta no tiene canal de YouTube: desconecta y entra con la cuenta " +
                        "(o la cuenta de marca) que SÍ tiene el canal"
                } else {
                    bruto
                }
                alError(claro)
            }
        }, alError = alError)
    }

    // ------------------------------------------------------------------ chat en vivo

    /**
     * Id del chat de la transmisión. A veces no viene al crearla, así que se pide aparte.
     * Bloqueante: se llama desde un hilo.
     */
    fun chatDe(contexto: Context, emisionId: String): String? {
        var chat = ""
        conToken(contexto, alListo = { token ->
            val json = pedir("$API/liveBroadcasts?id=$emisionId&part=snippet&access_token=$token")
            chat = JSONObject(json).optJSONArray("items")?.optJSONObject(0)
                ?.optJSONObject("snippet")?.optString("liveChatId", "").orEmpty()
        }, alError = { mensaje -> L.w("yt", "chat: $mensaje") })
        return chat.takeIf { it.isNotBlank() }
    }

    /**
     * Últimos mensajes del chat en vivo de YouTube, del más viejo al más nuevo.
     * Bloqueante: se llama desde un hilo.
     */
    fun comentariosChat(contexto: Context, chatId: String): List<ComentarioFb> {
        var salida: List<ComentarioFb> = emptyList()
        conToken(contexto, alListo = { token ->
            val json = pedir(
                "$API/liveChat/messages?liveChatId=$chatId" +
                    "&part=snippet,authorDetails&maxResults=25&access_token=$token"
            )
            val datos = JSONObject(json).optJSONArray("items") ?: return@conToken
            val lista = ArrayList<ComentarioFb>()
            for (i in 0 until datos.length()) {
                val o = datos.optJSONObject(i) ?: continue
                val texto = o.optJSONObject("snippet")
                    ?.optString("displayMessage", "").orEmpty().trim()
                if (texto.isEmpty()) continue
                val autor = o.optJSONObject("authorDetails")
                    ?.optString("displayName", "")?.takeIf { it.isNotBlank() } ?: "Alguien"
                lista.add(ComentarioFb(autor, texto))
            }
            salida = lista
        }, alError = { mensaje -> L.w("yt", "chat mensajes: $mensaje") })
        return salida
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

    /** Hora actual en el formato que pide YouTube (`2026-11-25T05:15:00Z`, en UTC). */
    private fun horaUtc(): String {
        val formato = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
        formato.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return formato.format(java.util.Date())
    }

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
