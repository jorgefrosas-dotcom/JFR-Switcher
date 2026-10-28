package com.jfrmedia.switcher.studio.rtc

import com.jfrmedia.switcher.core.media.VideoPreset
import com.jfrmedia.switcher.core.media.VideoPresets
import com.jfrmedia.switcher.core.net.Net
import com.jfrmedia.switcher.core.proto.Msg
import com.jfrmedia.switcher.core.proto.Proto
import com.jfrmedia.switcher.core.util.L
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import java.nio.ByteBuffer

/** Estado visible de una cámara en la UI. */
enum class CamState { CONNECTING, CONNECTED, STREAMING, ERROR, CLOSED }

/**
 * Una cámara remota: canal de señalización (TCP) + PeerConnection WebRTC.
 * La cámara envía la oferta, la tablet responde (unified plan, solo recepción).
 */
class CameraSession(
    val slot: Int,
    val remote: String,
    private val hub: CameraHub,
    private val socket: com.jfrmedia.switcher.core.proto.FramedSocket
) {

    @Volatile
    var state: CamState = CamState.CONNECTING
        private set

    var name: String = "Cámara ${slot + 1}"
    var model: String = ""
    var battery: Int = -1
    var muted: Boolean = false
    var facing: String = "back"

    /** Rotación real que reporta el celular para sus cuadros (0/90/180/270). */
    @Volatile
    var rotacion: Int = 0
    var preset: VideoPreset = VideoPresets.default
    var fps: Int = 0
    var kbps: Int = 0
    var lastError: String = ""

    var videoTrack: VideoTrack? = null
        private set

    private var pc: PeerConnection? = null
    private val pendingRemoteCandidates = ArrayList<IceCandidate>()
    @Volatile private var remoteDescriptionSet = false

    /** Se llama cuando cambia algo visible (estado, nombre, métricas). */
    private fun changed() = hub.notifyChanged()

    fun start() {
        // El PeerConnection se crea ANTES de escuchar: si la oferta llega primero se perdería.
        createPeer()
        Thread({ runSignaling() }, "cam-$slot").start()
    }

    // ---------------- Señalización ----------------

    private fun runSignaling() {
        // El primer mensaje debe ser hello
        val first = socket.read() ?: return closeWith("sin saludo")
        val hello = Msg.parse(first) ?: return closeWith("mensaje inicial inválido")
        if (hello.type != Proto.HELLO) return closeWith("se esperaba hello")

        name = hello.str(Proto.K_NAME, "").ifEmpty { "Cámara ${slot + 1}" }
        model = hello.str(Proto.K_MODEL, "")
        changed()

        val welcome = Msg.builder(Proto.WELCOME)
            .put(Proto.K_INDEX, slot + 1)
            .put(Proto.K_VERSION, Proto.VERSION)
            .put(Proto.K_NAME, Net.deviceLabel())
            .build()
        socket.send(welcome)
        state = CamState.CONNECTED
        changed()

        // Decirle al celular cómo debe orientar el video según el lienzo actual
        enviarOrientacion()

        // Pedir arranque de video con el preset por defecto
        sendCmd(
            Msg.builder(Proto.CMD)
                .put(Proto.K_ACTION, Proto.A_START)
                .put(Proto.K_WIDTH, preset.width)
                .put(Proto.K_HEIGHT, preset.height)
                .put(Proto.K_FPS, preset.fps)
                .put(Proto.K_KBPS, preset.kbps)
                .build()
        )

        while (true) {
            val text = socket.read() ?: break
            val msg = Msg.parse(text) ?: continue
            when (msg.type) {
                Proto.SDP -> onRemoteSdp(msg)
                Proto.ICE -> onRemoteCandidate(msg)
                Proto.STATE -> {
                    muted = msg.bool(Proto.K_MUTED, muted)
                    facing = msg.str(Proto.K_FACING, facing)
                    battery = msg.int(Proto.K_BATTERY, battery)
                    val rot = msg.int(Proto.K_ROTACION, rotacion)
                    if (rot != rotacion) {
                        rotacion = rot
                        hub.onRotacion?.invoke(this, rot)
                    }
                    changed()
                }

                Proto.STATS -> {
                    fps = msg.int(Proto.K_FPS, fps)
                    kbps = msg.int(Proto.K_KBPS, kbps)
                    changed()
                }

                Proto.PING -> socket.send(Msg.builder(Proto.PONG).build())
                Proto.BYE -> break
            }
        }
        L.i("cam$slot", "señalización terminada")
        close()
    }

    private fun onRemoteSdp(msg: Msg) {
        val kind = msg.str(Proto.K_KIND)
        val sdpText = msg.str(Proto.K_SDP)
        if (sdpText.isEmpty()) return
        val pc = pc ?: return
        val type = if (kind == "offer") SessionDescription.Type.OFFER else SessionDescription.Type.ANSWER
        pc.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {
                remoteDescriptionSet = true
                flushCandidates()
                if (type == SessionDescription.Type.OFFER) {
                    createAnswer(pc)
                }
            }

            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) {
                L.e("cam$slot", "setRemoteDescription falló: $p0")
            }
        }, SessionDescription(type, sdpText))
    }

    private fun createAnswer(pc: PeerConnection) {
        pc.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(answer: SessionDescription?) {
                if (answer == null) return
                pc.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onSetSuccess() {
                        socket.send(
                            Msg.builder(Proto.SDP)
                                .put(Proto.K_KIND, "answer")
                                .put(Proto.K_SDP, pc.localDescription?.description ?: answer.description)
                                .build()
                        )
                        L.i("cam$slot", "respuesta enviada")
                    }

                    override fun onCreateFailure(p0: String?) {}
                    override fun onSetFailure(p0: String?) {}
                }, answer)
            }

            override fun onSetSuccess() {}
            override fun onCreateFailure(p0: String?) {
                L.e("cam$slot", "createAnswer falló: $p0")
            }

            override fun onSetFailure(p0: String?) {}
        }, MediaConstraints())
    }

    private fun onRemoteCandidate(msg: Msg) {
        val cand = IceCandidate(
            msg.str(Proto.K_MID, "0"),
            msg.int(Proto.K_MLINE, 0),
            msg.str(Proto.K_CANDIDATE)
        )
        synchronized(pendingRemoteCandidates) {
            if (!remoteDescriptionSet) {
                pendingRemoteCandidates.add(cand)
            } else {
                pc?.addIceCandidate(cand)
            }
        }
    }

    private fun flushCandidates() {
        synchronized(pendingRemoteCandidates) {
            pendingRemoteCandidates.forEach { pc?.addIceCandidate(it) }
            pendingRemoteCandidates.clear()
        }
    }

    fun sendCmd(json: String) {
        socket.send(json)
    }

    /** Último estado de "al aire" enviado, para no repetir mensajes. */
    private var enAireEnviado: Boolean? = null

    /** Avisa al celular si su cámara está en el visor (se muestra en la app). */
    fun enviarPrograma(enAire: Boolean) {
        if (enAireEnviado == enAire) return
        enAireEnviado = enAire
        sendCmd(
            Msg.builder(Proto.CMD)
                .put(Proto.K_ACTION, Proto.A_PROGRAM)
                .put(Proto.K_ON_AIR, enAire)
                .build()
        )
    }

    /** Avisa al celular si el lienzo es vertical u horizontal (bloquea su orientación). */
    fun enviarOrientacion() {
        sendCmd(
            Msg.builder(Proto.CMD)
                .put(Proto.K_ACTION, Proto.A_ORIENTATION)
                .put(Proto.K_ORIENTATION, if (hub.vertical) "portrait" else "landscape")
                .build()
        )
    }

    // ---------------- WebRTC ----------------

    private fun createPeer() {
        val config = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
        }

        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                L.i("cam$slot", "ICE: $state")
                if (state == PeerConnection.IceConnectionState.CONNECTED ||
                    state == PeerConnection.IceConnectionState.COMPLETED
                ) {
                    this@CameraSession.state = CamState.STREAMING
                    changed()
                } else if (state == PeerConnection.IceConnectionState.FAILED ||
                    state == PeerConnection.IceConnectionState.DISCONNECTED
                ) {
                    this@CameraSession.state = CamState.ERROR
                    lastError = "Conexión de video perdida"
                    changed()
                }
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(candidate: IceCandidate?) {
                if (candidate == null) return
                socket.send(
                    Msg.builder(Proto.ICE)
                        .put(Proto.K_CANDIDATE, candidate.sdp)
                        .put(Proto.K_MID, candidate.sdpMid)
                        .put(Proto.K_MLINE, candidate.sdpMLineIndex)
                        .build()
                )
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}

            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}

            override fun onTrack(transceiver: RtpTransceiver?) {
                val track = transceiver?.receiver?.track() ?: return
                if (track is VideoTrack) {
                    videoTrack = track
                    hub.onVideoTrackReady(this@CameraSession, track)
                    this@CameraSession.state = CamState.STREAMING
                    changed()
                    L.i("cam$slot", "pista de video recibida")
                }
            }
        }

        pc = RtcCore.factory.createPeerConnection(config, observer)
    }

    // ---------------- Cierre ----------------

    fun closeWith(reason: String) {
        lastError = reason
        L.w("cam$slot", "cierre: $reason")
        close()
    }

    fun close() {
        if (state == CamState.CLOSED) return
        state = CamState.CLOSED
        runCatching { socket.send(Msg.builder(Proto.BYE).build()) }
        runCatching { socket.close() }
        runCatching {
            videoTrack?.let { hub.onVideoTrackGone(this) }
            pc?.close()
            pc?.dispose()
            pc = null
            videoTrack = null
        }
        changed()
        hub.onSessionClosed(this)
    }

    /** JPEG no aplica; util solo para depurar buffers. */
    private fun String.toBytes(): ByteBuffer = ByteBuffer.wrap(toByteArray())

    companion object {
        const val MAX_TRACKS = 1
    }
}
