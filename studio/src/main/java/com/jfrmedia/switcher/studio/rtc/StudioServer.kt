package com.jfrmedia.switcher.studio.rtc

import com.jfrmedia.switcher.core.proto.FramedSocket
import com.jfrmedia.switcher.core.proto.Msg
import com.jfrmedia.switcher.core.proto.Proto
import com.jfrmedia.switcher.core.util.L
import java.net.ServerSocket
import java.net.Socket

/**
 * Servidor de emparejamiento: la tablet escucha en la red local y cada celular
 * se conecta con la IP + puerto que aparecen en pantalla.
 */
class StudioServer(private val hub: CameraHub) {

    @Volatile
    var port: Int = Proto.DEFAULT_PORT
        private set

    @Volatile
    private var running = false
    private var server: ServerSocket? = null
    private var acceptThread: Thread? = null

    /** Devuelve true si quedó escuchando. */
    fun start(preferredPort: Int = Proto.DEFAULT_PORT): Boolean {
        if (running) return true
        var s: ServerSocket? = null
        var p = preferredPort
        var tries = 0
        while (s == null && tries < 10) {
            try {
                s = ServerSocket(p)
                port = p
            } catch (e: Exception) {
                tries++
                p = preferredPort + tries
            }
        }
        if (s == null) {
            L.e("server", "no se pudo abrir el puerto")
            return false
        }
        server = s
        running = true
        acceptThread = Thread({ acceptLoop() }, "studio-server").also { it.start() }
        L.i("server", "escuchando en $port")
        return true
    }

    private fun acceptLoop() {
        while (running) {
            val client: Socket = try {
                server?.accept() ?: break
            } catch (e: Exception) {
                break
            }
            try {
                client.tcpNoDelay = true
                client.soTimeout = 0
                val framed = FramedSocket(client)
                val free = hub.freeSlot()
                if (free == null) {
                    framed.send(
                        Msg.builder(Proto.REJECT)
                            .put(Proto.K_MESSAGE, "El switcher ya tiene 6 cámaras")
                            .build()
                    )
                    runCatching { client.close() }
                    continue
                }
                val session = CameraSession(free, client.inetAddress?.hostAddress ?: "?", hub, framed)
                hub.put(session)
                session.start()
            } catch (e: Exception) {
                L.e("server", "fallo al aceptar", e)
                runCatching { client.close() }
            }
        }
        L.i("server", "aceptación detenida")
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
        acceptThread = null
    }

    /** Envía un comando a todas las cámaras conectadas. */
    fun broadcast(json: String) {
        hub.all().forEach { runCatching { it.sendCmd(json) } }
    }
}
