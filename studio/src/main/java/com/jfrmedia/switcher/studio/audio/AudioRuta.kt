package com.jfrmedia.switcher.studio.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import com.jfrmedia.switcher.core.util.L

/**
 * Selección de la entrada de audio del switcher: micrófono interno, diadema con cable,
 * USB o Bluetooth (incluidos los micrófonos inalámbricos tipo solapa).
 *
 * Bluetooth necesita tres cosas para entregar audio de verdad:
 *  1. El enlace SCO activo (`startBluetoothSco` + `isBluetoothScoOn`).
 *  2. Modo de audio `MODE_IN_COMMUNICATION` mientras se captura.
 *  3. Que la captura use la fuente `VOICE_COMMUNICATION` (lo hace el grabador, que se
 *     entera por [com.jfrmedia.switcher.studio.rec.Recorder.entradaBluetooth]).
 *
 * Ojo: el sistema **no** muestra ningún puerto de entrada Bluetooth hasta que el SCO
 * sube; por eso antes se elegía un dispositivo inexistente y no sonaba nada.
 */
class AudioRuta(context: Context) {

    private val app = context.applicationContext
    private val manager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** Entradas disponibles ahora mismo. */
    data class Entrada(val id: Int, val nombre: String, val tipo: Int)

    /** true si la última selección fue Bluetooth. */
    var bluetoothSeleccionado: Boolean = false
        private set

    /** true si la última selección NO es el micrófono interno (USB, diadema o Bluetooth). */
    var entradaExterna: Boolean = false
        private set

    private var receptorSco: BroadcastReceiver? = null
    private var scoConectado: Boolean = false

    fun entradas(): List<Entrada> {
        val lista = ArrayList<Entrada>()
        val dispositivos = runCatching {
            manager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        }.getOrNull().orEmpty()

        val tiposUtiles = setOf(
            AudioDeviceInfo.TYPE_BUILTIN_MIC,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER
        )
        val vistos = HashSet<Int>()
        dispositivos.forEach { d ->
            if (d.type in tiposUtiles && vistos.add(d.type)) {
                lista.add(Entrada(d.id, nombreDe(d), d.type))
            }
        }
        if (lista.none { it.tipo == AudioDeviceInfo.TYPE_BUILTIN_MIC }) {
            lista.add(0, Entrada(-1, "Micrófono interno (sistema)", AudioDeviceInfo.TYPE_BUILTIN_MIC))
        }
        // Si hay un Bluetooth emparejado se ofrece aunque el puerto aún no exista:
        // aparece justo cuando el SCO sube.
        val hayBt = dispositivos.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        if (!hayBt && hayBluetoothDisponible()) {
            lista.add(
                Entrada(
                    BT_ENTRADA_ID,
                    "Micrófono Bluetooth (inalámbrico)",
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                )
            )
        }
        return lista
    }

    fun nombreDe(d: AudioDeviceInfo): String = nombreDeDispositivo(d)

    private fun esBluetooth(tipo: Int): Boolean = tipo == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
        (Build.VERSION.SDK_INT >= 31 && (tipo == AudioDeviceInfo.TYPE_BLE_HEADSET ||
            tipo == AudioDeviceInfo.TYPE_BLE_SPEAKER))

