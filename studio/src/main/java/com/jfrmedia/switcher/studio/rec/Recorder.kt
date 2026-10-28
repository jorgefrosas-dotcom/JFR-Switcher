package com.jfrmedia.switcher.studio.rec

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.Build
import android.view.Surface
import com.jfrmedia.switcher.core.util.L
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Grabador MP4: video H.264 codificado desde la superficie del compositor
 * + audio AAC del micrófono de la tablet.
 *
 * Dos detalles que costaron sangre y están resueltos aquí:
 *  1. Los hilos de codificación se lanzan DESPUÉS de activar `running` (si no, mueren al instante).
 *  2. La pista de audio se agrega al muxer solo cuando llega su primera muestra real.
 *     Si se agrega antes y no entra audio, MediaMuxer.stop() falla y el MP4 queda sin índice
 *     (archivo que ningún reproductor abre).
 */
class Recorder(private val context: Context) {

    var onError: ((String) -> Unit)? = null

    @Volatile
    var isRecording = false
        private set

    var outputFile: File? = null
        private set

    /** Marca de tiempo base (ns) para que el video empiece en 0. */
    var baseTimeNs: Long = 0L
        private set

    /** Nivel RMS del micrófono (0..1) para el vúmetro. */
    @Volatile
    var lastLevel: Float = 0f

    /**
     * Pico real del micrófono (0..1, sin amplificar) para el monitor de onda: es el que
     * dice si la entrada se está acercando al recorte (0 dBFS).
     */
    @Volatile
    var lastPeak: Float = 0f
        private set

    /**
     * Nombre del dispositivo que el sistema entregó de verdad (puede no ser el elegido si
     * el aparato no está disponible). Sirve para confirmar la fuente en el monitor.
     */
    @Volatile
    var entradaReal: String? = null
        private set

    /** Ganancia lineal aplicada al PCM antes de codificar (1 = sin cambios). */
    @Volatile
    var ganancia: Float = 1f

    /**
     * true cuando la entrada elegida es externa (USB, diadema o Bluetooth): hay que capturar con
     * VOICE_COMMUNICATION (las fuentes de "media" como CAMCORDER no siguen el ruteo
     * del dispositivo de comunicación y el micrófono Bluetooth se oye en silencio).
     */
    @Volatile
    var entradaExterna: Boolean = false

    /** Ecualizador de entrada que se aplica antes de codificar. */
    @Volatile
    var eqEntrada: com.jfrmedia.switcher.studio.audio.AudioEq? = null

    /** Salida de monitoreo (recibe el mismo audio ya procesado). */
    @Volatile
    var monitor: com.jfrmedia.switcher.studio.audio.AudioMonitor? = null

    /** Si está activo se manda silencio, sin detener la grabación. */
    @Volatile
    var silencio: Boolean = false

    @Volatile
    private var running = false

    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var muxer: MediaMuxer? = null

    private var videoTrack = -1
    private var audioTrack = -1
    private var muxerStarted = false
    private var videoFormatReady = false
    private var audioFormat: MediaFormat? = null

    private var videoThread: Thread? = null
    private var audioThread: Thread? = null

    private var audioRecord: AudioRecord? = null
    private var audioBufferSize = 0

    @Volatile
    private var audioFailed = false

    private val muxerLock = Any()
    private val pendingVideo = ArrayDeque<Pair<ByteArray, Long>>()
    private val info = MediaCodec.BufferInfo()

    /** Se pone al cerrar: a partir de ahí no se acepta ninguna muestra más en el muxer. */
    @Volatile
    private var cerrando = false

    /** El muxer ya arrancó y la cola de video espera la primera muestra de audio. */
    private var videoPendienteDeVolcar = false

    // Resumen para el registro: qué rango cubrió cada pista de verdad.
    private var vPrimeraUs = Long.MIN_VALUE
    private var vUltimaUs = 0L
    private var vCuadros = 0
    private var aPrimeraUs = Long.MIN_VALUE
    private var aUltimaUs = 0L
    private var aMuestras = 0

