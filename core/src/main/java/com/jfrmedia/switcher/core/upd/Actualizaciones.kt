package com.jfrmedia.switcher.core.upd

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Consulta la última versión publicada del proyecto en GitHub.
 *
 * Vive en `core` porque la usan las dos apps (tablet y celular). No descarga nada: solo lee
 * el JSON del *release* más reciente y devuelve la versión, las notas de los cambios y el
 * enlace directo del APK de cada app.
 *
 * Todo falla en silencio: si no hay red, si GitHub no responde o si el JSON cambia, devuelve
 * `null` y la app sigue funcionando igual (una comprobación de versión nunca debe estorbar).
 */
object Actualizaciones {

    const val REPO = "jorgefrosas-dotcom/JFR-Switcher"
    const val PAGINA = "https://jorgefrosas-dotcom.github.io/JFR-Switcher/"

    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    private const val ESPERA_MS = 6000

    /** Lo que publicó la última versión. */
    data class Publicada(
        /** Versión sin la "v" del tag: `0.2.2`. */
        val version: String,
        /** Descripción de los cambios (texto del release). */
        val notas: String,
        /** Enlace directo al APK del switcher. */
        val apkSwitcher: String,
        /** Enlace directo al APK de la cámara. */
        val apkCamara: String
    )

    /** Última versión publicada, o `null` si no se pudo averiguar. */
    fun ultima(): Publicada? {
        val texto = leer(API) ?: return null
        return try {
            val o = JSONObject(texto)
            val version = o.optString("tag_name", "").trim().removePrefix("v").removePrefix("V")
            if (version.isEmpty()) return null
            var switcher = ""
            var camara = ""
            val assets = o.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    val url = a.optString("browser_download_url", "")
                    when (a.optString("name", "").lowercase()) {
                        "jfr-switcher.apk" -> switcher = url
                        "jfr-camera.apk" -> camara = url
                    }
                }
            }
            Publicada(
                version = version,
                notas = o.optString("body", "").trim(),
                apkSwitcher = switcher.ifEmpty {
                    "https://github.com/$REPO/releases/latest/download/JFR-Switcher.apk"
                },
                apkCamara = camara.ifEmpty {
                    "https://github.com/$REPO/releases/latest/download/JFR-Camera.apk"
                }
            )
        } catch (t: Throwable) {
            null
        }
    }

    /** ¿La versión [publicada] es más nueva que la [instalada]? ("0.2.2" > "0.2.1") */
    fun hayNueva(instalada: String, publicada: String): Boolean = comparar(publicada, instalada) > 0

    /**
     * Compara dos versiones número por número: devuelve >0 si [a] es mayor, <0 si es menor y 0
     * si son iguales. Tolera sufijos ("0.3.0-beta" se compara como 0.3.0).
     */
    fun comparar(a: String, b: String): Int {
        val pa = numeros(a)
        val pb = numeros(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return if (x > y) 1 else -1
        }
        return 0
    }

    private fun numeros(v: String): List<Int> =
        v.split('.', '-', '_', '+')
            .mapNotNull { trozo -> trozo.takeWhile(Char::isDigit).toIntOrNull() }

    private fun leer(url: String): String? {
        var conexion: HttpURLConnection? = null
        return try {
            conexion = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = ESPERA_MS
                readTimeout = ESPERA_MS
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "JFR-Switcher")
            }
            if (conexion.responseCode !in 200..299) {
                null
            } else {
                conexion.inputStream.bufferedReader().use { it.readText() }
            }
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { conexion?.disconnect() }
        }
    }
}
