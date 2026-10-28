package com.jfrmedia.switcher.core.media

/**
 * Fuerza H.264 en la oferta SDP.
 *
 * Por qué: Android tiene decodificación H.264 por hardware en casi todos los equipos.
 * Si se negocia VP8/VP9 la tablet acaba decodificando 6 flujos por software y el
 * rendimiento se cae. Se eliminan de la oferta los demás códecs y se deja H.264.
 */
object SdpMunger {

    fun h264Only(sdp: String): String {
        val lines = sdp.split("\r\n", "\n").toMutableList()
        val videoStart = lines.indexOfFirst { it.startsWith("m=video") }
        if (videoStart < 0) return sdp
        val videoEnd = (videoStart + 1 until lines.size)
            .firstOrNull { lines[it].startsWith("m=") } ?: lines.size

        val videoBlock = lines.subList(videoStart, videoEnd)
        val mLine = videoBlock.first()
        val parts = mLine.split(" ")
        if (parts.size < 4) return sdp
        val payloads = parts.drop(3)

        // Qué payloads son H.264 según a=rtpmap
        val h264 = HashSet<String>()
        for (l in videoBlock) {
            if (l.startsWith("a=rtpmap:")) {
                val pt = l.removePrefix("a=rtpmap:").substringBefore(" ")
                if (l.contains("H264", ignoreCase = true) || l.contains("H.264", ignoreCase = true)) {
                    h264.add(pt)
                }
            }
        }
        if (h264.isEmpty()) return sdp

        val kept = payloads.filter { h264.contains(it) }
        if (kept.isEmpty()) return sdp

        val newBlock = ArrayList<String>()
        newBlock.add((parts.take(3) + kept).joinToString(" "))
        for (l in videoBlock.drop(1)) {
            when {
                l.startsWith("a=rtcp-fb-") -> newBlock.add(l) // atributos sin payload type
                l.startsWith("a=rtpmap:") || l.startsWith("a=fmtp:") || l.startsWith("a=rtcp-fb:") -> {
                    val pt = l.substringAfter(':').substringBefore(" ")
                    if (h264.contains(pt)) newBlock.add(l)
                }

                else -> newBlock.add(l)
            }
        }

        val result = ArrayList<String>(lines.size)
        result.addAll(lines.subList(0, videoStart))
        result.addAll(newBlock)
        if (videoEnd < lines.size) result.addAll(lines.subList(videoEnd, lines.size))
        return result.joinToString("\r\n")
    }
}