    /** ¿El teléfono/tablet ofrece algún dispositivo Bluetooth de comunicación? */
    private fun hayBluetoothDisponible(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= 31) {
            manager.availableCommunicationDevices.any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            }
        } else {
            manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        }
    }.getOrDefault(false)

    /**
     * Enruta la captura al dispositivo [id]. Con -1 se deja el que elija el sistema.
     * Devuelve true si se aplicó.
     */
    fun seleccionar(id: Int, tipo: Int): Boolean {
        return try {
            entradaExterna = tipo != AudioDeviceInfo.TYPE_BUILTIN_MIC
            if (esBluetooth(tipo)) {
                prepararBluetooth()
                L.i("audio", "entrada de audio: Bluetooth (SCO)")
                return true
            }
            liberarBluetooth()
            if (id < 0) {
                if (Build.VERSION.SDK_INT >= 31) manager.clearCommunicationDevice()
                manager.mode = AudioManager.MODE_NORMAL
                L.i("audio", "entrada de audio: la que elija el sistema")
                return true
            }
            if (Build.VERSION.SDK_INT >= 31) {
                val dispositivo = manager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                    .firstOrNull { it.id == id }
                if (dispositivo == null) {
                    L.w("audio", "la entrada $id ya no está disponible")
                    return false
                }
                manager.setCommunicationDevice(dispositivo)
                manager.mode = AudioManager.MODE_NORMAL
            }
            L.i("audio", "entrada de audio cambiada (id=$id tipo=$tipo)")
            true
        } catch (t: Throwable) {
            L.w("audio", "no se pudo cambiar la entrada: ${t.message}")
            false
        }
    }

    // --------------------------------------------------------------- Bluetooth

    /** Sube el SCO y deja el modo de audio listo para capturar del micrófono Bluetooth. */
    fun prepararBluetooth() {
        bluetoothSeleccionado = true
        runCatching {
            if (receptorSco == null) {
                receptorSco = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context?, intent: Intent?) {
                        val estado = intent?.getIntExtra(
                            AudioManager.EXTRA_SCO_AUDIO_STATE,
                            AudioManager.SCO_AUDIO_STATE_ERROR
                        ) ?: AudioManager.SCO_AUDIO_STATE_ERROR
                        when (estado) {
                            AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                                scoConectado = true
                                L.i("audio", "SCO conectado: el micrófono Bluetooth está listo")
                            }

                            AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                                scoConectado = false
                            }
                        }
                    }
                }
                val filtro = IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
                if (Build.VERSION.SDK_INT >= 33) {
                    app.registerReceiver(receptorSco, filtro, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("DEPRECATION")
                    app.registerReceiver(receptorSco, filtro)
                }
            }
            manager.mode = AudioManager.MODE_IN_COMMUNICATION
            @Suppress("DEPRECATION")
            manager.startBluetoothSco()
            @Suppress("DEPRECATION")
            manager.isBluetoothScoOn = true
        }
    }

    /** Espera (máximo [ms]) a que el enlace SCO esté conectado. */
    fun esperarSco(ms: Long = 1500): Boolean {
        val fin = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < fin) {
            @Suppress("DEPRECATION")
            if (scoConectado || manager.isBluetoothScoOn) return true
            try {
                Thread.sleep(60)
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return scoConectado
    }

    fun liberarBluetooth() {
        bluetoothSeleccionado = false
        runCatching {
            @Suppress("DEPRECATION")
            manager.stopBluetoothSco()
            @Suppress("DEPRECATION")
            manager.isBluetoothScoOn = false
            if (Build.VERSION.SDK_INT >= 31) manager.clearCommunicationDevice()
            manager.mode = AudioManager.MODE_NORMAL
        }
        receptorSco?.let { runCatching { app.unregisterReceiver(it) } }
        receptorSco = null
        scoConectado = false
    }

    companion object {
        /** Identificador ficticio que representa al micrófono Bluetooth antes de tener puerto. */
        const val BT_ENTRADA_ID = -2

        /**
         * Nombre legible de un dispositivo de entrada. Está en el companion para que el
         * grabador pueda etiquetar la entrada que el sistema le dio de verdad.
         */
        @JvmStatic
        fun nombreDeDispositivo(d: AudioDeviceInfo): String = when (d.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Micrófono interno"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Diadema con cable"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Audífonos con cable"
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET -> "Micrófono USB"

            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Micrófono Bluetooth"
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth LE (auricular)"
            AudioDeviceInfo.TYPE_BLE_SPEAKER -> "Bluetooth LE (bocina)"

            else -> d.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Entrada ${d.type}"
        }
    }
}
