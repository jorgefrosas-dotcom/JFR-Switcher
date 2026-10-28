package com.jfrmedia.switcher.core.net

import com.jfrmedia.switcher.core.proto.Proto

/**
 * Enlace de emparejamiento que viaja dentro del **código QR** de la tablet.
 *
 * Formato: `jfr://192.168.1.7:8787`
 *
 * La app del celular también acepta:
 * - la IP sola (`192.168.1.7`), por compatibilidad con quien ya la tenía apuntada;
 * - la IP con puerto (`192.168.1.7:8787`);
 * - cualquier esquema (`http://…`), del que solo se toma el host.
 *
 * Está aquí, en `core`, porque lo arman las dos apps: la tablet lo dibuja y el celular lo lee.
 */
object QrLink {

    const val ESQUEMA = "jfr"

    /** Texto que se codifica en el QR de la tablet. */
    fun armar(ip: String, puerto: Int = Proto.DEFAULT_PORT): String = "$ESQUEMA://$ip:$puerto"

    /**
     * IP contenida en el texto escaneado, o `null` si no hay una IPv4 válida.
     * Solo se aceptan IPv4 porque el canal TCP del switcher trabaja en la red local.
     */
    fun ipDe(texto: String?): String? {
        val limpio = texto?.trim().orEmpty()
        if (limpio.isEmpty()) return null
        val sinEsquema = when {
            limpio.startsWith("$ESQUEMA://", ignoreCase = true) -> limpio.substring(ESQUEMA.length + 3)
            limpio.contains("://") -> limpio.substringAfter("://")
            else -> limpio
        }
        val host = sinEsquema
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .substringBefore(':')
            .trim()
        return host.takeIf { esIpv4(it) }
    }

    /** Puerto indicado en el enlace, o `null` si no viene o está fuera de rango. */
    fun puertoDe(texto: String?): Int? {
        val limpio = texto?.trim().orEmpty()
        if (limpio.isEmpty()) return null
        val sinEsquema = when {
            limpio.startsWith("$ESQUEMA://", ignoreCase = true) -> limpio.substring(ESQUEMA.length + 3)
            limpio.contains("://") -> limpio.substringAfter("://")
            else -> limpio
        }
        val resto = sinEsquema.substringBefore('/').substringBefore('?')
        if (!resto.contains(':')) return null
        val puerto = resto.substringAfter(':').takeWhile(Char::isDigit)
        return puerto.toIntOrNull()?.takeIf { it in 1..65535 }
    }

    private fun esIpv4(host: String): Boolean {
        val partes = host.split('.')
        if (partes.size != 4) return false
        return partes.all { p ->
            p.isNotEmpty() && p.length <= 3 && p.all(Char::isDigit) && p.toInt() in 0..255
        }
    }
}
