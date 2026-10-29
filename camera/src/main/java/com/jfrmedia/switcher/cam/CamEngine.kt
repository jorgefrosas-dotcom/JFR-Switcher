package com.jfrmedia.switcher.cam

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.jfrmedia.switcher.core.media.SdpMunger
import com.jfrmedia.switcher.core.media.VideoPreset
import com.jfrmedia.switcher.core.media.VideoPresets
import com.jfrmedia.switcher.core.net.Net
import com.jfrmedia.switcher.core.proto.FramedSocket
import com.jfrmedia.switcher.core.proto.Msg
import com.jfrmedia.switcher.core.proto.Proto
import com.jfrmedia.switcher.core.util.L
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraEnumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.CapturerObserver
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Estado del enlace con la tablet, tal como se pinta en la barra superior. */
enum class EstadoEnlace {
    SIN_CONECTAR,
    CONECTANDO,
    CONECTADA,
    ERROR
}

/**
 * Ajustes actuales de la cámara. Cambian tanto por los botones del celular
 * como por los comandos que manda la tablet, y siempre se reflejan en la UI.
 */
data class AjustesCamara(
    val preset: VideoPreset = VideoPresets.default,
    val cara: String = "back", // back | front
    val linterna: Boolean = false,
    val espejo: Boolean = false
)

/**
 * Motor de la app de cámara: canal de señalización TCP con la tablet + PeerConnection
 * de WebRTC como emisor (solo video, sin audio).
 *
 * Reglas de hilos:
 *  - Todo el estado de WebRTC y de la UI se toca en el hilo principal.
 *  - El socket se abre en un hilo de red y se lee en su propio hilo; cada mensaje
 *    recibido se despacha al hilo principal antes de procesarse.
 *  - `FramedSocket.send` es seguro entre hilos, por eso el observador de ICE puede
 *    enviar candidatos directamente desde el hilo de WebRTC.
 */
class CamEngine(contexto: Context, private val escucha: Escucha) {

    /** Lo que la actividad necesita escuchar para pintar la pantalla. */
    interface Escucha {
        fun onEstado(estado: EstadoEnlace, mensaje: String)
        fun onVivo(enVivo: Boolean)
        fun onAjustes(ajustes: AjustesCamara)

        /** El switcher pide orientación vertical u horizontal según el lienzo actual. */
        fun onOrientacion(vertical: Boolean)

        /**
         * El switcher está grabando o transmitiendo: la cámara **congela su rotación** para que
         * el formato no cambie a media sesión. Se suelta al terminar.
         */
        fun onBloqueoRotacion(bloqueado: Boolean)

        /** El switcher avisa si esta cámara está o no en el visor (al aire). */
        fun onAlAire(enAire: Boolean)
    }

    private val app: Context = contexto.applicationContext
    private val hiloPrincipal = Handler(Looper.getMainLooper())
    private val hiloRed = Executors.newSingleThreadExecutor { tarea ->
        Thread(tarea, "jfr-cam-red").apply { isDaemon = true }
    }

    // ---- Estado visible ----
    var estado: EstadoEnlace = EstadoEnlace.SIN_CONECTAR
        private set

    var indiceCamara: Int = 0
        private set

    var ajustes: AjustesCamara = AjustesCamara()
        private set

    // ---- Canal con la tablet ----
    private var socket: FramedSocket? = null
    private var hiloLectura: Thread? = null

    // ---- WebRTC ----
    private var egl: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var capturer: CameraVideoCapturer? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var enumerador: CameraEnumerator? = null
    private var nombreDispositivoCamara: String? = null
    private var renderer: SurfaceViewRenderer? = null

    private val candidatosPendientes = ArrayList<IceCandidate>()
    private var descripcionRemotaLista = false
    private var capturando = false

    /** Orientación pedida por el switcher (false = horizontal). */
    @Volatile
    private var orientacionVertical = false

    /** El switcher avisó que esta cámara está en el visor. */
    @Volatile
    var alAire: Boolean = false
        private set

    /** Rotación real de los cuadros que ve esta app (0/90/180/270). */
    @Volatile
    private var rotacionCuadro = 0

    // ---- Métricas y batería ----
    private val contadorFrames = AtomicInteger(0)
    private var ultimaBateria = -2
    private var idCamaraFlash: String? = null
    private var flashYaBuscado = false

    // =====================================================================
    // API pública para la actividad
    // =====================================================================

