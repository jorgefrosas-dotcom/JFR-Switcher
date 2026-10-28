package com.jfrmedia.switcher.studio.plantillas

import android.content.Context
import com.jfrmedia.switcher.core.util.L
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Una cámara dentro de una plantilla. */
data class CamPlantilla(
    val slot: Int,
    val capa: String, // "A" fondo, "B" PiP
    val x: Float,
    val y: Float,
    val ancho: Float,
    val alto: Float,
    val visible: Boolean = true,
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f
)

/** Ajustes de sesión que viajan con la plantilla. */
data class AjustesPlantilla(
    val modo: String = "ambos",          // grabar | transmitir | ambos
    val recAlto: Int = 720,
    val recKbps: Int = 12000,
    val streamAlto: Int = 720,
    val streamKbps: Int = 4000,
    val autoBitrate: Boolean = true,
    val audioMudo: Boolean = false,
    val audioDb: Float = 0f
)

/** Transición guardada en la plantilla. */
data class TransicionPlantilla(val id: String = "fundido", val ms: Int = 420)

/** Medio (video o foto) cargado en el grupo MEDIOS. */
data class MedioPlantilla(val uri: String, val video: Boolean, val nombre: String)

/** Una imagen (overlay) dentro de una plantilla. */
data class ImagenPlantilla(
    val uri: String,
    val nombre: String,
    val x: Float,
    val y: Float,
    val ancho: Float,
    val alto: Float,
    val visible: Boolean = true,
    val opacidad: Float = 1f
)

/**
 * Un texto encima del programa dentro de una plantilla.
 *
 * Guarda el texto y cómo se ve (letra, color, animación y tamaño) en vez de apuntar a la
 * biblioteca: así la plantilla sigue funcionando aunque el texto se borre de ahí.
 */
data class TextoPlantilla(
    val texto: String,
    val fuente: String = "Sans negrita",
    val color: Int = 0xFFFFFFFF.toInt(),
    val animacion: String = "fundido",
    val tamano: Float = 1f,
    val x: Float = 0.21f,
    val y: Float = 0.30f,
    val ancho: Float = 0.58f,
    val alto: Float = 0.20f,
    val visible: Boolean = true,
    val opacidad: Float = 1f
)

/**
 * Plantilla de escena: orientación del lienzo, qué cámara va en cada capa y
 * dónde quedó cada overlay. Se guarda como JSON en el almacenamiento interno.
 */
