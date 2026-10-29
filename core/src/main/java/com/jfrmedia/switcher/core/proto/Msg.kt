package com.jfrmedia.switcher.core.proto

import org.json.JSONObject

/**
 * Protocolo JFR Switcher v1 — mensajes JSON con longitud prefijada sobre TCP.
 * La misma definición la usan la app Studio (tablet) y la app Cam (celular).
 */
object Proto {
    const val VERSION = 1
    const val DEFAULT_PORT = 8787
    const val MAX_FRAME_BYTES = 1_048_576 // 1 MB: un SDP ronda 4-8 KB

    // ---- Tipos de mensaje ----
    const val HELLO = "hello"     // cam -> studio: presentación del dispositivo
    const val WELCOME = "welcome" // studio -> cam: aceptado, número de cámara asignado
    const val REJECT = "reject"   // studio -> cam: sin espacio
    const val CMD = "cmd"         // studio -> cam: cambiar calidad, girar, silenciar, etc.
    const val SDP = "sdp"         // ambos: oferta/respuesta
    const val ICE = "ice"         // ambos: candidato ICE
    const val STATE = "state"     // cam -> studio: estado del dispositivo
    const val STATS = "stats"     // ambos: fps/bitrate
    const val PING = "ping"
    const val PONG = "pong"
    const val BYE = "bye"
    const val ERROR = "error"

    // ---- Claves ----
    const val K_TYPE = "t"
    const val K_VERSION = "v"
    const val K_NAME = "name"
    const val K_MODEL = "model"
    const val K_API = "api"
    const val K_INDEX = "index"       // número de cámara 1..6
    const val K_SESSION = "session"
    const val K_ACTION = "action"
    const val K_WIDTH = "width"
    const val K_HEIGHT = "height"
    const val K_FPS = "fps"
    const val K_KBPS = "kbps"
    const val K_KIND = "kind"         // offer | answer
    const val K_SDP = "sdp"
    const val K_CANDIDATE = "candidate"
    const val K_MID = "mid"
    const val K_MLINE = "mline"
    const val K_MIRROR = "mirror"
    const val K_MUTED = "muted"
    const val K_TORCH = "torch"
    const val K_FACING = "facing"     // back | front
    const val K_BATTERY = "battery"
    const val K_ROTACION = "rotacion"  // giro real de los cuadros: 0/90/180/270
    const val K_ON_AIR = "onAir"       // la cámara está en el visor del switcher
    const val K_MESSAGE = "message"
    const val K_ORIENTATION = "orientation"
    const val K_BLOQUEO = "bloqueo"    // true = la cámara no puede girar (sesión en curso)

    // ---- Acciones de CMD ----
    const val A_START = "start"
    const val A_STOP = "stop"
    const val A_QUALITY = "quality"
    const val A_FPS = "fps"
    const val A_FLIP = "flip"
    const val A_TORCH = "torch"
    const val A_MIRROR = "mirror"
    const val A_ORIENTATION = "orientation"

    /** Congela la rotación del celular mientras el switcher graba o transmite. */
    const val A_BLOQUEO = "bloqueo"
    const val A_PROGRAM = "program"
}

/** Envoltorio ligero e inmutable de un mensaje JSON. */
class Msg(val type: String, val data: JSONObject) {

    fun str(k: String, def: String = ""): String = data.optString(k, def)
    fun int(k: String, def: Int = 0): Int = data.optInt(k, def)
    fun long(k: String, def: Long = 0L): Long = data.optLong(k, def)
    fun bool(k: String, def: Boolean = false): Boolean = data.optBoolean(k, def)
    fun dbl(k: String, def: Double = 0.0): Double = data.optDouble(k, def)
    fun has(k: String): Boolean = data.has(k)

    override fun toString(): String = data.toString()

    companion object {
        fun parse(text: String): Msg? = try {
            val o = JSONObject(text)
            val t = o.optString(Proto.K_TYPE, "")
            if (t.isEmpty()) null else Msg(t, o)
        } catch (e: Exception) {
            null
        }

        fun builder(type: String): Builder = Builder(type)
    }

    class Builder(private val type: String) {
        private val o = JSONObject().put(Proto.K_TYPE, type)
        fun put(k: String, v: Any?): Builder {
            o.put(k, v ?: JSONObject.NULL)
            return this
        }

        fun putIf(k: String, v: Any?): Builder = if (v != null) put(k, v) else this
        fun build(): String = o.toString()
    }
}