    /** Engancha la vista previa local; se puede llamar antes de tener EglBase. */
    fun adjuntarPreview(vista: SurfaceViewRenderer) {
        renderer = vista
        val eglBase = asegurarEgl() ?: return
        runCatching { vista.init(eglBase.eglBaseContext, null) }
            .onFailure { L.e(TAG, "no se pudo inicializar la vista previa", it) }
        vista.setMirror(ajustes.espejo)
        videoTrack?.addSink(vista)
    }

    /** Suelta la vista previa (llamar antes de `SurfaceViewRenderer.release()`). */
    fun separarPreview(vista: SurfaceViewRenderer) {
        runCatching { videoTrack?.removeSink(vista) }
        if (renderer === vista) renderer = null
    }

    /**
     * Abre el canal con la tablet. [puerto] es el que trae el QR (`jfr://IP:PUERTO`): puede
     * no ser el 8787 si en la tablet estaba ocupado. Sin puerto se usa el de siempre.
     */
    fun conectar(ipTexto: String, puerto: Int = Proto.DEFAULT_PORT) {
        val ip = ipTexto.trim()
        if (ip.isEmpty()) {
            fallar("Escribe la IP de la tablet")
            return
        }
        if (estado == EstadoEnlace.CONECTANDO || estado == EstadoEnlace.CONECTADA) return
        if (puerto !in 1..65535) {
            fallar("El puerto $puerto no es válido")
            return
        }

        // Si quedó un canal a medias de un intento anterior, se tira antes de abrir otro.
        if (socket != null) cerrarCanal()

        cambiarEstado(EstadoEnlace.CONECTANDO, "Conectando…")
        hiloRed.execute {
            var porSiFalla: FramedSocket? = null
            try {
                val tcp = Socket()
                tcp.tcpNoDelay = true
                tcp.connect(InetSocketAddress(ip, puerto), TIMEOUT_CONEXION_MS)
                val canal = FramedSocket(tcp)
                porSiFalla = canal
                val saludo = Msg.builder(Proto.HELLO)
                    .put(Proto.K_VERSION, Proto.VERSION)
                    .put(Proto.K_NAME, Net.deviceLabel())
                    .put(Proto.K_MODEL, Build.MODEL)
                    .put(Proto.K_API, Build.VERSION.SDK_INT)
                    .build()
                if (!canal.send(saludo)) {
                    throw IllegalStateException("la tablet cerró el canal al saludar")
                }
                L.i(TAG, "conexión abierta con $ip:$puerto")
                hiloPrincipal.post {
                    if (estado != EstadoEnlace.CONECTANDO) {
                        // El usuario canceló mientras se conectaba.
                        runCatching { canal.close() }
                        return@post
                    }
                    socket = canal
                    iniciarLectura(canal)
                }
            } catch (e: Exception) {
                runCatching { porSiFalla?.close() }
                val detalle = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                L.w(TAG, "fallo al conectar: $detalle")
                hiloPrincipal.post {
                    if (estado == EstadoEnlace.CONECTANDO) {
                        fallar("No se pudo conectar a $ip:$puerto — $detalle")
                    }
                }
            }
        }
    }

    /** Cierra el canal por decisión del usuario (o porque la tablet mandó BYE). */
    fun desconectar(motivo: String? = null) {
        val habiaCanal = estado == EstadoEnlace.CONECTANDO || estado == EstadoEnlace.CONECTADA
        runCatching { socket?.send(Msg.builder(Proto.BYE).build()) }
        cerrarCanal()
        if (habiaCanal) cambiarEstado(EstadoEnlace.SIN_CONECTAR, motivo.orEmpty())
    }

    /** Libera todo: cámara apagada, socket cerrado, WebRTC destruido. Idempotente. */
    fun liberar() {
        // `cerrarCanal` ya suelta pista, surfaceHelper, fuente, capturer y PeerConnection.
        cerrarCanal()
        detenerTicker()
        renderer = null
        runCatching { factory?.dispose() }
        factory = null
        runCatching { egl?.release() }
        egl = null
        enumerador = null
        nombreDispositivoCamara = null
        candidatosPendientes.clear()
        hiloRed.shutdownNow()
        estado = EstadoEnlace.SIN_CONECTAR
        L.i(TAG, "motor liberado")
    }

