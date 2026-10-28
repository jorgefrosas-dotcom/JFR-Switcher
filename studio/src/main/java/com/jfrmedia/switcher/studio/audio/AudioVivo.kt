package com.jfrmedia.switcher.studio.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.jfrmedia.switcher.core.util.L
import com.jfrmedia.switcher.studio.rec.Recorder
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Nivel del micrófono **antes de grabar o transmitir**, para que el vúmetro se mueva desde que se
 * abre la app y se vea cómo está entrando el audio (si el micrófono está mudo, si hay ruido, si se
 * está acercando al recorte).
 *
 * Abre el micrófono con la **misma fuente y los mismos ajustes** que el grabador (mudo,
 * ecualizador y ganancia), así lo que marca la barra es lo que se va a grabar.
 *
 * Se cierra **antes** de que el grabador abra el micrófono: dos capturas a la vez se pelean por el
 * aparato y el grabador se quedaría sin audio (grabaría solo video, sin avisar).
 */
class AudioVivo(context: Context, private val grabador: Recorder) {

    companion object {
        private const val TAG = "audio-vivo"
        private const val SAMPLE_RATE = 44100

        /** Misma escala que el grabador: el RMS se multiplica por 4.5 para que la barra se vea. */
        private const val ESCALA = 4.5f
    }

    private val app = context.applicationContext

    /** Nivel RMS 0..1: el que usa el vúmetro. */
    @Volatile
    var nivel: Float = 0f
        private set

    /** Pico 0..1 del último bloque: el que usa el monitor de onda. */
    @Volatile
    var pico: Float = 0f
        private set

    /** true mientras el micrófono está abierto para medir. */
    @Volatile
    var activo: Boolean = false
        private set

    private var record: AudioRecord? = null
    private var hilo: Thread? = null
    private var tamBuffer = 0

    @Volatile
    private var corriendo = false

    /**
     * Abre el micrófono para medir. Devuelve false si no se puede: sin permiso, ocupado por otra
     * app o sin micrófono. El que llama decide si reintenta.
     */
    @SuppressLint("MissingPermission")
    fun arrancar(): Boolean {
        if (corriendo) return true
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            L.i(TAG, "sin permiso de micrófono: el vúmetro queda en cero")
            return false
        }
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return false
        tamBuffer = minBuf.coerceAtLeast(4096) * 2
        // La misma fuente que usará la grabación (con entrada externa cambia a comunicación).
        val fuente = if (grabador.entradaExterna) {
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        } else {
            MediaRecorder.AudioSource.CAMCORDER
        }
        val rec = try {
            AudioRecord(
                fuente,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                tamBuffer
            )
        } catch (t: Throwable) {
            L.w(TAG, "no se pudo crear el micrófono: ${t.message}")
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { rec.release() }
            L.w(TAG, "el micrófono no quedó listo (puede estar ocupado)")
            return false
        }
        return try {
            rec.startRecording()
            record = rec
            corriendo = true
            activo = true
            hilo = Thread({ leer(rec) }, "audio-vivo").also { it.start() }
            L.i(
                TAG,
                "micrófono abierto para el vúmetro (" +
                    (if (grabador.entradaExterna) "entrada externa" else "interna") + ")"
            )
            true
        } catch (t: Throwable) {
            L.w(TAG, "no se pudo empezar a leer: ${t.message}")
            runCatching { rec.release() }
            record = null
            corriendo = false
            activo = false
            false
        }
    }

    /**
     * Cierra el micrófono y **espera al hilo**: hasta que no se suelta de verdad, el grabador no
     * puede abrirlo para grabar con audio.
     */
    fun detener() {
        if (!corriendo) {
            nivel = 0f
            pico = 0f
            return
        }
        corriendo = false
        runCatching { hilo?.join(500) }
        runCatching {
            record?.stop()
            record?.release()
        }
        record = null
        hilo = null
        activo = false
        nivel = 0f
        pico = 0f
        L.i(TAG, "micrófono cerrado")
    }

    /** Lee del micrófono hasta que se cierre. La lectura ya bloquea: no hace falta esperar más. */
    private fun leer(rec: AudioRecord) {
        val pcm = ByteArray(tamBuffer)
        while (corriendo) {
            val leido = try {
                rec.read(pcm, 0, pcm.size)
            } catch (t: Throwable) {
                -1
            }
            if (!corriendo) break
            if (leido <= 0) {
                // Sin datos: se descansa un poco para no girar en vacío.
                try {
                    Thread.sleep(30)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
                continue
            }
            procesar(pcm, leido)
            medir(pcm, leido)
        }
    }

    /** Los mismos ajustes que la grabación: mudo, ecualizador de entrada y ganancia. */
    private fun procesar(pcm: ByteArray, bytes: Int) {
        if (grabador.silencio) {
            java.util.Arrays.fill(pcm, 0, bytes, 0.toByte())
            return
        }
        val eq = grabador.eqEntrada
        val g = grabador.ganancia
        if (eq != null && eq.activo) {
            eq.procesar(pcm, bytes, g)
        } else if (g != 1f) {
            // Misma cuenta que Recorder.aplicarGanancia (allá es privada).
            var i = 0
            while (i + 1 < bytes) {
                val lo = pcm[i].toInt() and 0xFF
                val hi = pcm[i + 1].toInt()
                var s = ((hi shl 8) or lo).toShort().toInt()
                s = (s * g).toInt().coerceIn(-32768, 32767)
                pcm[i] = (s and 0xFF).toByte()
                pcm[i + 1] = ((s shr 8) and 0xFF).toByte()
                i += 2
            }
        }
    }

    /** RMS y pico con la misma calibración que el grabador. */
    private fun medir(pcm: ByteArray, bytes: Int) {
        var suma = 0.0
        var maximo = 0.0
        var n = 0
        var i = 0
        while (i + 1 < bytes) {
            val muestra = ((pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)).toShort().toInt()
            val v = muestra / 32768.0
            suma += v * v
            val a = abs(v)
            if (a > maximo) maximo = a
            n++
            i += 2
        }
        if (n == 0) return
        pico = maximo.toFloat().coerceIn(0f, 1f)
        nivel = (sqrt(suma / n) * ESCALA).toFloat().coerceIn(0f, 1f)
    }
}
