package com.jfrmedia.switcher.studio.rtc

import com.jfrmedia.switcher.core.util.L
import org.webrtc.VideoTrack
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Registro de las 6 fuentes del programa.
 * Las sesiones se crean cuando un celular se conecta; la UI solo lee estado.
 */
class CameraHub {

    private val slots = arrayOfNulls<CameraSession>(6)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** Se dispara (en hilo WebRTC) cuando llega la pista de video de una cámara. */
    @Volatile var onTrackReady: ((CameraSession, VideoTrack) -> Unit)? = null

    /** Orientación actual del lienzo; se manda a cada cámara al conectarse. */
    @Volatile
    var vertical: Boolean = false

    /**
     * Hay grabación o transmisión en curso: las cámaras **congelan su rotación** para que el
     * formato no cambie a media sesión. Se manda al empezar y al terminar.
     */
    @Volatile
    var rotacionBloqueada: Boolean = false

    /** Se dispara cuando una cámara se va. */
    @Volatile var onTrackGone: ((CameraSession) -> Unit)? = null

    /** Rotación real del cuadro que reporta la cámara (0/90/180/270). */
    @Volatile var onRotacion: ((CameraSession, Int) -> Unit)? = null

    @Volatile var onCountChanged: ((Int) -> Unit)? = null

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    fun notifyChanged() {
        listeners.forEach { runCatching { it() } }
    }

    fun session(slot: Int): CameraSession? = slots.getOrNull(slot)

    fun all(): List<CameraSession> = slots.filterNotNull()

    fun count(): Int = slots.count { it != null && it.state != CamState.CLOSED }

    fun slotOf(session: CameraSession): Int = slots.indexOfFirst { it === session }

    fun freeSlot(): Int? = slots.indexOfFirst { it == null || it.state == CamState.CLOSED }
        .takeIf { it >= 0 }

    fun put(session: CameraSession) {
        slots[session.slot] = session
        onCountChanged?.invoke(count())
        notifyChanged()
    }

    fun onSessionClosed(session: CameraSession) {
        val i = slotOf(session)
        if (i >= 0 && slots[i] === session) slots[i] = null
        L.i("hub", "liberado slot $i")
        onCountChanged?.invoke(count())
        notifyChanged()
    }

    fun onVideoTrackReady(session: CameraSession, track: VideoTrack) {
        onTrackReady?.invoke(session, track)
        notifyChanged()
    }

    fun onVideoTrackGone(session: CameraSession) {
        onTrackGone?.invoke(session)
        notifyChanged()
    }

    fun shutdown() {
        slots.forEach { it?.close() }
        slots.fill(null)
        notifyChanged()
    }
}
