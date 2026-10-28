package com.jfrmedia.switcher.core.net

import android.os.Build
import java.net.Inet4Address
import java.net.NetworkInterface

/** Utilidades de red local (todo ocurre en la misma WiFi, sin nube). */
object Net {

    /** Direcciones IPv4 locales de interfaces activas, ordenadas por prioridad (wlan/ap primero). */
    fun localIpv4(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return out
            for (nif in ifaces) {
                if (!nif.isUp || nif.isLoopback) continue
                val name = nif.name ?: ""
                if (name.startsWith("rmnet") || name.startsWith("tun") || name.startsWith("docker")) continue
                for (addr in nif.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                        out.add(name to (addr.hostAddress ?: ""))
                    }
                }
            }
        } catch (e: Exception) {
            // sin permiso o interfaz cerrada: se ignora
        }
        return out.sortedBy { (name, _) ->
            when {
                name.startsWith("wlan") -> 0
                name.startsWith("ap") -> 1
                name.startsWith("eth") -> 2
                else -> 3
            }
        }
    }

    fun primaryIp(): String? = localIpv4().firstOrNull()?.second

    fun deviceLabel(): String {
        val model = Build.MODEL?.trim().orEmpty()
        val brand = Build.BRAND?.trim().orEmpty().replaceFirstChar { it.uppercase() }
        return when {
            model.isEmpty() -> brand.ifEmpty { "Android" }
            model.lowercase().startsWith(brand.lowercase()) -> model
            else -> "$brand $model".trim()
        }
    }
}
