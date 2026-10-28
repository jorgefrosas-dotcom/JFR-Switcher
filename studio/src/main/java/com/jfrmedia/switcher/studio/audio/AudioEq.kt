package com.jfrmedia.switcher.studio.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Ecualizador de 5 bandas con filtros biquad (peaking).
 *
 * Se aplica muestra por muestra sobre PCM 16 bits mono, así que sirve igual para la
 * entrada (lo que se graba) y para la salida de monitoreo. Es un EQ real: no es un
 * simple volumen por banda, cada banda es un filtro con su frecuencia central y su Q.
 */
class AudioEq(private val sampleRate: Int = 44100) {

    companion object {
        /** Frecuencias centrales de las 5 bandas. */
        val BANDAS = floatArrayOf(80f, 250f, 1000f, 3500f, 10000f)
        val NOMBRES = arrayOf("80 Hz", "250 Hz", "1 kHz", "3.5 kHz", "10 kHz")

        /** Presets rápidos (dB por banda). */
        fun presetPlano() = FloatArray(5)

        /** Voz hablada: quita retumbe, da presencia y aire sin sibilancias. */
        fun presetVoz() = floatArrayOf(-5f, -2f, 3f, 4f, 1f)

        /** Música: graves y agudos arriba, medios apenas abajo (curva suave). */
        fun presetMusica() = floatArrayOf(4f, 2f, -1f, 2f, 4f)

        /** Ambiente ruidoso: corta retumbe y zumbidos, realza voz. */
        fun presetAmbiente() = floatArrayOf(-8f, -4f, 3f, 5f, 2f)
    }

    /** Ganancia de cada banda en dB (-12..+12). */
    val gananciasDb = FloatArray(5)

    private val b0 = DoubleArray(5)
    private val b1 = DoubleArray(5)
    private val b2 = DoubleArray(5)
    private val a1 = DoubleArray(5)
    private val a2 = DoubleArray(5)
    private val x1 = DoubleArray(5)
    private val x2 = DoubleArray(5)
    private val y1 = DoubleArray(5)
    private val y2 = DoubleArray(5)

    /** ¿Hay algo que hacer? Si todo está en 0 se salta el procesado (ahorra CPU). */
    val activo: Boolean
        get() = gananciasDb.any { kotlin.math.abs(it) > 0.05f }

    fun fijarBanda(indice: Int, db: Float) {
        if (indice !in gananciasDb.indices) return
        gananciasDb[indice] = db.coerceIn(-12f, 12f)
        calcular(indice)
    }

    fun fijarTodas(db: FloatArray) {
        for (i in gananciasDb.indices) {
            gananciasDb[i] = (db.getOrNull(i) ?: 0f).coerceIn(-12f, 12f)
            calcular(i)
        }
    }

    /** Reinicia el estado interno de los filtros (al cambiar de fuente). */
    fun reiniciar() {
        x1.fill(0.0); x2.fill(0.0); y1.fill(0.0); y2.fill(0.0)
    }

    /** Diseña el biquad peaking de la banda [i]. */
    private fun calcular(i: Int) {
        val f0 = BANDAS[i].toDouble()
        val q = 1.0
        val ganancia = gananciasDb[i].toDouble()
        if (kotlin.math.abs(ganancia) < 0.05) {
            b0[i] = 1.0; b1[i] = 0.0; b2[i] = 0.0; a1[i] = 0.0; a2[i] = 0.0
            return
        }
        val a = Math.pow(10.0, ganancia / 40.0)
        val w0 = 2.0 * PI * f0 / sampleRate
        val alpha = sin(w0) / (2.0 * q)
        val cosW = cos(w0)
        val b0n = 1 + alpha * a
        val b1n = -2 * cosW
        val b2n = 1 - alpha * a
        val a0n = 1 + alpha / a
        val a1n = -2 * cosW
        val a2n = 1 - alpha / a
        b0[i] = b0n / a0n
        b1[i] = b1n / a0n
        b2[i] = b2n / a0n
        a1[i] = a1n / a0n
        a2[i] = a2n / a0n
    }

    /**
     * Procesa [bytes] muestras PCM 16 bits mono en el mismo buffer.
     * [ganancia] es un multiplicador lineal aplicado al final.
     */
    fun procesar(pcm: ByteArray, bytes: Int, ganancia: Float = 1f) {
        val usarEq = activo
        var k = 0
        while (k + 1 < bytes) {
            val lo = pcm[k].toInt() and 0xFF
            val hi = pcm[k + 1].toInt()
            var muestra = ((hi shl 8) or lo).toShort().toInt().toDouble()

            if (usarEq) {
                for (i in 0 until 5) {
                    if (b0[i] == 1.0 && b1[i] == 0.0 && a1[i] == 0.0) continue
                    val salida = b0[i] * muestra + b1[i] * x1[i] + b2[i] * x2[i] -
                        a1[i] * y1[i] - a2[i] * y2[i]
                    x2[i] = x1[i]
                    x1[i] = muestra
                    y2[i] = y1[i]
                    y1[i] = salida
                    muestra = salida
                }
            }

            var valor = (muestra * ganancia).toInt().coerceIn(-32768, 32767)
            pcm[k] = (valor and 0xFF).toByte()
            pcm[k + 1] = ((valor shr 8) and 0xFF).toByte()
            k += 2
        }
    }

    private fun sinCompat(x: Double): Double = sqrt(x * x)
}