    /** Voltea entre cámara trasera y frontal. */
    fun alternarCamara() {
        val capt = capturer ?: return
        apagarLinterna()
        runCatching {
            capt.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
                override fun onCameraSwitchDone(esFrontal: Boolean) {
                    hiloPrincipal.post {
                        ajustes = ajustes.copy(
                            cara = if (esFrontal) "front" else "back",
                            // La frontal se muestra en espejo, como cualquier selfie.
                            espejo = esFrontal
                        )
                        aplicarEspejo()
                        notificarAjustes()
                        enviarEstado()
                    }
                }

                override fun onCameraSwitchError(mensaje: String?) {
                    hiloPrincipal.post { L.w(TAG, "no se pudo voltear la cámara: ${mensaje ?: "sin detalle"}") }
                }
            })
        }.onFailure { L.w(TAG, "switchCamera falló: ${it.message}") }
    }

    /** Enciende o apaga la linterna (solo tiene sentido con la cámara trasera). */
    fun fijarLinterna(encender: Boolean) {
        if (encender && ajustes.cara != "back") {
            L.w(TAG, "la linterna solo aplica a la cámara trasera")
            return
        }
        val id = idCamaraConFlash()
        if (id == null) {
            L.w(TAG, "este equipo no reporta flash utilizable")
            return
        }
        try {
            app.getSystemService(CameraManager::class.java)?.setTorchMode(id, encender)
            ajustes = ajustes.copy(linterna = encender)
            notificarAjustes()
            enviarEstado()
        } catch (e: Exception) {
            // Algunos equipos lanzan excepción aunque declaren el flash.
            L.w(TAG, "no se pudo cambiar la linterna: ${e.message}")
            ajustes = ajustes.copy(linterna = false)
            notificarAjustes()
        }
    }

    /** true si hay una cámara trasera con flash. */
    fun hayLinterna(): Boolean = idCamaraConFlash() != null

    /** Cambia la resolución (360p / 540p / 720p). */
    fun fijarCalidad(preset: VideoPreset) {
        aplicarCalidad(preset)
        enviarEstado()
    }

    /** Activa o quita el espejo de la vista previa. */
    fun fijarEspejo(espejo: Boolean) {
        if (ajustes.espejo == espejo) return
        ajustes = ajustes.copy(espejo = espejo)
        aplicarEspejo()
        notificarAjustes()
        enviarEstado()
    }

    // =====================================================================
    // Señalización
    // =====================================================================

    private fun iniciarLectura(canal: FramedSocket) {
        hiloLectura = Thread({
            mientras@ while (true) {
                val texto = canal.read() ?: break@mientras
                val msg = Msg.parse(texto) ?: continue@mientras
                // Solo se atienden mensajes del canal que sigue vigente.
                hiloPrincipal.post { if (socket === canal) manejar(msg) }
            }
            hiloPrincipal.post { if (socket === canal) alPerderConexion() }
            L.i(TAG, "bucle de lectura terminado")
        }, "jfr-cam-lectura").apply { isDaemon = true }.also { it.start() }
    }

    private fun manejar(msg: Msg) {
        L.i(TAG, "← ${msg.type}")
        when (msg.type) {
            Proto.WELCOME -> {
                indiceCamara = msg.int(Proto.K_INDEX, indiceCamara).coerceIn(1, 6)
                cambiarEstado(EstadoEnlace.CONECTADA, "Cámara $indiceCamara")
                arrancarWebRtc()
            }

            Proto.SDP -> {
                if (msg.str(Proto.K_KIND, "answer") == "answer") aceptarRespuesta(msg.str(Proto.K_SDP))
            }

            Proto.ICE -> aceptarCandidato(msg)
            Proto.CMD -> manejarComando(msg)
            Proto.PING -> enviar(Msg.builder(Proto.PONG).build())
            Proto.BYE -> desconectar("La tablet cerró la conexión")
            Proto.REJECT -> fallar("La tablet rechazó la cámara: ${msg.str(Proto.K_MESSAGE, "sin espacio disponible")}")
            Proto.ERROR -> fallar(msg.str(Proto.K_MESSAGE, "La tablet reportó un error"))
            else -> L.w(TAG, "mensaje ignorado: ${msg.type}")
        }
    }

    /**
     * El switcher manda la orientación del lienzo al conectar (y si la cambia en vivo).
     * La app se bloquea en esa orientación y reinicia la captura para que WebRTC
     * recalcule la rotación de los cuadros: así el video sale vertical u horizontal
     * igual que el lienzo.
     */
    private fun aplicarOrientacionLienzo(vertical: Boolean) {
        if (vertical == orientacionVertical) return
        orientacionVertical = vertical
        L.i(TAG, "lienzo " + (if (vertical) "vertical" else "horizontal") + ": ajustando cámara")
        escucha.onOrientacion(vertical)
        hiloPrincipal.postDelayed({
            if (capturando) {
                detenerCaptura()
                arrancarCaptura(ajustes.preset)
            }
        }, 600)
    }

    private fun manejarComando(msg: Msg) {
        when (msg.str(Proto.K_ACTION)) {
            Proto.A_START -> {
                arrancarCaptura(presetDe(msg) ?: ajustes.preset)
                enviarEstado()
            }

            Proto.A_STOP -> detenerCaptura()

            Proto.A_QUALITY -> {
                val preset = presetDe(msg)
                if (preset == null) L.w(TAG, "comando de calidad sin resolución reconocible")
                else fijarCalidad(preset)
            }

            Proto.A_FPS -> {
                val base = ajustes.preset
                val fps = msg.int(Proto.K_FPS, base.fps).coerceIn(1, 60)
                aplicarCalidad(base.copy(fps = fps))
            }

            Proto.A_FLIP -> alternarCamara()
            Proto.A_TORCH -> fijarLinterna(msg.bool(Proto.K_TORCH, !ajustes.linterna))
            Proto.A_MIRROR -> fijarEspejo(msg.bool(Proto.K_MIRROR, !ajustes.espejo))

            Proto.A_ORIENTATION -> aplicarOrientacionLienzo(
                msg.str(Proto.K_ORIENTATION).equals("portrait", ignoreCase = true)
            )

            Proto.A_BLOQUEO -> {
                val bloqueado = msg.bool(Proto.K_BLOQUEO, false)
                L.i(TAG, if (bloqueado) "rotación congelada (sesión en curso)" else "rotación libre")
                hiloPrincipal.post { escucha.onBloqueoRotacion(bloqueado) }
            }

            Proto.A_PROGRAM -> {
                val enAire = msg.bool(Proto.K_ON_AIR, false)
                if (enAire != alAire) {
                    alAire = enAire
                    L.i(TAG, if (enAire) "AL AIRE" else "fuera de aire")
                    escucha.onAlAire(enAire)
                }
            }

            else -> L.w(TAG, "comando desconocido: ${msg.str(Proto.K_ACTION)}")
        }
    }

    /** Traduce un CMD de calidad a un preset: por resolución o por etiqueta. */
    private fun presetDe(msg: Msg): VideoPreset? {
        val ancho = msg.int(Proto.K_WIDTH, 0)
        val alto = msg.int(Proto.K_HEIGHT, 0)
        if (ancho > 0 && alto > 0) {
            val fps = msg.int(Proto.K_FPS, ajustes.preset.fps).coerceIn(1, 60)
            val kbps = msg.int(Proto.K_KBPS, ajustes.preset.kbps)
            return VideoPresets.all.firstOrNull { it.width == ancho && it.height == alto }
                ?: VideoPreset("${alto}p", ancho, alto, fps, kbps)
        }
        // Respaldo: la etiqueta suele venir en "kind" o en "message".
        val etiqueta = msg.str(Proto.K_KIND).ifEmpty { msg.str(Proto.K_MESSAGE) }
        if (etiqueta.isEmpty()) return null
        return VideoPresets.all.firstOrNull { it.label.equals(etiqueta, ignoreCase = true) }
    }

    private fun aceptarRespuesta(sdpTexto: String) {
        if (sdpTexto.isEmpty()) return
        val conexion = pc ?: return
        conexion.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {}

            override fun onSetSuccess() {
                // Se vuelca en el hilo principal: los ICE también llegan ahí, sin carreras.
                hiloPrincipal.post {
                    descripcionRemotaLista = true
                    volcarCandidatos()
                }
            }

            override fun onCreateFailure(error: String?) {}

            override fun onSetFailure(error: String?) {
                L.e(TAG, "setRemoteDescription falló: $error")
                hiloPrincipal.post { fallar("La respuesta de la tablet no es válida") }
            }
        }, SessionDescription(SessionDescription.Type.ANSWER, sdpTexto))
    }

    private fun aceptarCandidato(msg: Msg) {
        val texto = msg.str(Proto.K_CANDIDATE)
        if (texto.isEmpty()) return
        val candidato = IceCandidate(msg.str(Proto.K_MID, "0"), msg.int(Proto.K_MLINE, 0), texto)
        if (!descripcionRemotaLista) {
            candidatosPendientes.add(candidato)
            return
        }
        pc?.addIceCandidate(candidato)
    }

    private fun volcarCandidatos() {
        val conexion = pc ?: return
        candidatosPendientes.forEach { conexion.addIceCandidate(it) }
        candidatosPendientes.clear()
    }

    private fun enviar(json: String) {
        val canal = socket ?: return
        if (!canal.send(json)) L.w(TAG, "no se pudo enviar el mensaje")
    }

    private fun enviarEstado() {
        enviar(
            Msg.builder(Proto.STATE)
                .put(Proto.K_MUTED, false) // esta versión no envía audio
                .put(Proto.K_TORCH, ajustes.linterna)
                .put(Proto.K_FACING, ajustes.cara)
                .put(Proto.K_MIRROR, ajustes.espejo)
                .put(Proto.K_BATTERY, nivelBateria())
                .put(Proto.K_ROTACION, rotacionCuadro)
                .build()
        )
    }

    // =====================================================================
    // WebRTC (lado emisor)
    // =====================================================================

    private fun arrancarWebRtc() {
        if (pc != null) return // ya está armado para esta conexión

        val eglBase = asegurarEgl() ?: return fallar("No se pudo crear el contexto gráfico (EGL)")
        val fabrica = asegurarFabrica(eglBase) ?: return fallar("No se pudo iniciar WebRTC")

        val capt = crearCapturer()
        if (capt == null) {
            fallar("Este equipo no tiene una cámara utilizable")
            return
        }
        // Sin esta asignación el campo queda nulo y startCapture() sale sin encender la cámara.
        capturer = capt
        // La cámara elegida puede venir con espejo por defecto (frontal).
        aplicarEspejo()
        notificarAjustes()
        val helper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        if (helper == null) {
            fallar("No se pudo preparar el hilo de captura")
            return
        }
        val fuente = fabrica.createVideoSource(capt.isScreencast)
        if (fuente == null) {
            runCatching { helper.dispose() }
            fallar("No se pudo crear la fuente de video")
            return
        }
        // Se cuenta cada frame para reportar un fps real a la tablet.
        capt.initialize(helper, app, ObservadorFrames(fuente.capturerObserver, contadorFrames))

        val pista = fabrica.createVideoTrack(PISTA_VIDEO, fuente)
        if (pista == null) {
            runCatching { fuente.dispose() }
            runCatching { helper.dispose() }
            fallar("No se pudo crear la pista de video")
            return
        }

        surfaceHelper = helper
        videoSource = fuente
        videoTrack = pista
        renderer?.let { pista.addSink(it) }

        // La rotación no viaja en el stream, así que se reporta por señalización:
        // el switcher la usa para girar el cuadro y que no se vea acostado.
        pista.addSink(object : VideoSink {
            override fun onFrame(frame: VideoFrame) {
                val rot = ((frame.rotation % 360) + 360) % 360
                if (rot != rotacionCuadro) {
                    rotacionCuadro = rot
                    L.i(TAG, "rotación de cuadro: $rot")
                    hiloPrincipal.post { enviarEstado() }
                }
            }
        })

        val conexion = crearPeerConnection(fabrica, pista)
        if (conexion == null) {
            runCatching { pista.dispose() }
            videoTrack = null
            fallar("No se pudo crear la conexión WebRTC")
            return
        }
        pc = conexion

        arrancarCaptura(ajustes.preset)
        crearOferta(conexion)
        enviarEstado()
        iniciarTicker()
        L.i(TAG, "WebRTC armado como cámara ${indiceCamara}")
    }

    private fun asegurarEgl(): EglBase? {
        egl?.let { return it }
        return try {
            EglBase.create().also { egl = it }
        } catch (e: Exception) {
            L.e(TAG, "EglBase.create falló", e)
            null
        }
    }

    private fun asegurarFabrica(eglBase: EglBase): PeerConnectionFactory? {
        factory?.let { return it }
        return try {
            if (!fabricaIniciada) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(app)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions()
                )
                fabricaIniciada = true
            }
            PeerConnectionFactory.builder()
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
                .createPeerConnectionFactory()
                .also { factory = it }
        } catch (e: Exception) {
            L.e(TAG, "PeerConnectionFactory falló", e)
            null
        }
    }

    /** Elige cámara (trasera por defecto) con Camera2 y cae a Camera1 si hace falta. */
    private fun crearCapturer(): CameraVideoCapturer? {
        val previo = enumerador
        val nombrePrevio = nombreDispositivoCamara
        if (previo != null && nombrePrevio != null && previo.deviceNames.contains(nombrePrevio)) {
            previo.createCapturer(nombrePrevio, null)?.let { return it }
        }

        val opciones = ArrayList<CameraEnumerator>(2)
        if (runCatching { Camera2Enumerator.isSupported(app) }.getOrDefault(false)) {
            opciones.add(Camera2Enumerator(app))
        }
        opciones.add(Camera1Enumerator(true))

        for (opcion in opciones) {
            val nombres = runCatching { opcion.deviceNames }.getOrNull().orEmpty()
            if (nombres.isEmpty()) continue
            val elegido = nombres.firstOrNull { opcion.isBackFacing(it) } ?: nombres.first()
            val creado = runCatching { opcion.createCapturer(elegido, null) }.getOrNull() ?: continue
            enumerador = opcion
            nombreDispositivoCamara = elegido
            val esFrontal = runCatching { opcion.isFrontFacing(elegido) }.getOrDefault(false)
            ajustes = ajustes.copy(cara = if (esFrontal) "front" else "back", espejo = esFrontal)
            return creado
        }
        return null
    }

    private fun crearPeerConnection(
        fabrica: PeerConnectionFactory,
        pista: VideoTrack
    ): PeerConnection? {
        val configuracion = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
        }
        val conexion = fabrica.createPeerConnection(configuracion, crearObservador()) ?: return null
        conexion.addTrack(pista, listOf(STREAM_VIDEO))
        pc = conexion
        aplicarBitrateEnvio(ajustes.preset)
        return conexion
    }

    private fun crearObservador(): PeerConnection.Observer = object : PeerConnection.Observer {

        override fun onSignalingChange(estado: PeerConnection.SignalingState?) {
            L.i(TAG, "señalización: $estado")
        }

        override fun onIceConnectionChange(estado: PeerConnection.IceConnectionState?) {
            val vivo = estado == PeerConnection.IceConnectionState.CONNECTED ||
                estado == PeerConnection.IceConnectionState.COMPLETED
            L.i(TAG, "ICE: $estado")
            if (vivo) {
                // Los "encodings" del emisor existen recién después de negociar: se aplica
                // aquí y se reintenta un par de veces por si el SDP aún no está listo.
                aplicarBitrateEnvio(ajustes.preset)
                hiloPrincipal.postDelayed({ aplicarBitrateEnvio(ajustes.preset) }, 800)
                hiloPrincipal.postDelayed({ aplicarBitrateEnvio(ajustes.preset) }, 2500)
            }
            hiloPrincipal.post {
                escucha.onVivo(vivo)
                when (estado) {
                    PeerConnection.IceConnectionState.DISCONNECTED ->
                        L.w(TAG, "video interrumpido, la tablet puede reconectar")

                    PeerConnection.IceConnectionState.FAILED ->
                        fallar("Se perdió el video con la tablet. Vuelve a conectar.")

                    else -> Unit
                }
            }
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) {}

        override fun onIceGatheringChange(estado: PeerConnection.IceGatheringState?) {
            L.i(TAG, "recolección ICE: $estado")
        }

        override fun onIceCandidate(candidato: IceCandidate?) {
            if (candidato == null) return
            // Envío directo: FramedSocket.send está sincronizado.
            enviar(
                Msg.builder(Proto.ICE)
                    .put(Proto.K_CANDIDATE, candidato.sdp)
                    // En unified plan el mid siempre viene; el respaldo evita mandar null.
                    .put(Proto.K_MID, candidato.sdpMid ?: "0")
                    .put(Proto.K_MLINE, candidato.sdpMLineIndex)
                    .build()
            )
        }

        override fun onIceCandidatesRemoved(candidatos: Array<out IceCandidate>?) {}

        override fun onAddStream(stream: MediaStream?) {}

        override fun onRemoveStream(stream: MediaStream?) {}

        override fun onDataChannel(canal: DataChannel?) {}

        override fun onRenegotiationNeeded() {}

        override fun onAddTrack(receptor: RtpReceiver?, streams: Array<out MediaStream>?) {}

        // Esta app solo emite: no se esperan pistas remotas.
        override fun onTrack(transceptor: RtpTransceiver?) {}
    }

    private fun crearOferta(conexion: PeerConnection) {
        conexion.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                if (sdp == null) {
                    hiloPrincipal.post { fallar("WebRTC no generó la oferta") }
                    return
                }
                // Se fuerza H.264 para que la tablet decodifique por hardware.
                val mungido = SdpMunger.h264Only(sdp.description)
                val local = SessionDescription(SessionDescription.Type.OFFER, mungido)

                conexion.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(sdp: SessionDescription?) {}

                    override fun onSetSuccess() {
                        val texto = conexion.localDescription?.description ?: mungido
                        enviar(
                            Msg.builder(Proto.SDP)
                                .put(Proto.K_KIND, "offer")
                                .put(Proto.K_SDP, texto)
                                .build()
                        )
                        L.i(TAG, "oferta enviada (${texto.length} caracteres)")
                    }

                    override fun onCreateFailure(error: String?) {}

                    override fun onSetFailure(error: String?) {
                        L.e(TAG, "setLocalDescription falló: $error")
                        hiloPrincipal.post { fallar("WebRTC no pudo fijar la oferta local") }
                    }
                }, local)
            }

            override fun onSetSuccess() {}

            override fun onCreateFailure(error: String?) {
                L.e(TAG, "createOffer falló: $error")
                hiloPrincipal.post { fallar("WebRTC no pudo crear la oferta") }
            }

            override fun onSetFailure(error: String?) {}
        }, MediaConstraints())
    }

    // =====================================================================
    // Captura
    // =====================================================================

    private fun arrancarCaptura(preset: VideoPreset) {
        val capt = capturer ?: return
        if (capturando) {
            aplicarCalidad(preset)
            return
        }
        try {
            capt.startCapture(preset.width, preset.height, preset.fps)
            capturando = true
            ajustes = ajustes.copy(preset = preset)
            notificarAjustes()
            L.i(TAG, "captura iniciada a ${preset.width}x${preset.height}@${preset.fps}")
        } catch (e: Exception) {
            L.e(TAG, "startCapture falló", e)
            fallar("No se pudo encender la cámara: ${e.message ?: "error desconocido"}")
        }
    }

    private fun detenerCaptura() {
        if (!capturando) return
        try {
            capturer?.stopCapture()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            L.w(TAG, "stopCapture falló: ${e.message}")
        }
        capturando = false
        L.i(TAG, "captura detenida")
    }

    /**
     * Fija el techo de bits y la prioridad del envío de video. Sin esto, WebRTC usa su
     * propio estimado (bajo para 720p) y la imagen se pixela en cuanto hay movimiento.
     */
    private fun aplicarBitrateEnvio(preset: VideoPreset) {
        val conexion = pc ?: return
        runCatching {
            conexion.senders.firstOrNull { it.track()?.kind() == "video" }?.let { emisor ->
                val params = emisor.parameters
                params.degradationPreference =
                    org.webrtc.RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
                if (params.encodings.isEmpty()) {
                    L.w(TAG, "aún no hay encodings para fijar el bitrate; se reintentará")
                    return@let
                }
                params.encodings.forEach { codificacion ->
                    codificacion.maxBitrateBps = preset.kbps * 1000
                    codificacion.minBitrateBps = preset.kbps * 1000 / 3
                    codificacion.maxFramerate = preset.fps
                }
                emisor.parameters = params
                L.i(TAG, "envío de video: ${preset.kbps} kbps @ ${preset.fps} fps")
            }
        }.onFailure { L.w(TAG, "no se pudo fijar el bitrate: ${it.message}") }
    }

    private fun aplicarCalidad(preset: VideoPreset) {
        val anterior = ajustes.preset
        ajustes = ajustes.copy(preset = preset)
        aplicarBitrateEnvio(preset)
        if (capturando && preset != anterior) {
            val capt = capturer
            if (capt == null) {
                capturando = false
            } else {
                try {
                    capt.changeCaptureFormat(preset.width, preset.height, preset.fps)
                } catch (e: Exception) {
                    // Si el equipo no soporta el cambio en caliente, se reinicia la captura.
                    L.w(TAG, "changeCaptureFormat falló: ${e.message}")
                    try {
                        capt.stopCapture()
                    } catch (corte: Exception) {
                        L.w(TAG, "stopCapture en el reinicio falló: ${corte.message}")
                    }
                    capturando = false
                    arrancarCaptura(preset)
                }
            }
        }
        notificarAjustes()
    }

    // =====================================================================
    // Linterna
    // =====================================================================

    private fun idCamaraConFlash(): String? {
        if (flashYaBuscado) return idCamaraFlash
        flashYaBuscado = true
        idCamaraFlash = try {
            val gestor = app.getSystemService(CameraManager::class.java)
            gestor?.cameraIdList?.firstOrNull { id ->
                val caracteristicas = gestor.getCameraCharacteristics(id)
                val tieneFlash = caracteristicas.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                val esTrasera =
                    caracteristicas.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                tieneFlash && esTrasera
            }
        } catch (e: Exception) {
            L.w(TAG, "no se pudo consultar el flash: ${e.message}")
            null
        }
        return idCamaraFlash
    }

    private fun apagarLinterna() {
        val id = idCamaraConFlash()
        val estabaEncendida = ajustes.linterna
        if (estabaEncendida && id != null) {
            try {
                app.getSystemService(CameraManager::class.java)?.setTorchMode(id, false)
            } catch (e: Exception) {
                L.w(TAG, "no se pudo apagar la linterna: ${e.message}")
            }
        }
        if (estabaEncendida) {
            ajustes = ajustes.copy(linterna = false)
            notificarAjustes()
        }
    }

    // =====================================================================
    // Estado, métricas y cierre
    // =====================================================================

    private fun cambiarEstado(nuevo: EstadoEnlace, mensaje: String) {
        estado = nuevo
        if (nuevo != EstadoEnlace.CONECTADA) escucha.onVivo(false)
        escucha.onEstado(nuevo, mensaje)
    }

    private fun fallar(mensaje: String) {
        L.w(TAG, "error: $mensaje")
        cerrarCanal()
        cambiarEstado(EstadoEnlace.ERROR, mensaje)
    }

    private fun alPerderConexion() {
        cerrarCanal()
        cambiarEstado(EstadoEnlace.ERROR, "Se perdió la conexión con la tablet")
    }

    /** Suelta canal, cámara y PeerConnection pero conserva EglBase y la fábrica. */
    private fun cerrarCanal() {
        detenerTicker()
        detenerCaptura()
        apagarLinterna()

        val canal = socket
        socket = null
        runCatching { canal?.close() }
        hiloLectura = null

        runCatching { videoTrack?.let { pista -> renderer?.let { pista.removeSink(it) } } }
        runCatching { videoTrack?.dispose() }
        videoTrack = null
        runCatching { surfaceHelper?.dispose() }
        surfaceHelper = null
        runCatching { videoSource?.dispose() }
        videoSource = null
        runCatching { capturer?.dispose() }
        capturer = null
        runCatching {
            pc?.close()
            pc?.dispose()
        }
        pc = null

        candidatosPendientes.clear()
        descripcionRemotaLista = false
        contadorFrames.set(0)
        notificarAjustes()
    }

    private fun notificarAjustes() = escucha.onAjustes(ajustes)

    private fun aplicarEspejo() {
        renderer?.setMirror(ajustes.espejo)
    }

    private fun nivelBateria(): Int {
        return try {
            val gestor: BatteryManager? = app.getSystemService(BatteryManager::class.java)
            val porcentaje = gestor?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            if (porcentaje in 0..100) porcentaje else -1
        } catch (e: Exception) {
            -1
        }
    }

    // ---- Ticker de STATS (cada 2 s) ----

    private val tareaTicker = object : Runnable {
        override fun run() {
            if (socket == null) return
            val frames = contadorFrames.getAndSet(0)
            val fps = (frames * 1000L / INTERVALO_STATS_MS).toInt()
            enviar(
                Msg.builder(Proto.STATS)
                    .put(Proto.K_FPS, fps)
                    // Bitrate estimado: el preset es el objetivo real del encode.
                    .put(Proto.K_KBPS, ajustes.preset.kbps)
                    .build()
            )
            val bateria = nivelBateria()
            if (bateria != ultimaBateria) {
                ultimaBateria = bateria
                enviarEstado()
            }
            hiloPrincipal.postDelayed(this, INTERVALO_STATS_MS)
        }
    }

    private fun iniciarTicker() {
        hiloPrincipal.removeCallbacks(tareaTicker)
        hiloPrincipal.postDelayed(tareaTicker, INTERVALO_STATS_MS)
    }

    private fun detenerTicker() {
        hiloPrincipal.removeCallbacks(tareaTicker)
    }

    /** Cuenta frames para reportar fps real sin tocar el flujo de la captura. */
    private class ObservadorFrames(
        private val base: CapturerObserver,
        private val contador: AtomicInteger
    ) : CapturerObserver {

        override fun onCapturerStarted(exito: Boolean) = base.onCapturerStarted(exito)

        override fun onCapturerStopped() = base.onCapturerStopped()

        override fun onFrameCaptured(frame: VideoFrame) {
            contador.incrementAndGet()
            base.onFrameCaptured(frame)
        }
    }

    private companion object {
        const val TAG = "cam"
        const val PISTA_VIDEO = "video0"
        const val STREAM_VIDEO = "stream0"
        const val TIMEOUT_CONEXION_MS = 6000
        const val INTERVALO_STATS_MS = 2000L

        /** `PeerConnectionFactory.initialize` solo debe correr una vez por proceso. */
        @Volatile
        var fabricaIniciada = false
    }
}