    private fun anotarVideo(us: Long) {
        if (vPrimeraUs == Long.MIN_VALUE) vPrimeraUs = us
        vUltimaUs = us
        vCuadros++
    }

    private fun anotarAudio(us: Long) {
        if (aPrimeraUs == Long.MIN_VALUE) aPrimeraUs = us
        aUltimaUs = us
        aMuestras++
    }

    private fun seg(us: Long): String =
        if (us == Long.MIN_VALUE) "—" else String.format(Locale.US, "%.2f", us / 1_000_000.0) + "s"

    var inputSurface: Surface? = null
        private set

    private var width = 1920
    private var height = 1080
    private var bitrate = 8_000_000
    private var fps = 30

    @Volatile
    private var withAudio = true

    /** Prepara codificadores y devuelve la superficie donde el compositor debe dibujar. */
    fun start(w: Int, h: Int, fpsTarget: Int, bitrateKbps: Int, audio: Boolean): Surface? {
        if (isRecording) return inputSurface
        width = w
        height = h
        fps = fpsTarget.coerceIn(15, 60)
        bitrate = bitrateKbps * 1000
        withAudio = audio
        audioFailed = false

        return try {
            val dir = File(context.getExternalFilesDir(null), "Grabaciones")
            if (!dir.exists()) dir.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(dir, "JFR_$stamp.mp4")
            outputFile = file

            muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            baseTimeNs = System.nanoTime()

            running = true
            isRecording = true

            // El codificador de video arranca primero; el de audio, enseguida después.
            startVideo()
            if (withAudio && !startAudio()) {
                withAudio = false
                audioFailed = true
                L.w("rec", "sin micrófono: se graba solo video")
            }

            L.i("rec", "grabando ${file.absolutePath} (${width}x$height, audio=$withAudio)")
            inputSurface
        } catch (t: Throwable) {
            L.e("rec", "no se pudo iniciar la grabación: ${t.message}", t)
            onError?.invoke("No se pudo iniciar la grabación: ${t.message}")
            running = false
            isRecording = false
            cleanup()
            // Si la resolución no la acepta el codificador, no se deja el MP4 vacío tirado
            // (la pantalla reintenta con una resolución menor).
            runCatching { outputFile?.let { if (it.length() == 0L) it.delete() } }
            null
        }
    }

    // ------------------------------------------------------------------ video

    private fun startVideo() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
        codec.start()
        // Se pide un fotograma clave de entrada: el MP4 debe empezar en un IDR, y así el muxer
        // no espera hasta el siguiente (con I_FRAME_INTERVAL a 2 s se perdían los primeros ~2 s).
        runCatching {
            codec.setParameters(android.os.Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        }
        videoCodec = codec
        L.i("rec", "codificador de video: ${codec.name}")
        videoThread = Thread({ videoLoop() }, "rec-video").also { it.start() }
    }

