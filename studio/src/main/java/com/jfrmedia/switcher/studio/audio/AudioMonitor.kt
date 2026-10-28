package com.jfrmedia.switcher.studio.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.jfrmedia.switcher.core.util.L

/**
 * Salida de audio para **monitoreo**: reproduce por la bocina, audífonos, USB o Bluetooth
 * el mismo audio que se está grabando/transmitiendo, con su propio ecualizador y volumen.
 * Sirve para cuidar cómo queda el sonido final antes de grabar.
 */
class AudioMonitor(context: Context) {

    companion object {
        private const val TAG = "monitor"
        private const val SAMPLE_RATE = 44100
    }

    private val app = context.applicationContext
    private val manager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    var encendido: Boolean = false
        private set

    /** Volumen de monitoreo 0..1.5 */
    @Volatile
    var volumen: Float = 1f

    val eq = AudioEq(SAMPLE_RATE)

    private var track: AudioTrack? = null

    fun arrancar(): Boolean {
        if (encendido) return true
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            L.w(TAG, "AudioTrack no disponible")
            return false
        }
        return try {
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBuf * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            t.play()
            track = t
            encendido = true
            L.i(TAG, "monitoreo encendido")
            true
        } catch (t: Throwable) {
            L.w(TAG, "no se pudo abrir la salida: ${t.message}")
            false
        }
    }

    fun detener() {
        encendido = false
        runCatching {
            track?.stop()
            track?.release()
        }
        track = null
        L.i(TAG, "monitoreo apagado")
    }

    /** Escribe un bloque PCM ya procesado por la entrada (mono 16 bits). */
    fun escribir(pcm: ByteArray, bytes: Int) {
        val t = track ?: return
        if (!encendido || bytes <= 0) return
        val copia = pcm.copyOf(bytes)
        eq.procesar(copia, bytes, volumen)
        runCatching { t.write(copia, 0, bytes) }
    }

    /** Salidas disponibles (bocina, audífonos, USB, Bluetooth). */
    fun salidas(): List<Salida> {
        val lista = ArrayList<Salida>()
        val tipos = setOf(
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER
        )
        val vistos = HashSet<Int>()
        runCatching { manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }
            .getOrNull().orEmpty()
            .forEach { d ->
                if (d.type in tipos && vistos.add(d.type)) {
                    lista.add(Salida(d.id, nombreDe(d), d.type))
                }
            }
        if (lista.isEmpty()) {
            lista.add(Salida(-1, "Bocina de la tablet", AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        }
        return lista
    }

    data class Salida(val id: Int, val nombre: String, val tipo: Int)

    private fun nombreDe(d: AudioDeviceInfo): String = when (d.type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Bocina de la tablet"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Audífonos con cable"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Diadema con cable"
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "Salida USB"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth (audio)"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth (llamadas)"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth LE (auricular)"
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> "Bluetooth LE (bocina)"
        else -> d.productName?.toString() ?: "Salida ${d.type}"
    }

    /** Enruta la salida al dispositivo elegido; -1 deja la que decida el sistema. */
    fun seleccionarSalida(id: Int): Boolean = try {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            if (id < 0) {
                manager.clearCommunicationDevice()
            } else {
                val d = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    .firstOrNull { it.id == id }
                if (d != null) manager.setCommunicationDevice(d)
            }
        }
        true
    } catch (t: Throwable) {
        L.w(TAG, "salida: ${t.message}")
        false
    }
}
