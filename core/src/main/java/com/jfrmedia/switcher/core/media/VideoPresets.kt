package com.jfrmedia.switcher.core.media

/** Presets de calidad de la cámara remota (todos 16:9, que es el formato del lienzo). */
data class VideoPreset(
    val label: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val kbps: Int
)

object VideoPresets {
    val BAJA = VideoPreset("360p", 640, 360, 30, 1200)
    val MEDIA = VideoPreset("480p", 854, 480, 30, 2600)
    val ALTA = VideoPreset("720p", 1280, 720, 30, 4500)

    val all = listOf(BAJA, MEDIA, ALTA)

    fun byLabel(label: String): VideoPreset = all.firstOrNull { it.label == label } ?: MEDIA

    /** La tablet decodifica 6 flujos: por defecto calidad media para no ahogarla. */
    val default = MEDIA
}