    private fun videoLoop() {
        val codec = videoCodec ?: return
        val bufferInfo = MediaCodec.BufferInfo()
        // Al cerrar se deja de dibujar, pero el codificador todavía tiene cuadros dentro:
        // se sigue drenando hasta que devuelva el fin de flujo (o 1 s de margen).
        var cerrandoDesde = 0L
        // Cuántos cuadros han salido del codificador: sirve para insistir con el fotograma clave.
        var cuadros = 0
        while (true) {
            if (!running) {
                if (cerrandoDesde == 0L) cerrandoDesde = System.nanoTime()
                if (System.nanoTime() - cerrandoDesde > 1_000_000_000L) break
            }
            try {
                val index = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        synchronized(muxerLock) {
                            videoTrack = muxer?.addTrack(codec.outputFormat) ?: -1
                            videoFormatReady = true
                            maybeStartMuxer()
                        }
                    }

                    index >= 0 -> {
                        // Se insiste con el pedido de fotograma clave durante el primer segundo:
                        // el codificador de la tablet no atiende el primero y sin un IDR temprano
                        // el muxer descarta todo el tramo inicial (se probó y con uno solo no basta).
                        if (bufferInfo.size > 0 && cuadros <= 30 &&
                            cuadros % 5 == 0 &&
                            bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME == 0
                        ) {
                            runCatching {
                                codec.setParameters(android.os.Bundle().apply {
                                    putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                                })
                            }
                            if (cuadros == 0) {
                                L.i("rec", "se insiste con el fotograma clave para no perder el arranque")
                            }
                        }
                        if (bufferInfo.size > 0) cuadros++
                        val buf = codec.getOutputBuffer(index)
                        if (buf != null && bufferInfo.size > 0) writeVideo(buf, bufferInfo)
                        val eos = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(index, false)
                        if (eos) break
                    }
                }
            } catch (t: Throwable) {
                L.e("rec", "video: ${t.message}", t)
                break
            }
        }
    }

    /**
     * Última marca de tiempo escrita en la pista de video.
     *
     * Las del codificador vienen del reloj de pared (`nanoTime` del compositor) y pueden repetirse
     * o retroceder unos microsegundos. MediaMuxer descarta en silencio esas muestras: de 267 cuadros
     * enviados quedaban 211 en el archivo, con el video empezando 2 segundos tarde.
     * Aquí se garantiza que siempre avance.
     */
    private var ultimoPtsVideo = -1L

    private fun ptsMonotono(us: Long): Long {
        val pts = if (us > ultimoPtsVideo) us else ultimoPtsVideo + 1
        ultimoPtsVideo = pts
        return pts
    }

    private fun writeVideo(buf: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
        val pts = ptsMonotono(bufferInfo.presentationTimeUs)
        bufferInfo.presentationTimeUs = pts
        synchronized(muxerLock) {
            if (cerrando) return
            if (!muxerStarted) {
                if (pendingVideo.size > 240) pendingVideo.pollFirst()
                val copy = ByteArray(bufferInfo.size)
                buf.position(bufferInfo.offset)
                buf.get(copy, 0, bufferInfo.size)
                pendingVideo.addLast(copy to pts)
                return
            }
            buf.position(bufferInfo.offset)
            buf.limit(bufferInfo.offset + bufferInfo.size)
            try {
                muxer?.writeSampleData(videoTrack, buf, bufferInfo)
                anotarVideo(pts)
            } catch (t: Throwable) {
                L.e("rec", "escribir video: ${t.message}", t)
            }
        }
    }

    // ------------------------------------------------------------------ audio

    private fun startAudio(): Boolean {
        return try {
            val sampleRate = 44100
            val channels = 1
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) return false
            audioBufferSize = minBuf.coerceAtLeast(4096) * 2
            // Con Bluetooth el enlace SCO tarda en subir: se le da un margen antes de abrir.
            if (entradaExterna) {
                try {
                    Thread.sleep(450)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            val fuente = if (entradaExterna) {
                MediaRecorder.AudioSource.VOICE_COMMUNICATION
            } else {
                MediaRecorder.AudioSource.CAMCORDER
            }
            L.i("rec", "fuente de audio: ${if (entradaExterna) "VOICE_COMMUNICATION (entrada externa)" else "CAMCORDER (interna)"}")
            val rec = AudioRecord(
                fuente,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                audioBufferSize
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                return false
            }
            audioRecord = rec

            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            audioCodec = codec
            rec.startRecording()
            // Qué dispositivo está usando el sistema de verdad (puede no ser el elegido).
            runCatching {
                entradaReal = rec.routedDevice?.let {
                    com.jfrmedia.switcher.studio.audio.AudioRuta.nombreDeDispositivo(it)
                }
                L.i("rec", "entrada real: ${entradaReal ?: "?"}")
            }
            audioThread = Thread({ audioLoop(sampleRate) }, "rec-audio").also { it.start() }
            true
        } catch (t: Throwable) {
            L.w("rec", "audio deshabilitado: ${t.message}")
            runCatching { audioRecord?.release() }
            audioRecord = null
            false
        }
    }

    /**
     * Procesa el bloque PCM antes de encodificarlo: silencio, ecualizador de entrada y
     * ganancia. Después lo manda al monitoreo para poder escucharlo.
     */
    private fun procesarPcm(pcm: ByteArray, bytes: Int) {
        if (silencio) {
            java.util.Arrays.fill(pcm, 0, bytes, 0.toByte())
            monitor?.escribir(pcm, bytes)
            return
        }
        val eq = eqEntrada
        if (eq != null && eq.activo) {
            eq.procesar(pcm, bytes, ganancia)
        } else if (ganancia != 1f) {
            aplicarGanancia(pcm, bytes, ganancia)
        }
        monitor?.escribir(pcm, bytes)
    }

    private fun aplicarGanancia(pcm: ByteArray, bytes: Int, g: Float) {
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

    private fun audioLoop(sampleRate: Int) {
        val codec = audioCodec ?: return
        val rec = audioRecord ?: return
        val bufferInfo = MediaCodec.BufferInfo()
        val pcm = ByteArray(audioBufferSize)
        var totalSamples = 0L
        var silentReads = 0

        while (running) {
            try {
                val read = rec.read(pcm, 0, pcm.size)
                if (read <= 0) {
                    if (++silentReads > 200) {
                        audioFailed = true
                        L.w("rec", "el micrófono no entrega datos: sigo solo con video")
                        break
                    }
                    continue
                }
                silentReads = 0
                procesarPcm(pcm, read)
                lastLevel = computeLevel(pcm, read)
                val ptsUs = totalSamples * 1_000_000L / sampleRate
                totalSamples += read / 2

                val inIndex = codec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val inBuf = codec.getInputBuffer(inIndex) ?: continue
                    inBuf.clear()
                    inBuf.put(pcm, 0, read)
                    codec.queueInputBuffer(inIndex, 0, read, ptsUs, 0)
                }

                while (true) {
                    val outIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        synchronized(muxerLock) {
                            audioFormat = codec.outputFormat
                            maybeStartMuxer()
                        }
                    } else if (outIndex >= 0) {
                        escribirAudio(codec, outIndex, bufferInfo)
                        codec.releaseOutputBuffer(outIndex, false)
                    } else {
                        break
                    }
                }
            } catch (t: Throwable) {
                L.e("rec", "audio: ${t.message}", t)
                break
            }
        }

        // --- cierre: el codificador todavía guarda muestras; se vacían antes de cerrar el muxer.
        // Sin esto el MP4 terminaba el audio antes que el video y el último cuadro AAC quedaba
        // cortado (ffprobe: «Input buffer exhausted before END element found»).
        var vaciadas = 0
        // 1) Lo que quedó en el búfer del micrófono: hasta ~150 ms que antes se perdían con el
        //    corte, y por eso el audio terminaba antes que el video.
        val finVaciado = System.nanoTime() + 250_000_000L
        while (vaciadas < 8 && System.nanoTime() < finVaciado) {
            val leido = try {
                rec.read(pcm, 0, pcm.size)
            } catch (t: Throwable) {
                -1
            }
            if (leido <= 0) break
            procesarPcm(pcm, leido)
            lastLevel = computeLevel(pcm, leido)
            val ptsUs = totalSamples * 1_000_000L / sampleRate
            totalSamples += leido / 2
            val inIndex = codec.dequeueInputBuffer(50_000)
            if (inIndex < 0) break
            val inBuf = codec.getInputBuffer(inIndex) ?: break
            inBuf.clear()
            inBuf.put(pcm, 0, leido)
            codec.queueInputBuffer(inIndex, 0, leido, ptsUs, 0)
            vaciadas++
        }
        if (vaciadas > 0) L.i("rec", "audio: $vaciadas lectura(s) del micrófono recuperadas al cerrar")

        var eos = false
        runCatching {
            val inIndex = codec.dequeueInputBuffer(100_000)
            if (inIndex >= 0) {
                codec.queueInputBuffer(
                    inIndex, 0, 0,
                    totalSamples * 1_000_000L / sampleRate,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )
                eos = true
            }
        }
        var muestrasCierre = 0
        val limite = System.nanoTime() + 500_000_000L
        while (eos && System.nanoTime() < limite) {
            val outIndex = codec.dequeueOutputBuffer(bufferInfo, 20_000)
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> synchronized(muxerLock) {
                    audioFormat = codec.outputFormat
                    maybeStartMuxer()
                }

                outIndex >= 0 -> {
                    if (bufferInfo.size > 0) {
                        escribirAudio(codec, outIndex, bufferInfo)
                        muestrasCierre++
                    }
                    val fin = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(outIndex, false)
                    if (fin) break
                }
            }
        }
        if (muestrasCierre > 0) L.i("rec", "audio: $muestrasCierre muestra(s) recuperadas al cerrar")
    }

    /** Escribe una muestra de audio ya codificada (o la descarta si el muxer aún no arranca). */
    private fun escribirAudio(codec: MediaCodec, outIndex: Int, bufferInfo: MediaCodec.BufferInfo) {
        val outBuf = codec.getOutputBuffer(outIndex) ?: return
        if (bufferInfo.size <= 0) return
        synchronized(muxerLock) {
            if (cerrando) return
            // La pista se agrega hasta tener una muestra real que escribir.
            if (audioTrack < 0) {
                val fmt = audioFormat ?: codec.outputFormat
                audioTrack = muxer?.addTrack(fmt) ?: -1
                maybeStartMuxer()
            }
            if (muxerStarted && audioTrack >= 0) {
                outBuf.position(bufferInfo.offset)
                outBuf.limit(bufferInfo.offset + bufferInfo.size)
                try {
                    muxer?.writeSampleData(audioTrack, outBuf, bufferInfo)
                    anotarAudio(bufferInfo.presentationTimeUs)
                    // Con la primera muestra de audio ya escrita se puede volcar el video.
                    if (videoPendienteDeVolcar) volcarVideoPendiente()
                } catch (t: Throwable) {
                    L.e("rec", "escribir audio: ${t.message}", t)
                }
            }
        }
    }

    private fun computeLevel(pcm: ByteArray, len: Int): Float {
        var sum = 0.0
        var pico = 0.0
        var n = 0
        var i = 0
        while (i + 1 < len) {
            val sample = ((pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)).toShort().toInt()
            val v = sample.toDouble() / 32768.0
            sum += v * v
            val abs = kotlin.math.abs(v)
            if (abs > pico) pico = abs
            n++
            i += 2
        }
        if (n == 0) return 0f
        lastPeak = pico.toFloat().coerceIn(0f, 1f)
        return (kotlin.math.sqrt(sum / n) * 4.5).toFloat().coerceIn(0f, 1f)
    }

    // ------------------------------------------------------------------ muxer

    private fun maybeStartMuxer() {
        if (muxerStarted) return
        val videoOk = videoFormatReady && videoTrack >= 0
        if (!videoOk) return
        // Si hay audio se espera a su primera muestra; si el micrófono falló, se arranca sin audio.
        if (withAudio && !audioFailed && audioTrack < 0) return
        try {
            muxer?.start()
            muxerStarted = true
            // La cola de video NO se vuelca aquí: se espera a que el audio escriba su primera
            // muestra. Con el orden contrario (video en 0.24 s antes que audio en 0) MediaMuxer
            // descartaba los primeros ~1.9 s de video; el archivo empezaba en el cuadro clave
            // de los 2 s y se perdía el arranque de la grabación.
            videoPendienteDeVolcar = true
            if (!withAudio || aMuestras > 0) volcarVideoPendiente()
        } catch (t: Throwable) {
            L.e("rec", "no se pudo iniciar el muxer: ${t.message}", t)
            onError?.invoke("No se pudo iniciar el archivo de video")
        }
    }

    /** Escribe los cuadros de video que esperaban a que el muxer arrancara. */
    private fun volcarVideoPendiente() {
        if (!videoPendienteDeVolcar) return
        videoPendienteDeVolcar = false
        var escritas = 0
        while (pendingVideo.isNotEmpty()) {
            val (bytes, pts) = pendingVideo.pollFirst() ?: break
            info.set(0, bytes.size, pts, 0)
            try {
                muxer?.writeSampleData(videoTrack, ByteBuffer.wrap(bytes), info)
                anotarVideo(pts)
                escritas++
            } catch (t: Throwable) {
                L.e("rec", "escribir video en cola: ${t.message}", t)
            }
        }
        L.i("rec", "muxer iniciado (video=$videoTrack audio=$audioTrack, $escritas cuadros en cola)")
    }

    // ------------------------------------------------------------------ fin

    fun stop() {
        if (!isRecording && !running) return
        running = false
        isRecording = false

        // 1) Se le pide al codificador de video su fin de flujo.
        runCatching { videoCodec?.signalEndOfInputStream() }

        // 2) El hilo de audio se cierra solo: vacía el búfer del micrófono, manda su EOS y
        //    escribe las últimas muestras. Al micrófono se le da la vuelta DESPUÉS, para que
        //    esas lecturas finales todavía reciban datos. Antes se cerraba el muxer con
        //    escrituras en vuelo y se perdía el último tramo.
        esperarHilo(audioThread, 3000)
        runCatching { audioRecord?.stop() }
        esperarHilo(videoThread, 3000)

        // 3) Recién ahora se cierra el archivo, y ya sin escrituras en vuelo.
        synchronized(muxerLock) {
            cerrando = true
            if (muxerStarted) {
                try {
                    muxer?.stop()
                } catch (t: Throwable) {
                    L.e("rec", "al cerrar el MP4: ${t.message}", t)
                }
            } else {
                L.w("rec", "el muxer nunca arrancó: el archivo quedará vacío")
            }
        }
        L.i(
            "rec",
            "resumen: video ${seg(vPrimeraUs)}→${seg(vUltimaUs)} ($vCuadros cuadros) · " +
                "audio ${seg(aPrimeraUs)}→${seg(aUltimaUs)} ($aMuestras muestras)"
        )
        cleanup()
        L.i("rec", "grabación detenida: ${outputFile?.absolutePath}")
    }

    private fun esperarHilo(hilo: Thread?, ms: Long) {
        if (hilo == null) return
        try {
            hilo.join(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun cleanup() {
        isRecording = false
        runCatching { videoCodec?.stop() }
        runCatching { videoCodec?.release() }
        videoCodec = null
        runCatching { audioCodec?.stop() }
        runCatching { audioCodec?.release() }
        audioCodec = null
        runCatching { audioRecord?.release() }
        audioRecord = null
        runCatching { muxer?.release() }
        muxer = null
        synchronized(muxerLock) {
            muxerStarted = false
            videoFormatReady = false
            audioFormat = null
            videoTrack = -1
            audioTrack = -1
            pendingVideo.clear()
        }
        runCatching { inputSurface?.release() }
        inputSurface = null
        lastLevel = 0f
        lastPeak = 0f
        entradaReal = null
        cerrando = false
        videoPendienteDeVolcar = false
        ultimoPtsVideo = -1L
        vPrimeraUs = Long.MIN_VALUE
        vUltimaUs = 0L
        vCuadros = 0
        aPrimeraUs = Long.MIN_VALUE
        aUltimaUs = 0L
        aMuestras = 0
    }
}