data class Plantilla(
    val nombre: String,
    val vertical: Boolean,
    val camaras: List<CamPlantilla>,
    val imagenes: List<ImagenPlantilla>,
    val ajustes: AjustesPlantilla = AjustesPlantilla(),
    val transicion: TransicionPlantilla = TransicionPlantilla(),
    val medios: List<MedioPlantilla> = emptyList(),
    val textos: List<TextoPlantilla> = emptyList()
) {
    fun aJson(): String {
        val raiz = JSONObject()
        raiz.put("nombre", nombre)
        raiz.put("vertical", vertical)
        raiz.put("version", 1)

        val cams = JSONArray()
        camaras.forEach { c ->
            cams.put(
                JSONObject()
                    .put("slot", c.slot)
                    .put("capa", c.capa)
                    .put("x", c.x.toDouble())
                    .put("y", c.y.toDouble())
                    .put("w", c.ancho.toDouble())
                    .put("h", c.alto.toDouble())
                    .put("visible", c.visible)
                    .put("zoom", c.zoom.toDouble())
                    .put("panX", c.panX.toDouble())
                    .put("panY", c.panY.toDouble())
            )
        }
        raiz.put("camaras", cams)

        val imgs = JSONArray()
        imagenes.forEach { i ->
            imgs.put(
                JSONObject()
                    .put("uri", i.uri)
                    .put("nombre", i.nombre)
                    .put("x", i.x.toDouble())
                    .put("y", i.y.toDouble())
                    .put("w", i.ancho.toDouble())
                    .put("h", i.alto.toDouble())
                    .put("visible", i.visible)
                    .put("opacidad", i.opacidad.toDouble())
            )
        }
        raiz.put("imagenes", imgs)

        val txts = JSONArray()
        textos.forEach { t ->
            txts.put(
                JSONObject()
                    .put("texto", t.texto)
                    .put("fuente", t.fuente)
                    .put("color", t.color)
                    .put("animacion", t.animacion)
                    .put("tamano", t.tamano.toDouble())
                    .put("x", t.x.toDouble())
                    .put("y", t.y.toDouble())
                    .put("w", t.ancho.toDouble())
                    .put("h", t.alto.toDouble())
                    .put("visible", t.visible)
                    .put("opacidad", t.opacidad.toDouble())
            )
        }
        raiz.put("textos", txts)

        raiz.put(
            "ajustes",
            JSONObject()
                .put("modo", ajustes.modo)
                .put("recAlto", ajustes.recAlto)
                .put("recKbps", ajustes.recKbps)
                .put("streamAlto", ajustes.streamAlto)
                .put("streamKbps", ajustes.streamKbps)
                .put("autoBitrate", ajustes.autoBitrate)
                .put("audioMudo", ajustes.audioMudo)
                .put("audioDb", ajustes.audioDb.toDouble())
        )
        raiz.put(
            "transicion",
            JSONObject().put("id", transicion.id).put("ms", transicion.ms)
        )
        val ms = JSONArray()
        medios.forEach { m ->
            ms.put(
                JSONObject()
                    .put("uri", m.uri)
                    .put("video", m.video)
                    .put("nombre", m.nombre)
            )
        }
        raiz.put("medios", ms)
        return raiz.toString(2)
    }

    companion object {
        fun desdeJson(texto: String): Plantilla? {
            return try {
                val raiz = JSONObject(texto)
                val cams = ArrayList<CamPlantilla>()
                raiz.optJSONArray("camaras")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        cams.add(
                            CamPlantilla(
                                slot = o.optInt("slot", 0),
                                capa = o.optString("capa", "A"),
                                x = o.optDouble("x", 0.0).toFloat(),
                                y = o.optDouble("y", 0.0).toFloat(),
                                ancho = o.optDouble("w", 1.0).toFloat(),
                                alto = o.optDouble("h", 1.0).toFloat(),
                                visible = o.optBoolean("visible", true),
                                zoom = o.optDouble("zoom", 1.0).toFloat(),
                                panX = o.optDouble("panX", 0.0).toFloat(),
                                panY = o.optDouble("panY", 0.0).toFloat()
                            )
                        )
                    }
                }
                val imgs = ArrayList<ImagenPlantilla>()
                raiz.optJSONArray("imagenes")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        imgs.add(
                            ImagenPlantilla(
                                uri = o.optString("uri", ""),
                                nombre = o.optString("nombre", "Overlay"),
                                x = o.optDouble("x", 0.0).toFloat(),
                                y = o.optDouble("y", 0.0).toFloat(),
                                ancho = o.optDouble("w", 0.4).toFloat(),
                                alto = o.optDouble("h", 0.2).toFloat(),
                                visible = o.optBoolean("visible", true),
                                opacidad = o.optDouble("opacidad", 1.0).toFloat()
                            )
                        )
                    }
                }
                val textos = ArrayList<TextoPlantilla>()
                raiz.optJSONArray("textos")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val contenido = o.optString("texto", "")
                        if (contenido.isBlank()) continue
                        textos.add(
                            TextoPlantilla(
                                texto = contenido,
                                fuente = o.optString("fuente", "Sans negrita"),
                                color = o.optInt("color", 0xFFFFFFFF.toInt()),
                                animacion = o.optString("animacion", "fundido"),
                                tamano = o.optDouble("tamano", 1.0).toFloat(),
                                x = o.optDouble("x", 0.21).toFloat(),
                                y = o.optDouble("y", 0.30).toFloat(),
                                ancho = o.optDouble("w", 0.58).toFloat(),
                                alto = o.optDouble("h", 0.20).toFloat(),
                                visible = o.optBoolean("visible", true),
                                opacidad = o.optDouble("opacidad", 1.0).toFloat()
                            )
                        )
                    }
                }
                val medios = ArrayList<MedioPlantilla>()
                raiz.optJSONArray("medios")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        medios.add(
                            MedioPlantilla(
                                uri = o.optString("uri", ""),
                                video = o.optBoolean("video", false),
                                nombre = o.optString("nombre", "Medio")
                            )
                        )
                    }
                }
                val aj = raiz.optJSONObject("ajustes")
                val ajustes = AjustesPlantilla(
                    modo = aj?.optString("modo", "ambos") ?: "ambos",
                    recAlto = aj?.optInt("recAlto", 720) ?: 720,
                    recKbps = aj?.optInt("recKbps", 12000) ?: 12000,
                    streamAlto = aj?.optInt("streamAlto", 720) ?: 720,
                    streamKbps = aj?.optInt("streamKbps", 4000) ?: 4000,
                    autoBitrate = aj?.optBoolean("autoBitrate", true) ?: true,
                    audioMudo = aj?.optBoolean("audioMudo", false) ?: false,
                    audioDb = (aj?.optDouble("audioDb", 0.0) ?: 0.0).toFloat()
                )
                val tr = raiz.optJSONObject("transicion")
                val transicion = TransicionPlantilla(
                    id = tr?.optString("id", "fundido") ?: "fundido",
                    ms = tr?.optInt("ms", 420) ?: 420
                )
                Plantilla(
                    nombre = raiz.optString("nombre", "Escena"),
                    vertical = raiz.optBoolean("vertical", false),
                    camaras = cams,
                    imagenes = imgs,
                    ajustes = ajustes,
                    transicion = transicion,
                    medios = medios,
                    textos = textos
                )
            } catch (t: Throwable) {
                L.e("plantillas", "plantilla ilegible: ${t.message}", t)
                null
            }
        }
    }
}

