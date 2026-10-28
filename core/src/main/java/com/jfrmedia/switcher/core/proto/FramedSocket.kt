package com.jfrmedia.switcher.core.proto

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue

/**
 * Canal TCP con encuadre: 4 bytes de longitud (big endian) + texto UTF-8.
 *
 * Los envíos pasan por un hilo escritor propio. Motivo: Android lanza
 * NetworkOnMainThreadException si se escribe en un socket desde el hilo de UI,
 * y los mensajes de estado (STATS/STATE/CMD) sí salen desde ahí.
 * Con la cola, `send()` nunca toca la red y es seguro desde cualquier hilo.
 */
class FramedSocket(private val socket: Socket) : Closeable {

    private val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
    private val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

    private val queue = LinkedBlockingQueue<String>(512)

    @Volatile
    var closed: Boolean = false
        private set

    val remote: String
        get() = socket.inetAddress?.hostAddress ?: "?"

    private val writer = Thread({
        try {
            while (!closed) {
                val text = queue.take()
                if (text.isEmpty()) continue
                val bytes = text.toByteArray(Charsets.UTF_8)
                output.writeInt(bytes.size)
                output.write(bytes)
                output.flush()
            }
        } catch (e: Exception) {
            if (!closed) {
                closed = true
                runCatching { socket.close() }
            }
        }
    }, "framed-writer").apply {
        isDaemon = true
        start()
    }

    /** Bloquea hasta recibir un mensaje. Devuelve null si el par cerró o hubo error. */
    fun read(): String? {
        return try {
            val len = input.readInt()
            if (len <= 0 || len > Proto.MAX_FRAME_BYTES) return null
            val bytes = ByteArray(len)
            input.readFully(bytes)
            String(bytes, Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    /** Encola un mensaje. Devuelve false solo si el canal ya está cerrado o la cola se llenó. */
    fun send(text: String): Boolean {
        if (closed) return false
        if (text.toByteArray(Charsets.UTF_8).size > Proto.MAX_FRAME_BYTES) return false
        return queue.offer(text)
    }

    fun send(msg: Msg): Boolean = send(msg.toString())

    override fun close() {
        if (closed) return
        closed = true
        runCatching { queue.offer("") }
        runCatching { writer.interrupt() }
        runCatching { socket.close() }
    }
}