/** Almacén de plantillas en disco (una por archivo JSON). */
object Plantillas {

    private fun carpeta(ctx: Context): File =
        File(ctx.filesDir, "plantillas").apply { if (!exists()) mkdirs() }

    private fun limpiarNombre(nombre: String): String =
        nombre.trim().replace(Regex("[^A-Za-z0-9ÁÉÍÓÚáéíóúÑñ _-]"), "_").take(60)

    fun listar(ctx: Context): List<String> =
        carpeta(ctx).listFiles { f -> f.extension == "json" }
            ?.mapNotNull { f -> f.nameWithoutExtension.takeIf { it.isNotBlank() } }
            ?.sortedBy { it.lowercase() }
            ?: emptyList()

    fun guardar(ctx: Context, plantilla: Plantilla): Boolean {
        val nombre = limpiarNombre(plantilla.nombre)
        if (nombre.isEmpty()) return false
        return try {
            File(carpeta(ctx), "$nombre.json").writeText(plantilla.aJson(), Charsets.UTF_8)
            L.i("plantillas", "guardada $nombre")
            true
        } catch (t: Throwable) {
            L.e("plantillas", "no se pudo guardar: ${t.message}", t)
            false
        }
    }

    /** Copia una plantilla con otro nombre. Devuelve el nombre final o null. */
    fun duplicar(ctx: Context, nombre: String, nuevo: String? = null): String? {
        val original = cargar(ctx, nombre) ?: return null
        var base = limpiarNombre(nuevo ?: "$nombre copia")
        if (base.isEmpty()) base = "${nombre}_copia"
        var final = base
        var n = 2
        while (File(carpeta(ctx), "$final.json").exists()) {
            final = "${base}_$n"
            n++
        }
        val copia = original.copy(nombre = final)
        return if (guardar(ctx, copia)) final else null
    }

    /** Renombra una plantilla (mueve el archivo). */
    fun renombrar(ctx: Context, viejo: String, nuevo: String): Boolean {
        val plantilla = cargar(ctx, viejo) ?: return false
        val nombreNuevo = limpiarNombre(nuevo)
        if (nombreNuevo.isEmpty()) return false
        return try {
            File(carpeta(ctx), "${limpiarNombre(viejo)}.json").delete()
            guardar(ctx, plantilla.copy(nombre = nombreNuevo))
        } catch (t: Throwable) {
            L.e("plantillas", "renombrar: ${t.message}", t)
            false
        }
    }

    fun cargar(ctx: Context, nombre: String): Plantilla? {
        val f = File(carpeta(ctx), "${limpiarNombre(nombre)}.json")
        if (!f.exists()) return null
        return try {
            Plantilla.desdeJson(f.readText(Charsets.UTF_8))
        } catch (t: Throwable) {
            L.e("plantillas", "no se pudo leer: ${t.message}", t)
            null
        }
    }

    fun borrar(ctx: Context, nombre: String): Boolean =
        File(carpeta(ctx), "${limpiarNombre(nombre)}.json").delete()
}
