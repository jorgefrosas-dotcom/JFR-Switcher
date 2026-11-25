package com.jfrmedia.switcher.studio

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.graphics.RectF
import android.widget.EditText
import android.widget.CheckBox
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.jfrmedia.switcher.core.media.VideoPreset
import com.jfrmedia.switcher.core.media.VideoPresets
import com.jfrmedia.switcher.core.net.Net
import com.jfrmedia.switcher.core.proto.Msg
import com.jfrmedia.switcher.core.proto.Proto
import com.jfrmedia.switcher.core.upd.Actualizaciones
import com.jfrmedia.switcher.core.util.L
import com.jfrmedia.switcher.studio.canvas.AspectFrameLayout
import com.jfrmedia.switcher.studio.canvas.CanvasItem
import com.jfrmedia.switcher.studio.canvas.CamItem
import com.jfrmedia.switcher.studio.canvas.CompositorView
import com.jfrmedia.switcher.studio.canvas.ImageItem
import com.jfrmedia.switcher.studio.canvas.MediaItem
import com.jfrmedia.switcher.studio.canvas.Scene
import com.jfrmedia.switcher.studio.canvas.TextoItem
import com.jfrmedia.switcher.studio.databinding.ActivityStudioBinding
import com.jfrmedia.switcher.studio.databinding.DialogPairingBinding
import com.jfrmedia.switcher.studio.audio.AudioEq
import com.jfrmedia.switcher.studio.audio.AudioMonitor
import com.jfrmedia.switcher.studio.audio.AudioRuta
import com.jfrmedia.switcher.studio.audio.AudioVivo
import com.jfrmedia.switcher.studio.databinding.DialogAudioBinding
import com.jfrmedia.switcher.studio.databinding.DialogSettingsBinding
import com.jfrmedia.switcher.studio.databinding.DialogTransicionesBinding
import com.jfrmedia.switcher.studio.rec.Recorder
import com.jfrmedia.switcher.studio.rtc.CamState
import com.jfrmedia.switcher.studio.rtc.CameraHub
import com.jfrmedia.switcher.studio.rtc.CameraSession
import com.jfrmedia.switcher.studio.rtc.LocalCam
import com.jfrmedia.switcher.studio.rtc.RtcCore
import com.jfrmedia.switcher.studio.rtc.StudioServer
import com.jfrmedia.switcher.studio.tcg.Banners
import com.jfrmedia.switcher.studio.tcg.EstadoTcg
import com.jfrmedia.switcher.studio.tcg.JugadorEvento
import com.jfrmedia.switcher.studio.tcg.JugadorTcg
import com.jfrmedia.switcher.studio.tcg.PanelTcg
import com.jfrmedia.switcher.studio.ui.OverlayTileView
import com.jfrmedia.switcher.studio.ui.SourceTileView
import org.webrtc.VideoTrack
import java.io.File
import java.util.Locale
import com.jfrmedia.switcher.studio.plantillas.AjustesPlantilla
import com.jfrmedia.switcher.studio.plantillas.TransicionPlantilla
import com.jfrmedia.switcher.studio.plantillas.MedioPlantilla
import com.jfrmedia.switcher.studio.texto.Entradas
import com.jfrmedia.switcher.studio.texto.Fuentes
import com.jfrmedia.switcher.studio.texto.TextoGuardado
import com.jfrmedia.switcher.studio.texto.Textos
import com.jfrmedia.switcher.studio.plantillas.CamPlantilla
import com.jfrmedia.switcher.studio.plantillas.ImagenPlantilla
import com.jfrmedia.switcher.studio.plantillas.Plantilla
import com.jfrmedia.switcher.studio.plantillas.Plantillas
import com.jfrmedia.switcher.studio.plantillas.TextoPlantilla

/**
 * Pantalla del director: 6 fuentes, lienzo de programa con 2 capas + imágenes,
 * grabación local en MP4 y emparejamiento de celulares por WiFi.
 */
class StudioActivity : AppCompatActivity(), CompositorView.Listener {

    private lateinit var b: ActivityStudioBinding
    private val tiles = ArrayList<SourceTileView>(6)
    private lateinit var recorder: Recorder

    private val hub = CameraHub()
    private val server = StudioServer(hub)
    /**
     * Handler de la interfaz.
     *
     * OJO con el nombre: **no** puede llamarse `handler`. Dentro de un `apply {}` de una vista,
     * `handler` se resuelve al `getHandler()` de la vista, que es nulo mientras no está pegada a
     * la ventana, y la app se caía con «Attempt to invoke virtual method postDelayed on a null
     * object reference» (pasaba al guardar una plantilla).
     */
    private val uiHandler = Handler(Looper.getMainLooper())

    private val localCam = LocalCam()
    /** Segunda cámara del propio equipo: en el celular se usan las dos a la vez. */
    private val localCam2 = LocalCam().apply { slot = LocalCam.SLOT_FRONT }

    private var camA: Int? = null
    private var camB: Int? = null
    private var activeLayer = 'A'

    /** El estado real lo manda el emisor: el botón no puede decir «apagado» si está enviando. */
    private val transmitiendo: Boolean
        get() = emisor.transmitiendo

    /** Modo PiP: con dos capas. Apagado, el visor muestra una sola cámara. */
    private var pipActivo = false

    /** Modo multicámara: pantalla partida 50/50 (izq/der o arriba/abajo según el lienzo). */
    private var splitActivo = false
    private var pipCorner = 3

    private var preset: VideoPreset = VideoPresets.default

    /** Si está activa, la calidad sube a 720p con pocas cámaras y baja con muchas. */
    private var calidadAuto = true

    /** false = plantilla horizontal (16:9), true = vertical (9:16). */
    private var vertical = false

    /** true = Modo Pokémon TCG: 16:9 fijo, sin rotación y con el banner del torneo en el video. */
    private var modoTcg = false
    private var estadoTcg = EstadoTcg()
    private var aplicandoRectTcg = false
    private var bannerTcg: CanvasItem? = null
    /** Chips Energy / Supporter / Retreat dibujados dentro de la cámara de cada jugador. */
    private var chipsTcgUno: List<ImageItem> = emptyList()
    private var chipsTcgDos: List<ImageItem> = emptyList()
    /** Última posición de cada chip, para animar el que se acaba de usar. */
    private val posChipPrevia = HashMap<String, RectF>()
    private var panelTcg: PanelTcg? = null

    /** true = grabar en 1080p/1080x1920, false = 720p/720x1280. */
    private var grabarAlta = false
    private var recAudio = true

    /** Modo de la sesión: "grabar", "transmitir" o "ambos". */
    private var modoSesion = "ambos"

    /** Lado corto de grabación elegido: 360, 480, 720, 1080, 1440 (2K) o 2160 (4K). */
    private var recAltoElegido = 720

    /** Bitrate de grabación en kbps. */
    private var recBitrateKbps = 12000

    /** Resolución objetivo de la transmisión (alto en px). */
    private var streamAlto = 720
    private var streamBitrateKbps = 4000
    private var bitrateAuto = true

    /**
     * Tamaño real de grabación según la calidad elegida y la orientación.
     *
     * El número elegido es el **lado corto**, igual en las dos orientaciones:
     * `720` → 1280×720 (horizontal) / 720×1280 (vertical); `1080` → 1920×1080 / 1080×1920.
     * Antes en vertical se tomaba como *alto del lienzo* y salía 404×720: un tercio del tamaño.
     * El compositor dibuja la escena entera en ese tamaño, así que la conversión
     * (incluido el reescalado si la fuente es menor) es automática.
     */
    private fun tamanoGrabacion(ladoCorto: Int): Pair<Int, Int> {
        val corto = ladoCorto.coerceIn(360, 2160)
        val largo = corto * 16 / 9
        return if (lienzoVertical) {
            (corto / 2 * 2) to (largo / 2 * 2)
        } else {
            (largo / 2 * 2) to (corto / 2 * 2)
        }
    }

    /**
     * El lienzo es vertical si se eligió 9:16 **o** si está puesto el Modo Studio en 9:16.
     * Así la grabación, la transmisión y las tarjetas salen en el mismo formato que los monitores.
     */
    private val lienzoVertical: Boolean
        get() = if (modoStudio) studioVertical else vertical

    /**
     * Forma de las tarjetas de fuente (cámaras, overlays y medios).
     *
     * Siguen el formato del lienzo, pero en el Modo Studio vertical se aplanan un poco (3:4 en vez
     * de 9:16): así las 6 cámaras quedan más bajas y los monitores se llevan más alto.
     */
    private val aspectoFuente: Float
        get() = when {
            !lienzoVertical -> 16f / 9f
            modoStudio -> 3f / 4f
            else -> 9f / 16f
        }

    private val recAncho: Int get() = tamanoGrabacion(recAltoElegido).first
    private val recAlto: Int get() = tamanoGrabacion(recAltoElegido).second

    private val recBitrate: Int
        get() = if (bitrateAuto) bitrateGrabacionAuto(recAltoElegido) else recBitrateKbps

    /**
     * Bitrate de grabación recomendado (kbps) para H.264 según el lado corto del cuadro.
     * Es más generoso que un vídeo casual porque aquí hay movimiento de cámara y texto nítido.
     */
    private fun bitrateGrabacionAuto(ladoCorto: Int): Int = when {
        ladoCorto >= 2160 -> 45000
        ladoCorto >= 1440 -> 22000
        ladoCorto >= 1080 -> 12000
        ladoCorto >= 720 -> 7000
        ladoCorto >= 480 -> 4000
        else -> 2500
    }

    /**
     * Bitrate de emisión recomendado (kbps): más bajo que el de grabación porque tiene que
     * caber en la subida del WiFi y en lo que acepta Facebook.
     */
    private fun bitrateEmisionAuto(ladoCorto: Int): Int = when {
        ladoCorto >= 1080 -> 8000
        ladoCorto >= 720 -> 4500
        else -> 2500
    }

    /** Resoluciones que el equipo puede codificar, de mayor a menor para el respaldo. */
    private fun resolucionesGrabacion(): List<Int> =
        listOf(2160, 1440, 1080, 720, 480, 360).filter { it <= recAltoElegido }

    private var recStartMs = 0L
    /** Tamaño real de la grabación en curso (si el equipo bajó la resolución pedida). */
    private var anchoGrabando = 0
    private var altoGrabando = 0
    private var lastVideoUri: Uri? = null
    private var lastVideoFile: File? = null

    private val audioPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // El permiso se pide desde los dos caminos: grabación local y sesión con directo.
        if (modoSesion == "transmitir" || modoSesion == "ambos") {
            if (granted) {
                iniciarTransmision()
            } else {
                toast("Sin micrófono no se puede abrir el directo")
            }
        } else {
            startRecording(granted)
        }
    }

    /** Aviso de la notificación: si no se concede, la sesión sigue, solo no se ve el aviso. */
    private val permisoNotificaciones = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    /** La notificación del servicio pide cerrar la sesión: se hace como si el botón. */
    private val receptorSesion = object : android.content.BroadcastReceiver() {
        override fun onReceive(contexte: android.content.Context?, intent: android.content.Intent?) {
            val accion = com.jfrmedia.switcher.studio.stream.EmisionService.ACCION_SESION_DETENIDA
            if (intent?.action != accion) return
            runOnUiThread {
                if (recorder.isRecording) stopRecording()
                if (transmitiendo) cortarTransmision()
            }
        }
    }

    private val pickImage = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) addOverlay(uri)
    }

    /** Selector de medios del grupo MEDIOS (fotos y videos). */
    private val pickMedia = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) agregarMedio(uri)
    }

    /** Servicio de login de Google que hay que conservar mientras vuelve la respuesta. */
    private var servicioYt: net.openid.appauth.AuthorizationService? = null

    /** Etiqueta del cuadro de Facebook que muestra cómo está YouTube. */
    private var rotuloYoutube: TextView? = null

    /** Vuelta del login de Google para YouTube (Authorization Code + PKCE). */
    private val loginYouTube = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { resultado ->
        val datos = resultado.data
        val respuesta = datos?.let { net.openid.appauth.AuthorizationResponse.fromIntent(it) }
        val fallo = datos?.let { net.openid.appauth.AuthorizationException.fromIntent(it) }
        if (respuesta == null) {
            toast("Login de Google sin respuesta: ${fallo?.errorDescription ?: "cancelado"}")
            return@registerForActivityResult
        }
        val servicio = servicioYt
            ?: com.jfrmedia.switcher.studio.stream.YouTubeLive.servicio(this)
        val estado = net.openid.appauth.AuthState(respuesta, fallo)
        estado.performActionWithFreshTokens(servicio) { token, _, excepcion ->
            if (token.isNullOrBlank() || excepcion != null) {
                toast("Google no dio el permiso: ${excepcion?.message ?: "sin token"}")
                return@performActionWithFreshTokens
            }
            com.jfrmedia.switcher.studio.stream.YouTubeLive.guardar(this, estado)
            runOnUiThread {
                toast("YouTube conectado")
                actualizarRotuloYouTube()
            }
        }
    }

    /** Describe cómo está YouTube en el cuadro (conectado con qué canal, o sin conectar). */
    private fun actualizarRotuloYouTube() {
        val rotulo = rotuloYoutube ?: return
        if (!com.jfrmedia.switcher.studio.stream.YouTubeLive.conectado(this)) {
            rotulo.text = "YouTube: sin conectar"
            return
        }
        rotulo.text = "YouTube: conectado, leyendo el canal…"
        com.jfrmedia.switcher.studio.stream.YouTubeLive.nombreCanal(this) { nombre ->
            runOnUiThread {
                rotuloYoutube?.text = if (nombre == null) {
                    "YouTube: conectado"
                } else {
                    "YouTube: conectado como «$nombre»"
                }
            }
        }
    }

    /** Arranca el login de Google. */
    private fun conectarYouTube() {
        val servicio = com.jfrmedia.switcher.studio.stream.YouTubeLive.servicio(this)
        servicioYt = servicio
        val peticion = com.jfrmedia.switcher.studio.stream.YouTubeLive.peticion()
        runCatching { loginYouTube.launch(servicio.getAuthorizationRequestIntent(peticion)) }
            .onFailure { toast("No hay navegador para el login de Google") }
    }

    private val ticker = object : Runnable {
        override fun run() {
            // Cuenta tanto la grabación local como el directo: en «Solo transmitir» el
            // cronómetro se quedaba en 00:00 aunque el envío estuviera en el aire.
            if (!recorder.isRecording && !transmitiendo) return
            val s = (SystemClock.elapsedRealtime() - recStartMs) / 1000
            b.txtTimer.text = String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
            uiHandler.postDelayed(this, 500)
        }
    }

    /**
     * Reloj de un segundo para el **cronómetro de la partida** y la **batería de cada cámara**.
     * Solo hace trabajo en el Modo Pokémon con la vista de mesa; fuera de ahí se mantiene dormido.
     */
    private val relojTcg = object : Runnable {
        override fun run() {
            runCatching {
                if (modoTcg) {
                    tickCronometroTcg()
                    // La batería cambia despacio: se refresca cada 5 segundos.
                    relojTcgVueltas++
                    if (relojTcgVueltas % 5 == 0) actualizarBateriasTcg()
                }
            }
            uiHandler.postDelayed(this, 1_000)
        }
    }

    private var relojTcgVueltas = 0

    // ------------------------------------------------------------------ uso del equipo

    private var cpuAnteriorMs = -1L
    private var cpuAnteriorNs = 0L
    private var redAnteriorBytes = -1L
    private var redAnteriorNs = 0L
    /** Muestra anterior de `/proc/stat`: [ticks totales, ticks ociosos] de la tablet entera. */
    private var statAnterior: LongArray? = null
    /** Android negó `/proc/stat`: el total de la tablet no está disponible y no se reintenta. */
    private var tabBloqueado = false
    /** Android negó `/sys/class/thermal`: se usa la temperatura de la batería y no se reintenta. */
    private var thermalBloqueado = false

    /**
     * Consumo en la etiqueta del visor:
     * `PROGRAMA · CPU 14% · TAB 42% · RAM 62% · 11.8 Mbps · 44°`
     *
     * **100 % = todos los núcleos al máximo.** Android mide el CPU por núcleo (100 % = un núcleo
     * entero), así que aquí se reparte entre los núcleos que tiene la tablet: 50 % significa que se
     * están usando 4 de 8 núcleos, y 100 % que los 8 están a tope. Así el número se lee de un vistazo
     * y es comparable con la RAM (Jorge, 15/11).
     *
     * - `CPU` → lo que usa **esta app** (suma de todos sus hilos, repartida entre núcleos).
     * - `TAB` → lo que usa **la tablet completa** (todos los procesos), leído de `/proc/stat`.
     * RAM es la del sistema. La red es lo que **sube esta app**, o sea lo que cuesta el directo. La
     * temperatura se lee del sensor térmico; si Android no lo expone, se usa la de la batería.
     */
    private val medidorUso = object : Runnable {
        override fun run() {
            val ahoraNs = SystemClock.elapsedRealtimeNanos()
            val cpuMs = android.os.Process.getElapsedCpuTime()
            var cpuPorNucleo = 0L
            if (cpuAnteriorMs >= 0 && ahoraNs > cpuAnteriorNs) {
                cpuPorNucleo = (cpuMs - cpuAnteriorMs) * 100_000_000L / (ahoraNs - cpuAnteriorNs)
            }
            cpuAnteriorMs = cpuMs
            cpuAnteriorNs = ahoraNs
            // `cpuPorNucleo` YA viene en porcentaje por núcleo (100 = un núcleo a tope), así que
            // para pasarlo a "equipo completo" se DIVIDE entre los núcleos (no se multiplica por
            // 100: el 15/11 se coló ese error y un 63 % por núcleo salía como 100 %).
            val nucleos = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            val cpu = (cpuPorNucleo / nucleos).coerceIn(0L, 100L)
            val cpuTablet = cpuDeLaTablet()

            val ramPct = runCatching {
                val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                val info = android.app.ActivityManager.MemoryInfo()
                am.getMemoryInfo(info)
                if (info.totalMem > 0) (info.totalMem - info.availMem) * 100 / info.totalMem else 0L
            }.getOrDefault(0L)

            val bytes = android.net.TrafficStats.getUidTxBytes(android.os.Process.myUid())
            var mbps = 0.0
            if (redAnteriorBytes >= 0 && ahoraNs > redAnteriorNs && bytes >= redAnteriorBytes) {
                val segundos = (ahoraNs - redAnteriorNs) / 1_000_000_000.0
                if (segundos > 0) mbps = (bytes - redAnteriorBytes) * 8.0 / segundos / 1_000_000.0
            }
            redAnteriorBytes = bytes
            redAnteriorNs = ahoraNs

            val temp = temperatura()
            val tempTexto = if (temp != null) "$temp°" else "—°"
            cpuActual = cpu
            ramActual = ramPct
            mbpsActual = mbps
            tempActual = temp
            actualizarEstadoEquipo()
            // Los dos van en la misma escala: 100 % = todos los núcleos a tope. Si la tablet no deja
            // leer /proc/stat, el TAB se omite en vez de dejar un «—» que no dice nada.
            val detalle = if (cpuTablet >= 0) {
                String.format(
                    Locale.US, "CPU %d%% · TAB %d%% · RAM %d%% · %.1f Mbps · %s",
                    cpu, cpuTablet, ramPct, mbps, tempTexto
                )
            } else {
                String.format(
                    Locale.US, "CPU %d%% · RAM %d%% · %.1f Mbps · %s",
                    cpu, ramPct, mbps, tempTexto
                )
            }
            b.txtEtiquetaPrograma.text = if (cabeceraEstrecha()) {
                // Cabecera estrecha (9:16): solo el nombre del visor (la resolución va al otro
                // extremo) para que quepa en una línea.
                if (redFloja) "⚠ PROGRAMA" else "PROGRAMA"
            } else {
                val aviso = if (redFloja) "⚠ " else ""
                "${aviso}PROGRAMA · $detalle"
            }
            // En el celular en vertical el monitoreo se muestra debajo del monitor (Jorge, 29/10).
            txtMonitoreo?.text = if (redFloja) "⚠ $detalle" else detalle
            if (registro != null && ahoraNs - ultimoRegistroNs > 10_000_000_000L) {
                ultimoRegistroNs = ahoraNs
                anotar(
                    String.format(
                        Locale.US,
                        "cpu %d%% (%.1f de %d nucleos)%s · ram %d%% · %.1f Mbps · %s",
                        cpu,
                        cpu / 100.0 * nucleos,
                        nucleos,
                        if (cpuTablet >= 0) String.format(Locale.US, " · tablet %d%% (%.1f de %d nucleos)", cpuTablet, cpuTablet / 100.0 * nucleos, nucleos) else "",
                        ramPct,
                        mbps,
                        tempTexto
                    )
                )
            }
            uiHandler.postDelayed(this, 2000)
        }
    }

    /**
     * ¿La cabecera del programa es la estrecha (9:16)? Ahí solo cabe «PROGRAMA».
     *
     * En Modo Studio manda **el formato que se está viendo** (`studioVertical`), no el guardado
     * (`vertical`): el chip cambia uno y el otro se queda como estaba, así que al pasar a 16:9 dentro
     * del Modo Studio la info del sistema seguía oculta (visto el 15/11 en la tablet).
     */
    private fun cabeceraEstrecha(): Boolean = if (modoStudio) studioVertical else vertical

    /**
     * Uso de CPU de **toda la tablet** (todos los procesos) en la misma escala que la app:
     * **0–100 %, donde 100 % es que los ocho núcleos están a tope**.
     *
     * La línea `cpu` de `/proc/stat` acumula los ticks de todos los núcleos: el 4.º valor es el tiempo
     * ocioso y el 5.º la espera de disco (iowait), que también se cuenta como ocioso. Con la
     * diferencia entre dos muestras sale directamente la proporción ocupada del equipo completo, sin
     * multiplicar por nada.
     *
     * Devuelve -1 si Android no deja leer el archivo; en ese caso la etiqueta muestra «—».
     */
    private fun cpuDeLaTablet(): Long {
        if (tabBloqueado) return -1
        val lectura = runCatching {
            var total = 0L
            var ocioso = 0L
            java.io.File("/proc/stat").forEachLine { linea ->
                if (linea.startsWith("cpu ")) {
                    val v = linea.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
                    if (v.size >= 4) {
                        total = v.sum()
                        ocioso = v[3] + v.getOrElse(4) { 0L }
                    }
                }
            }
            longArrayOf(total, ocioso)
        }
        val muestra = lectura.getOrNull()
        if (muestra == null) {
            // Android 10+ puede negar /proc/stat a las apps. Se anota una vez y no se reintenta,
            // para no repetir la misma negativa cada 2 segundos.
            tabBloqueado = true
            L.w("uso", "no puedo leer /proc/stat: ${lectura.exceptionOrNull()?.message ?: "sin datos"}")
            return -1
        }
        val previo = statAnterior
        statAnterior = muestra
        if (previo == null) return -1
        val dTotal = muestra[0] - previo[0]
        val dOcioso = muestra[1] - previo[1]
        if (dTotal <= 0) return 0
        return ((dTotal - dOcioso) * 100 / dTotal).coerceIn(0L, 100L)
    }

    /**
     * Temperatura del procesador en grados enteros.
     *
     * Android no tiene API: se lee de `/sys/class/thermal`, buscando la zona del SoC. Si el sistema
     * lo bloquea, se devuelve la de la batería, que sube junto con la del equipo.
     */
    private fun temperatura(): Int? {
        if (!thermalBloqueado) {
            val zonas = runCatching {
                java.io.File("/sys/class/thermal")
                    .listFiles { f -> f.name.startsWith("thermal_zone") }
                    .orEmpty()
            }.getOrDefault(emptyArray())
            if (zonas.isEmpty()) {
                // Android 10+ suele negar /sys/class/thermal a las apps. Se anota una vez y se deja
                // de intentar: antes se repetía cada 2 s (30 negativas por minuto en el registro).
                thermalBloqueado = true
                L.i("uso", "termómetro del SoC no disponible: se usa el de la batería")
            }
            for (zona in zonas) {
                val tipo = runCatching { java.io.File(zona, "type").readText().trim().lowercase() }
                    .getOrDefault("")
                if (tipo.contains("cpu") || tipo.contains("soc") || tipo.contains("ap") ||
                    tipo.contains("tscpu")
                ) {
                    val valor = runCatching { java.io.File(zona, "temp").readText().trim().toFloat() }
                        .getOrNull() ?: continue
                    val grados = if (valor > 1000f) valor / 1000f else valor
                    if (grados in 1f..120f) return grados.toInt()
                }
            }
        }
        val bateria = runCatching {
            val i = registerReceiver(
                null,
                android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
            )
            i?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        }.getOrDefault(-1)
        return if (bateria > 0) bateria / 10 else null
    }

    // ------------------------------------------------------------------ registro de sesión

    private var registro: java.io.Writer? = null
    private var ultimoRegistroNs = 0L

    /** Abre un archivo de registro para esta sesión (transmisión o grabación). */
    private fun abrirRegistro(modo: String) {
        if (registro != null) return
        runCatching {
            val dir = java.io.File(getExternalFilesDir(null) ?: filesDir, "Registros")
            if (!dir.exists()) dir.mkdirs()
            val nombre = "JFR_" +
                java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(java.util.Date()) + ".txt"
            registro = java.io.File(dir, nombre).bufferedWriter()
            anotar("=== inicio de $modo ===")
            anotar("equipo ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}")
            anotar(
                "grabación ${recAncho}x$recAlto @ $recBitrate kbps · envío ${streamAlto}p @ " +
                    "${if (bitrateAuto) bitrateEmisionAuto(streamAlto) else streamBitrateKbps} kbps" +
                    if (bitrateAuto) " (automático)" else ""
            )
        }.onFailure { L.w("registro", "no se pudo abrir: ${it.message}") }
    }

    private fun anotar(texto: String) {
        runCatching {
            val hora = java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(java.util.Date())
            registro?.write("$hora $texto\n")
            registro?.flush()
        }
    }

    private fun cerrarRegistro() {
        runCatching {
            anotar("=== fin de la sesión ===")
            registro?.close()
        }
        registro = null
    }

    // ------------------------------------------------------------------ ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RtcCore.init(this)
        b = ActivityStudioBinding.inflate(layoutInflater)
        setContentView(b.root)

        recorder = Recorder(this)
        recorder.onError = { msg -> toast(msg) }
        b.canvas.listener = this
        // El celular sigue fijo en horizontal (su diseño propio se hará después). La tablet gira:
        // al ponerla en vertical entra el Modo Studio.
        if (esPantallaCelular()) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
        } else if (!getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(CLAVE_MODO_TCG, false)) {
            // La tablet tiene que poder girar para entrar al Modo Studio. Se fija AQUÍ porque el
            // Modo Pokémon deja la pantalla trabada en horizontal y ese bloqueo se guarda en la
            // pantalla: al volver a Modo Studio no se soltaba solo (Jorge, 21/11/2026).
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER
        }
        // Segundo compositor (programa) para el Modo Studio: se crea siempre y solo se engancha
        // al layout cuando la tablet está en vertical.
        monitorPrograma = CompositorView(this).apply {
            scene = b.canvas.scene
            editable = false
        }

        buildSourcesGrid()
        vigilarScrollFuentes()

        vertical = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(CLAVE_VERTICAL, false)
        // Modo Pokémon TCG: en este modo el lienzo es siempre 16:9.
        modoTcg = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(CLAVE_MODO_TCG, false)
        if (modoTcg) {
            vertical = false
            estadoTcg = leerEstadoTcg()
            b.canvas.scene.maxCams = 4
        }
        // Ajustes guardados de sesión (modo, resoluciones, bitrates).
        runCatching {
            val p = getSharedPreferences(PREFS, MODE_PRIVATE)
            modoSesion = p.getString(CLAVE_MODO, modoSesion) ?: modoSesion
            recAltoElegido = p.getInt(CLAVE_REC_ALTO, recAltoElegido)
            recBitrateKbps = p.getInt(CLAVE_REC_KBPS, recBitrateKbps)
            streamAlto = p.getInt(CLAVE_STREAM_ALTO, streamAlto)
            streamBitrateKbps = p.getInt(CLAVE_STREAM_KBPS, streamBitrateKbps)
            bitrateAuto = p.getBoolean(CLAVE_AUTO_KBPS, bitrateAuto)
            recAudio = p.getBoolean(CLAVE_REC_AUDIO, recAudio)
            grabarAlta = recAltoElegido >= 1080
            val transicionGuardada = p.getString(CLAVE_TRANSICION, null)
            if (transicionGuardada != null) {
                CompositorView.Transicion.values()
                    .firstOrNull { it.id == transicionGuardada }
                    ?.let { b.canvas.transicion = it }
            }
            when (p.getString(CLAVE_PRESET, VideoPresets.MEDIA.label)) {
                VideoPresets.BAJA.label -> preset = VideoPresets.BAJA
                VideoPresets.ALTA.label -> preset = VideoPresets.ALTA
                // "auto" deja que la app decida según cuántas cámaras haya
                else -> preset = VideoPresets.MEDIA
            }
        }
        // Ajustes de audio guardados: ganancia, silencio y entrada elegida.
        runCatching {
            val p = getSharedPreferences(PREFS, MODE_PRIVATE)
            val dbGuardado = p.getFloat(CLAVE_AUDIO_DB, 0f)
            recorder.ganancia = Math.pow(10.0, (dbGuardado / 20.0)).toFloat()
            recorder.silencio = p.getBoolean(CLAVE_AUDIO_MUDO, false)
            // Ecualizador y monitoreo: el EQ de entrada entra al grabador.
        recorder.eqEntrada = eqEntrada
        recorder.monitor = monitorAudio
        cargarAudioAvanzado()
        aplicarAudioGuardado()
        }
        aplicarOrientacion()
        // En el celular, si quedó guardado el lienzo 9:16 la app abre directo en vertical.
        if (esPantallaCelular()) {
            requestedOrientation = if (vertical) {
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
            }
        }
        // Los últimos medios de la sesión anterior: ya con la parrilla y la orientación listas.
        cargarMediosRecientes()

        // El vúmetro de arriba muestra la entrada desde que se abre la app (el micrófono lo abre
        // ajustarAudioVivo() con onResume); al grabar o transmitir toma el nivel del grabador.
        b.audioMeter.levelProvider = { nivelEntrada() }

        applyWindowInsets()
        wireHub()
        wireClicks()
        montarControlesAudio()
        if (esPantallaCelular()) aplicarModoCelular()
        // Chips de la barra superior, todos del mismo tamaño (tablet y celular).
        estandarizarChipsBarra()
        huecoVideo = b.cajaVideo.parent as? ViewGroup
        indiceVideo = huecoVideo?.indexOfChild(b.cajaVideo) ?: 0

        if (!server.start()) {
            toast("No se pudo abrir el puerto de emparejamiento")
        } else if (server.port != Proto.DEFAULT_PORT) {
            // El QR lleva el puerto real, así que las cámaras que lo escaneen no fallan.
            toast("El 8787 estaba ocupado: esta tablet usa el puerto ${server.port} (va en el QR)")
        }
        refreshUi()
        updatePairingLabels()
        if (modoTcg) aplicarModoTcg()
        uiHandler.post(medidorUso)
        escucharEmisor()
        // Si la pantalla se recreó con un envío en curso, se reengancha el compositor a la
        // superficie del emisor en vez de mostrar «Transmitir» con el directo en el aire.
        if (emisor.transmitiendo) {
            emisor.superficieParaCompositor()?.let { superficie ->
                vistaAlAire().beginRecordingOutput(
                    superficie,
                    emisor.anchoVideo,
                    emisor.altoVideo,
                    System.nanoTime()
                )
                L.i("stream", "compositor reenganchado a la transmisión en curso")
            }
            actualizarBotonSesion()
            actualizarCandadoOrientacion()
        }
        // La notificación del servicio puede cerrar la sesión sin abrir la app.
        runCatching {
            ContextCompat.registerReceiver(
                this,
                receptorSesion,
                android.content.IntentFilter(
                    com.jfrmedia.switcher.studio.stream.EmisionService.ACCION_SESION_DETENIDA
                ),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
        L.i("studio", "listo en ${Net.primaryIp()}:${server.port}")
        // Si la tablet ya está en vertical, se abre directo en Modo Studio.
        evaluarModoStudio()
        // Aviso de versión nueva: se consulta al abrir la app, en segundo plano, sin estorbar.
        buscarActualizacion(manual = false)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // La tablet gira: al pasar a vertical entra el Modo Studio y al volver a horizontal sale.
        evaluarModoStudio()
        // Modo Pokémon TCG: el manifiesto se queda con el cambio de configuración, así que la
        // pantalla NO se recrea sola. Hay que volver a armar el modo con las medidas nuevas (si la
        // app se abrió con la tablet vertical, la primera medida sale en vertical y no sirve).
        if (modoTcg) {
            uiHandler.postDelayed({ runCatching { aplicarModoTcg() } }, 450)
        }
        // En el celular, al girar (por el botón 9:16) hay que reacomodar con las medidas nuevas.
        if (esPantallaCelular() && !modoStudio) {
            uiHandler.post {
                runCatching {
                    aplicarOrientacion()
                    if (!vertical) aplicarModoCelular()
                }
            }
        }
    }

    // ------------------------------------------------------- versión y actualizaciones

    /** Versión instalada, tal como la declara el APK (`0.2.2`). */
    private fun versionInstalada(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName
    }.getOrNull() ?: "?"

    /**
     * Consulta GitHub y, si hay una versión más nueva, avisa.
     * [manual] = lo pidió el usuario desde Ajustes; entonces también confirma si no hay nada nuevo.
     */
    private fun buscarActualizacion(manual: Boolean) {
        val instalada = versionInstalada()
        if (manual) toast("Buscando actualización…")
        Thread {
            val ultima = Actualizaciones.ultima()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when {
                    ultima == null ->
                        if (manual) toast("No se pudo consultar: revisa la conexión a internet")
                    !Actualizaciones.hayNueva(instalada, ultima.version) ->
                        if (manual) toast("Estás en la última versión ($instalada)")
                    else -> avisarActualizacion(instalada, ultima)
                }
            }
        }.apply { isDaemon = true }.start()
    }

    /** Cuadro con las novedades de la versión nueva y el botón para descargarla. */
    private fun avisarActualizacion(instalada: String, ultima: Actualizaciones.Publicada) {
        val notas = limpiarNotas(ultima.notas).ifEmpty { "Sin notas de esta versión." }
        val cuerpo = "Tienes la $instalada y ya salió la ${ultima.version}.\n\nQué cambia:\n\n$notas"
        val vista = TextView(this).apply {
            text = cuerpo
            setTextSize(13.5f)
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            val pad = (18 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        AlertDialog.Builder(this)
            .setTitle("Versión ${ultima.version} disponible")
            .setView(ScrollView(this).apply { addView(vista) })
            .setPositiveButton("Descargar") { _, _ -> abrirEnNavegador(ultima.apkSwitcher) }
            .setNeutralButton("Ver la página") { _, _ -> abrirEnNavegador(Actualizaciones.PAGINA) }
            .setNegativeButton("Ahora no", null)
            .show()
    }

    /** Quita el formato Markdown de las notas para que se lean bien en el cuadro. */
    private fun limpiarNotas(texto: String): String = texto
        .replace(Regex("(?m)^#{1,6}\\s*"), "")
        .replace("**", "")
        .replace("`", "")
        .trim()

    /** Abre una dirección en el navegador (Chrome si está; si no, el que haya). */
    private fun abrirEnNavegador(url: String) {
        val directo = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(Intent(directo).setPackage("com.android.chrome")) }
            .recoverCatching { startActivity(directo) }
            .onFailure { toast("No hay navegador para abrir la descarga") }
    }

    override fun onResume() {
        super.onResume()
        enPrimerPlano = true
        // El vúmetro empieza a moverse en cuanto la app está a la vista.
        ajustarAudioVivo()
    }

    override fun onPause() {
        // Con la app fuera de la vista no se deja el micrófono abierto: no hace falta y gasta pila.
        enPrimerPlano = false
        ajustarAudioVivo()
        super.onPause()
    }

    override fun onDestroy() {
        uiHandler.removeCallbacksAndMessages(null)
        detenerConsultasFacebook()
        runCatching { unregisterReceiver(receptorSesion) }
        runCatching { audioVivo.detener() }
        runCatching { localCam.detener() }
        runCatching { if (recorder.isRecording) { vistaAlAire().endRecordingOutput(); recorder.stop() } }
        runCatching { server.stop() }
        runCatching { hub.shutdown() }
        tiles.forEach { runCatching { it.release() } }
        super.onDestroy()
    }

    /**
     * Android 15+ dibuja de borde a borde: sin esto la app queda debajo de la barra de
     * estado y de los botones de navegación (se veían encima de la barra superior).
     */
    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(b.root)
    }

    @Deprecated("Compatibilidad con versiones anteriores")
    override fun onBackPressed() {
        if (recorder.isRecording) {
            toast("Detén la grabación antes de salir")
            return
        }
        // Con el directo en el aire, cerrar la pantalla se llevaría las cámaras: para salir
        // está Inicio (la sesión sigue gracias al servicio en primer plano).
        if (transmitiendo) {
            toast("Detén la transmisión antes de salir (o usa Inicio para seguir en el aire)")
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    // ------------------------------------------------------------------ conexión de cámaras

    private fun wireHub() {
        hub.onTrackReady = { session, track ->
            runOnUiThread { onTrackReady(session, track) }
        }
        hub.onTrackGone = { session ->
            runOnUiThread { onTrackGone(session) }
        }
        hub.onRotacion = { session, grados ->
            runOnUiThread {
                // El decodificador ya entrega los píxeles orientados: solo se avisa a la
                // tarjeta para que no vuelva a girar el cuadro.
                tiles.getOrNull(session.slot)?.fijarRotacion(grados)
                // Se deja en el log: es el único dato de orientación que manda el celular (el video
                // viaja sin giro), y de eso depende la proporción real del cuadro en el programa.
                com.jfrmedia.switcher.core.util.L.i("rot", "camara ${session.slot}: giro reportado $grados")
            }
        }
        hub.onCountChanged = {
            runOnUiThread {
                refreshUi()
                if (calidadAuto) applyQualityToCameras()
            }
        }
        hub.addListener { runOnUiThread { refreshUi() } }
    }

    private fun onTrackReady(session: CameraSession, track: VideoTrack) {
        // Si la sesión se cerró entre el aviso y este hilo, la pista ya está liberada y
        // engancharla tronaba con «MediaStreamTrack has been disposed» (pasó al reconectar).
        if (session.state == com.jfrmedia.switcher.studio.rtc.CamState.CLOSED) return
        val tile = tiles.getOrNull(session.slot) ?: return
        if (runCatching { tile.bind(session, track) }.isFailure) return
        // En Modo Pokémon TCG la escena es fija: la cámara solo se engancha a su hueco del monitor.
        // (Antes se ponía sola a pantalla completa y tapaba la mesa.)
        if (modoTcg) {
            // A la cámara que acaba de entrar se le pide su orientación (jugadores verticales,
            // mesa y ambiente horizontales) antes de dibujarla.
            aplicarOrientacionCamarasTcg()
            runCatching { adjuntar(session.slot, track) }
            runCatching { aplicarRectangulosTcg() }
            b.canvas.requestRender()
            refreshUi()
            // Si llegó el cuarto celular mientras la cámara de la tablet está encendida, se avisa:
            // la 4 es de la tablet hasta que se apague con el botón de su tarjeta.
            if (session.slot == 3 && localCam.encendida) {
                toast("Conectó el 4.º celular: toca «${etiquetaBotonCamara4()}» en la tarjeta 4 hasta apagarla para usarlo")
            }
            return
        }
        if (camA == null && camB == null) {
            assignCamera(session.slot, 'A')
        } else if (camA == session.slot || camB == session.slot) {
            adjuntar(session.slot, track)
        }
        refreshUi()
    }

    private fun onTrackGone(session: CameraSession) {
        tiles.getOrNull(session.slot)?.clearSession()
        if (camA == session.slot) camA = null
        if (camB == session.slot) camB = null
        // En Modo Pokémon TCG el hueco de la cámara se queda (en negro) y, si se fue la de
        // ambiente, se vuelve sola a la mesa con los jugadores.
        if (modoTcg) {
            runCatching { aplicarEscenaTcg() }
            b.canvas.requestRender()
            refreshUi()
            return
        }
        b.canvas.scene.items.filterIsInstance<CamItem>()
            .filter { it.slot == session.slot }
            .forEach { b.canvas.scene.remove(it) }
        b.canvas.requestRender()
        refreshUi()
    }

    private fun refreshUi() {
        tiles.forEachIndexed { index, tile ->
            val session = hub.session(index)
            if (index == LocalCam.SLOT) {
                tile.setEstadoLocal(localCam.encendida, nombreCamaraTablet())
            } else if (session == null) {
                tile.clearSession()
            } else {
                tile.refreshInfo(session)
                // La rotación se vuelve a aplicar aquí: al rehacer la parrilla (cambio de
                // formato) las tarjetas nuevas nacen sin girar y el celular no la reporta otra vez.
                tile.fijarRotacion(session.rotacion)
            }
            tile.setLayerMark(
                when (index) {
                    camA -> 'A'
                    camB -> 'B'
                    else -> null
                }
            )
        }
        actualizarProgramaCamaras()
        b.txtCamCount.text = "${hub.count()} / 6"
        b.canvasHint.visibility =
            if (hub.count() == 0 && b.canvas.scene.items.isEmpty() && !localCam.encendida) {
                View.VISIBLE
            } else {
                View.GONE
            }
        updatePairingLabels()
    }

    /**
     * Avisa a cada celular si su cámara está en el visor. La app del celular muestra
     * «AL AIRE» o «FUERA DE AIRE», para que el operador sepa si está en pantalla.
     */
    private fun actualizarProgramaCamaras() {
        // La escena que importa es la que está AL AIRE (en Modo Studio, la del monitor de programa).
        val scene = escenaAlAire()
        // Si hay un video o foto de MEDIOS a pantalla completa, el programa no muestra cámaras.
        val tapado = scene.items.any { it is MediaItem && it.visible }
        hub.all().forEach { session ->
            val item = scene.camAt(session.slot)
            session.enviarPrograma(!tapado && item != null && item.visible)
        }
        // Y las tarjetas se marcan con el marco naranja para verlas de un golpe.
        marcarFuentesEnPrograma()
    }

    /**
     * Marco naranja en las tarjetas de las fuentes que están **al aire** (en programa).
     * En Modo Studio manda la escena del monitor de programa, no la del preview.
     */
    private fun marcarFuentesEnPrograma() {
        runCatching {
            val alAire = escenaAlAire().cams().filter { it.visible }.map { it.slot }.toSet()
            tiles.forEachIndexed { slot, tile -> tile.marcarEnPrograma(slot in alAire) }
        }
    }

    // ------------------------------------------------------------------ asignación de capas

    private fun onTileTap(slot: Int) {
        // En Modo Pokémon TCG tocar una tarjeta pone esa cámara a pantalla completa (para ver la cara
        // del jugador o el ambiente), y la mesa devuelve la composición con el marcador. Tocar otra
        // vez la misma cámara vuelve a la mesa (Jorge, 21/11/2026).
        if (modoTcg) {
            when (slot) {
                0, 1 -> {
                    if (vistaTcg == slot) {
                        mostrarCamaraTcg(VISTA_MESA)
                    } else if (mostrarCamaraTcg(slot)) {
                        toast(if (slot == 0) "Jugador 1 a pantalla completa" else "Jugador 2 a pantalla completa")
                    } else {
                        // No hay cámara en ese hueco: se abre el QR para conectar una, sin salir
                        // del modo (Jorge, 22/11/2026).
                        showPairing()
                    }
                }

                2 -> {
                    // La mesa no necesita botón «Cámara general»: se toca su tarjeta y alterna entre
                    // la cámara de ambiente a pantalla completa y la mesa con el marcador.
                    alternarAmbienteTcg()
                }

                3 -> alternarAmbienteTcg()
                else -> {}
            }
            return
        }
        // Segunda cámara del equipo (frontal): en el celular las dos funcionan a la vez.
        if (slot == LocalCam.SLOT_FRONT) {
            if (!localCam2.encendida) {
                encenderLocal(localCam2, LocalCam.SLOT_FRONT, "Frente")
            } else if (!pipActivo && !splitActivo) {
                mostrarEnPrograma(slot)
            } else {
                assignCamera(slot, activeLayer)
            }
            return
        }
        if (slot == LocalCam.SLOT) {
            // En el celular la cámara propia sí funciona en vertical (es la cámara del teléfono).
            if (vertical && !esPantallaCelular()) {
                toast("La cámara de la tablet solo está disponible en modo horizontal")
                return
            }
            if (!localCam.encendida) {
                encenderCamaraTablet()
                return
            }
            if (!pipActivo && !splitActivo) mostrarEnPrograma(slot) else assignCamera(slot, activeLayer)
            return
        }
        if (hub.session(slot) == null) {
            showPairing()
            return
        }
        if (!pipActivo && !splitActivo) mostrarEnPrograma(slot) else assignCamera(slot, activeLayer)
    }

    // ------------------------------------------------------------------ cámara de la tablet

    private val permisoCamara = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { concedido ->
        if (concedido) encenderCamaraTablet()
        else toast("Sin permiso de cámara no se puede usar la cámara de la tablet")
    }

    /** Enciende o apaga la cámara de la tablet (menú). Recuerda la preferencia. */
    private fun alternarCamaraTablet() {
        if (localCam.encendida) {
            apagarCamaraTablet()
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(CLAVE_TABLET_CAM, false).apply()
            toast("Cámara de la tablet apagada")
        } else {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(CLAVE_TABLET_CAM, true).apply()
            encenderCamaraTablet()
        }
    }

    /**
     * Botón de la tablet: apagada → frontal → trasera → apagada.
     * Sustituye a las opciones del menú.
     */
    private fun ciclarLenteTablet() {
        // En la tablet la cámara local no existe en vertical; en el celular sí (9:16).
        if (vertical && !esPantallaCelular()) {
            toast("La cámara de la tablet solo está disponible en modo horizontal")
            return
        }
        when {
            !localCam.encendida -> {
                prefsTabCam(true, true)
                encenderCamaraTablet()
            }

            localCam.frontal -> {
                prefsTabCam(true, false)
                if (localCam.cambiarCamara(false)) {
                    localCam.track?.let { tiles.getOrNull(LocalCam.SLOT)?.bindLocal(it, nombreCamaraTablet()) }
                }
            }

            else -> {
                apagarCamaraTablet()
                prefsTabCam(false, false)
            }
        }
        actualizarBotonTablet()
    }

    private fun prefsTabCam(encendida: Boolean, frontal: Boolean) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(CLAVE_TABLET_CAM, encendida)
            .putBoolean(CLAVE_TABLET_FRONTAL, frontal)
            .apply()
    }

    /** Pinta el botón con el estado actual de la cámara de la tablet. */
    private fun actualizarBotonTablet() {
        // Etiqueta corta a propósito: con «Tablet: frontal» el botón empujaba el medidor de audio
        // fuera del borde derecho. El icono de cámara ya dice de qué cámara se trata.
        val estadoCamara = when {
            !localCam.encendida -> "Apagada"
            localCam.frontal -> "Frontal"
            else -> "Trasera"
        }
        // En el celular la fila de controles flota sobre el visor y tiene que caber entera: ahí
        // la palabra completa empujaba «Img.» fuera de la vista. Icono + estado corto.
        b.btnTabletLens.text = if (esPantallaCelular()) {
            when (estadoCamara) {
                "Frontal" -> "F"
                "Trasera" -> "T"
                else -> "Off"
            }
        } else estadoCamara
        b.btnTabletLens.alpha = if (localCam.encendida) 1f else 0.6f
    }

    /** Cambia entre la cámara frontal y la trasera de la tablet (menú). */
    private fun cambiarLenteTablet() {
        val usarFrontal = !localCam.frontal
        if (localCam.cambiarCamara(usarFrontal)) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(CLAVE_TABLET_FRONTAL, usarFrontal).apply()
            localCam.track?.let { tiles.getOrNull(LocalCam.SLOT)?.bindLocal(it, nombreCamaraTablet()) }
            refreshUi()
            toast(if (usarFrontal) "Usando la cámara frontal" else "Usando la cámara trasera")
        } else {
            toast("Esta tablet no tiene esa cámara")
        }
    }

    private fun nombreCamaraTablet(): String =
        "Tablet · " + (if (localCam.frontal) "frontal" else "trasera")

    /**
     * Toque del botón de la cámara propia en el celular: si está apagada la enciende y, si ya
     * está encendida, **rota entre frontal y trasera** (Jorge, 29/10).
     */
    private fun tocarCamaraPropia() {
        if (!localCam.encendida) {
            encenderCamaraTablet()
            return
        }
        if (localCam.cambiarCamara(!localCam.frontal)) {
            localCam.track?.let { pista ->
                adjuntar(LocalCam.SLOT, pista)
                tiles.getOrNull(LocalCam.SLOT)?.bindLocal(pista, nombreCamaraTablet())
            }
            actualizarBotonTablet()
            toast(if (localCam.frontal) "Cámara frontal" else "Cámara trasera")
        } else {
            toast("No pude cambiar de cámara en este equipo")
        }
    }

    /** Enciende la cámara local (solo horizontal) y la deja lista para A o B. */
    private fun encenderCamaraTablet() {
        encenderLocal(localCam, LocalCam.SLOT, "Tablet")
    }

    /**
     * Enciende una cámara del propio equipo (la de atrás o la de adelante) como fuente del
     * switcher. En el celular se usan **las dos a la vez** (Jorge, 29/10).
     */
    private fun encenderLocal(cam: LocalCam, slot: Int, etiqueta: String) {
        // En la tablet la cámara local solo existe en horizontal; en el celular también en 9:16.
        if (vertical && !esPantallaCelular()) return
        val permiso = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
        if (permiso != PackageManager.PERMISSION_GRANTED) {
            permisoCamara.launch(Manifest.permission.CAMERA)
            return
        }
        if (cam.encendida) return
        // La trasera (slot 6) arranca según lo guardado; la frontal (slot 7) siempre en frontal.
        val frontal = if (slot == LocalCam.SLOT_FRONT) {
            true
        } else {
            getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(CLAVE_TABLET_FRONTAL, true)
        }
        val pista = cam.arrancar(this, frontal)
        if (pista == null) {
            toast("No pude encender la cámara $etiqueta (¿el equipo permite las dos a la vez?)")
            return
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(CLAVE_TABLET_CAM, true).apply()
        adjuntar(slot, pista)
        tiles.getOrNull(slot)?.bindLocal(pista, "$etiqueta · ${if (cam.frontal) "frontal" else "trasera"}")
        refreshUi()
    }

    /** Apaga la cámara local y la saca del lienzo. */
    private fun apagarCamaraTablet() {
        apagarLocal(localCam, LocalCam.SLOT)
    }

    /** Apaga una cámara del propio equipo y limpia su tarjeta y su lugar en el lienzo. */
    private fun apagarLocal(cam: LocalCam, slot: Int) {
        val pista = cam.track
        if (pista != null) soltar(slot, pista)
        cam.detener()
        val scene = b.canvas.scene
        scene.camAt(slot)?.let { scene.remove(it) }
        if (camA == slot) camA = null
        if (camB == slot) camB = null
        tiles.getOrNull(slot)?.setEstadoLocal(false)
        // En Modo Pokémon TCG, apagar la cámara de ambiente tiene que devolver la mesa y los
        // jugadores al monitor (si no, el programa se queda en negro).
        if (modoTcg) runCatching { aplicarEscenaTcg() }
        // Si la pantalla partida usaba la cámara de la tablet (no existe en vertical),
        // se avisa que falta la segunda fuente.
        if (splitActivo) {
            aplicarLayoutSplit()
            if (camA == null || camB == null) {
                toast("Multicámara: conecta otra cámara para la segunda mitad")
            }
        }
        b.canvas.requestRender()
        refreshUi()
    }

    private fun assignCamera(slot: Int, layer: Char) {
        val scene = b.canvas.scene
        L.i(
            "studio",
            "asignar slot=$slot capa=$layer camA=$camA camB=$camB items=${scene.cams().size} pip=$pipActivo split=$splitActivo"
        )
        if (splitActivo) {
            // En pantalla partida cada capa ocupa su mitad.
            if (layer == 'A') camA = slot else camB = slot
            aplicarLayoutSplit()
            styleLayerButtons()
            updateTip()
            refreshUi()
            onSelectionChanged(b.canvas.scene.selected())
            return
        }
        val other = if (layer == 'A') camB else camA
        if (other == slot) {
            // Ya estaba en la otra capa: solo cambia de rol
            if (layer == 'A') camB = null else camA = null
        }

        val existing = scene.camAt(slot)
        if (existing == null) {
            if (scene.cams().size >= scene.maxCams) {
                // Si el lienzo está lleno, la capa pedida reemplaza a la que ocupa ese rol.
                val viejo = if (layer == 'A') camA else camB
                if (viejo != null) {
                    scene.camAt(viejo)?.let { item ->
                        scene.remove(item)
                        if (item.slot == LocalCam.SLOT) {
                            soltar(item.slot, localCam.track)
                        } else {
                            soltar(item.slot, hub.session(item.slot)?.videoTrack)
                        }
                    }
                    if (layer == 'A') camA = null else camB = null
                } else {
                    toast("El lienzo admite 2 cámaras a la vez")
                    return
                }
            }
            val item = scene.addCam(slot) ?: return
            item.label = when {
                slot == LocalCam.SLOT -> "Tablet"
                else -> hub.session(slot)?.name ?: "Cámara ${slot + 1}"
            }
            if (layer == 'A') {
                item.rect.set(0f, 0f, 1f, 1f)
                scene.sendToBack(item)
            } else {
                scene.placePip(item, pipCorner)
                scene.bringToFront(item)
            }
            if (slot == LocalCam.SLOT) {
                localCam.track?.let { adjuntar(slot, it) }
            } else {
                hub.session(slot)?.videoTrack?.let { adjuntar(slot, it) }
            }
        } else {
            if (layer == 'A') {
                existing.rect.set(0f, 0f, 1f, 1f)
                scene.sendToBack(existing)
            } else {
                scene.placePip(existing, pipCorner)
                scene.bringToFront(existing)
            }
            // El hueco ya existía (por ejemplo, el que deja la escena del Modo Pokémon TCG): hay
            // que engancharle la pista igual, o la cámara se queda en negro en el monitor.
            engancharFuente(slot)
            scene.selectedId = existing.id
        }

        if (layer == 'A') camA = slot else camB = slot
        b.canvas.requestRender()
        refreshUi()
        onSelectionChanged(scene.selected())
    }

    private fun switchActiveLayer(layer: Char) {
        if (!pipActivo) {
            toast("Activa PiP para usar dos cámaras a la vez")
            return
        }
        activeLayer = layer
        styleLayerButtons()
        toast(if (layer == 'A') "Toca una cámara para el fondo (A)" else "Toca una cámara para la ventana (B)")
    }


    /**
     * Ajustes por orientación: en vertical no tiene sentido el botón de la cámara de la
     * tablet (no está disponible) y la barra es más angosta, así que se acortan etiquetas.
     */
    private fun ajustarBarraPorOrientacion() {
        // En la tablet vertical la cámara propia no existe; en el celular 9:16 sí, y ahí el
        // botón se queda (Jorge, 4/11: quiere cambiar frontal/trasera también en vertical).
        b.btnTabletLens.visibility =
            if (vertical && !esPantallaCelular()) View.GONE else View.VISIBLE
        b.btnPlantillas.text = if (vertical) "Plant." else "Plantillas"
        b.btnTransiciones.text = if (vertical) "Trans." else "Transiciones"
    }

    private fun styleLayerButtons() {
        // A y B solo tienen sentido con dos cámaras en pantalla (PiP o pantalla partida).
        val dosCapas = pipActivo || splitActivo
        val a = if (!dosCapas) 0.35f else if (activeLayer == 'A') 1f else 0.75f
        val bb = if (!dosCapas) 0.35f else if (activeLayer == 'B') 1f else 0.75f
        b.btnLayerA.alpha = a
        b.btnLayerB.alpha = bb
        b.btnLayerA.isEnabled = dosCapas
        b.btnLayerB.isEnabled = dosCapas
        b.btnLayerA.setBackgroundResource(
            if (dosCapas && activeLayer == 'A') R.drawable.bg_pill_accent else R.drawable.bg_pill
        )
        b.btnLayerA.setTextColor(
            ContextCompat.getColor(
                this,
                if (dosCapas && activeLayer == 'A') R.color.bg_root else R.color.text_primary
            )
        )
        b.btnLayerB.setBackgroundResource(
            if (dosCapas && activeLayer == 'B') R.drawable.bg_pill_accent else R.drawable.bg_pill
        )
        b.btnLayerB.setTextColor(
            ContextCompat.getColor(
                this,
                if (dosCapas && activeLayer == 'B') R.color.bg_root else R.color.text_primary
            )
        )
        // Marcar los botones de modo cuando están activos.
        // En el celular PiP y «2 cam» van sin texto, con icono dentro del mismo botón.
        b.btnPip.text = if (esPantallaCelular()) "" else "PiP"
        b.btnSplit.text = if (esPantallaCelular()) "" else "2cam"
        b.btnPip.background = fondoPillConIcono(pipActivo, R.drawable.ic_pip)
        b.btnPip.setTextColor(
            ContextCompat.getColor(this, if (pipActivo) R.color.bg_root else R.color.text_primary)
        )
        b.btnSplit.background = fondoPillConIcono(splitActivo, R.drawable.ic_2cam)
        b.btnSplit.setTextColor(
            ContextCompat.getColor(this, if (splitActivo) R.color.bg_root else R.color.text_primary)
        )
    }

    /**
     * Fondo de píldora con el icono centrado, para los botones que en el celular no llevan texto.
     * Mantiene el color de «activo» (naranja) porque se apoya en la misma píldora de siempre.
     */
    private fun fondoPillConIcono(activo: Boolean, icono: Int): android.graphics.drawable.Drawable? {
        val pildora = ContextCompat.getDrawable(
            this,
            if (activo) R.drawable.bg_pill_accent else R.drawable.bg_pill
        ) ?: return null
        if (!esPantallaCelular()) return pildora
        val dibujo = ContextCompat.getDrawable(this, icono) ?: return pildora
        // El icono toma el color del texto (oscuro sobre naranja, claro sobre gris).
        val color = ContextCompat.getColor(this, if (activo) R.color.bg_root else R.color.text_primary)
        dibujo.setTint(color)
        val capas = android.graphics.drawable.LayerDrawable(arrayOf(pildora, dibujo))
        capas.setLayerInset(1, 15.dp, 13.dp, 15.dp, 13.dp)
        return capas
    }

    /**
     * Enciende o apaga el modo PiP. Apagado: una sola cámara al aire (tocar cambia).
     * Encendido: se elige fondo (A) y ventana (B).
     */
    private fun alternarPip() {
        pipActivo = !pipActivo
        if (pipActivo) {
            splitActivo = false // PiP y pantalla partida son excluyentes
            // Sin un fondo claro, el PiP se vería sobre negro: se deja la única cámara
            // que haya como capa A.
            if (camA == null) {
                b.canvas.scene.cams().firstOrNull()?.let { item ->
                    camA = item.slot
                    item.rect.set(0f, 0f, 1f, 1f)
                    b.canvas.scene.sendToBack(item)
                }
            }
            activeLayer = if (camA == null) 'A' else 'B'
            toast(
                if (camA == null) "PiP activado · elige primero la cámara del fondo (A)"
                else "PiP activado · toca la cámara que va en la ventana (B)"
            )
        } else {
            // Quitar la ventana y dejar el fondo a pantalla completa.
            camB?.let { slot ->
                b.canvas.scene.camAt(slot)?.let { item ->
                    b.canvas.scene.remove(item)
                    if (slot == LocalCam.SLOT) {
                        soltar(slot, localCam.track)
                    } else {
                        soltar(slot, hub.session(slot)?.videoTrack)
                    }
                }
            }
            camB = null
            camA?.let { slot ->
                b.canvas.scene.camAt(slot)?.let { item ->
                    item.rect.set(0f, 0f, 1f, 1f)
                    b.canvas.scene.sendToBack(item)
                }
            }
            activeLayer = 'A'
            toast("PiP desactivado · una cámara al aire")
        }
        styleLayerButtons()
        updateTip()
        b.canvas.requestRender()
        refreshUi()
    }

    /** Pone la cámara elegida al aire (una sola, a pantalla completa). */
    private fun mostrarEnPrograma(slot: Int) {
        val scene = b.canvas.scene
        // Sacar del lienzo cualquier otra cámara: el visor muestra solo la elegida.
        scene.cams().filter { it.slot != slot }.forEach { item ->
            scene.remove(item)
            if (item.slot == LocalCam.SLOT) {
                soltar(item.slot, localCam.track)
            } else {
                soltar(item.slot, hub.session(item.slot)?.videoTrack)
            }
        }
        camB = null
        val item = scene.camAt(slot) ?: scene.addCam(slot) ?: return
        item.label = when {
            slot == LocalCam.SLOT -> "Tablet"
            else -> hub.session(slot)?.name ?: "Cámara ${slot + 1}"
        }
        item.rect.set(0f, 0f, 1f, 1f)
        scene.sendToBack(item)
        camA = slot
        if (slot == LocalCam.SLOT) {
            localCam.track?.let { adjuntar(slot, it) }
        } else {
            hub.session(slot)?.videoTrack?.let { adjuntar(slot, it) }
        }
        scene.selectedId = item.id
        b.canvas.animarEntrada(item)
        b.canvas.requestRender()
        refreshUi()
        onSelectionChanged(item)
    }

    // ------------------------------------------------------------------ multicámara (pantalla partida)

    /**
     * Zona que ocupa cada capa en pantalla partida.
     * Horizontal: corte vertical (izquierda / derecha). Vertical: corte horizontal (arriba / abajo).
     */
    private fun mitadDe(capa: Char): RectF = if (vertical) {
        if (capa == 'A') RectF(0f, 0f, 1f, 0.5f) else RectF(0f, 0.5f, 1f, 1f)
    } else {
        if (capa == 'A') RectF(0f, 0f, 0.5f, 1f) else RectF(0.5f, 0f, 1f, 1f)
    }

    private fun fuenteDisponible(slot: Int): Boolean = when {
        slot == LocalCam.SLOT -> localCam.encendida
        else -> hub.session(slot) != null
    }

    private fun camarasDisponibles(): List<Int> {
        val lista = ArrayList<Int>()
        for (i in 0..5) if (hub.session(i) != null) lista.add(i)
        if (localCam.encendida) lista.add(LocalCam.SLOT)
        return lista
    }

    private fun quitarCamaraDelLienzo(item: CamItem) {
        b.canvas.scene.remove(item)
        if (item.slot == LocalCam.SLOT) {
            soltar(item.slot, localCam.track)
        } else {
            soltar(item.slot, hub.session(item.slot)?.videoTrack)
        }
    }

    private fun engancharFuente(slot: Int) {
        if (slot == LocalCam.SLOT) {
            localCam.track?.let { adjuntar(slot, it) }
        } else {
            hub.session(slot)?.videoTrack?.let { adjuntar(slot, it) }
        }
    }

    private fun nombreFuente(slot: Int): String = when {
        slot == LocalCam.SLOT -> "Tablet"
        else -> hub.session(slot)?.name ?: "Cámara ${slot + 1}"
    }

    /** Coloca las dos mitades con las cámaras actuales (A y B). */
    private fun aplicarLayoutSplit() {
        val scene = b.canvas.scene
        // Solo quedan en el lienzo las dos cámaras de las mitades.
        scene.cams().filter { it.slot != camA && it.slot != camB }.forEach { quitarCamaraDelLienzo(it) }

        listOfNotNull(camA?.let { 'A' to it }, camB?.let { 'B' to it }).forEach { par ->
            val capa = par.first
            val slot = par.second
            val item = scene.camAt(slot) ?: scene.addCam(slot) ?: return@forEach
            item.label = nombreFuente(slot)
            item.rect.set(mitadDe(capa))
            engancharFuente(slot)
        }
        b.canvas.requestRender()
    }

    /** Enciende o apaga la pantalla partida 50/50. */
    private fun alternarSplit() {
        splitActivo = !splitActivo
        if (splitActivo) {
            pipActivo = false
            // Relleno automático: las dos primeras cámaras disponibles.
            val disponibles = camarasDisponibles()
            if (disponibles.isEmpty()) {
                splitActivo = false
                toast("Conecta al menos una cámara para la pantalla partida")
                styleLayerButtons()
                return
            }
            if (camA == null || !fuenteDisponible(camA!!)) camA = disponibles.getOrNull(0)
            if (camB == null || !fuenteDisponible(camB!!) || camB == camA) {
                camB = disponibles.firstOrNull { it != camA } ?: camA
            }
            aplicarLayoutSplit()
            activeLayer = 'B'
            toast(
                if (vertical) "Multicámara activa · A arriba, B abajo. Toca una cámara para cambiarla"
                else "Multicámara activa · A izquierda, B derecha. Toca una cámara para cambiarla"
            )
        } else {
            camB?.let { slot ->
                b.canvas.scene.camAt(slot)?.let { quitarCamaraDelLienzo(it) }
            }
            camB = null
            camA?.let { slot ->
                b.canvas.scene.camAt(slot)?.let { item ->
                    item.rect.set(0f, 0f, 1f, 1f)
                    b.canvas.scene.sendToBack(item)
                }
            }
            activeLayer = 'A'
            toast("Multicámara desactivada · una cámara al aire")
        }
        styleLayerButtons()
        updateTip()
        b.canvas.requestRender()
        refreshUi()
    }

    /** Intercambia las dos cámaras de la pantalla partida. */
    private fun intercambiarLados() {
        if (!splitActivo) {
            toast("Activa «2 cam» para intercambiar lados")
            return
        }
        val a = camA
        camA = camB
        camB = a
        aplicarLayoutSplit()
        refreshUi()
        updateTip()
        toast("Lados intercambiados")
    }

    /** Acerca la cámara seleccionada (alternativa al pellizco). */
    private fun cambiarZoomSeleccion(delta: Float) {
        val item = b.canvas.selection as? CamItem ?: run {
            toast("Selecciona una cámara en el lienzo")
            return
        }
        item.zoom = (item.zoom + delta).coerceIn(1f, 5f)
        b.canvas.requestRender()
        updateTip()
        toast(String.format(java.util.Locale.US, "Zoom %.1f×", item.zoom))
    }

    /** Devuelve la cámara seleccionada al plano completo. */
    private fun quitarZoomSeleccion() {
        val item = b.canvas.selection as? CamItem ?: run {
            toast("Selecciona una cámara en el lienzo")
            return
        }
        item.resetZoom()
        b.canvas.requestRender()
        updateTip()
        toast("Zoom 1.0× (plano completo)")
    }

    // ------------------------------------------------------------------ acciones de UI

    private fun wireClicks() {
        b.btnOrientacion.setOnClickListener { cambiarOrientacion(!lienzoVertical) }
        b.btnLayerA.setOnClickListener { switchActiveLayer('A') }
        b.btnLayerB.setOnClickListener { switchActiveLayer('B') }
        b.btnPip.setOnClickListener { alternarPip() }
        b.btnSplit.setOnClickListener { alternarSplit() }
        b.btnPip.setOnLongClickListener { cyclePip(); true }
        b.btnPhoto.setOnClickListener { tomarFoto() }
        b.btnRecord.setOnClickListener { toggleRecording() }
        b.btnSettings.setOnClickListener { showSettings() }
        b.btnAudio.setOnClickListener { dialogoAudio() }
        b.btnFacebook.setOnClickListener { dialogoFacebook() }
        b.btnTransiciones.setOnClickListener { dialogoTransiciones() }
        val botonesVideo = mapOf(
            "luz" to b.btnLuz,
            "contraste" to b.btnContraste,
            "wb" to b.btnWb,
            "color" to b.btnColor,
            "vineta" to b.btnVineta,
            "poster" to b.btnPoster
        )
        botonesVideo.forEach { (clave, boton) ->
            boton.setOnClickListener { seleccionarParametro(clave) }
        }
        estilizarFader(b.sbAjuste)
        b.sbAjuste.max = 200
        b.sbAjuste.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, value: Int, fromUser: Boolean) {
                aplicarSliderAjuste(value)
            }

            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
        })
        b.btnAjusteReset.setOnClickListener {
            val cam = b.canvas.selection as? CamItem
            if (cam == null) {
                toast("Selecciona una cámara para ajustar su imagen")
            } else {
                fijarVideo(cam, paramVideo, 0f)
                b.canvas.requestRender()
                refrescarPanelAjuste()
            }
        }
        b.btnPlantillas.setOnClickListener { dialogoPlantillas() }
        b.btnZoomIn.setOnClickListener { cambiarZoomSeleccion(0.5f) }
        b.btnZoomOut.setOnClickListener { cambiarZoomSeleccion(-0.5f) }
        b.btnTabletLens.setOnClickListener { ciclarLenteTablet() }
        styleLayerButtons()
        actualizarBotonSesion()
    }

    // ------------------------------------------------------------------ modo celular

    /** El diseño principal está pensado para tablet; en un celular se compacta. */
    private fun esPantallaCelular(): Boolean =
        resources.configuration.smallestScreenWidthDp < 600

    // ---------------------------------------------------------------- celular: pestañas
    /**
     * Ancho de la columna de fuentes en el celular.
     *
     * Medido el 29/10 en el POCO: el visor 16:9 está limitado por el **alto** y le sobraban ~320 px
     * de ancho a la derecha. Se le dan a la columna, así vuelven a entrar **3 tarjetas por fila**
     * sin que el monitor se achique (queda pegado a la derecha).
     */
    private val ANCHO_FUENTES_CELULAR = 322

    /** Pestañas Cámaras / Overlays / Medios (solo celular). */
    private var tabsFuentes: LinearLayout? = null
    private var seccionFuentes = 0
    /** Cuántas filas de la parrilla ocupan las cámaras; el resto del grid es overlays y medios. */
    private var filasCamaras = 0
    private var etiquetaOverlays: TextView? = null
    private var etiquetaMedios: TextView? = null
    /** «Img.» en celular: los ajustes de imagen abren su ventana en vez de una banda fija. */
    private var chipImagenCelular: TextView? = null
    /** Botones de solo icono de la barra flotante (audio y ajustes de imagen). */
    private var chipAudioIcono: ImageView? = null
    private var chipImagenIcono: ImageView? = null
    /** Flecha que muestra u oculta la barra flotante de controles (solo celular). */
    private var btnColapsarBarra: TextView? = null
    private var barraVisible = true
    /** Triángulos que avisan de que la barra se desliza (izquierda / derecha). */
    private var flechaIzq: TextView? = null
    private var flechaDer: TextView? = null
    /** En vertical el monitoreo (CPU, RAM, Mbps, °C) va debajo del monitor de programa. */
    private var txtMonitoreo: TextView? = null
    /** Botón de la cámara propia en la barra del celular (primero en vertical). */
    private var chipCamaraCelular: ImageView? = null
    /** Tarjeta donde vive la info del sistema, debajo del monitor (vertical). */
    private var cajaInfo: LinearLayout? = null

    /**
     * En el celular la lista de fuentes es larguísima (6 cámaras + tablet + overlays + medios) y
     * las de abajo quedaban fuera de la vista. Con estas pestañas se ve un grupo a la vez y la
     * columna puede ser angosta, que es lo que agranda el visor.
     */
    private fun montarTabsFuentes() {
        if (tabsFuentes != null) return
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(4.dp, 4.dp, 4.dp, 2.dp)
        }
        listOf("Cámaras", "Overlays", "Medios").forEachIndexed { i, nombre ->
            val t = TextView(this).apply {
                text = nombre
                textSize = 9f
                gravity = Gravity.CENTER
                isClickable = true
                layoutParams = LinearLayout.LayoutParams(0, 28.dp, 1f).apply {
                    if (i > 0) marginStart = 3.dp
                }
                setOnClickListener {
                    seccionFuentes = i
                    aplicarSeccionFuentes()
                }
            }
            fila.addView(t)
        }
        tabsFuentes = fila
        // Va debajo de la cabecera «CÁMARAS 0/6» y arriba de la parrilla.
        b.panelFuentes.addView(
            fila, 1,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    /** Muestra solo el grupo elegido en las pestañas (cámaras, overlays o medios). */
    private fun aplicarSeccionFuentes() {
        if (!esPantallaCelular()) return
        runCatching {
            tabsFuentes?.let { fila ->
                for (i in 0 until fila.childCount) {
                    val t = fila.getChildAt(i) as? TextView ?: continue
                    val activo = i == seccionFuentes
                    t.setBackgroundResource(
                        if (activo) R.drawable.bg_pill_accent else R.drawable.bg_pill_ghost
                    )
                    t.setTextColor(if (activo) 0xFF111318.toInt() else colorSecundario())
                }
            }
            val grid = b.llGrid
            for (i in 0 until grid.childCount) {
                val v = grid.getChildAt(i)
                val seccion = when {
                    i < filasCamaras -> 0
                    v === etiquetaOverlays || (::overlayContainer.isInitialized && v === overlayContainer) -> 1
                    v === etiquetaMedios || (::mediosContainer.isInitialized && v === mediosContainer) -> 2
                    else -> -1
                }
                v.visibility = if (seccion == -1 || seccion == seccionFuentes) View.VISIBLE else View.GONE
            }
            b.scrollFuentes.post { ocultarTarjetasFueraDeVista() }
        }
    }

    private fun colorSecundario(): Int =
        ContextCompat.getColor(this, R.color.text_secondary)

    /**
     * En el celular la fila de controles va flotando sobre el visor y tiene que **caber entera**
     * con botones del **mismo tamaño y cómodos para un dedo grande** (50×48 dp; el mínimo táctil de
     * Android son 48). Pedido de Jorge del 29/10.
     *
     * Con 9 botones no caben a ese tamaño (el visor mide ~376 dp de ancho), así que «Audio» e
     * «Img.» se suben a la barra superior —donde viven los demás ajustes— y en la barra flotante
     * quedan los 7 controles de emisión, todos iguales.
     */
    private fun compactarControlesCelular() {
        if (!esPantallaCelular()) return
        runCatching {
            val fila = b.controlesCapa
            // PiP y «2 cam» van con icono: el texto se limpia aquí (es lo último que corre al
            // armar la barra, así no queda el «2cam» encima del dibujo).
            b.btnPip.text = ""
            b.btnSplit.text = ""
            for (i in 0 until fila.childCount) {
                val v = fila.getChildAt(i) as? TextView ?: continue
                v.textSize = 12f
                v.gravity = Gravity.CENTER
                v.minWidth = 0
                v.setPadding(0, 0, 0, 0)
                v.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
                v.layoutParams = LinearLayout.LayoutParams(56.dp, 48.dp).apply {
                    if (i > 0) marginStart = 3.dp
                }
            }
            b.scrollControles.isHorizontalScrollBarEnabled = false
            // Aire a los lados para que los triángulos no tapen el primer y el último botón.
            b.controlesCapa.setPadding(20.dp, 0, 20.dp, 0)
            if (vertical) {
                // En vertical el botón de la cámara propia va PRIMERO: encenderla y cambiar de
                // frontal a trasera tiene que estar a la mano (Jorge, 29/10).
                (b.btnTabletLens.parent as? ViewGroup)?.removeView(b.btnTabletLens)
                b.controlesCapa.addView(b.btnTabletLens, 0)
                b.btnTabletLens.layoutParams = LinearLayout.LayoutParams(56.dp, 48.dp).apply {
                    marginStart = 0
                }
            }
        }
    }

    /**
     * Botón de la flecha: muestra u oculta la barra flotante de controles.
     *
     * Arriba = la barra está oculta (tocas y aparece). Abajo = está a la vista (tocas y se va).
     * El botón vive en el visor, así que sigue a la mano con la barra escondida.
     */
    private fun montarBotonColapsar() {
        if (!esPantallaCelular()) return
        val boton = btnColapsarBarra ?: TextView(this).apply {
            textSize = 24f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            isClickable = true
            setTextColor(ContextCompat.getColor(this@StudioActivity, R.color.text_primary))
            // Vidrio: translúcido con brillo, para que se lea sobre el video.
            setBackgroundResource(R.drawable.bg_glass_redondo)
            elevation = 6.dp.toFloat()
        }.also { btnColapsarBarra = it }
        if (boton.parent == null) {
            b.canvasHost.addView(
                boton,
                FrameLayout.LayoutParams(54.dp, 54.dp).apply {
                    gravity = Gravity.BOTTOM or Gravity.END
                }
            )
        }
        boton.setOnClickListener { ponerBarraVisible(!barraVisible) }
        ponerBarraVisible(barraVisible)
    }

    /**
     * En el celular el monitor de entrada **no** ocupa una banda fija (le robaba la mitad del alto
     * al visor): abre una **ventana a la izquierda**, igual que los ajustes de imagen.
     */
    private fun dialogoMonitorAudio() {
        if (vertical) return
        val caja = panelAudio ?: return
        (caja.parent as? ViewGroup)?.removeView(caja)
        caja.visibility = View.VISIBLE
        val cont = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14.dp, 4.dp, 14.dp, 4.dp)
        }
        cont.addView(
            caja,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        ondaAudio?.layoutParams =
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 120.dp)
        txtFormatoAudio?.visibility = View.VISIBLE

        val dialogo = AlertDialog.Builder(this)
            .setTitle("Monitor de entrada")
            .setView(ScrollView(this).apply { addView(cont) })
            .setPositiveButton("Listo", null)
            .create()
        dialogo.setOnDismissListener {
            uiHandler.removeCallbacks(refrescoEntrada)
            (caja.parent as? ViewGroup)?.removeView(caja)
            zonaControles?.addView(
                caja,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            ponerBarraVisible(barraVisible)
        }
        dialogo.show()
        // A la izquierda y angosta, como la ventana de imagen: el visor sigue a la vista.
        dialogo.window?.let { ventana ->
            val ancho = resources.displayMetrics.widthPixels
            val alto = resources.displayMetrics.heightPixels
            ventana.setGravity(Gravity.CENTER_VERTICAL or Gravity.START)
            ventana.setLayout((ancho * 0.42f).toInt(), (alto * 0.78f).toInt())
            val attrs = ventana.attributes
            attrs.x = 10.dp
            ventana.attributes = attrs
        }
        actualizarEntradaAudio()
        uiHandler.post(refrescoEntrada)
    }

    /** Pone la barra flotante a la vista o la esconde, y cambia la flecha del botón. */
    private fun ponerBarraVisible(visible: Boolean) {
        if (!esPantallaCelular()) return
        barraVisible = visible
        b.slotControles.visibility = if (visible) View.VISIBLE else View.GONE
        btnColapsarBarra?.let { boton ->
            // ▼ = «ocúltala» (está abierta) · ▲ = «muéstrala» (está escondida).
            boton.text = if (visible) "▼" else "▲"
            boton.contentDescription =
                if (visible) "Ocultar la barra de controles" else "Mostrar la barra de controles"
            (boton.layoutParams as? FrameLayout.LayoutParams)?.let {
                it.marginEnd = 12.dp
                // Con la barra a la vista, la flecha se sube para no encimarse con ella.
                it.bottomMargin = if (visible) 74.dp else 12.dp
                boton.layoutParams = it
            }
            boton.alpha = if (visible) 0.92f else 1f
        }
        // La foto flota más arriba para no quedar debajo de la flecha.
        (b.btnPhoto.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.bottomMargin = if (visible) 124.dp else 64.dp
            b.btnPhoto.layoutParams = it
        }
        // Los triángulos de la barra viajan con ella.
        actualizarFlechasBarra()
    }

    /**
     * Triángulos a los lados de la barra flotante: avisan de que se puede **deslizar** y que hay
     * más botones a la derecha (o a la izquierda, si ya se deslizó). Se apagan cuando ya no queda
     * nada en esa dirección, así el aviso nunca miente. Tocar un triángulo también desliza.
     */
    private fun montarFlechasBarra() {
        if (!esPantallaCelular()) return
        fun flecha(texto: String): TextView = TextView(this).apply {
            this.text = texto
            textSize = 13f
            gravity = Gravity.CENTER
            isClickable = true
            setTextColor(ContextCompat.getColor(this@StudioActivity, R.color.text_primary))
        }
        val izq = flechaIzq ?: flecha("◀").also { flechaIzq = it }
        val der = flechaDer ?: flecha("▶").also { flechaDer = it }
        val alto = 48.dp
        if (vertical) {
            // En vertical la barra está abajo, fuera del visor: los triángulos van DENTRO de la
            // barra, a los lados de la fila de botones (Jorge, 29/10).
            (izq.parent as? ViewGroup)?.removeView(izq)
            (der.parent as? ViewGroup)?.removeView(der)
            val filaBarra = b.scrollControles.parent as? ViewGroup
            if (filaBarra is LinearLayout && filaBarra.indexOfChild(izq) < 0) {
                filaBarra.orientation = LinearLayout.HORIZONTAL
                (b.scrollControles.parent as? ViewGroup)?.removeView(b.scrollControles)
                filaBarra.addView(izq, LinearLayout.LayoutParams(26.dp, alto))
                filaBarra.addView(
                    b.scrollControles,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                )
                filaBarra.addView(der, LinearLayout.LayoutParams(26.dp, alto))
            }
        } else {
            if (izq.parent == null) {
                b.canvasHost.addView(izq, FrameLayout.LayoutParams(22.dp, alto).apply {
                    gravity = Gravity.BOTTOM or Gravity.START
                    marginStart = 12.dp
                    bottomMargin = 14.dp
                })
            }
            if (der.parent == null) {
                b.canvasHost.addView(der, FrameLayout.LayoutParams(22.dp, alto).apply {
                    gravity = Gravity.BOTTOM or Gravity.END
                    marginEnd = 12.dp
                    bottomMargin = 14.dp
                })
            }
        }
        izq.setOnClickListener { b.scrollControles.smoothScrollBy(-3 * 54.dp, 0) }
        der.setOnClickListener { b.scrollControles.smoothScrollBy(3 * 54.dp, 0) }
        b.scrollControles.setOnScrollChangeListener { _, _, _, _, _ -> actualizarFlechasBarra() }
        b.scrollControles.post { actualizarFlechasBarra() }
        actualizarFlechasBarra()
    }

    /** Prende el triángulo solo si de ese lado hay botones fuera de la vista. */
    private fun actualizarFlechasBarra() {
        val sc = b.scrollControles
        val hayIzq = sc.canScrollHorizontally(-1)
        val hayDer = sc.canScrollHorizontally(1)
        flechaIzq?.let { it.alpha = if (hayIzq) 0.9f else 0.2f }
        flechaDer?.let { it.alpha = if (hayDer) 0.9f else 0.2f }
        flechaIzq?.visibility = if (barraVisible) View.VISIBLE else View.GONE
        flechaDer?.visibility = if (barraVisible) View.VISIBLE else View.GONE
    }

    /** «Audio» e «Img.» se mudan a la barra superior en el celular: ahí hay hueco y se tocan mejor.
     * Se vuelve a llamar cada vez que se rehace la fila de controles.
     */
    /**
     * Los dos botones de icono de la barra flotante: monitor de audio y ajustes de imagen.
     *
     * Van **sin texto**, con un icono cada uno, y al principio de la barra (que es deslizable)
     * para que siempre se vean.
     */
    private fun montarChipsIconoCelular() {
        if (!esPantallaCelular()) return
        // El chip de texto «♪ Audio» de la tablet se oculta aquí: lo reemplaza el icono.
        btnVistaControles?.visibility = View.GONE
        chipImagenCelular?.visibility = View.GONE
        val audio = chipAudioIcono ?: iconoChip(R.drawable.ic_audio_onda, "Monitor de entrada") {
            dialogoMonitorAudio()
        }.also { chipAudioIcono = it }
        val imagen = chipImagenIcono ?: iconoChip(R.drawable.ic_image, "Ajustes de imagen") {
            dialogoAjustesImagen()
        }.also { chipImagenIcono = it }
        // Se agregan al principio, en este orden para que queden [imagen][audio].
        for (chip in listOf(audio, imagen)) {
            if (chip.parent == null) b.controlesCapa.addView(chip, 0)
        }
    }

    /** Botón cuadrado de solo icono, del mismo tamaño que los demás de la barra. */
    private fun iconoChip(icono: Int, descripcion: String, alTocar: () -> Unit): ImageView =
        ImageView(this).apply {
            setImageResource(icono)
            contentDescription = descripcion
            isClickable = true
            setBackgroundResource(R.drawable.bg_pill_ghost)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(14.dp, 12.dp, 14.dp, 12.dp)
            setColorFilter(ContextCompat.getColor(this@StudioActivity, R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(56.dp, 48.dp).apply { marginStart = 3.dp }
            setOnClickListener { alTocar() }
        }

    private fun acomodarChipsCelular() {
        if (!esPantallaCelular()) return
        montarChipsIconoCelular()
    }

    /** Pone los dos botones de icono en la barra (antes eran un chip de texto «Img.»). */
    private fun asegurarChipImagen() {
        if (!esPantallaCelular()) return
        montarChipsIconoCelular()
    }

    /**
     * El aviso «Sin cámaras todavía» venía para tablet: en un visor de celular se salía del cuadro.
     * Se achica y se cambia el texto, que hablaba de «esta tablet».
     */
    private fun achicarAvisoCanvas() {
        runCatching {
            val hint = b.canvasHint
            hint.setPadding(12.dp, 8.dp, 12.dp, 8.dp)
            for (i in 0 until hint.childCount) {
                when (val v = hint.getChildAt(i)) {
                    is TextView -> {
                        v.textSize = 9f
                        v.maxWidth = 400.dp
                        v.text = "Sin cámaras todavía\nToca «Conectar cámaras» y usa\nla IP de este equipo en JFR Camera"
                    }
                    is ImageView -> (v.layoutParams as? FrameLayout.LayoutParams)?.let {
                        it.height = 20.dp
                        v.layoutParams = it
                    }
                }
            }
        }
    }

    /**
     * Todos los chips de la barra superior con el **mismo tamaño** (pedido de Jorge, 29/10):
     * antes cada uno medía lo que pedía su texto y quedaban desiguales.
     * El de «Facebook» pasa a llamarse **Conectar** porque pronto servirá para más plataformas.
     */
    private fun estandarizarChipsBarra() {
        // Botones parejos y cómodos para el dedo (Jorge, 29/10): la barra mide 58 dp, así que los
        // chips van a 44 dp de alto en tablet y 40 dp en celular, con más aire entre ellos.
        val celular = esPantallaCelular()
        val ancho = if (celular) 68.dp else 96.dp
        val alto = if (celular) 40.dp else 44.dp
        val letra = if (celular) 11.5f else 12.5f
        val hueco = if (celular) 6.dp else 8.dp
        listOf(
            b.btnSettings, b.btnPlantillas, b.btnTransiciones,
            b.btnOrientacion, b.btnAudio, b.btnFacebook
        ).forEach { chip ->
            chip.textSize = letra
            chip.gravity = Gravity.CENTER
            chip.setPadding(0, 0, 0, 0)
            chip.includeFontPadding = false
            chip.letterSpacing = 0.01f
            chip.layoutParams = LinearLayout.LayoutParams(ancho, alto).apply { marginStart = hueco }
        }
        b.btnFacebook.text = "Conectar"
    }

    /** Ajusta el diseño para celular: más visor, controles pequeños y ajustes en ventana. */
    private fun aplicarModoCelular() {
        if (!esPantallaCelular()) return
        // Con el lienzo 9:16 el teléfono se pone en vertical y el acomodo es otro.
        if (vertical) {
            aplicarModoCelularVertical()
            return
        }
        runCatching {
            // Al volver del 9:16 el audio quedó dentro del panel de programa: se devuelve a la
            // banda de controles, que es su sitio en horizontal.
            if (panelAudio?.parent === b.panelPrograma) ubicarPanelAudio()
            // Y se devuelve todo lo que el modo vertical esconde o encoge (Jorge, 29/10): si no,
            // la info del sistema salía dos veces y faltaba la flecha para ocultar la barra.
            b.separadorBarra.visibility = View.VISIBLE
            b.espacioBarra.visibility = View.VISIBLE
            b.btnFacebook.text = "Conectar"
            cajaInfo?.visibility = View.GONE
            txtMonitoreo?.visibility = View.GONE
            btnColapsarBarra?.visibility = View.VISIBLE
            chipAudioIcono?.visibility = View.VISIBLE
            (b.btnRecord.layoutParams as? LinearLayout.LayoutParams)?.let {
                it.width = ViewGroup.LayoutParams.WRAP_CONTENT
                it.height = ViewGroup.LayoutParams.WRAP_CONTENT
                b.btnRecord.layoutParams = it
            }
            (b.txtTimer.layoutParams as? LinearLayout.LayoutParams)?.let {
                it.width = ViewGroup.LayoutParams.WRAP_CONTENT
                it.height = ViewGroup.LayoutParams.WRAP_CONTENT
                it.marginStart = 8.dp
                b.txtTimer.layoutParams = it
            }
            b.txtTimer.textSize = 12f
            // 1) Columna de fuentes más ancha (3 tarjetas por fila) y visor pegado a la derecha con
            //    su tamaño intacto: el ancho que le sobraba al lienzo se lo queda la columna.
            //    En vertical no aplica: ahí el ancho del visor lo fija la relación 9:16.
            (b.panelFuentes.layoutParams as? LinearLayout.LayoutParams)?.let {
                it.width = ANCHO_FUENTES_CELULAR.dp
                it.weight = 0f
            }
            (b.panelPrograma.layoutParams as? LinearLayout.LayoutParams)?.let {
                it.width = 0
                it.weight = 1f
            }
            // WRAP_CONTENT + END: el visor conserva la medida que le da el 16:9 y se pega al borde
            // derecho del panel (antes quedaba un hueco negro de ~320 px a su derecha).
            (b.canvasHost.layoutParams as? LinearLayout.LayoutParams)?.let {
                it.width = ViewGroup.LayoutParams.WRAP_CONTENT
                it.gravity = Gravity.END
            }

            // 2) Barra superior compacta.
            b.btnPlantillas.text = "Plant."
            b.btnTransiciones.text = "Trans."
            // Con «Img.» y «Monitor» en la barra, «Rec + Broadcast» se salía de la pantalla.
            b.btnRecord.text = "Rec"
            b.txtLive.visibility = View.GONE
            // La línea «CÁMARAS · PROGRAMA · CPU…» es solo informativa: más chica para ganar aire.
            b.txtEtiquetaPrograma.textSize = 9f
            b.txtCanvasInfo.textSize = 9f
            b.txtCamCount.textSize = 9f
            (b.imgLogo.layoutParams as? LinearLayout.LayoutParams)?.let {
                it.width = 34.dp
                it.height = 26.dp
            }
            listOf(
                b.btnSettings, b.btnPlantillas, b.btnTransiciones,
                b.btnOrientacion, b.btnAudio
            ).forEach { vista ->
                vista.textSize = 10f
                vista.setPadding(7.dp, 4.dp, 7.dp, 4.dp)
                (vista.layoutParams as? LinearLayout.LayoutParams)?.let { it.marginStart = 4.dp }
            }

            // 3) Controles bajo el visor más pequeños.
            for (i in 0 until b.controlesCapa.childCount) {
                val v = b.controlesCapa.getChildAt(i) as? TextView ?: continue
                recordarEstilo(v)
                v.textSize = 8f
                v.setPadding(4.dp, 2.dp, 4.dp, 2.dp)
            }

            // 4) Los ajustes de imagen se van a una ventana aparte (no caben en una banda).
            b.cajaVideo.visibility = View.GONE
            zonaControles?.visibility =
                if (vistaControles == "audio") View.VISIBLE else View.GONE
            // El medidor de audio ya vive en la barra superior (junto a Facebook): en el celular
            // solo se hace más chico, sin moverlo de sitio.
            b.audioMeter.layoutParams = LinearLayout.LayoutParams(56.dp, 16.dp)
            // El botón de la cámara de la tablet ya lleva su estado (Apagada / Frontal / Trasera):
            // no se renombra aquí para no perder esa información.
            b.btnSplit.text = "2cam"
            // 5) Pestañas de la columna de fuentes, aviso del visor a la medida y «Img.» en la fila.
            montarTabsFuentes()
            achicarAvisoCanvas()
            aplicarSeccionFuentes()

            // 6) Los controles del programa se pasan ENCIMA del visor. Antes vivían en una fila
            //    debajo del lienzo que costaba 116 px de alto: al flotarlos, ese alto es del
            //    programa (el visor pasa de 687 a ~800 px de alto en el POCO).
            val host = b.canvasHost
            (b.slotControles.parent as? ViewGroup)?.let { padre ->
                if (padre !== host) padre.removeView(b.slotControles)
            }
            if (b.slotControles.parent == null) {
                host.addView(
                    b.slotControles,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        gravity = Gravity.BOTTOM
                        setMargins(10.dp, 0, 10.dp, 8.dp)
                    }
                )
            }
            b.slotControles.setBackgroundResource(R.drawable.bg_barra_flotante)
            b.slotControles.setPadding(6.dp, 4.dp, 6.dp, 4.dp)
            // El botón de foto flota por encima de la fila de controles, no debajo de ella.
            (b.btnPhoto.layoutParams as? FrameLayout.LayoutParams)?.let {
                it.bottomMargin = 62.dp
                b.btnPhoto.layoutParams = it
            }
            // Y la fila tiene que caber entera sobre el visor.
            compactarControlesCelular()
            // «Audio» e «Img.» se van a la barra superior (en la flotante no caben a buen tamaño).
            asegurarChipImagen()
            acomodarChipsCelular()
            // Flecha para mostrar u ocultar la barra flotante.
            montarBotonColapsar()
            // Triángulos de «se desliza» a los dos lados de la barra.
            montarFlechasBarra()
        }
    }

    /**
     * Celular con el lienzo en **9:16**: la pantalla va en vertical y manda el visor.
     *
     * Arriba una barra sin adornos (logo, medidor y cronómetro fuera: en vertical no caben), el
     * visor 9:16 con todo el ancho, la columna de fuentes como **tira abajo** (128 dp, se desliza)
     * y los controles flotando encima del visor, igual que en horizontal.
     */
    private fun aplicarModoCelularVertical() {
        runCatching {
            // 1) Barra superior que quepa: los seis chips se reparten el ancho.
            b.imgLogo.visibility = View.GONE
            b.audioMeter.visibility = View.GONE
            b.liveDot.visibility = View.GONE
            b.txtLive.visibility = View.GONE
            // El cronómetro de grabación/emisión SÍ se queda en vertical, pegado a «Conectar».
            b.txtTimer.visibility = View.VISIBLE
            b.txtTimer.textSize = 9f
            b.txtTimer.includeFontPadding = false
            b.txtTimer.gravity = Gravity.CENTER
            (b.txtTimer.layoutParams as? LinearLayout.LayoutParams)?.let {
                it.width = 38.dp
                it.height = 38.dp
                it.weight = 0f
                it.marginStart = 3.dp
                b.txtTimer.layoutParams = it
            }
            // «Rec» un poco más angosto para que todo siga entrando en la misma línea.
            (b.btnRecord.layoutParams as? LinearLayout.LayoutParams)?.let {
                it.width = 84.dp
                it.height = 38.dp
                it.marginStart = 5.dp
                b.btnRecord.layoutParams = it
            }
            b.btnRecord.text = "Rec"
            for (chip in listOf(
                b.btnSettings, b.btnPlantillas, b.btnTransiciones,
                b.btnOrientacion, b.btnAudio, b.btnFacebook
            )) {
                chip.textSize = 8f
                chip.gravity = Gravity.CENTER
                chip.maxLines = 1
                chip.setPadding(0, 0, 0, 0)
                chip.layoutParams = LinearLayout.LayoutParams(0, 30.dp, 1f).apply {
                    marginStart = 3.dp
                }
            }
            // La cabecera «CÁMARAS 0/6» no hace falta: en vertical manda la fila de pestañas.
            (b.txtCamCount.parent as? View)?.visibility = View.GONE

            // 2) Dos columnas, como en horizontal pero angostas: las tarjetas a la izquierda
            //    (2 por fila) y el monitor de programa arriba a la derecha (pedido de Jorge, 29/10).
            b.cuerpo.orientation = LinearLayout.HORIZONTAL
            b.slotIzquierdo.visibility = View.GONE
            (b.panelPrograma.parent as? ViewGroup)?.removeView(b.panelPrograma)
            (b.panelFuentes.parent as? ViewGroup)?.removeView(b.panelFuentes)
            b.cuerpo.addView(
                b.panelFuentes,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.46f)
            )
            b.cuerpo.addView(
                b.panelPrograma,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.54f)
            )
            (b.panelFuentes.layoutParams as? LinearLayout.LayoutParams)?.let {
                it.width = 178.dp
                it.height = ViewGroup.LayoutParams.MATCH_PARENT
                it.weight = 0f
            }
            (b.panelPrograma.layoutParams as? LinearLayout.LayoutParams)?.let {
                it.width = 0
                it.height = ViewGroup.LayoutParams.MATCH_PARENT
                it.weight = 1f
            }
            // El visor se queda arriba a la derecha y con su forma 9:16 (alto según el ancho).
            (b.canvasHost.layoutParams as? LinearLayout.LayoutParams)?.let {
                it.width = ViewGroup.LayoutParams.MATCH_PARENT
                // Más chico y siempre dentro de la pantalla: toma el 58 % del alto del panel y el
                // AspectFrameLayout lo ajusta al 9:16 sin salirse a los lados.
                it.height = 0
                it.weight = 0.58f
                it.gravity = Gravity.CENTER_HORIZONTAL
            }

            // 3) Los botones van al fondo de la pantalla, a todo lo ancho (no sobre el visor).
            val contenido = b.contenido
            (b.slotControles.parent as? ViewGroup)?.removeView(b.slotControles)
            contenido.addView(
                b.slotControles,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            b.slotControles.visibility = View.VISIBLE
            b.slotControles.setBackgroundResource(R.drawable.bg_barra_flotante)
            b.slotControles.setPadding(6.dp, 4.dp, 6.dp, 4.dp)
            (b.btnPhoto.layoutParams as? FrameLayout.LayoutParams)?.let {
                it.bottomMargin = 12.dp
                b.btnPhoto.layoutParams = it
            }

            // 4) Pestañas, iconos, flechas y aviso del visor.
            montarTabsFuentes()
            compactarControlesCelular()
            asegurarChipImagen()
            montarBotonColapsar()
            montarFlechasBarra()
            achicarAvisoCanvas()
            aplicarSeccionFuentes()

            // 5) Aquí la barra no está dentro del visor, así que la flecha de colapsar no hace
            //    falta (Jorge, 29/10). El botón de audio tampoco: las ondas ya están fijas abajo.
            // En vertical NO hay flecha para volver a mostrarla: si venía oculta del horizontal,
            // aquí la barra se fuerza visible (Jorge, 29/10).
            if (!barraVisible) ponerBarraVisible(true)
            btnColapsarBarra?.visibility = View.GONE
            chipAudioIcono?.visibility = View.GONE
            // La info del sistema va en su propia tarjeta, con aire (Jorge, 29/10).
            val caja = cajaInfo ?: LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_chip)
                setPadding(9.dp, 6.dp, 9.dp, 6.dp)
            }.also { cajaInfo = it }
            val monitor = txtMonitoreo ?: TextView(this).apply {
                textSize = 10f
                setTextColor(ContextCompat.getColor(this@StudioActivity, R.color.text_secondary))
            }.also { txtMonitoreo = it }
            (monitor.parent as? ViewGroup)?.removeView(monitor)
            caja.removeAllViews()
            caja.addView(
                monitor,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            (caja.parent as? ViewGroup)?.removeView(caja)
            val trasVisor = (b.panelPrograma.indexOfChild(b.canvasHost) + 1).coerceAtLeast(0)
            b.panelPrograma.addView(
                caja,
                trasVisor,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(6.dp, 6.dp, 6.dp, 6.dp) }
            )
            caja.visibility = View.VISIBLE
            monitor.visibility = View.VISIBLE

            // 6) Las ondas de audio ocupan el espacio libre debajo del monitor (Jorge, 29/10).
            panelAudio?.let { audio ->
                audio.visibility = View.VISIBLE
                if ((audio.parent as? ViewGroup) !== b.panelPrograma) {
                    (audio.parent as? ViewGroup)?.removeView(audio)
                    b.panelPrograma.addView(
                        audio,
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.34f)
                    )
                } else {
                    (audio.layoutParams as? LinearLayout.LayoutParams)?.let {
                        it.width = ViewGroup.LayoutParams.MATCH_PARENT
                        it.height = 0
                        it.weight = 0.34f
                        audio.layoutParams = it
                    }
                }
                ondaAudio?.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
                )
                // En vertical el panel es angosto: la ayuda corta y sin el formato en la cabecera.
                txtFormatoAudio?.visibility = View.GONE
                txtAyudaAudio?.text = "Verde < −12 · naranja −12 a −3 · rojo > −3"
            }
            // Y el visor se queda con el 58 % del alto.
            (b.canvasHost.layoutParams as? LinearLayout.LayoutParams)?.let {
                it.height = 0
                it.weight = 0.58f
            }

            // 7) La cámara propia, PRIMERA en la barra, con el MISMO botón de la barra horizontal
            //    («Off / F / T»): Jorge lo pidió el 4/11 para cambiar entre frontal y trasera
            //    también en 9:16. El chip de icono que había antes se retira (no se entendía).
            b.btnTabletLens.visibility = View.VISIBLE
            (b.btnTabletLens.parent as? ViewGroup)?.removeView(b.btnTabletLens)
            b.controlesCapa.addView(b.btnTabletLens, 0)
            b.btnTabletLens.layoutParams = LinearLayout.LayoutParams(56.dp, 48.dp)
            chipCamaraCelular?.let { (it.parent as? ViewGroup)?.removeView(it) }
            actualizarBotonTablet()
            for (i in 0 until b.controlesCapa.childCount) {
                val v = b.controlesCapa.getChildAt(i) as? TextView ?: continue
                v.textSize = 12f
                v.gravity = Gravity.CENTER
                v.setPadding(0, 0, 0, 0)
                v.layoutParams = LinearLayout.LayoutParams(56.dp, 48.dp).apply {
                    if (i > 0) marginStart = 3.dp
                }
            }
            // 9) La barra de arriba se reafirma AQUÍ: estandarizarChipsBarra() corre después y
            //    dejaría los chips fijos (no caben los seis + «Rec» en vertical).
            for (chip in listOf(
                b.btnSettings, b.btnPlantillas, b.btnTransiciones,
                b.btnOrientacion, b.btnAudio, b.btnFacebook
            )) {
                // Con el cronómetro en la misma línea queda poco ancho: 8,7 sp y sin espaciado
                // extra para que «Ajustes» entre completo.
                chip.textSize = 9.2f
                chip.gravity = Gravity.CENTER
                chip.setPadding(0, 0, 0, 0)
                chip.includeFontPadding = false
                chip.letterSpacing = 0f
                chip.layoutParams = LinearLayout.LayoutParams(0, 38.dp, 1f).apply {
                    marginStart = 2.dp
                }
            }
            // Todos del MISMO ancho (Jorge, 29/10). «Conectar» se abrevia para que entre parejo, y
            // el separador se quita: ese espacio se reparte entre los botones.
            b.btnFacebook.text = "Conect."
            b.separadorBarra.visibility = View.GONE
            // El hueco flexible entre los chips y el cronómetro también se reparte: así los
            // botones quedan más anchos y la barra no tiene espacio muerto (Jorge, 29/10).
            b.espacioBarra.visibility = View.GONE

            // 8) El botón de la foto, a la esquina derecha de abajo del monitor (Jorge, 29/10).
            (b.btnPhoto.layoutParams as? FrameLayout.LayoutParams)?.let {
                it.gravity = Gravity.BOTTOM or Gravity.END
                it.bottomMargin = 10.dp
                it.marginEnd = 10.dp
                b.btnPhoto.layoutParams = it
            }
        }
    }

    /** Ventana con los seis ajustes de imagen de la cámara activa. */
    private fun dialogoAjustesImagen() {
        // En el celular hay poco alto: filas apretadas para que entren los seis sin desplazar.
        val apretado = esPantallaCelular()
        // En vertical hay espacio: riel grueso, filas separadas y letra un poco más grande.
        val fino = esPantallaCelular() && vertical
        val contenedor = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp, if (apretado) 2.dp else 8.dp, 20.dp, if (apretado) 2.dp else 8.dp)
        }
        val cam = camaraParaAjustes()
        paramsVideo.forEach { (clave, nombre, pista) ->
            val fila = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(
                    0,
                    if (fino) 8.dp else if (apretado) 1.dp else 5.dp,
                    0,
                    if (fino) 8.dp else if (apretado) 1.dp else 5.dp
                )
            }
            fila.addView(TextView(this).apply {
                text = nombre
                textSize = if (fino) 12f else if (apretado) 9f else 11f
                setTextColor(ContextCompat.getColor(this@StudioActivity, R.color.text_secondary))
                width = 64.dp
            })
            val valor = TextView(this).apply {
                text = etiquetaAjuste(clave, cam?.let { valorVideo(it, clave) } ?: 0f)
                textSize = if (fino) 12f else if (apretado) 9f else 11f
                gravity = Gravity.END
                setTextColor(ContextCompat.getColor(this@StudioActivity, R.color.accent))
                width = 42.dp
            }
            val barra = android.widget.SeekBar(this).apply {
                // En vertical de celular los deslizadores van finos (línea) para que la ventana
                // sea baja y deje ver el monitor mientras se ajusta (Jorge, 29/10).
                if (fino) {
                    progressDrawable = ContextCompat.getDrawable(this@StudioActivity, R.drawable.fader_riel)
                    thumb = ContextCompat.getDrawable(this@StudioActivity, R.drawable.fader_perilla)
                    thumbOffset = 0
                    splitTrack = false
                } else {
                    estilizarFader(this)
                }
                max = 200
                progress = (if (escalaVideo(clave)) {
                    100f + (cam?.let { valorVideo(it, clave) } ?: 0f) * 100f
                } else {
                    ((cam?.let { valorVideo(it, clave) } ?: 0f) + 1f) * 100f
                }).toInt().coerceIn(0, 200)
                setOnSeekBarChangeListener(object :
                    android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                        sb: android.widget.SeekBar?,
                        value: Int,
                        fromUser: Boolean
                    ) {
                        val objetivo = camaraParaAjustes() ?: return
                        val v = if (escalaVideo(clave)) {
                            ((value - 100f) / 100f).coerceAtLeast(0f)
                        } else {
                            value / 100f - 1f
                        }
                        fijarVideo(objetivo, clave, v)
                        valor.text = etiquetaAjuste(clave, v)
                        b.canvas.requestRender()
                    }

                    override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                    override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
                })
            }
            fila.addView(
                barra,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { setMargins(6.dp, 0, 6.dp, 0) }
            )
            fila.addView(valor)
            contenedor.addView(fila)
        }
        val dialogo = AlertDialog.Builder(this)
            .setTitle("Imagen · ${cam?.label ?: "sin cámara"}")
            .setView(ScrollView(this).apply { addView(contenedor) })
            .setPositiveButton("Listo", null)
            .setNeutralButton("Restablecer") { _, _ ->
                camaraParaAjustes()?.let { objetivo ->
                    objetivo.resetImagen()
                    b.canvas.requestRender()
                }
            }
            .create()
        dialogo.show()
        // A la izquierda y angosto: el visor sigue visible mientras se ajusta.
        dialogo.window?.let { ventana ->
            val anchoPantalla = resources.displayMetrics.widthPixels
            ventana.setGravity(if (vertical) Gravity.BOTTOM or Gravity.START else Gravity.CENTER_VERTICAL or Gravity.START)
            if (esPantallaCelular()) {
                // En el celular el alto es poco: la ventana se acota y los seis ajustes se
                // alcanzan desplazando (antes los últimos quedaban cortados sin poder llegar).
                val altoPantalla = resources.displayMetrics.heightPixels
                // En vertical (9:16) la pantalla es angosta: se le da casi todo el ancho, si no la
                // ventana abría de 155 dp y no servía para nada (Jorge, 29/10).
                val ancho = if (vertical) (anchoPantalla * 0.92f).toInt() else (anchoPantalla * 0.42f).toInt()
                // En vertical la ventana es BAJA y va abajo: así el monitor sigue a la vista.
                val alto = if (vertical) (altoPantalla * 0.47f).toInt() else (altoPantalla * 0.86f).toInt()
                ventana.setLayout(ancho, alto)
            } else {
                ventana.setLayout(
                    (anchoPantalla * 0.46f).toInt(),
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            val attrs = ventana.attributes
            attrs.x = 10.dp
            ventana.attributes = attrs
        }
    }
    /** Ajuste de imagen de la cámara seleccionada (luz, contraste, WB, temperatura, color). */
    // ------------------------------------------------------------------ ajustes de imagen

    /** Parámetro de imagen seleccionado en el panel permanente. */
    private var paramVideo = "luz"

    /** Contenedor e índice originales del bloque de ajustes (para volver a horizontal). */
    private var huecoVideo: ViewGroup? = null
    private var indiceVideo = 0

    private val paramsVideo = listOf(
        Triple("luz", "Luz", "Brillo general"),
        Triple("contraste", "Contraste", "Separa luces y sombras"),
        Triple("wb", "WB", "Balance de blancos (magenta ↔ verde)"),
        Triple("color", "Color", "Saturación"),
        Triple("vineta", "Viñeta", "Oscurece las orillas"),
        Triple("poster", "Poster.", "Reduce los niveles de color")
    )

    private fun valorVideo(cam: CamItem, clave: String): Float = when (clave) {
        "luz" -> cam.luz
        "contraste" -> cam.contraste
        "wb" -> cam.wb
        "color" -> cam.color
        "vineta" -> cam.vineta
        else -> cam.poster
    }

    private fun fijarVideo(cam: CamItem, clave: String, v: Float) {
        when (clave) {
            "luz" -> cam.luz = v
            "contraste" -> cam.contraste = v
            "wb" -> cam.wb = v
            "color" -> cam.color = v
            "vineta" -> cam.vineta = v.coerceIn(0f, 1f)
            "poster" -> cam.poster = v.coerceIn(0f, 1f)
        }
    }

    /** Escala real del parámetro: viñeta y posterización van de 0 a 1. */
    private fun escalaVideo(clave: String): Boolean = clave == "vineta" || clave == "poster"

    private fun etiquetaAjuste(clave: String, v: Float): String {
        val n = (v * 100).toInt()
        return if (escalaVideo(clave)) "$n" else if (n > 0) "+$n" else "$n"
    }

    /** Refresca el panel del deslizable con el parámetro y la cámara actuales. */
    /** Cámara sobre la que actúan los ajustes: la seleccionada o, si no hay, la que está al aire. */
    private fun camaraParaAjustes(): CamItem? {
        (b.canvas.selection as? CamItem)?.let { return it }
        val slotAire = camA ?: camB ?: return null
        return b.canvas.scene.items.filterIsInstance<CamItem>().firstOrNull { it.slot == slotAire }
    }

    private fun refrescarPanelAjuste() {
        val (clave, nombre, pista) = paramsVideo.firstOrNull { it.first == paramVideo }
            ?: paramsVideo.first()
        val cam = b.canvas.selection as? CamItem
        b.txtAjusteNombre.text = nombre
        b.txtAjusteNombre.contentDescription = pista
        val v = cam?.let { valorVideo(it, clave) } ?: 0f
        b.sbAjuste.isEnabled = cam != null
        b.sbAjuste.alpha = if (cam != null) 1f else 0.45f
        b.sbAjuste.progress = (if (escalaVideo(clave)) (100f + v * 100f) else ((v + 1f) * 100f))
            .toInt().coerceIn(0, 200)
        b.txtAjusteValor.text = etiquetaAjuste(clave, v)
        // Resalta el botón del parámetro activo
        val mapa = mapOf(
            "luz" to b.btnLuz,
            "contraste" to b.btnContraste,
            "wb" to b.btnWb,
            "color" to b.btnColor,
            "vineta" to b.btnVineta,
            "poster" to b.btnPoster
        )
        mapa.forEach { (k, boton) ->
            val activo = k == clave
            // El seleccionado se rellena de naranja y se ve un poco más grande.
            boton.setBackgroundResource(
                if (activo) R.drawable.bg_pill_accent else R.drawable.bg_pill_ghost
            )
            boton.setTextColor(
                ContextCompat.getColor(this, if (activo) R.color.bg_root else R.color.text_primary)
            )
            boton.alpha = 1f
            boton.scaleX = if (activo) 1.08f else 1f
            boton.scaleY = if (activo) 1.08f else 1f
        }
    }

    /**
     * Elige el parámetro. En horizontal cambia el deslizable permanente; en vertical abre
     * el cuadro de ajuste como al principio.
     */
    private fun seleccionarParametro(clave: String) {
        paramVideo = clave
        refrescarPanelAjuste()
        if (vertical) dialogoAjuste(clave)
    }

    /** Cuadro de ajuste de un solo parámetro (modo vertical). */
    private fun dialogoAjuste(clave: String) {
        val info = paramsVideo.firstOrNull { it.first == clave }
        val nombre = info?.second ?: clave
        val pista = info?.third ?: ""
        val caja = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp, 12.dp, 24.dp, 12.dp)
        }
        val valor = TextView(this).apply {
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@StudioActivity, R.color.accent))
        }
        val barra = android.widget.SeekBar(this).apply {
            estilizarFader(this)
            max = 200
        }
        fun pintar(v: Float) {
            barra.progress = if (escalaVideo(clave)) (100f + v * 100f).toInt().coerceIn(0, 200)
            else ((v + 1f) * 100f).toInt().coerceIn(0, 200)
            valor.text = etiquetaAjuste(clave, v)
        }
        pintar(camaraParaAjustes()?.let { valorVideo(it, clave) } ?: 0f)
        barra.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, value: Int, fromUser: Boolean) {
                val cam = camaraParaAjustes() ?: return
                val v = if (escalaVideo(clave)) ((value - 100f) / 100f).coerceAtLeast(0f)
                else value / 100f - 1f
                fijarVideo(cam, clave, v)
                valor.text = etiquetaAjuste(clave, v)
                b.canvas.requestRender()
            }

            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
        })
        caja.addView(valor)
        caja.addView(TextView(this).apply {
            text = pista
            textSize = 11f
            setTextColor(ContextCompat.getColor(this@StudioActivity, R.color.text_secondary))
            setPadding(0, 2.dp, 0, 10.dp)
        })
        caja.addView(barra)
        caja.addView(
            TextView(this).apply {
                text = "Restablecer"
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(this@StudioActivity, R.color.text_primary))
                setBackgroundResource(R.drawable.bg_pill_ghost)
                setPadding(16.dp, 8.dp, 16.dp, 8.dp)
                setOnClickListener {
                    camaraParaAjustes()?.let { cam ->
                        fijarVideo(cam, clave, 0f)
                        b.canvas.requestRender()
                        pintar(0f)
                    }
                }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 14.dp }
        )
        AlertDialog.Builder(this)
            .setTitle("$nombre · ${camaraParaAjustes()?.label ?: "sin cámara"}")
            .setView(caja)
            .setPositiveButton("Listo", null)
            .show()
    }

    private fun aplicarSliderAjuste(progress: Int) {
        val cam = b.canvas.selection as? CamItem ?: return
        val clave = paramVideo
        val v = if (escalaVideo(clave)) ((progress - 100f) / 100f).coerceAtLeast(0f)
        else progress / 100f - 1f
        fijarVideo(cam, clave, v)
        b.txtAjusteValor.text = etiquetaAjuste(clave, v)
        b.canvas.requestRender()
    }

    private fun cyclePip() {
        pipCorner = (pipCorner + 1) % 4
        val names = listOf("superior izquierda", "superior derecha", "inferior izquierda", "inferior derecha")
        toast("PiP en esquina ${names[pipCorner]}")
        val slot = camB ?: return
        b.canvas.applyPip(pipCorner, slot)
    }

    private fun removeSelected() {
        val item = b.canvas.selection ?: return
        b.canvas.scene.remove(item)
        if (item is CamItem) {
            if (camA == item.slot) camA = null
            if (camB == item.slot) camB = null
            if (item.slot == LocalCam.SLOT) {
                soltar(item.slot, localCam.track)
            } else {
                soltar(item.slot, hub.session(item.slot)?.videoTrack)
            }
            // Si queda otra cámara en el lienzo, esa pasa a ser el fondo (capa A).
            val restante = b.canvas.scene.cams().firstOrNull()
            if (restante != null && camA == null) {
                camA = restante.slot
                restante.rect.set(0f, 0f, 1f, 1f)
                b.canvas.scene.sendToBack(restante)
            }
        }
        b.canvas.requestRender()
        refreshUi()
        onSelectionChanged(null)
    }

    private fun addOverlay(uri: Uri) {
        // Permiso permanente para que la imagen siga disponible al abrir una plantilla
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        try {
            val bitmap = loadScaledBitmap(uri) ?: run {
                toast("No se pudo abrir la imagen")
                return
            }
            val item = b.canvas.scene.addImage(bitmap, "Overlay", uri.toString())
            b.canvas.requestRender()
            onSelectionChanged(item)
            toast("Imagen agregada: arrástrala en el lienzo")
        } catch (t: Throwable) {
            L.e("studio", "overlay: ${t.message}", t)
            toast("No se pudo cargar la imagen")
        }
    }

    private fun loadScaledBitmap(uri: Uri, maxSide: Int = 1600): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    // ------------------------------------------------------------------ grabación

    private fun toggleRecording() {
        // «Solo transmitir» y «Grabar y transmitir a la vez» arrancan el directo: en los dos
        // la grabación va con el mismo codificador del envío. Solo «Solo grabar» usa el
        // grabador local.
        if (modoSesion == "transmitir" || modoSesion == "ambos") {
            if (transmitiendo) {
                cortarTransmision()
                return
            }
            // Sin permiso de micrófono el codificador de audio no arranca y la sesión falla:
            // se pide antes y, si se niega, no se abre el directo.
            if (recAudio && ContextCompat.checkSelfPermission(
                    this, Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                audioPermission.launch(Manifest.permission.RECORD_AUDIO)
                return
            }
            iniciarTransmision()
            return
        }
        if (recorder.isRecording) {
            stopRecording()
        } else {
            if (recAudio && ContextCompat.checkSelfPermission(
                    this, Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                audioPermission.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                startRecording(recAudio)
            }
        }
    }

    /** Protege micrófono y cámara mientras la sesión sigue fuera de la pantalla. */
    private fun iniciarProteccionSesion() {
        // Se congela la orientación: el diseño no cambia a media grabación ni a media transmisión.
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED
        // Y las cámaras también: su formato queda fijo hasta que termine la sesión.
        bloquearRotacionCamaras(true)
        // Si el equipo pausa el video al minimizar la app, se reanuda solo: así no se graba congelado.
        vigilarVideoEnSegundoPlano()
        com.jfrmedia.switcher.studio.stream.EmisionService.iniciar(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permisoNotificaciones.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun detenerProteccionSesion() {
        com.jfrmedia.switcher.studio.stream.EmisionService.detener(this)
        uiHandler.removeCallbacks(vigilarVideo)
        // Las cámaras vuelven a seguir la posición del teléfono.
        bloquearRotacionCamaras(false)
        // Se vuelve a permitir que la tablet gire (en el celular queda fijo en horizontal).
        requestedOrientation = if (esPantallaCelular()) {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
        } else {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER
        }
        avaliarDisenoTrasSesion()
    }

    /**
     * Congela (o suelta) la rotación de los celulares. Mientras el switcher graba o transmite el
     * formato queda fijo: si alguien mueve un teléfono, la cámara no se voltea a media sesión.
     */
    private fun bloquearRotacionCamaras(bloqueado: Boolean) {
        hub.rotacionBloqueada = bloqueado
        hub.all().forEach { runCatching { it.enviarBloqueoRotacion() } }
        L.i("studio", if (bloqueado) "rotación de cámaras bloqueada" else "rotación de cámaras libre")
    }

    /**
     * Mientras dura la sesión, cada segundo se comprueba que el video al aire siga corriendo.
     * Algunos equipos lo pausan al minimizar la app y la grabación quedaba congelada.
     */
    private val vigilarVideo = object : Runnable {
        override fun run() {
            if (sesionEnCurso()) {
                runCatching { vistaAlAire().reanudarMediosSiHaceFalta() }
                uiHandler.postDelayed(this, 1000)
            }
        }
    }

    private fun vigilarVideoEnSegundoPlano() {
        uiHandler.removeCallbacks(vigilarVideo)
        uiHandler.postDelayed(vigilarVideo, 1000)
    }

    /** Al soltar la sesión, si la tablet quedó girada, el diseño se ajusta ahora. */
    private fun avaliarDisenoTrasSesion() {
        uiHandler.postDelayed({ evaluarModoStudio() }, 350)
    }

    private fun startRecording(withAudio: Boolean) {
        // El vúmetro suelta el micrófono antes de que el grabador lo abra: si siguiera tomado,
        // la grabación saldría sin audio y sin avisar.
        runCatching { audioVivo.detener() }
        // Durante la transmisión se graba con el mismo codificador del emisor.
        if (transmitiendo) {
            if (grabarConEmisor()) {
                recStartMs = SystemClock.elapsedRealtime()
                b.liveDot.setBackgroundResource(R.drawable.bg_dot_red)
            }
            return
        }
        if (recorder.isRecording) return
        // Si el codificador del equipo no acepta la resolución elegida (2K y 4K no existen en
        // esta tablet), se baja a la anterior hasta que alguna arranque: es lo que promete la
        // pantalla de Ajustes. Se silencia el aviso del grabador para no llenar de toasts.
        val avisoGrabador = recorder.onError
        recorder.onError = null
        var surface: Surface? = null
        var anchoGrabado = recAncho
        var altoGrabado = recAlto
        var ladoGrabado = recAltoElegido
        try {
            for (lado in resolucionesGrabacion()) {
                val (w, h) = tamanoGrabacion(lado)
                surface = recorder.start(w, h, 30, recBitrate, withAudio)
                if (surface != null) {
                    anchoGrabado = w
                    altoGrabado = h
                    ladoGrabado = lado
                    break
                }
            }
        } finally {
            recorder.onError = avisoGrabador
        }
        if (surface == null) {
            toast("No se pudo iniciar la grabación")
            return
        }
        if (ladoGrabado != recAltoElegido) {
            toast("El equipo no acepta ${recAltoElegido}p: se graba en ${ladoGrabado}p ($anchoGrabado×$altoGrabado)")
        }
        anchoGrabando = anchoGrabado
        altoGrabando = altoGrabado
        actualizarInfoLienzo()
        vistaAlAire().beginRecordingOutput(surface, anchoGrabado, altoGrabado, recorder.baseTimeNs)
        recStartMs = SystemClock.elapsedRealtime()
        iniciarProteccionSesion()
        actualizarCandadoOrientacion()
        b.liveDot.setBackgroundResource(R.drawable.bg_dot_red)
        abrirRegistro("grabación")
        b.txtLive.text = "Grabando"
        b.btnRecord.text = "Detener"
        b.btnRecord.setBackgroundResource(R.drawable.bg_pill_ghost)
        b.btnRecord.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        uiHandler.post(ticker)

    }

    private fun stopRecording() {
        vistaAlAire().endRecordingOutput()
        recorder.stop()
        uiHandler.removeCallbacks(ticker)
        actualizarCandadoOrientacion()
        b.liveDot.setBackgroundResource(R.drawable.bg_dot_gray)
        if (!emisor.transmitiendo) cerrarRegistro()
        b.txtLive.text = "Sin grabación"
        b.txtTimer.text = "00:00"
        actualizarBotonSesion()
        b.btnRecord.setBackgroundResource(R.drawable.bg_pill_accent)
        b.btnRecord.setTextColor(ContextCompat.getColor(this, R.color.bg_root))

        val file = recorder.outputFile
        if (file != null && file.exists() && file.length() > 1024) {
            lastVideoFile = file
            lastVideoUri = publishVideo(file)
            val mb = file.length() / (1024 * 1024)
            toast("Grabación lista: ${mb.coerceAtLeast(1)} MB")
        } else {
            toast("No se generó el archivo de video")
        }
        // El chip del visor vuelve a mostrar la resolución elegida en Ajustes.
        anchoGrabando = 0
        altoGrabando = 0
        actualizarInfoLienzo()
        // Si no queda ninguna sesión abierta, se suelta la protección de segundo plano.
        if (!transmitiendo) detenerProteccionSesion()
    }

    /** Publica el MP4 en la galería (Android 10+) y regresa su URI. */
    /**
     * Foto instantánea del visor (programa). Captura lo que se ve en el lienzo con
     * PixelCopy y la guarda en Pictures/JFR Switcher.
     */
    private fun tomarFoto() {
        // La foto es del PROGRAMA (lo que está al aire), no del preview.
        val vista = vistaAlAire()
        if (vista.width == 0 || vista.height == 0) {
            toast("El lienzo todavía no está listo")
            return
        }
        val ancho = vista.width
        val alto = vista.height
        val bitmap = Bitmap.createBitmap(ancho, alto, Bitmap.Config.ARGB_8888)
        // Se quita el marco de selección para que no salga en la foto.
        val seleccion = b.canvas.scene.selectedId
        b.canvas.scene.selectedId = null
        b.canvas.requestRender()
        uiHandler.postDelayed({
            try {
                android.view.PixelCopy.request(
                    vista, bitmap,
                    { resultado ->
                        b.canvas.scene.selectedId = seleccion
                        b.canvas.requestRender()
                        if (resultado == android.view.PixelCopy.SUCCESS) {
                            guardarFoto(bitmap)
                        } else {
                            toast("No se pudo capturar el cuadro (código $resultado)")
                        }
                    },
                    uiHandler
                )
            } catch (t: Throwable) {
                b.canvas.scene.selectedId = seleccion
                toast("No se pudo capturar: ${t.message}")
            }
        }, 120)
    }

    /** Guarda el bitmap como JPEG en la galería. */
    private fun guardarFoto(bitmap: Bitmap) {
        val nombre = "JFR_Foto_" + java.text.SimpleDateFormat(
            "yyyyMMdd_HHmmss", java.util.Locale.US
        ).format(java.util.Date()) + ".jpg"
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, nombre)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/JFR Switcher")
                }
                val uri = contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                )
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                    }
                    toast("Foto guardada en Pictures/JFR Switcher")
                    return
                }
            }
            val carpeta = File(getExternalFilesDir(null), "Fotos").apply { mkdirs() }
            val archivo = File(carpeta, nombre)
            archivo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            toast("Foto guardada en ${archivo.parentFile?.name}/$nombre")
        } catch (t: Throwable) {
            L.e("studio", "foto: ${t.message}", t)
            toast("No se pudo guardar la foto")
        }
    }

    private fun publishVideo(file: File): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/JFR Switcher")
            }
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: return null
            contentResolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
            }
            file.delete()
            uri
        } catch (t: Throwable) {
            L.e("studio", "publicar: ${t.message}", t)
            null
        }
    }

    private fun shareLast() {
        val uri = lastVideoUri
        if (uri == null) {
            val file = lastVideoFile
            if (file == null || !file.exists()) {
                toast("Todavía no hay grabaciones")
                return
            }
            toast("Video en: ${file.absolutePath}")
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(Intent.createChooser(send, "Compartir video")) }
    }

    // ------------------------------------------------------------------ diálogos

    private fun updatePairingLabels() {
        val ip = Net.primaryIp() ?: "Sin WiFi"
        actualizarProgramaCamaras()
        b.txtCamCount.text = "${hub.count()} / 6"
        pairingBinding?.let {
            it.txtIp.text = ip
            it.txtConnCount.text = "Cámaras conectadas: ${hub.count()} / 6"
        }
    }

    private var pairingBinding: DialogPairingBinding? = null

    private var slotAB: LinearLayout? = null

    // ------------------------------------------------------------------ parrilla de fuentes

    private lateinit var overlayContainer: LinearLayout

    private lateinit var mediosContainer: LinearLayout

    /** Biblioteca: textos guardados y grabaciones de la app. */
    private lateinit var bibliotecaContainer: LinearLayout

    /**
     * Construye la parrilla de fuentes: 3 tarjetas por fila.
     * Primero las 6 cámaras (2 filas) y después el grupo de overlays, que se
     * reconstruye cada vez que se agrega, oculta o quita uno.
     */
    /** En vertical las tarjetas son verticales (9:16) y entran más por fila. */
    private fun columnasFuentes(): Int = when {
        // Modo Pokémon TCG: dos columnas. Arriba los dos jugadores (verticales) y abajo la mesa y
        // el ambiente (horizontales), que es la forma de cada cámara en el programa (Jorge, 23/11/2026).
        modoTcg -> 2
        // En celular la columna es más ancha: 2 tarjetas por fila, más grandes. Para ver el resto se
        // desliza la columna (pedido de Jorge, 29/10).
        esPantallaCelular() -> 2
        // Modo Studio en 9:16: las 6 cámaras en una sola fila, con tarjetas chicas.
        modoStudio && studioVertical -> 6
        // Modo Studio en 16:9: 4 por fila.
        modoStudio -> 4
        // En vertical, 3 por fila: cada tarjeta conserva su tamaño y el cuarto hueco pasa a la
        // columna de controles (allí caben los botones y el monitor de audio).
        vertical -> 3
        else -> 3
    }

    /**
     * Las miniaturas son `SurfaceView` y su capa **no se recorta con el scroll**: al deslizar las
     * tarjetas hacia arriba, el video se dibujaba encima del monitor de preview. Aquí se oculta el
     * video de las tarjetas que dejan de estar completas dentro del panel.
     */
    private fun vigilarScrollFuentes() {
        b.scrollFuentes.setOnScrollChangeListener { _, _, _, _, _ -> ocultarTarjetasFueraDeVista() }
        b.scrollFuentes.post { ocultarTarjetasFueraDeVista() }
    }

    private fun ocultarTarjetasFueraDeVista() {
        val rect = Rect()
        tiles.forEach { tile ->
            // `getLocalVisibleRect` sí toma en cuenta el recorte de los padres (aunque el
            // SurfaceView no lo respete al dibujar): sirve para saber si la tarjeta se ve completa.
            val visible = tile.getLocalVisibleRect(rect) &&
                rect.width() >= tile.width && rect.height() >= tile.height
            tile.mostrarVideoSiSeVe(visible)
        }
    }

    private fun buildSourcesGrid() {
        val grid = b.llGrid
        grid.removeAllViews()
        // Las tarjetas viejas hay que soltarlas: si no, sus visores siguen enganchados a la pista
        // y se quedan recibiendo cuadros en una superficie ya muerta.
        tiles.forEach { runCatching { it.release() } }
        tiles.clear()
        val cols = columnasFuentes()
        // En horizontal se agrega la cámara de la propia tablet como 7ª fuente. En Modo Studio
        // también (ahí la tablet sí puede usarla), sea 9:16 o 16:9.
        // En vertical de tablet no hay cámara local; en el celular sí (es su propia cámara).
        // 7 fuentes: 6 cámaras remotas + la cámara del equipo (que rota entre frontal y trasera).
        // En Modo Pokémon TCG son 4 fuentes fijas: Jugador 1, Jugador 2, Mesa y Ambiente.
        val total = if (modoTcg) 4 else if (vertical && !modoStudio && !esPantallaCelular()) 6 else 7
        // Modo Pokémon TCG: los dos jugadores arriba (verticales, media columna cada uno), la **mesa
        // a todo lo ancho** en la segunda fila y **el ambiente debajo** en la tercera, las dos
        // horizontales como estaban antes (Jorge, 23/11/2026). Los demás modos siguen con la parrilla
        // pareja de siempre.
        val plan: List<List<Int>> = if (modoTcg) {
            listOf(listOf(0, 1), listOf(2), listOf(3))
        } else {
            (0 until (total + cols - 1) / cols).map { fila ->
                (0 until cols).map { col -> fila * cols + col }
            }
        }
        // En celular las pestañas necesitan saber dónde terminan las cámaras y empiezan los grupos.
        filasCamaras = plan.size

        for (celdas in plan) {
            val filaView = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            grid.addView(
                filaView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            for (index in celdas) {
                if (index >= total) {
                    filaView.addView(Space(this), celdaParams())
                    continue
                }
                val tile = SourceTileView(this)
                // Las tarjetas siguen la forma del lienzo (en Modo Studio vertical van a 3:4).
                // En Modo Pokémon TCG cada tarjeta tiene la forma de su cámara en el programa: los
                // jugadores verticales (3:4) y la mesa y el ambiente horizontales (16:9).
                tile.modoAspecto(
                    if (!modoTcg) aspectoFuente
                    else if (index <= 1) 3f / 4f
                    else 16f / 9f
                )
                // Con 6 cámaras en una fila hacen falta letras más chicas; en el celular vertical
                // las tarjetas son angostas y también (Jorge, 29/10: «sí caben si son más chicos»).
                // En Modo Pokémon TCG los jugadores van a media columna, así que las letras también se
                // achican: si no, «Sin conectar» y «Jugador 1» salen cortados (23/11/2026).
                tile.modoCompacto(
                    modoTcg || (modoStudio && studioVertical) || (esPantallaCelular() && vertical)
                )
                if (esPantallaCelular() && vertical) tile.modoMuyCompacto()
                if (index == LocalCam.SLOT) {
                    tile.setup(index) { onTileTap(LocalCam.SLOT) }
                    tile.setEstadoLocal(localCam.encendida, nombreCamaraTablet())
                } else if (esPantallaCelular() && index == LocalCam.SLOT_FRONT) {
                    tile.setup(index) { onTileTap(LocalCam.SLOT_FRONT) }
                    tile.setEstadoLocal(localCam2.encendida, "Frente")
                } else {
                    tile.setup(index) { onTileTap(index) }
                }
                filaView.addView(tile, celdaParams())
                tiles.add(tile)
            }
            // La última fila de la parrilla pareja se completa con huecos para que las tarjetas no se
            // estiren (en Modo TCG cada fila va con las que le tocan y sí ocupan todo el ancho).
            if (!modoTcg) {
                repeat(cols - celdas.size) { filaView.addView(Space(this), celdaParams()) }
            }
        }

        if (modoTcg) {
            // **Debajo de la cámara 4**: los botones que antes estaban en la columna de la derecha,
            // en 3 filas de 2 (Jorge, 24/11/2026). Esa columna ahora es de los comentarios.
            val acciones: List<List<Pair<String, () -> Unit>>> = listOf(
                listOf(
                    "Evento" to { abrirDialogoEvento() },
                    "Partida nueva" to { nuevaPartidaTcg() },
                ),
                listOf(
                    "Empate" to { registrarResultadoTcg(0) },
                    "Reiniciar" to { reiniciarMarcadorTcg() },
                ),
                listOf(
                    "Banner" to { cambiarPaletaTcg() },
                    "＋ Cámara" to { showPairing() },
                ),
            )
            acciones.forEach { par ->
                val filaBotones = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                grid.addView(
                    filaBotones,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
                par.forEach { (rotulo, accion) ->
                    filaBotones.addView(botonAccionTcg(rotulo, accion), celdaParams())
                }
            }
        }

        if (modoTcg) {
            val nombres = listOf("Jugador 1", "Jugador 2", "Mesa", "Ambiente")
            tiles.forEachIndexed { i, t -> t.ponerNombre(nombres.getOrElse(i) { "Cámara ${i + 1}" }) }
            // Cámara 4: botón de tres pasos para la cámara de la tablet
            // (Tablet → frontal → trasera → apagada). Apagada, la cámara 4 es la del cuarto celular.
            botonCamara4 = tiles.getOrNull(3)?.botonEsquina(etiquetaBotonCamara4(), chico = true) {
                cicloCamaraAmbiente()
            }
            // La parrilla se rehace cada vez que cambia una cámara y eso limpia la tarjeta:
            // si la cámara de la tablet está en uso, se le devuelve su vista a la tarjeta 4.
            if (localCam.encendida) {
                localCam.track?.let { t ->
                    runCatching { tiles.getOrNull(3)?.bindLocal(t, "Tablet · ambiente") }
                }
            }
        }

        // Volver a enganchar los videos: las tarjetas son nuevas y sin esto se quedaban en negro
        // aunque la cámara estuviera conectada (pasaba al cambiar de formato o entrar al Modo
        // Studio, porque ahí se rehace la parrilla).
        hub.all().forEach { session ->
            val pista = session.videoTrack ?: return@forEach
            tiles.getOrNull(session.slot)?.let { tile ->
                tile.bind(session, pista)
                tile.fijarRotacion(session.rotacion)
            }
        }
        localCam.track?.let { pista ->
            tiles.getOrNull(LocalCam.SLOT)?.bindLocal(pista, nombreCamaraTablet())
        }
        localCam2.track?.let { pista ->
            tiles.getOrNull(LocalCam.SLOT_FRONT)?.bindLocal(pista, "Frente")
        }
        // Al rehacer la parrilla hay que volver a decidir qué miniaturas se ven completas (las que
        // quedan fuera se ocultan para que no se encimen al monitor).
        b.scrollFuentes.post { ocultarTarjetasFueraDeVista() }
        val rotuloOverlays = TextView(this).apply {
            text = "OVERLAYS"
            textSize = 10f
            letterSpacing = 0.12f
            setTextColor(ContextCompat.getColor(this@StudioActivity, R.color.text_dim))
            setPadding(6.dp, 12.dp, 6.dp, 4.dp)
        }
        grid.addView(rotuloOverlays)
        etiquetaOverlays = rotuloOverlays

        overlayContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        grid.addView(
            overlayContainer,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        refreshOverlayTiles()

        val rotuloMedios = TextView(this).apply {
            text = "MEDIOS · VIDEOS Y FOTOS"
            textSize = 10f
            letterSpacing = 0.12f
            setTextColor(ContextCompat.getColor(this@StudioActivity, R.color.text_dim))
            setPadding(6.dp, 12.dp, 6.dp, 4.dp)
        }
        grid.addView(rotuloMedios)
        etiquetaMedios = rotuloMedios

        mediosContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        grid.addView(
            mediosContainer,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        refreshMediaTiles()
        // Si es un celular, la parrilla recién hecha vuelve a mostrar solo el grupo de la pestaña.
        aplicarSeccionFuentes()
        // Y se marcan las cámaras que están en programa.
        marcarFuentesEnPrograma()
    }

    // ------------------------------------------------------------------ medios (videos y fotos)

    /** Medio cargado en el grupo MEDIOS. */
    data class Medio(val uri: String, val video: Boolean, val nombre: String) {
        var thumb: Bitmap? = null
    }

    private val medios = ArrayList<Medio>()

    /**
     * Hasta que se leen los medios de la sesión anterior, la parrilla se dibuja pero **no** se guarda.
     * El primer `refreshMediaTiles()` ocurre antes de cargar; si guardara, borraría lo guardado.
     */
    private var mediosListos = false

    /** Reconstruye las tarjetas de medios + la tarjeta de agregar. */
    private fun refreshMediaTiles() {
        if (!::mediosContainer.isInitialized) return
        // El grupo es de «recientes»: los últimos 10. Los viejos salen también de la pantalla para
        // que lo que se ve sea exactamente lo que se guarda.
        while (medios.size > MEDIOS_RECIENTES) medios.removeAt(0)
        mediosContainer.removeAllViews()
        val cols = columnasFuentes()
        val total = medios.size + 1
        val filas = (total + cols - 1) / cols
        for (fila in 0 until filas) {
            val filaView = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            mediosContainer.addView(
                filaView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            for (col in 0 until cols) {
                val index = fila * cols + col
                when {
                    index < medios.size -> {
                        val medio = medios[index]
                        filaView.addView(tarjetaMedio(medio), celdaParams())
                    }

                    index == medios.size -> filaView.addView(tarjetaAgregarMedio(), celdaParams())
                    else -> filaView.addView(Space(this), celdaParams())
                }
            }
        }
        // Un solo punto de guardado: cualquier alta o baja del grupo pasa por aquí.
        if (mediosListos) guardarMediosRecientes()
    }

    /** Tarjeta de un medio: miniatura, nombre y botón para quitarlo del grupo. */
    private fun tarjetaMedio(medio: Medio): View {
        val frame = AspectFrameLayout(this).apply {
            aspect = aspectoFuente
            setBackgroundResource(R.drawable.bg_tile)
            isClickable = true
            isFocusable = true
        }
        // Marco naranja si este video/foto es el que está en programa.
        val enPrograma = runCatching {
            escenaAlAire().items.any { it is MediaItem && it.uri == medio.uri && it.visible }
        }.getOrDefault(false)
        if (enPrograma) {
            frame.foreground = ContextCompat.getDrawable(this, R.drawable.bg_marco_programa)
        }
        val imagen = android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            setImageBitmap(medio.thumb)
        }
        frame.addView(
            imagen,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        val velo = View(this).apply {
            setBackgroundColor(0x99000000.toInt())
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                54.dp,
                Gravity.BOTTOM
            )
        }
        frame.addView(velo)
        frame.addView(
            TextView(this).apply {
                text = (if (medio.video) "▶ " else "") + medio.nombre
                textSize = 11f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(ContextCompat.getColor(this@StudioActivity, R.color.text_primary))
                setPadding(8.dp, 0, 8.dp, 0)
            },
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                54.dp,
                Gravity.BOTTOM
            ).apply { gravity = Gravity.BOTTOM or Gravity.START }
        )
        // Botón para quitar del grupo
        frame.addView(
            TextView(this).apply {
                text = "✕"
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(this@StudioActivity, R.color.text_primary))
                setBackgroundResource(R.drawable.bg_delete)
                setOnClickListener {
                    // Si ese medio está al aire hay que cortarlo y quitarlo del programa antes de
                    // sacarlo del grupo: si no, el video seguía sonando aunque ya no estuviera.
                    quitarMedioDePrograma(medio)
                    medios.remove(medio)
                    refreshMediaTiles()
                }
            },
            FrameLayout.LayoutParams(30.dp, 30.dp, Gravity.TOP or Gravity.END).apply {
                topMargin = 6.dp
                marginEnd = 6.dp
            }
        )
        frame.setOnClickListener { alternarMedio(medio) }
        return frame
    }

    /**
     * Saca un medio del programa: corta su video (y su audio) y quita su ítem de los dos escenarios.
     *
     * Se usa al eliminar un medio del grupo y al quitar cosas del aire: el reproductor de video vive
     * pegado al monitor, así que si solo se quitaba el ítem, el audio seguía sonando.
     */
    private fun quitarMedioDePrograma(medio: Medio) {
        val escenas = listOfNotNull(b.canvas.scene, monitorPrograma?.scene).distinct()
        var habiaVideo = false
        escenas.forEach { escena ->
            escena.items.filterIsInstance<MediaItem>()
                .filter { it.uri == medio.uri }
                .forEach { item ->
                    if (escena.items.contains(item)) {
                        habiaVideo = true
                        escena.remove(item)
                    }
                }
            escena.images().filter { it.uri == medio.uri }.forEach { escena.remove(it) }
        }
        if (habiaVideo) {
            if (b.canvas.hayVideo) b.canvas.detenerVideo()
            monitorPrograma?.let { if (it.hayVideo) it.detenerVideo() }
        }
        b.canvas.requestRender()
        monitorPrograma?.requestRender()
    }

    /** Agrega al grupo un archivo elegido del dispositivo. */
    private fun agregarMedio(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        val tipo = contentResolver.getType(uri) ?: ""
        val esVideo = tipo.startsWith("video")
        val nombre = (tipo.substringAfterLast("/", "medio")) + " " + (medios.size + 1)
        val medio = Medio(uri.toString(), esVideo, nombre)
        medio.thumb = cargarMiniatura(medio)
        medios.add(medio)
        refreshMediaTiles()
        toast(if (esVideo) "Video agregado a MEDIOS" else "Foto agregada a MEDIOS")
    }

    private fun tarjetaAgregarMedio(): View {
        val frame = AspectFrameLayout(this).apply {
            aspect = aspectoFuente
            setBackgroundResource(R.drawable.bg_tile)
            isClickable = true
            isFocusable = true
        }
        frame.addView(
            TextView(this).apply {
                text = "+ Medio"
                gravity = Gravity.CENTER
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@StudioActivity, R.color.text_secondary))
            },
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        frame.setOnClickListener { pickMedia.launch(arrayOf("image/*", "video/*")) }
        return frame
    }

    /** Muestra u oculta un medio en el programa. */
    private fun alternarMedio(medio: Medio) {
        // Los videos llevan el reproductor pegado al monitor, así que van **directo al aire**:
        // en Modo Studio eso es el monitor de programa, no el preview.
        val vistaVideo = vistaAlAire()
        val scene = if (medio.video && modoStudio) escenaAlAire() else b.canvas.scene
        if (medio.video) {
            val actual = scene.items.filterIsInstance<MediaItem>().firstOrNull { it.uri == medio.uri }
            if (actual != null) {
                vistaVideo.detenerVideo()
                scene.remove(actual)
                vistaVideo.requestRender()
                refreshMediaTiles()
                toast("Video detenido")
                return
            }
            val item = MediaItem(scene.nextId(), medio.uri, true, medio.nombre)
            item.rect.set(0f, 0f, 1f, 1f)
            scene.agregar(item)
            if (!modoStudio) scene.selectedId = item.id
            vistaVideo.animarEntrada(item)
            vistaVideo.requestRender()
            vistaVideo.reproducirVideo(item) {
                // Al terminar se quita solo y vuelve la cámara que estaba al aire.
                scene.remove(item)
                vistaVideo.requestRender()
                if (!modoStudio) onSelectionChanged(scene.selected())
                actualizarProgramaCamaras()
                toast("Fin del video: vuelve la cámara")
            }
            refreshMediaTiles()
            toast(
                if (modoStudio) "Video al aire: ${medio.nombre}"
                else "Reproduciendo ${medio.nombre}"
            )
        } else {
            val item = scene.cams().firstOrNull()
            val existente = scene.images().firstOrNull { it.uri == medio.uri }
            if (existente != null) {
                existente.visible = !existente.visible
                b.canvas.requestRender()
                refreshMediaTiles()
                return
            }
            val bmp = cargarMiniatura(medio) ?: run {
                toast("No se pudo abrir la imagen")
                return
            }
            val img = scene.addImage(bmp, medio.nombre, medio.uri) ?: return
            img.rect.set(0f, 0f, 1f, 1f)
            scene.sendToBack(img)
            scene.selectedId = img.id
            b.canvas.animarEntrada(img)
            b.canvas.requestRender()
            refreshMediaTiles()
            if (item == null) toast("Imagen al aire")
        }
    }

    /** Carga la miniatura de un medio (foto o primer cuadro del video). */
    private fun cargarMiniatura(medio: Medio): Bitmap? {
        medio.thumb?.let { return it }
        val bmp = try {
            if (medio.video) {
                val r = android.media.MediaMetadataRetriever()
                r.setDataSource(this, Uri.parse(medio.uri))
                val marco = r.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                r.release()
                marco
            } else {
                loadScaledBitmap(Uri.parse(medio.uri))
            }
        } catch (t: Throwable) {
            L.w("medios", "miniatura: ${t.message}")
            null
        }
        medio.thumb = bmp
        return bmp
    }

    // ------------------------------------------------- medios de la sesión anterior

    /**
     * Deja anotados los medios del grupo para que sigan ahí al volver a abrir la app.
     * Se guarda la dirección del archivo (y su permiso, que ya se pidió al agregarlo), no el archivo.
     */
    private fun guardarMediosRecientes() {
        val arr = org.json.JSONArray()
        for (m in medios) {
            arr.put(
                org.json.JSONObject()
                    .put("uri", m.uri)
                    .put("video", m.video)
                    .put("nombre", m.nombre)
            )
        }
        runCatching {
            // commit() y no apply(): si el equipo cierra la app de golpe, lo anotado no se pierde.
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(CLAVE_MEDIOS, arr.toString())
                .commit()
        }.onFailure { L.w("medios", "no se pudo guardar la lista: ${it.message}") }
    }

    /**
     * Trae los últimos 10 medios de la sesión anterior. Los que ya no se pueden abrir (archivo
     * borrado o permiso perdido) se descartan sin reventar; al guardar, quedan fuera de la lista.
     */
    private fun cargarMediosRecientes() {
        val guardados = getSharedPreferences(PREFS, MODE_PRIVATE).getString(CLAVE_MEDIOS, null)
        if (!guardados.isNullOrBlank()) {
            runCatching {
                val arr = org.json.JSONArray(guardados)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val uri = o.optString("uri")
                    if (uri.isEmpty()) continue
                    if (!medioDisponible(uri)) {
                        L.w("medios", "ya no se puede abrir, se descarta: $uri")
                        continue
                    }
                    medios.add(
                        Medio(uri, o.optBoolean("video", false), o.optString("nombre", "medio"))
                    )
                }
            }.onFailure { L.w("medios", "no se pudo leer la lista guardada: ${it.message}") }
        }
        // A partir de aquí sí se guarda; el guardado de ahora limpia los que ya no servían.
        mediosListos = true
        refreshMediaTiles()
        cargarMiniaturasDeMedios()
    }

    /** ¿Todavía se puede abrir? (el archivo sigue en el equipo y el permiso de lectura está dado). */
    private fun medioDisponible(uri: String): Boolean = runCatching {
        contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.use { true } ?: false
    }.getOrDefault(false)

    /**
     * Las miniaturas se sacan fuera del hilo principal para que la app abra rápido aunque haya
     * videos guardados; al terminar se repinta la parrilla una sola vez.
     */
    private fun cargarMiniaturasDeMedios() {
        val pendientes = medios.filter { it.thumb == null }
        if (pendientes.isEmpty()) return
        Thread {
            for (m in pendientes) cargarMiniatura(m)
            uiHandler.post {
                if (isFinishing || isDestroyed) return@post
                refreshMediaTiles()
            }
        }.apply { isDaemon = true }.start()
    }

    /** Botón chico de la columna de cámaras (Modo Pokémon TCG): fondo oscuro y borde suave. */
    private fun botonAccionTcg(rotulo: String, accion: () -> Unit): View =
        TextView(this).apply {
            text = rotulo
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10.5f)
            setTextColor(0xFFECEEF2.toInt())
            gravity = Gravity.CENTER
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(4.dp, 7.dp, 4.dp, 7.dp)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF23262E.toInt())
                cornerRadius = 10f * resources.displayMetrics.density
                setStroke(
                    (1f * resources.displayMetrics.density).toInt().coerceAtLeast(1),
                    0xFF343A46.toInt()
                )
            }
            setOnClickListener { accion() }
        }

    private fun celdaParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(4.dp, 4.dp, 4.dp, 4.dp)
        }

    /** Reconstruye las tarjetas de overlays (3 por fila) + la tarjeta de agregar. */
    private fun refreshOverlayTiles() {
        if (!::overlayContainer.isInitialized) return
        overlayContainer.removeAllViews()
        // El banner del torneo y los chips de las cámaras son del modo, no overlays del usuario.
        val propios = setOfNotNull(bannerTcg?.id) +
            chipsTcgUno.map { it.id } + chipsTcgDos.map { it.id }
        val imagenes = b.canvas.scene.images().filter { it.id !in propios }
        val cols = columnasFuentes()
        // Primera celda: crear texto. Después los overlays puestos y al final «+ Overlay».
        val total = 2 + imagenes.size
        val filas = (total + cols - 1) / cols

        for (fila in 0 until filas) {
            val filaView = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            overlayContainer.addView(
                filaView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            for (col in 0 until cols) {
                val index = fila * cols + col
                if (index >= total) {
                    filaView.addView(Space(this), celdaParams())
                    continue
                }
                when {
                    index == 0 -> filaView.addView(tarjetaCrearTexto(), celdaParams())
                    index - 1 < imagenes.size -> {
                        val item = imagenes[index - 1]
                        val tile = OverlayTileView(this)
                        tile.modoVertical(lienzoVertical)
                        tile.bind(
                            item = item,
                            selected = b.canvas.scene.selectedId == item.id,
                            onTap = { toggleOverlay(item) },
                            onDelete = { borrarOverlay(item) }
                        )
                        filaView.addView(tile, celdaParams())
                    }

                    else -> filaView.addView(tarjetaAgregarOverlay(), celdaParams())
                }
            }
        }
    }

    // ------------------------------------------------------------- textos del programa

    /**
     * Carga el cuadro hacia la izquierda de la pantalla, con un margen para que no se pegue
     * al borde. Así el visor de programa queda libre a la derecha.
     */
    private fun cuadroALaIzquierda(dialogo: android.app.Dialog, altoCompleto: Boolean = true) {
        runCatching {
            val ventana = dialogo.window ?: return
            val attrs = ventana.attributes
            attrs.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            attrs.x = 40.dp
            if (altoCompleto) {
                // El cuadro de Facebook creció (token, Páginas, YouTube): con el alto por defecto
                // los botones de abajo quedaban fuera de la vista.
                attrs.height = (resources.displayMetrics.heightPixels * 0.92f).toInt()
                attrs.width = (resources.displayMetrics.widthPixels * 0.88f).toInt()
            } else {
                // Los cuadros chicos (destino, descripción) usan solo lo que necesitan.
                attrs.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                attrs.width = (resources.displayMetrics.widthPixels * 0.55f).toInt()
            }
            ventana.attributes = attrs
        }
    }

    private fun etiquetaTexto(chico: String): TextView = TextView(this).apply {
        text = chico
        textSize = 10f
        letterSpacing = 0.1f
        setTextColor(ContextCompat.getColor(this@StudioActivity, R.color.text_dim))
        setPadding(0, 12.dp, 0, 2.dp)
    }

    private fun chipOpcion(texto: String, alTocar: () -> Unit): TextView =
        TextView(this).apply {
            text = texto
            textSize = 12f
            setPadding(12.dp, 8.dp, 12.dp, 8.dp)
            setBackgroundResource(R.drawable.bg_chip)
            setOnClickListener { alTocar() }
        }

    private fun pintarChip(chip: TextView, elegido: Boolean) {
        chip.setTextColor(
            ContextCompat.getColor(
                this,
                if (elegido) R.color.accent else R.color.text_secondary
            )
        )
        chip.backgroundTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(this, if (elegido) R.color.bg_panel_alt else R.color.bg_panel)
        )
    }

    /** Tarjeta, primera de OVERLAYS, que abre el editor de texto. */
    private fun tarjetaCrearTexto(): View {
        val frame = AspectFrameLayout(this).apply {
            aspect = aspectoFuente
            setBackgroundResource(R.drawable.bg_tile)
            isClickable = true
            isFocusable = true
        }
        val columna = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        columna.addView(TextView(this).apply {
            text = "T"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@StudioActivity, R.color.accent))
        })
        columna.addView(TextView(this).apply {
            text = "Texto"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@StudioActivity, R.color.text_secondary))
        })
        frame.addView(
            columna,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        frame.setOnClickListener { dialogoTexto() }
        return frame
    }

    /** Editor: qué dice, con qué letra, de qué color, qué tamaño y cómo entra. */
    private fun dialogoTexto(paraEditar: TextoGuardado? = null) {
        // El botón de texto es solo para fuera de grabación/transmisión: en vivo no se toca nada.
        if (transmitiendo || recorder.isRecording || emisor.grabando) {
            toast("No se crea texto mientras grabas o transmites")
            return
        }
        val caja = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Abajo se deja aire: los botones del cuadro se dibujan encima del contenido.
            setPadding(20.dp, 6.dp, 20.dp, 76.dp)
        }

        val campo = EditText(this).apply {
            hint = "Escribe el texto…"
            setText(paraEditar?.texto.orEmpty())
            textSize = 14f
            gravity = Gravity.TOP
            minLines = 2
        }
        caja.addView(etiquetaTexto("TEXTO"))
        caja.addView(campo)

        // --- Tipo de letra (lista desplegable, cada nombre con su propia letra) ---
        var fuente = paraEditar?.fuente ?: "Sans negrita"
        caja.addView(etiquetaTexto("TIPO DE LETRA"))
        val espFuente = android.widget.Spinner(this).apply {
            adapter = com.jfrmedia.switcher.studio.ui.AdaptadorFuentes(this@StudioActivity)
            setSelection(Fuentes.TODAS.indexOfFirst { it.first == fuente }.coerceAtLeast(0))
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: View?,
                    pos: Int,
                    id: Long
                ) {
                    fuente = Fuentes.TODAS[pos].first
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
        }
        caja.addView(espFuente)

        // --- Color: paleta propia (se pueden agregar colores) ---
        var color = paraEditar?.color ?: android.graphics.Color.WHITE
        caja.addView(etiquetaTexto("COLOR"))
        val filaColores = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        caja.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(
                filaColores,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        })

        fun pintarPaleta() {
            filaColores.removeAllViews()
            for (c in paletaColores()) {
                val chip = TextView(this).apply {
                    text = "    "
                    setBackgroundColor(c)
                    setPadding(14.dp, 10.dp, 14.dp, 10.dp)
                    alpha = if (c == color) 1f else 0.4f
                    setOnClickListener {
                        color = c
                        pintarPaleta()
                    }
                }
                filaColores.addView(
                    chip,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = 8.dp }
                )
            }
            filaColores.addView(
                TextView(this).apply {
                    text = "＋"
                    textSize = 15f
                    gravity = Gravity.CENTER
                    setTextColor(ContextCompat.getColor(this@StudioActivity, R.color.accent))
                    setBackgroundResource(R.drawable.bg_chip)
                    setPadding(14.dp, 8.dp, 14.dp, 8.dp)
                    setOnClickListener {
                        dialogoColorNuevo { nuevo ->
                            val lista = paletaColores()
                            if (!lista.contains(nuevo)) {
                                lista.add(nuevo)
                                guardarPaleta(lista)
                            }
                            color = nuevo
                            pintarPaleta()
                        }
                    }
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        pintarPaleta()

        // --- Tamaño ---
        var tamano = paraEditar?.tamano ?: 1.0f
        caja.addView(etiquetaTexto("TAMAÑO"))
        val barra = android.widget.SeekBar(this).apply {
            max = 120
            progress = ((tamano - 0.6f) * 100f).toInt().coerceIn(0, 120)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    sb: android.widget.SeekBar?,
                    v: Int,
                    deUsuario: Boolean
                ) {
                    tamano = 0.6f + v / 100f
                }

                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
            })
        }
        caja.addView(barra)

        // --- Animación: la entrada y la salida van con el mismo estilo ---
        var animacion = paraEditar?.animacion ?: "fundido"
        caja.addView(etiquetaTexto("ANIMACIÓN (ENTRA Y SALE)"))
        val espAnim = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(
                this@StudioActivity,
                android.R.layout.simple_spinner_dropdown_item,
                Entradas.TODAS.map { it.nombre }
            )
            setSelection(
                Entradas.TODAS.indexOfFirst { it.clave == animacion }.coerceAtLeast(0)
            )
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: View?,
                    pos: Int,
                    id: Long
                ) {
                    animacion = Entradas.TODAS[pos].clave
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
        }
        caja.addView(espAnim)

        fun armar(): TextoGuardado = TextoGuardado(
            id = paraEditar?.id ?: Textos.nuevoId(),
            texto = campo.text.toString().trim(),
            fuente = fuente,
            color = color,
            animacion = animacion,
            tamano = tamano
        )

        // Vista previa en el visor: se refresca sola cuando cambia algo (texto, letra, color…).
        val ultimoVisto = arrayOfNulls<String>(1)
        val vigilante = object : Runnable {
            override fun run() {
                val t = armar()
                val clave = "${t.texto}|${t.fuente}|${t.color}|${t.animacion}|${t.tamano}"
                if (clave != ultimoVisto[0]) {
                    ultimoVisto[0] = clave
                    if (t.texto.isBlank()) quitarPreviewTexto() else mostrarPreviewTexto(t)
                }
                uiHandler.postDelayed(this, 300)
            }
        }
        uiHandler.post(vigilante)

        AlertDialog.Builder(this)
            .setTitle(if (paraEditar == null) "Nuevo texto" else "Editar texto")
            .setOnDismissListener {
                uiHandler.removeCallbacks(vigilante)
                quitarPreviewTexto()
                refreshOverlayTiles()
            }
            .setView(ScrollView(this).apply { addView(caja) })
            .setPositiveButton("Al programa") { _, _ ->
                val t = armar()
                if (t.texto.isBlank()) {
                    toast("Escribe algo de texto")
                } else {
                    if (Textos.guardar(this, t)) refreshBiblioteca()
                    agregarTextoAlPrograma(t)
                }
            }
            .setNeutralButton("Solo guardar") { _, _ ->
                val t = armar()
                if (t.texto.isBlank()) {
                    toast("Escribe algo de texto")
                } else if (Textos.guardar(this, t)) {
                    refreshBiblioteca()
                    toast("Guardado en la biblioteca")
                } else {
                    toast("No se pudo guardar")
                }
            }
            .setNegativeButton("Cerrar", null)
            .show()
            .also { cuadroALaIzquierda(it) }
    }

    /** Texto que se está viendo en el visor mientras se edita (solo fuera de sesión). */
    private var previewTexto: TextoItem? = null

    /** Muestra (o actualiza) el texto en el visor de programa. */
    private fun mostrarPreviewTexto(t: TextoGuardado) {
        quitarPreviewTexto()
        val mapa = Textos.rasterizar(t)
        val item = TextoItem(
            b.canvas.scene.nextId(), t.texto, t.fuente, t.animacion, null, mapa,
            t.color, t.tamano
        )
        val aspecto = mapa.width.toFloat() / mapa.height.toFloat()
        val ancho = 0.58f
        val alto = (ancho * b.canvas.scene.aspect / aspecto).coerceAtMost(0.85f)
        val x = (1f - ancho) / 2f
        val y = if (vertical) 0.26f else 0.30f
        item.rect.set(x, y, x + ancho, y + alto)
        b.canvas.scene.agregar(item)
        previewTexto = item
        b.canvas.requestRender()
    }

    /** Quita el texto de la vista previa (no toca los que ya están puestos). */
    private fun quitarPreviewTexto() {
        previewTexto?.let { item ->
            b.canvas.scene.remove(item)
            b.canvas.requestRender()
        }
        previewTexto = null
    }

    /** Pone el texto en el programa con su animación de entrada. */
    private fun agregarTextoAlPrograma(t: TextoGuardado) {
        runCatching {
            val mapa = Textos.rasterizar(t)
            val item = TextoItem(
                b.canvas.scene.nextId(), t.texto, t.fuente, t.animacion, t.id, mapa,
                t.color, t.tamano
            )
            val aspecto = mapa.width.toFloat() / mapa.height.toFloat()
            val ancho = 0.58f
            val alto = (ancho * b.canvas.scene.aspect / aspecto).coerceAtMost(0.85f)
            val x = (1f - ancho) / 2f
            val y = if (vertical) 0.26f else 0.30f
            item.rect.set(x, y, x + ancho, y + alto)
            item.animInicio = System.nanoTime()
            b.canvas.scene.agregar(item)
            animarEntradaDeTexto()
            b.canvas.requestRender()
            onSelectionChanged(item)
            refreshOverlayTiles()
            toast("Texto agregado · arrástralo donde quieras")
        }.onFailure {
            L.e("studio", "texto: ${it.message}", it)
            toast("No se pudo crear el texto")
        }
    }

    /** El lienzo se dibuja bajo demanda: se le piden cuadros mientras dura la animación. */
    private fun animarEntradaDeTexto() {
        val fin = SystemClock.uptimeMillis() + 1000
        val paso = object : Runnable {
            override fun run() {
                b.canvas.requestRender()
                if (SystemClock.uptimeMillis() < fin) uiHandler.postDelayed(this, 33)
            }
        }
        uiHandler.post(paso)
    }

    /** Anima la salida (que hace juego con la entrada) y al terminar hace [alTerminar]. */
    private fun animarSalidaDeTexto(item: TextoItem, alTerminar: () -> Unit) {
        item.animSalida = System.nanoTime()
        item.animInicio = 0L
        val fin = SystemClock.uptimeMillis() + item.animDuracionMs.toLong()
        val paso = object : Runnable {
            override fun run() {
                b.canvas.requestRender()
                if (SystemClock.uptimeMillis() < fin) {
                    uiHandler.postDelayed(this, 33)
                } else {
                    item.animSalida = 0L
                    alTerminar()
                    b.canvas.requestRender()
                }
            }
        }
        uiHandler.post(paso)
    }

    // ---------------------------------------------------------- paleta de colores

    /** Colores disponibles: los de siempre más los que agregue Jorge (se guardan en preferencias). */
    private fun paletaColores(): MutableList<Int> {
        val base = mutableListOf(
            android.graphics.Color.WHITE,
            android.graphics.Color.BLACK,
            0xFFFF5A2B.toInt(),
            0xFFFFC13B.toInt(),
            0xFF35C759.toInt(),
            0xFF2F8FFF.toInt()
        )
        val guardados = getSharedPreferences(PREFS, MODE_PRIVATE).getString(CLAVE_PALETA, null)
        if (guardados != null) {
            runCatching {
                val arr = org.json.JSONArray(guardados)
                for (i in 0 until arr.length()) {
                    val c = arr.optInt(i)
                    if (!base.contains(c)) base.add(c)
                }
            }
        }
        return base
    }

    private fun guardarPaleta(colores: List<Int>) {
        val arr = org.json.JSONArray()
        for (c in colores) arr.put(c)
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(CLAVE_PALETA, arr.toString()).apply()
    }

    /** Selector de color: rueda (tono y saturación) + brillo + código hex + valores RGB. */
    private fun dialogoColorNuevo(alElegir: (Int) -> Unit) {
        var colorElegido = 0xFFFF5A2B.toInt()
        val caja = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22.dp, 6.dp, 22.dp, 6.dp)
        }

        val rueda = com.jfrmedia.switcher.studio.ui.RuedaColorView(this).apply {
            fijarColor(colorElegido)
        }
        caja.addView(
            rueda,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 300.dp)
        )

        val barra = android.widget.SeekBar(this).apply {
            max = 100
            progress = 100
        }
        caja.addView(etiquetaTexto("BRILLO"))
        caja.addView(barra)

        caja.addView(etiquetaTexto("CÓDIGO HEX (#RRGGBB)"))
        val campoHex = EditText(this).apply {
            setText(String.format("#%06X", 0xFFFFFF and colorElegido))
            textSize = 13f
            setSingleLine(true)
        }
        caja.addView(campoHex)

        val filaRgb = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        caja.addView(filaRgb)
        var activo = true   // evita que los campos se reescriban entre sí

        fun campo(rotulo: String): EditText {
            val caja2 = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 6.dp, 10.dp, 0)
            }
            caja2.addView(TextView(this).apply {
                text = rotulo
                textSize = 10f
                setTextColor(ContextCompat.getColor(this@StudioActivity, R.color.text_dim))
            })
            val campo = EditText(this).apply {
                textSize = 13f
                setSingleLine(true)
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
            }
            caja2.addView(campo)
            filaRgb.addView(caja2, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            return campo
        }

        val campoR = campo("ROJO")
        val campoG = campo("VERDE")
        val campoB = campo("AZUL")

        fun pintarCampos(c: Int) {
            activo = false
            campoHex.setText(String.format("#%06X", 0xFFFFFF and c))
            campoR.setText((android.graphics.Color.red(c)).toString())
            campoG.setText((android.graphics.Color.green(c)).toString())
            campoB.setText((android.graphics.Color.blue(c)).toString())
            activo = true
        }

        fun fijarDesdeCampos() {
            if (!activo) return
            val r = campoR.text.toString().toIntOrNull()?.coerceIn(0, 255) ?: 0
            val g = campoG.text.toString().toIntOrNull()?.coerceIn(0, 255) ?: 0
            val b = campoB.text.toString().toIntOrNull()?.coerceIn(0, 255) ?: 0
            colorElegido = android.graphics.Color.rgb(r, g, b)
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(colorElegido, hsv)
            rueda.fijarColor(colorElegido)
            activo = false
            campoHex.setText(String.format("#%06X", 0xFFFFFF and colorElegido))
            activo = true
        }

        rueda.alCambiarColor = { c ->
            colorElegido = c
            pintarCampos(c)
        }
        barra.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(
                sb: android.widget.SeekBar?,
                v: Int,
                deUsuario: Boolean
            ) {
                rueda.brillo = (v / 100f).coerceAtLeast(0.05f)
                colorElegido = rueda.colorActual()
                pintarCampos(colorElegido)
            }

            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
        })
        for (campoRgb in listOf(campoR, campoG, campoB)) {
            campoRgb.addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) = fijarDesdeCampos()
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        campoHex.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                if (!activo) return
                val texto = s?.toString().orEmpty().trim().removePrefix("#")
                if (texto.length != 6) return
                val valor = texto.toLongOrNull(16)?.toInt() ?: return
                colorElegido = valor or 0xFF000000.toInt()
                rueda.fijarColor(colorElegido)
                pintarCampos(colorElegido)
            }

            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        pintarCampos(colorElegido)

        AlertDialog.Builder(this)
            .setTitle("Elegir color")
            .setView(ScrollView(this).apply { addView(caja) })
            .setPositiveButton("Usar este") { _, _ -> alElegir(colorElegido) }
            .setNegativeButton("Cerrar", null)
            .show()
            .also { cuadroALaIzquierda(it) }
    }

    // ---------------------------------------------------------------- biblioteca

    /** Textos guardados + grabaciones de la app. Tocar una cosa la pone en el programa. */
    private fun refreshBiblioteca() {
        if (!::bibliotecaContainer.isInitialized) return
        bibliotecaContainer.removeAllViews()

        val textos = Textos.listar(this)
        val grabaciones = grabacionesDeLaApp()
        if (textos.isEmpty() && grabaciones.isEmpty()) {
            bibliotecaContainer.addView(TextView(this).apply {
                text = "Aquí se guardan los textos que crees y las grabaciones de la app."
                textSize = 11f
                setTextColor(ContextCompat.getColor(this@StudioActivity, R.color.text_dim))
                setPadding(6.dp, 4.dp, 6.dp, 6.dp)
            })
            return
        }

        val celdas = ArrayList<View>()
        for (t in textos) celdas.add(tarjetaTextoBiblioteca(t))
        for (g in grabaciones) celdas.add(tarjetaGrabacion(g.first, g.second))

        val cols = columnasFuentes()
        val filas = (celdas.size + cols - 1) / cols
        var i = 0
        for (fila in 0 until filas) {
            val filaView = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            bibliotecaContainer.addView(
                filaView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            for (col in 0 until cols) {
                if (i < celdas.size) filaView.addView(celdas[i], celdaParams())
                else filaView.addView(Space(this), celdaParams())
                i++
            }
        }
    }

    private fun tarjetaTextoBiblioteca(t: TextoGuardado): View {
        val frame = AspectFrameLayout(this).apply {
            aspect = aspectoFuente
            setBackgroundResource(R.drawable.bg_tile)
            isClickable = true
            isFocusable = true
        }
        val columna = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8.dp, 8.dp, 8.dp, 8.dp)
        }
        columna.addView(TextView(this).apply {
            text = t.texto
            textSize = 11f
            maxLines = 3
            typeface = Fuentes.de(t.fuente)
            setTextColor(t.color)
        })
        columna.addView(TextView(this).apply {
            text = Entradas.nombre(t.animacion)
            textSize = 9f
            setTextColor(ContextCompat.getColor(this@StudioActivity, R.color.text_dim))
        })
        frame.addView(
            columna,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        // Un toque lo pone en el programa; mantenerlo pulsado lo borra de la biblioteca.
        frame.setOnClickListener { agregarTextoAlPrograma(t) }
        frame.setOnLongClickListener {
            AlertDialog.Builder(this)
                .setTitle("¿Borrar este texto?")
                .setMessage(t.texto)
                .setPositiveButton("Borrar") { _, _ ->
                    Textos.borrar(this, t.id)
                    refreshBiblioteca()
                }
                .setNegativeButton("No", null)
                .show()
            true
        }
        return frame
    }

    private fun tarjetaGrabacion(uri: String, nombre: String): View {
        val frame = AspectFrameLayout(this).apply {
            aspect = aspectoFuente
            setBackgroundResource(R.drawable.bg_tile)
            isClickable = true
            isFocusable = true
        }
        val columna = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(8.dp, 8.dp, 8.dp, 8.dp)
        }
        columna.addView(TextView(this).apply {
            text = "▶"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@StudioActivity, R.color.accent))
        })
        columna.addView(TextView(this).apply {
            text = nombre
            textSize = 9f
            maxLines = 2
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@StudioActivity, R.color.text_secondary))
        })
        frame.addView(
            columna,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        frame.setOnClickListener { agregarMedio(Uri.parse(uri)) }
        return frame
    }

    /** Grabaciones que la app guardó en la galería (Movies/JFR Switcher). */
    private fun grabacionesDeLaApp(limite: Int = 8): List<Pair<String, String>> {
        val salida = ArrayList<Pair<String, String>>()
        runCatching {
            val coleccion = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val columnas = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME
            )
            contentResolver.query(
                coleccion,
                columnas,
                "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?",
                arrayOf("%JFR Switcher%"),
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val iNom = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                while (c.moveToNext() && salida.size < limite) {
                    val uri = android.content.ContentUris
                        .withAppendedId(coleccion, c.getLong(iId)).toString()
                    salida.add(uri to (c.getString(iNom) ?: "Grabación"))
                }
            }
        }.onFailure { L.e("studio", "biblioteca: ${it.message}", it) }
        return salida
    }


    private fun tarjetaAgregarOverlay(): View {
        val frame = AspectFrameLayout(this).apply {
            aspect = aspectoFuente
            setBackgroundResource(R.drawable.bg_tile)
            isClickable = true
            isFocusable = true
        }
        val texto = TextView(this).apply {
            text = "+ Overlay"
            gravity = Gravity.CENTER
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@StudioActivity, R.color.text_secondary))
        }
        frame.addView(
            texto,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        frame.setOnClickListener { pickImage.launch(arrayOf("image/*")) }
        return frame
    }

    /** Tocar un overlay lo muestra u oculta en el lienzo (switch rápido). */
    private fun toggleOverlay(item: ImageItem) {
        if (item.visible) {
            // Ocultar: si es texto, primero sale con su animación y después se apaga.
            if (item is TextoItem) {
                animarSalidaDeTexto(item) {
                    item.visible = false
                    if (b.canvas.scene.selectedId == item.id) b.canvas.scene.selectedId = null
                    b.canvas.scene.touch()
                    refreshOverlayTiles()
                    onSelectionChanged(b.canvas.scene.selected())
                }
                return
            }
            item.visible = false
            if (b.canvas.scene.selectedId == item.id) b.canvas.scene.selectedId = null
        } else {
            item.visible = true
            if (item is TextoItem) {
                item.animSalida = 0L
                item.animInicio = System.nanoTime()
                animarEntradaDeTexto()
            }
            b.canvas.scene.selectedId = item.id
        }
        b.canvas.scene.touch()
        b.canvas.requestRender()
        onSelectionChanged(b.canvas.scene.selected())
    }

    private fun borrarOverlay(item: ImageItem) {
        // El texto se despide con la animación que le toca antes de desaparecer.
        if (item is TextoItem && item.visible) {
            animarSalidaDeTexto(item) {
                b.canvas.scene.remove(item)
                refreshOverlayTiles()
                onSelectionChanged(b.canvas.selection)
            }
            return
        }
        b.canvas.scene.remove(item)
        b.canvas.requestRender()
        onSelectionChanged(b.canvas.selection)
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------------ orientación del lienzo

    private fun actualizarInfoLienzo() {
        // En vertical el chip ya dice 9:16, así que la leyenda muestra solo la resolución.
        // Si el equipo bajó la resolución al grabar, se muestra la que de verdad se usa.
        val ancho = if (anchoGrabando > 0) anchoGrabando else recAncho
        val alto = if (altoGrabando > 0) altoGrabando else recAlto
        b.txtCanvasInfo.text = when {
            modoStudio -> "${if (studioVertical) "9:16" else "16:9"} · $ancho×$alto"
            vertical -> "$ancho×$alto"
            else -> "16:9 · $ancho×$alto"
        }
        b.btnOrientacion.text = if (lienzoVertical) "9:16" else "16:9"
        // La cabecera del visor se acorta en vertical (el medidor la reescribe cada 2 s).
        if (vertical || modoStudio) b.txtEtiquetaPrograma.text = "PROGRAMA"
    }

    private fun cambiarOrientacion(nuevoVertical: Boolean) {
        // En Modo Studio el chip cambia el formato del modo (9:16 / 16:9) sin salir de él:
        // monitores y tarjetas cambian juntos.
        if (modoStudio) {
            if (sesionEnCurso()) {
                toast("Formato bloqueado mientras grabas o transmites")
                return
            }
            if (studioVertical == nuevoVertical) return
            studioVertical = nuevoVertical
            // El acomodo de los botones cambia con el formato: se rehace el layout de Modo Studio.
            layoutStudioListo = false
            b.cuerpo.removeAllViews()
            construirLayoutStudio()
            aplicarAspectoEstudio()
            refreshOverlayTiles()
            refreshMediaTiles()
            toast(if (studioVertical) "Modo Studio · 9:16" else "Modo Studio · 16:9")
            return
        }
        if (vertical == nuevoVertical) return
        // Candado: el formato no se puede cambiar en medio de una grabación o transmisión.
        if (sesionEnCurso()) {
            toast("Formato bloqueado mientras grabas o transmites")
            return
        }
        vertical = nuevoVertical
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(CLAVE_VERTICAL, vertical).apply()
        // En el celular el 9:16 pone la pantalla en vertical (y el 16:9 la devuelve a horizontal):
        // así el lienzo vertical llena la pantalla en vez de quedar chico dentro de un marco apaisado.
        if (esPantallaCelular()) {
            requestedOrientation = if (vertical) {
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
            }
        }
        aplicarOrientacion()
        toast(
            if (vertical) {
                "Lienzo vertical 9:16: listo para historias y transmisión vertical"
            } else {
                "Lienzo horizontal 16:9"
            }
        )
    }

    /** Hay una sesión en curso que no admite cambios de formato. */
    private fun sesionEnCurso(): Boolean = recorder.isRecording || transmitiendo

    /** Refleja en la UI el candado de orientación (chip). */
    private fun actualizarCandadoOrientacion() {
        val bloqueado = sesionEnCurso()
        b.btnOrientacion.alpha = if (bloqueado) 0.4f else 1f
        b.btnOrientacion.text = when {
            bloqueado -> (if (vertical) "🔒 9:16" else "🔒 16:9")
            vertical -> "9:16"
            else -> "16:9"
        }
    }

    /** Cambia la duración de la transición entre 300, 500, 800 y 1200 ms. */
    /** Selector de las 12 transiciones de entrada. */
    /** Selector de transición con vista previa y duración con barra. */
    private fun dialogoTransiciones() {
        val db = DialogTransicionesBinding.inflate(layoutInflater)
        val tipos = CompositorView.Transicion.values()

        // Lista de las 12 opciones
        db.rgTransiciones.removeAllViews()
        tipos.forEachIndexed { i, tipo ->
            val rb = RadioButton(this).apply {
                id = 2000 + i
                text = tipo.label
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@StudioActivity, R.color.text_secondary))
                buttonTintList = ContextCompat.getColorStateList(this@StudioActivity, R.color.accent)
                isChecked = tipo == b.canvas.transicion
            }
            db.rgTransiciones.addView(rb)
        }

        // Duración con barra: 200..1600 ms
        db.sbDuracion.progress = (b.canvas.transicionMs - 200).coerceIn(0, 1400)
        db.txtDuracion.text = "Duración: ${b.canvas.transicionMs} ms"

        fun duracionActual(): Int = db.sbDuracion.progress + 200

        fun guardar() {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(CLAVE_TRANSICION, b.canvas.transicion.id)
                .putInt(CLAVE_TRANSICION_MS, b.canvas.transicionMs)
                .apply()
        }

        db.rgTransiciones.setOnCheckedChangeListener { grupo, id ->
            val i = id - 2000
            if (i in tipos.indices) {
                b.canvas.transicion = tipos[i]
                b.canvas.transicionMs = duracionActual()
                guardar()
                reproducirPreview(db, tipos[i], b.canvas.transicionMs)
            }
        }

        db.sbDuracion.setOnSeekBarChangeListener(
            object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    sb: android.widget.SeekBar?,
                    value: Int,
                    fromUser: Boolean
                ) {
                    db.txtDuracion.text = "Duración: ${value + 200} ms"
                    if (fromUser) {
                        b.canvas.transicionMs = value + 200
                        guardar()
                    }
                }

                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}

                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                    reproducirPreview(db, b.canvas.transicion, b.canvas.transicionMs)
                }
            }
        )

        db.btnProbar.setOnClickListener {
            reproducirPreview(db, b.canvas.transicion, b.canvas.transicionMs)
        }

        val dialogo = AlertDialog.Builder(this).setView(db.root).create()
        db.btnCerrar.setOnClickListener {
            guardar()
            dialogo.dismiss()
            toast("Transición: ${b.canvas.transicion.label} · ${b.canvas.transicionMs} ms")
        }
        dialogo.setOnShowListener {
            // Primera pasada automática para mostrar cómo se ve
            db.preview.postDelayed(
                { reproducirPreview(db, b.canvas.transicion, b.canvas.transicionMs) },
                250
            )
        }
        dialogo.show()
    }

    /** Anima la vista previa del diálogo imitando la transición del lienzo. */
    private fun reproducirPreview(
        db: DialogTransicionesBinding,
        tipo: CompositorView.Transicion,
        duracionMs: Int
    ) {
        val item = db.previewItem
        val cortina = db.previewCortina
        val ancho = db.preview.width.toFloat().coerceAtLeast(1f)
        val alto = db.preview.height.toFloat().coerceAtLeast(1f)

        // Estado inicial de cada transición
        item.animate().cancel()
        cortina.animate().cancel()
        item.alpha = 1f
        item.translationX = 0f
        item.translationY = 0f
        item.scaleX = 1f
        item.scaleY = 1f
        cortina.visibility = View.GONE
        cortina.translationX = 0f
        cortina.translationY = 0f

        val d = duracionMs.toLong().coerceIn(80L, 2000L)
        val suave = android.view.animation.DecelerateInterpolator()

        when (tipo) {
            CompositorView.Transicion.CORTE -> {
                item.alpha = 1f
                return
            }

            CompositorView.Transicion.FUNDIDO -> {
                item.alpha = 0f
                item.animate().alpha(1f).setDuration(d).setInterpolator(suave).start()
            }

            CompositorView.Transicion.DESLIZ_IZQ -> {
                item.translationX = -ancho
                item.animate().translationX(0f).setDuration(d).setInterpolator(suave).start()
            }

            CompositorView.Transicion.DESLIZ_DER -> {
                item.translationX = ancho
                item.animate().translationX(0f).setDuration(d).setInterpolator(suave).start()
            }

            CompositorView.Transicion.DESLIZ_ARRIBA -> {
                item.translationY = -alto
                item.animate().translationY(0f).setDuration(d).setInterpolator(suave).start()
            }

            CompositorView.Transicion.DESLIZ_ABAJO -> {
                item.translationY = alto
                item.animate().translationY(0f).setDuration(d).setInterpolator(suave).start()
            }

            CompositorView.Transicion.WIPE_IZQ, CompositorView.Transicion.WIPE_DER,
            CompositorView.Transicion.WIPE_ARRIBA, CompositorView.Transicion.WIPE_ABAJO -> {
                item.alpha = 1f
                cortina.visibility = View.VISIBLE
                when (tipo) {
                    CompositorView.Transicion.WIPE_IZQ -> {
                        cortina.translationX = 0f
                        cortina.animate().translationX(ancho).setDuration(d)
                            .setInterpolator(suave).start()
                    }

                    CompositorView.Transicion.WIPE_DER -> {
                        cortina.translationX = 0f
                        cortina.animate().translationX(-ancho).setDuration(d)
                            .setInterpolator(suave).withEndAction { cortina.visibility = View.GONE }.start()
                    }

                    CompositorView.Transicion.WIPE_ARRIBA -> {
                        cortina.translationY = 0f
                        cortina.animate().translationY(-alto).setDuration(d)
                            .setInterpolator(suave).withEndAction { cortina.visibility = View.GONE }.start()
                    }

                    else -> {
                        cortina.translationY = 0f
                        cortina.animate().translationY(alto).setDuration(d)
                            .setInterpolator(suave).start()
                    }
                }
                cortina.animate().withEndAction { cortina.visibility = View.GONE }.start()
            }

            CompositorView.Transicion.ZOOM_IN -> {
                item.alpha = 0f
                item.scaleX = 1.35f
                item.scaleY = 1.35f
                item.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(d)
                    .setInterpolator(suave).start()
            }

            CompositorView.Transicion.ZOOM_OUT -> {
                item.alpha = 0f
                item.scaleX = 0.75f
                item.scaleY = 0.75f
                item.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(d)
                    .setInterpolator(suave).start()
            }
        }
    }

    // ------------------------------------------------------------------ audio

    private val rutaAudio by lazy { AudioRuta(this) }

    /**
     * Micrófono abierto mientras no hay sesión, para que el vúmetro se mueva **desde que se abre
     * la app** y se vea cómo está entrando el audio antes de grabar o transmitir.
     */
    private val audioVivo by lazy { AudioVivo(this, recorder) }

    /** La app está a la vista: solo entonces vale la pena tener el micrófono abierto. */
    private var enPrimerPlano = false

    /** Nivel que muestra el vúmetro: en sesión lo manda el grabador; si no, la entrada en vivo. */
    private fun nivelEntrada(): Float = when {
        recorder.isRecording -> recorder.lastLevel
        transmitiendo -> emisor.nivel
        else -> audioVivo.nivel
    }

    /**
     * Abre o cierra el micrófono del vúmetro según haga falta.
     *
     * Se cierra en cuanto hay sesión: dos capturas a la vez se pelean por el micrófono y el
     * grabador se quedaría sin audio. Si el aparato todavía está ocupado (el grabador lo suelta un
     * instante después de detenerse), se reintenta enseguida en vez de quedarse sin vúmetro.
     */
    private fun ajustarAudioVivo(intento: Int = 0) {
        val quiere = enPrimerPlano && !sesionEnCurso()
        if (quiere) {
            if (!audioVivo.activo && !audioVivo.arrancar() && intento < 5) {
                uiHandler.postDelayed({ ajustarAudioVivo(intento + 1) }, 400)
            }
        } else if (audioVivo.activo) {
            audioVivo.detener()
        }
    }

    private fun dbAProgreso(progreso: Int): Float = (progreso / 100f) * 24f - 12f

    /**
     * Deja lista la entrada de audio al abrir la app: si el dispositivo guardado sigue
     * disponible se usa ese; si no, cae en el micrófono de la tablet (entrada del sistema).
     */
    private fun aplicarAudioGuardado() {
        runCatching {
            val p = getSharedPreferences(PREFS, MODE_PRIVATE)
            val id = p.getInt(CLAVE_AUDIO_ID, -1)
            val tipo = p.getInt(CLAVE_AUDIO_TIPO, -1)
            val entradas = rutaAudio.entradas()
            val elegida = entradas.firstOrNull { it.id == id }
                ?: entradas.firstOrNull { it.tipo == tipo }
                ?: entradas.firstOrNull { it.tipo == android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC }
                ?: entradas.firstOrNull()
            if (elegida != null) {
                rutaAudio.seleccionar(elegida.id, elegida.tipo)
                recorder.entradaExterna = rutaAudio.entradaExterna
                entradaAudioNombre = elegida.nombre
                L.i("audio", "entrada al arrancar: ${elegida.nombre}")
            }
        }
    }

    /** Recupera los EQ, el volumen de monitoreo y su estado de las preferencias. */
    private fun cargarAudioAvanzado() {
        runCatching {
            val p = getSharedPreferences(PREFS, MODE_PRIVATE)
            eqEntrada.fijarTodas(
                floatArrayOf(
                    p.getFloat(CLAVE_EQ_ENT_0, 0f),
                    p.getFloat(CLAVE_EQ_ENT_1, 0f),
                    p.getFloat(CLAVE_EQ_ENT_2, 0f),
                    p.getFloat(CLAVE_EQ_ENT_3, 0f),
                    p.getFloat(CLAVE_EQ_ENT_4, 0f)
                )
            )
            monitorAudio.eq.fijarTodas(
                floatArrayOf(
                    p.getFloat(CLAVE_EQ_SAL_0, 0f),
                    p.getFloat(CLAVE_EQ_SAL_1, 0f),
                    p.getFloat(CLAVE_EQ_SAL_2, 0f),
                    p.getFloat(CLAVE_EQ_SAL_3, 0f),
                    p.getFloat(CLAVE_EQ_SAL_4, 0f)
                )
            )
            monitorAudio.volumen = p.getFloat(CLAVE_AUDIO_MON_VOL, 1f)
        }
    }

    /** Diálogo de audio: fuente de entrada, medidor, ganancia y silencio. */
    // ------------------------------------------------------------------ audio: EQ y monitoreo

    private val eqEntrada by lazy { AudioEq() }
    private val monitorAudio by lazy { AudioMonitor(this) }

    /** Deja un SeekBar con aspecto de fader de consola (riel oscuro y agarradera naranja). */
    private fun estilizarFader(sb: android.widget.SeekBar) {
        sb.progressDrawable = ContextCompat.getDrawable(this, R.drawable.fader_track)
        sb.thumb = ContextCompat.getDrawable(this, R.drawable.fader_thumb)
        sb.splitTrack = false
        sb.minHeight = 42.dp
        sb.setPadding(6.dp, 5.dp, 6.dp, 5.dp)
        sb.progressTintList = null
        sb.thumbTintList = null
    }

    /** Fila de control para una banda del ecualizador. */
    private fun filaBanda(
        titulo: String,
        eq: AudioEq,
        indice: Int,
        alCambiar: () -> Unit
    ): Triple<View, android.widget.SeekBar, TextView> {
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fila.addView(TextView(this).apply {
            text = titulo
            textSize = 11f
            setTextColor(ContextCompat.getColor(this@StudioActivity, R.color.text_secondary))
            width = 62.dp
        })
        val valor = TextView(this).apply {
            text = String.format(java.util.Locale.US, "%+.0f dB", eq.gananciasDb[indice])
            textSize = 11f
            setTextColor(ContextCompat.getColor(this@StudioActivity, R.color.accent))
            gravity = Gravity.END
            width = 56.dp
        }
        val barra = android.widget.SeekBar(this).apply {
            estilizarFader(this)
            max = 2400
            progress = ((eq.gananciasDb[indice] + 12f) * 100f).toInt().coerceIn(0, 2400)
            setOnSeekBarChangeListener(object :
                android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    sb: android.widget.SeekBar?,
                    value: Int,
                    fromUser: Boolean
                ) {
                    val db = value / 100f - 12f
                    eq.fijarBanda(indice, db)
                    valor.text = String.format(java.util.Locale.US, "%+.0f dB", db)
                    if (fromUser) alCambiar()
                }

                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
            })
        }
        fila.addView(
            barra,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 12.dp; marginEnd = 12.dp }
        )
        fila.addView(valor)
        fila.setPadding(0, 4.dp, 0, 4.dp)
        return Triple(fila, barra, valor)
    }

    /** Bloque de ecualizador con sus 5 bandas y presets. */
    private fun bloqueEq(eq: AudioEq, alCambiar: () -> Unit): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bandas = ArrayList<Triple<View, android.widget.SeekBar, TextView>>()
        for (i in AudioEq.BANDAS.indices) {
            val fila = filaBanda(AudioEq.NOMBRES[i], eq, i, alCambiar)
            bandas.add(fila)
            col.addView(fila.first)
        }
        val presets = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8.dp, 0, 0)
        }
        fun boton(texto: String, curva: FloatArray): TextView = TextView(this).apply {
            text = texto
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@StudioActivity, R.color.text_primary))
            setBackgroundResource(R.drawable.bg_pill_ghost)
            setPadding(12.dp, 6.dp, 12.dp, 6.dp)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = 6.dp }
            setOnClickListener {
                eq.fijarTodas(curva)
                // Mover los faders para que se vea el preset
                bandas.forEachIndexed { i, banda ->
                    val db = curva.getOrNull(i) ?: 0f
                    banda.second.progress = ((db + 12f) * 100f).toInt().coerceIn(0, 2400)
                    banda.third.text = String.format(java.util.Locale.US, "%+.0f dB", db)
                }
                alCambiar()
                toast("Preset aplicado: $texto")
            }
        }
        presets.addView(boton("Plano", AudioEq.presetPlano()))
        presets.addView(boton("Voz", AudioEq.presetVoz()))
        presets.addView(boton("Música", AudioEq.presetMusica()))
        presets.addView(boton("Ambiente", AudioEq.presetAmbiente()))
        col.addView(presets)
        return col
    }

    private fun seccion(titulo: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 14.dp, 0, 0)
        addView(TextView(this@StudioActivity).apply {
            text = titulo
            textSize = 11f
            letterSpacing = 0.1f
            setTextColor(ContextCompat.getColor(this@StudioActivity, R.color.text_dim))
            setPadding(0, 0, 0, 6.dp)
        })
    }

    /**
     * Editor de audio: fuente de entrada con su ecualizador y monitoreo de salida con
     * el suyo. El EQ es real (biquad de 5 bandas) y se aplica a lo que se graba y a
     * lo que se escucha.
     */
    // ------------------------------------------------------------------ transmisión

    // ------------------------------------------------------------------ transmisión (RootEncoder)

    /** Motor de salida: un solo codificador para RTMP y para el archivo. */
    /** Motor de salida: uno solo por proceso y fuera de la pantalla (ver [Emision]). */
    private val emisor: com.jfrmedia.switcher.studio.stream.Emisor
        get() = com.jfrmedia.switcher.studio.stream.Emision.obtener(this)

    /**
     * Vuelve a enganchar esta pantalla como escucha del emisor.
     *
     * Se llama al crear la pantalla porque Android la recrea (cambio de ventana, poca memoria) y el
     * emisor sigue siendo el mismo: sin esto la pantalla nueva no se enteraba de nada y mostraba
     * «Transmitir» con el directo en el aire.
     */
    private fun escucharEmisor() {
        emisor.alEstado = { estado, mensaje ->
            runOnUiThread {
                L.i("stream", "estado: $estado · $mensaje")
                anotar("estado: $estado · $mensaje")
                when (estado) {
                    com.jfrmedia.switcher.studio.stream.Emisor.Estado.TRANSMITIENDO -> {
                        // El cronómetro mide el tiempo ENVIANDO a Facebook, no el del directo
                        // publicado (ese solo lo sabe el Producer).
                        b.txtLive.text = "ENVIANDO"
                        b.liveDot.setBackgroundResource(R.drawable.bg_dot_red)
                        recStartMs = SystemClock.elapsedRealtime()
                        uiHandler.removeCallbacks(ticker)
                        uiHandler.post(ticker)
                        abrirRegistro("transmisión")
                        anotar("conectado a Facebook")
                    }

                    com.jfrmedia.switcher.studio.stream.Emisor.Estado.ERROR -> {
                        b.txtLive.text = "Sin conexión"
                        b.txtTimer.text = "00:00"
                        uiHandler.removeCallbacks(ticker)
                        b.liveDot.setBackgroundResource(R.drawable.bg_dot_gray)
                        anotar("error: $mensaje")
                    }

                    com.jfrmedia.switcher.studio.stream.Emisor.Estado.DETENIDO -> {
                        b.txtLive.text = "Sin transmisión"
                        b.txtTimer.text = "00:00"
                        uiHandler.removeCallbacks(ticker)
                        b.liveDot.setBackgroundResource(R.drawable.bg_dot_gray)
                        if (!recorder.isRecording) cerrarRegistro()
                    }

                    else -> Unit
                }
                actualizarBotonSesion()
                actualizarCandadoOrientacion()
            }
        }
    }

    /** MP4 de respaldo del directo, pendiente de publicar en la galería. */
    private var archivoEmision: java.io.File? = null

    /** true cuando el usuario detiene el directo: entonces sí se publica la grabación. */
    private var publicarEmision = false

    /** Graba en MP4 con el mismo codificador que está transmitiendo. */
    private fun grabarConEmisor(): Boolean {
        val carpeta = java.io.File(getExternalFilesDir(null) ?: filesDir, "Emision")
        if (!carpeta.exists()) carpeta.mkdirs()
        val nombre = "JFR_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            .format(java.util.Date()) + ".mp4"
        val archivo = java.io.File(carpeta, nombre)
        archivoEmision = archivo
        publicarEmision = false
        emisor.alCerrarGrabacion = { ruta ->
            runOnUiThread { publicarGrabacionDelDirecto(java.io.File(ruta)) }
        }
        val ok = emisor.grabar(archivo.absolutePath)
        if (ok) toast("Grabando en $nombre") else {
            archivoEmision = null
            toast("No se pudo grabar")
        }
        return ok
    }

    /** true mientras se está publicando, para que no se copie dos veces. */
    private var publicandoEmision = false

    /**
     * Publica en la galería la grabación hecha durante el directo. Solo cuando la sesión la
     * detiene el usuario: si el envío falla, el respaldo se queda en «Emision» como antes.
     *
     * El muxer escribe el índice («moov») al cerrar, un instante DESPUÉS del aviso STOPPED.
     * Si se copia antes, el MP4 queda sin índice y ningún reproductor lo abre: se espera y
     * se reintenta hasta que el índice está.
     */
    private fun publicarGrabacionDelDirecto(archivo: java.io.File, intento: Int = 0) {
        if (!publicarEmision || archivoEmision == null) return
        if (intento == 0) {
            if (publicandoEmision) return
            publicandoEmision = true
        }
        val terminar = {
            archivoEmision = null
            publicarEmision = false
            publicandoEmision = false
        }
        if (!archivo.exists() || archivo.length() < 1024) {
            if (intento < 8) {
                uiHandler.postDelayed({ publicarGrabacionDelDirecto(archivo, intento + 1) }, 400)
            } else {
                terminar()
            }
            return
        }
        if (!mp4TieneIndice(archivo)) {
            if (intento < 10) {
                uiHandler.postDelayed({ publicarGrabacionDelDirecto(archivo, intento + 1) }, 500)
            } else {
                terminar()
                toast("La grabación del directo no se pudo cerrar; quedó en la carpeta Emision")
            }
            return
        }
        val mb = (archivo.length() / (1024 * 1024)).coerceAtLeast(1)
        terminar()
        val uri = publishVideo(archivo)
        if (uri != null) {
            lastVideoUri = uri
            toast("Grabación del directo guardada en la galería ($mb MB)")
        } else {
            toast("Grabación del directo en ${archivo.parentFile?.name}/${archivo.name}")
        }
    }

    /** true si el MP4 ya tiene su átomo «moov» (índice): sin él no se puede reproducir. */
    private fun mp4TieneIndice(archivo: java.io.File): Boolean = runCatching {
        val buscado = byteArrayOf(
            'm'.code.toByte(), 'o'.code.toByte(), 'o'.code.toByte(), 'v'.code.toByte()
        )
        java.io.FileInputStream(archivo).use { entrada ->
            val buffer = ByteArray(64 * 1024)
            var leidos = entrada.read(buffer)
            while (leidos > 0) {
                var i = 0
                while (i + 4 <= leidos) {
                    if (buffer[i] == buscado[0] && buffer[i + 1] == buscado[1] &&
                        buffer[i + 2] == buscado[2] && buffer[i + 3] == buscado[3]
                    ) {
                        return@use true
                    }
                    i++
                }
                leidos = entrada.read(buffer)
            }
            false
        }
    }.getOrDefault(false)

    /**
     * Abre el Live Producer de Facebook en Chrome, a pantalla completa.
     *
     * Se fuerza Chrome porque Android entrega los enlaces de facebook.com a la app de Facebook,
     * que abre su Producer en modo cámara (pantalla negra y sin opción de RTMP).
     */
    private fun abrirLiveProducer() {
        val pagina = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(CLAVE_FB_PAGE_ID, "").orEmpty().trim()
        if (pagina.isBlank()) {
            toast("Escribe el ID de tu página en este diálogo para abrir su Producer")
            return
        }
        // Sin `target` Facebook manda al muro en vez del Producer.
        val url = "https://www.facebook.com/live/producer/?target=$pagina"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            setPackage("com.android.chrome")
        }
        val abierto = runCatching { startActivity(intent) }.isSuccess
        if (!abierto) {
            val suelto = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { startActivity(suelto) }
                .onFailure { toast("No hay navegador para abrir el Live Producer") }
        }
    }

    /**
     * Arranca el envío a Facebook.
     *
     * [urlDirecto] llega cuando **la app creó el directo**: esa dirección ya trae servidor y clave
     * juntos, así que no hay que pegar nada. Sin ella se usa la clave copiada del Live Producer.
     */
    private fun iniciarTransmision(urlDirecto: String? = null, conFacebook: Boolean = true) {
        // El vúmetro suelta el micrófono antes de que el emisor lo abra para el directo.
        runCatching { audioVivo.detener() }
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val clave = prefs.getString(CLAVE_FB_CLAVE, "").orEmpty().trim()
        // La clave se copia del Live Producer: servidor RTMPS guardado + clave.
        val url = if (!urlDirecto.isNullOrBlank()) {
            urlDirecto
        } else {
            if (clave.isEmpty()) {
                toast("Falta la clave: elige la Página y toca «Crear directo», o pega la clave")
                return
            }
            prefs.getString(CLAVE_FB_URL, FB_URL_POR_DEFECTO).orEmpty().trim() + clave
        }
        // El envío usa el mismo criterio que la grabación: el número es el lado corto, así que
        // en vertical 720p sale 720×1280 (antes se forzaba 16:9 y el lienzo vertical se iba
        // con barras negras a los lados).
        val ladoCorto = when {
            streamAlto >= 1080 -> 1080
            streamAlto >= 720 -> 720
            else -> 480
        }
        val (ancho, alto) = tamanoGrabacion(ladoCorto)
        val kbps = if (bitrateAuto) bitrateEmisionAuto(ladoCorto) else streamBitrateKbps
        emisor.volteoVertical = prefs.getBoolean("stream_flip", false)
        val superficie = emisor.preparar(ancho, alto, kbps, 30, lienzoVertical)
        if (superficie == null) {
            toast("No se pudo preparar la transmisión")
            return
        }
        // El compositor dibuja el programa en la superficie del emisor.
        vistaAlAire().beginRecordingOutput(superficie, ancho, alto, System.nanoTime())
        // La grabación va antes de transmitir: así el muxer recibe el formato de video.
        // En «Grabar y transmitir a la vez» se graba siempre, aunque el interruptor de
        // respaldo esté apagado.
        val grabarRespaldo = modoSesion == "ambos" || prefs.getBoolean(CLAVE_FB_GRABAR, true)
        if (grabarRespaldo && !emisor.grabando) {
            if (grabarConEmisor()) {
                recStartMs = SystemClock.elapsedRealtime()
                b.liveDot.setBackgroundResource(R.drawable.bg_dot_red)
            }
        }
        if (!emisor.transmitir(url)) {
            // Sin conexión no se deja abierta la grabación de respaldo: quedaba un MP4
            // creciendo en la carpeta «Emision» que la pantalla no mostraba ni podía detener
            // (pasó de verdad al probar el 27/10).
            if (emisor.grabando) emisor.detenerGrabacion()
            vistaAlAire().endRecordingOutput()
            vistaAlAire().salidaInvertida = false
            b.liveDot.setBackgroundResource(R.drawable.bg_dot_gray)
            return
        }
        ventanasRedFloja = 0
        actualizarBotonSesion()
        actualizarCandadoOrientacion()
        abrirRegistro("transmisión")
        iniciarProteccionSesion()
        toast("Conectando con Facebook…")
        // Al salir al aire se arranca la lectura. El id del directo va dentro de la dirección
        // (`.../rtmp/<id>?…` o `.../rtmp/FB-<id>-0-…`); si no saliera, se usa el que guardó la app.
        // Si el destino es solo YouTube no hay nada que leer en Facebook, pero el panel sí sigue
        // activo: es el que muestra los comentarios del chat de YouTube.
        fbLeyendo = true
        if (conFacebook) {
            iniciarConsultasFacebook(
                com.jfrmedia.switcher.studio.stream.FacebookLive.videoDeLaUrl(url).orEmpty()
                    .ifBlank { prefs.getString(CLAVE_FB_DIRECTO_ID, "").orEmpty().trim() }
            )
        }
        // Cronómetro del directo: si no hay grabación local, cuenta el tiempo en el aire.
        if (!recorder.isRecording) {
            if (!emisor.grabando) recStartMs = SystemClock.elapsedRealtime()
            uiHandler.removeCallbacks(ticker)
            uiHandler.post(ticker)
        }
    }

    /** Corta la transmisión y libera la superficie del compositor. */
    private fun cortarTransmision() {
        // El usuario cierra la sesión: la grabación de respaldo sí se publica en la galería.
        publicarEmision = archivoEmision != null
        if (emisor.grabando) emisor.detenerGrabacion()        // Si la librería no avisa del cierre del MP4, se publica igual un instante después.
        val pendiente = archivoEmision
        if (publicarEmision && pendiente != null) {
            uiHandler.postDelayed({
                if (publicarEmision && archivoEmision != null) {
                    publicarGrabacionDelDirecto(pendiente)
                }
            }, 1500)
        }
        emisor.detenerTransmision()
        // Ya no hay directo que leer: se deja de consultar Facebook.
        detenerConsultasFacebook()
        // Si el directo lo creó la app, se cierra también en Facebook para que no quede «fantasma».
        cerrarDirectoFacebook()
        cerrarDirectoYouTube()
        vistaAlAire().endRecordingOutput()
        vistaAlAire().salidaInvertida = false
        actualizarBotonSesion()
        actualizarCandadoOrientacion()
        if (!recorder.isRecording) {
            uiHandler.removeCallbacks(ticker)
            b.txtTimer.text = "00:00"
            b.liveDot.setBackgroundResource(R.drawable.bg_dot_gray)
            cerrarRegistro()
            detenerProteccionSesion()
        }
        // Se apaga el aviso de red floja: ya no hay envío que vigilar.
        uiHandler.removeCallbacks(parpadeoRed)
        ventanasRedFloja = 0
        if (redFloja) {
            redFloja = false
            valoresEstado["red"]?.alpha = 1f
            valoresEstado["red"]?.setTextColor(
                ContextCompat.getColor(this, R.color.text_primary)
            )
        }
        toast("Transmisión detenida")
    }
    /** Clave y URL de la transmisión (Facebook por ahora, luego más destinos). */
    /**
     * Diálogo de Facebook. El camino es **Live Producer + clave**: aquí se guardan el ID de la
     * página (para abrir su Producer), el servidor RTMPS y la clave que copias del Producer.
     */
    private fun dialogoFacebook() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val caja = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22.dp, 10.dp, 22.dp, 10.dp)
        }

        caja.addView(TextView(this).apply {
            text = "Con el token de usuario, la app trae tus Páginas y crea el directo sola. " +
                "Todo lo del Live Producer queda guardado en «Configurar respaldo»."
            textSize = 11f
            setTextColor(ContextCompat.getColor(this@StudioActivity, R.color.text_secondary))
            setPadding(0, 0, 0, 10.dp)
        })

        // --- Página y respaldo: van en un cuadro aparte para no estorbar aquí ---
        val cajaRespaldo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22.dp, 10.dp, 22.dp, 10.dp)
        }
        cajaRespaldo.addView(TextView(this).apply {
            text = "ID de la Página"
            textSize = 11f
            setTextColor(ContextCompat.getColor(this@StudioActivity, R.color.text_dim))
        })
        val campoPagina = EditText(this).apply {
            setText(prefs.getString(CLAVE_FB_PAGE_ID, ""))
            textSize = 12f
            setSingleLine(true)
            hint = "Se llena solo al elegir la Página"
        }
        cajaRespaldo.addView(campoPagina)
        cajaRespaldo.addView(TextView(this).apply {
            text = "Servidor RTMPS y clave (solo si transmites desde el Live Producer)"
            textSize = 11f
            setTextColor(ContextCompat.getColor(this@StudioActivity, R.color.text_dim))
            setPadding(0, 14.dp, 0, 0)
        })
        val campoUrl = EditText(this).apply {
            setText(prefs.getString(CLAVE_FB_URL, FB_URL_POR_DEFECTO))
            textSize = 12f
            setSingleLine(true)
        }
        cajaRespaldo.addView(campoUrl)
        val campoClave = EditText(this).apply {
            setText(prefs.getString(CLAVE_FB_CLAVE, ""))
            textSize = 12f
            setSingleLine(true)
            hint = "Clave del Live Producer"
        }
        cajaRespaldo.addView(campoClave)
        cajaRespaldo.addView(
            botonFb("Abrir el Live Producer") {
                prefs.edit().putString(CLAVE_FB_PAGE_ID, campoPagina.text.toString().trim()).apply()
                abrirLiveProducer()
            }
        )

        // --- Token de la Página (para espectadores y comentarios) ---
        caja.addView(TextView(this).apply {
            text = "Token de Facebook"
            textSize = 11f
            setTextColor(ContextCompat.getColor(this@StudioActivity, R.color.text_dim))
            setPadding(0, 14.dp, 0, 0)
        })
        val campoToken = EditText(this).apply {
            setText(prefs.getString(CLAVE_FB_TOKEN, ""))
            textSize = 12f
            setSingleLine(true)
            hint = "Token de USUARIO (el que trae tus Páginas)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        caja.addView(campoToken)
        caja.addView(TextView(this).apply {
            text = "Con este token la app trae la lista de tus Páginas y guarda el de la que elijas. " +
                "Necesita pages_show_list, pages_read_engagement y read_insights. Se guarda solo " +
                "en esta tablet."
            textSize = 10f
            setTextColor(ContextCompat.getColor(this@StudioActivity, R.color.text_secondary))
            setPadding(0, 4.dp, 0, 8.dp)
        })
        // Aquí se muestra el resultado de «Probar»: el error completo de Facebook si falla.
        val estadoFb = TextView(this).apply {
            text = ""
            textSize = 10f
            setTextColor(ContextCompat.getColor(this@StudioActivity, R.color.text_secondary))
            setPadding(0, 8.dp, 0, 0)
        }
        // --- Páginas que administras: se eligen aquí y la app crea el directo sola ---
        val rotuloPagina = TextView(this).apply {
            text = paginaElegidaTexto()
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@StudioActivity, R.color.text_primary))
            setPadding(0, 16.dp, 0, 4.dp)
        }
        caja.addView(rotuloPagina)
        val listaPaginas = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        caja.addView(
            botonFb("Traer mis Páginas") {
                listaPaginas.removeAllViews()
                val usuario = campoToken.text.toString().trim()
                if (usuario.isBlank()) {
                    listaPaginas.addView(textoAyuda("Pega primero el token de usuario (el del Explorer sin elegir Página)."))
                    return@botonFb
                }
                prefs.edit().putString(CLAVE_FB_USUARIO_TOKEN, usuario).apply()
                listaPaginas.addView(textoAyuda("Consultando tus Páginas…"))
                Thread {
                    var paginas: List<com.jfrmedia.switcher.studio.stream.FacebookLive.PaginaFb> =
                        emptyList()
                    var error = ""
                    try {
                        paginas = com.jfrmedia.switcher.studio.stream.FacebookLive.paginas(usuario)
                    } catch (t: Throwable) {
                        error = t.message ?: "error"
                    }
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        listaPaginas.removeAllViews()
                        if (error.isNotEmpty()) {
                            listaPaginas.addView(textoAyuda(cuandoFallaFacebook(error)))
                            return@runOnUiThread
                        }
                        if (paginas.isEmpty()) {
                            listaPaginas.addView(
                                textoAyuda(
                                    "No apareció ninguna Página. Revisa que el token sea de usuario y " +
                                        "que tenga el permiso pages_show_list."
                                )
                            )
                            return@runOnUiThread
                        }
                        paginas.forEach { pagina ->
                            listaPaginas.addView(
                                botonFb(pagina.nombre) {
                                    prefs.edit()
                                        .putString(CLAVE_FB_PAGE_ID, pagina.id)
                                        .putString(CLAVE_FB_PAGINA_TOKEN, pagina.token)
                                        .putString(CLAVE_FB_PAGINA_NOMBRE, pagina.nombre)
                                        .apply()
                                    campoPagina.setText(pagina.id)
                                    rotuloPagina.text = paginaElegidaTexto()
                                    listaPaginas.removeAllViews()
                                    estadoFb.text = "Página elegida: ${pagina.nombre}"
                                }
                            )
                        }
                    }
                }.apply { isDaemon = true }.start()
            }
        )
        caja.addView(listaPaginas)

        // --- YouTube: conectar y mandar ahí también (multistream) ---
        val rotuloYt = TextView(this).apply {
            text = "YouTube: sin conectar"
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@StudioActivity, R.color.text_primary))
            setPadding(0, 16.dp, 0, 4.dp)
        }
        rotuloYoutube = rotuloYt
        caja.addView(rotuloYt)
        caja.addView(
            botonFb("Conectar con YouTube") {
                val yt = com.jfrmedia.switcher.studio.stream.YouTubeLive
                if (yt.conectado(this)) {
                    yt.salir(this)
                    actualizarRotuloYouTube()
                    toast("YouTube desconectado")
                } else {
                    conectarYouTube()
                }
            }
        )
        actualizarRotuloYouTube()

        // --- Salir al aire: primero pregunta a dónde ---
        caja.addView(
            botonFb("Salir al aire") {
                cuadroActual?.dismiss()
                elegirDestinoYSalir()
            }
        )

        caja.addView(estadoFb)

        // --- Lo del Live Producer (ID de página, servidor, clave y la prueba del token) ---
        caja.addView(
            botonFb("Configurar respaldo (Live Producer)") {
                // La prueba del token vive aquí adentro para no llenar la pantalla principal.
                val botonProbar = botonFb("Probar Facebook") {
                    prefs.edit()
                        .putString(CLAVE_FB_PAGE_ID, campoPagina.text.toString().trim())
                        .putString(CLAVE_FB_TOKEN, campoToken.text.toString().trim())
                        .apply()
                    toast("Consultando Facebook…")
                    estadoFb.text = "Consultando…"
                    consultarFacebook(manual = true) { mensaje -> estadoFb.text = mensaje }
                }
                val cajaRespaldoConPrueba = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(cajaRespaldo)
                    addView(botonProbar)
                }
                AlertDialog.Builder(this)
                    .setTitle("Respaldo · Live Producer")
                    .setView(ScrollView(this).apply { addView(cajaRespaldoConPrueba) })
                    .setPositiveButton("Guardar") { _, _ ->
                        prefs.edit()
                            .putString(CLAVE_FB_PAGE_ID, campoPagina.text.toString().trim())
                            .putString(CLAVE_FB_URL, campoUrl.text.toString().trim())
                            .putString(CLAVE_FB_CLAVE, campoClave.text.toString().trim())
                            .apply()
                        toast("Respaldo guardado")
                    }
                    .setNegativeButton("Cerrar", null)
                    .show()
                    .also { cuadroALaIzquierda(it) }
            }
        )

        AlertDialog.Builder(this)
            .setTitle("Conectar con Facebook")
            .setView(ScrollView(this).apply { addView(caja) })
            .setPositiveButton(if (transmitiendo) "Detener" else "Transmitir") { _, _ ->
                prefs.edit()
                    .putString(CLAVE_FB_PAGE_ID, campoPagina.text.toString().trim())
                    .putString(CLAVE_FB_URL, campoUrl.text.toString().trim())
                    .putString(CLAVE_FB_CLAVE, campoClave.text.toString().trim())
                    .putString(CLAVE_FB_TOKEN, campoToken.text.toString().trim())
                    .apply()
                if (transmitiendo) cortarTransmision() else iniciarTransmision()
            }
            .setNegativeButton("Cerrar", null)
            .show()
            .also {
                cuadroActual = it
                cuadroALaIzquierda(it)
            }
    }

    /** Cuadro abierto del Facebook, para poder cerrarlo desde un botón de adentro. */
    private var cuadroActual: AlertDialog? = null

    /** Botón con el estilo de los demás del cuadro de Facebook. */
    private fun botonFb(texto: String, accion: () -> Unit): TextView = TextView(this).apply {
        text = texto
        textSize = 13f
        gravity = Gravity.CENTER
        setTextColor(ContextCompat.getColor(this@StudioActivity, R.color.text_primary))
        setBackgroundResource(R.drawable.bg_pill_ghost)
        setPadding(16.dp, 12.dp, 16.dp, 12.dp)
        setOnClickListener { accion() }
    }

    /** Texto chico de ayuda dentro del cuadro de Facebook. */
    private fun textoAyuda(texto: String): TextView = TextView(this).apply {
        text = texto
        textSize = 11f
        setTextColor(ContextCompat.getColor(this@StudioActivity, R.color.text_secondary))
        setPadding(0, 8.dp, 0, 4.dp)
    }

    /** Qué Página está elegida, en una línea. */
    private fun paginaElegidaTexto(): String {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val nombre = prefs.getString(CLAVE_FB_PAGINA_NOMBRE, "").orEmpty().trim()
        return if (nombre.isBlank()) "Página: ninguna elegida todavía" else "Página: $nombre"
    }
    private fun dialogoAudio() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val contenedor = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22.dp, 10.dp, 22.dp, 22.dp)
        }

        fun guardarTodo() {
            prefs.edit()
                .putFloat(CLAVE_AUDIO_DB, 20f * kotlin.math.log10(recorder.ganancia.coerceAtLeast(0.01f)))
                .putBoolean(CLAVE_AUDIO_MUDO, recorder.silencio)
                .putInt(CLAVE_AUDIO_MON, if (monitorAudio.encendido) 1 else 0)
                .putFloat(CLAVE_AUDIO_MON_VOL, monitorAudio.volumen)
                .putFloat(CLAVE_EQ_ENT_0, eqEntrada.gananciasDb[0])
                .putFloat(CLAVE_EQ_ENT_1, eqEntrada.gananciasDb[1])
                .putFloat(CLAVE_EQ_ENT_2, eqEntrada.gananciasDb[2])
                .putFloat(CLAVE_EQ_ENT_3, eqEntrada.gananciasDb[3])
                .putFloat(CLAVE_EQ_ENT_4, eqEntrada.gananciasDb[4])
                .putFloat(CLAVE_EQ_SAL_0, monitorAudio.eq.gananciasDb[0])
                .putFloat(CLAVE_EQ_SAL_1, monitorAudio.eq.gananciasDb[1])
                .putFloat(CLAVE_EQ_SAL_2, monitorAudio.eq.gananciasDb[2])
                .putFloat(CLAVE_EQ_SAL_3, monitorAudio.eq.gananciasDb[3])
                .putFloat(CLAVE_EQ_SAL_4, monitorAudio.eq.gananciasDb[4])
                .apply()
        }

        // ---- ENTRADA ----
        val secEnt = seccion("ENTRADA · lo que se graba y se transmite")
        contenedor.addView(secEnt)
        val rgEnt = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val entradas = rutaAudio.entradas()
        val idGuardado = prefs.getInt(CLAVE_AUDIO_ID, -1)
        entradas.forEachIndexed { i, entrada ->
            rgEnt.addView(RadioButton(this).apply {
                id = 1000 + i
                text = entrada.nombre
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@StudioActivity, R.color.text_secondary))
                buttonTintList = ContextCompat.getColorStateList(this@StudioActivity, R.color.accent)
                tag = entrada
                isChecked = entrada.id == idGuardado || (idGuardado == -1 && i == 0)
            })
        }
        rgEnt.setOnCheckedChangeListener { grupo, id ->
            val elegida = grupo.findViewById<RadioButton>(id)?.tag as? AudioRuta.Entrada
            if (elegida != null) {
                rutaAudio.seleccionar(elegida.id, elegida.tipo)
                recorder.entradaExterna = rutaAudio.entradaExterna
                entradaAudioNombre = elegida.nombre
                actualizarEntradaAudio()
                prefs.edit()
                    .putInt(CLAVE_AUDIO_ID, elegida.id)
                    .putInt(CLAVE_AUDIO_TIPO, elegida.tipo)
                    .apply()
                eqEntrada.reiniciar()
                // El vúmetro vuelve a abrir el micrófono con la entrada nueva.
                audioVivo.detener()
                ajustarAudioVivo()
            }
        }
        secEnt.addView(rgEnt)

        secEnt.addView(TextView(this).apply {
            text = "Ganancia de entrada"
            textSize = 11f
            setTextColor(ContextCompat.getColor(this@StudioActivity, R.color.text_secondary))
            setPadding(0, 8.dp, 0, 0)
        })
        val txtGanancia = TextView(this).apply {
            text = String.format(
                java.util.Locale.US, "%+.1f dB",
                20f * kotlin.math.log10(recorder.ganancia.coerceAtLeast(0.01f))
            )
            textSize = 11f
            setTextColor(ContextCompat.getColor(this@StudioActivity, R.color.accent))
        }
        secEnt.addView(txtGanancia)
        val sbGanancia = android.widget.SeekBar(this).apply {
            estilizarFader(this)
            max = 2400
            progress = (((20f * kotlin.math.log10(recorder.ganancia.coerceAtLeast(0.01f))) + 12f) * 100f)
                .toInt().coerceIn(0, 2400)
        }
        sbGanancia.setOnSeekBarChangeListener(object :
            android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, value: Int, fromUser: Boolean) {
                val db = value / 100f - 12f
                recorder.ganancia = Math.pow(10.0, (db / 20.0)).toFloat()
                txtGanancia.text = String.format(java.util.Locale.US, "%+.1f dB", db)
                if (fromUser) guardarTodo()
            }

            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
        })
        secEnt.addView(sbGanancia)

        val swMudo = android.widget.CheckBox(this).apply {
            text = "Silenciar la entrada"
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@StudioActivity, R.color.text_secondary))
            buttonTintList = ContextCompat.getColorStateList(this@StudioActivity, R.color.accent)
            isChecked = recorder.silencio
            setOnCheckedChangeListener { _, valor ->
                recorder.silencio = valor
                guardarTodo()
            }
        }
        secEnt.addView(swMudo)

        secEnt.addView(TextView(this).apply {
            text = "ECUALIZADOR DE ENTRADA"
            textSize = 11f
            letterSpacing = 0.1f
            setTextColor(ContextCompat.getColor(this@StudioActivity, R.color.text_dim))
            setPadding(0, 12.dp, 0, 4.dp)
        })
        secEnt.addView(bloqueEq(eqEntrada) { guardarTodo() })

        secEnt.addView(TextView(this).apply {
            text = "Nivel de entrada (en vivo: se mueve aunque no estés grabando)"
            textSize = 11f
            setTextColor(ContextCompat.getColor(this@StudioActivity, R.color.text_secondary))
            setPadding(0, 10.dp, 0, 4.dp)
        })
        secEnt.addView(
            com.jfrmedia.switcher.studio.ui.AudioMeterView(this).apply {
                // El mismo nivel que el vúmetro de arriba: en vivo fuera de sesión.
                levelProvider = { nivelEntrada() }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 24.dp
                )
            }
        )

        // ---- SALIDA ----
        val secSal = seccion("SALIDA · monitoreo para escuchar cómo queda")
        contenedor.addView(secSal)
        val swMon = android.widget.CheckBox(this).apply {
            text = "Escuchar por la salida (usa audífonos para evitar acople)"
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@StudioActivity, R.color.text_secondary))
            buttonTintList = ContextCompat.getColorStateList(this@StudioActivity, R.color.accent)
            isChecked = prefs.getInt(CLAVE_AUDIO_MON, 0) == 1
            setOnCheckedChangeListener { _, valor ->
                if (valor) {
                    if (monitorAudio.arrancar()) toast("Monitoreo encendido") else toast("No se pudo abrir la salida")
                } else {
                    monitorAudio.detener()
                }
                guardarTodo()
            }
        }
        secSal.addView(swMon)

        val rgSal = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val salidas = monitorAudio.salidas()
        salidas.forEachIndexed { i, salida ->
            rgSal.addView(RadioButton(this).apply {
                id = 3000 + i
                text = salida.nombre
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@StudioActivity, R.color.text_secondary))
                buttonTintList = ContextCompat.getColorStateList(this@StudioActivity, R.color.accent)
                tag = salida
                isChecked = i == 0
            })
        }
        rgSal.setOnCheckedChangeListener { grupo, id ->
            val elegida = grupo.findViewById<RadioButton>(id)?.tag as? AudioMonitor.Salida
            if (elegida != null) monitorAudio.seleccionarSalida(elegida.id)
        }
        secSal.addView(rgSal)

        val txtVol = TextView(this).apply {
            text = "Volumen de monitoreo: ${(monitorAudio.volumen * 100).toInt()}%"
            textSize = 11f
            setTextColor(ContextCompat.getColor(this@StudioActivity, R.color.accent))
            setPadding(0, 8.dp, 0, 0)
        }
        secSal.addView(txtVol)
        val sbVol = android.widget.SeekBar(this).apply {
            estilizarFader(this)
            max = 150
            progress = (monitorAudio.volumen * 100).toInt().coerceIn(0, 150)
            setOnSeekBarChangeListener(object :
                android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    sb: android.widget.SeekBar?,
                    value: Int,
                    fromUser: Boolean
                ) {
                    monitorAudio.volumen = value / 100f
                    txtVol.text = "Volumen de monitoreo: $value%"
                    if (fromUser) guardarTodo()
                }

                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
            })
        }
        secSal.addView(sbVol)

        secSal.addView(TextView(this).apply {
            text = "ECUALIZADOR DE SALIDA"
            textSize = 11f
            letterSpacing = 0.1f
            setTextColor(ContextCompat.getColor(this@StudioActivity, R.color.text_dim))
            setPadding(0, 12.dp, 0, 4.dp)
        })
        secSal.addView(bloqueEq(monitorAudio.eq) { guardarTodo() })

        val scroll = ScrollView(this).apply { addView(contenedor) }
        AlertDialog.Builder(this)
            .setTitle("Audio")
            .setView(scroll)
            .setNegativeButton("Cerrar") { _, _ -> guardarTodo() }
            .show()
    }

    /**
     * Horizontal: fuentes a la izquierda y programa con los controles debajo.
     * Vertical: controles en columna a la izquierda, lienzo 9:16 al centro y las
     * fuentes (cámaras y overlays, 3 por fila) ocupando el espacio libre a la derecha.
     */
    private fun aplicarOrientacion() {
        val aspecto = if (vertical) 9f / 16f else 16f / 9f
        b.canvas.scene.aspect = aspecto
        b.canvasHost.aspect = aspecto

        // El visor vive siempre dentro de su panel; en vertical el ancho se ajusta al alto.
        b.canvasHost.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        )

        ajustarBarraPorOrientacion()
        hub.vertical = vertical
        // Fuera del Modo Pokémon, los celulares vuelven a seguir la orientación del lienzo (en TCG
        // se pide por cámara: jugadores verticales aunque el programa sea 16:9).
        if (!modoTcg) soltarOrientacionCamaras()
        // La cámara de la tablet solo existe en horizontal: en vertical se apaga. En el celular
        // la cámara propia sigue disponible también en vertical (Jorge, 29/10).
        if (vertical && !esPantallaCelular()) {
            if (localCam.encendida) apagarCamaraTablet()
        } else {
            // Solo se enciende sola si el usuario la dejó encendida la última vez. En Modo Pokémon
            // NO: ahí la cámara de la tablet es opcional (cámara 4) y si se encendiera sola le
            // quitaría el lugar al cuarto celular (Jorge, 21/11/2026).
            if (!modoTcg &&
                getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(CLAVE_TABLET_CAM, false) &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                encenderCamaraTablet()
            }
        }
        // En Modo Studio el acomodo lo manda `construirLayoutStudio()`: si se reacomodara aquí se
        // desarmaría. Solo se reafirma el 9:16 de los dos monitores.
        if (modoStudio) {
            aplicarAspectoEstudio()
        } else {
            moverControles()
            reacomodarCuerpo()
        }
        buildSourcesGrid()
        // La rejilla se reconstruye: hay que volver a enganchar la cámara local a su tarjeta.
        localCam.track?.let { pista ->
            tiles.getOrNull(LocalCam.SLOT)?.bindLocal(pista, "Tablet")
        }
        // Si ya hay cámaras conectadas, se les avisa la nueva orientación del lienzo.
        hub.all().forEach { runCatching { it.enviarOrientacion() } }
        actualizarBotonTablet()
        // El corte de la pantalla partida depende de la orientación: se recoloca.
        if (splitActivo) aplicarLayoutSplit()
        actualizarInfoLienzo()
        ajustarLienzo()
        b.canvas.requestRender()
        refreshOverlayTiles()
        // En el celular el acomodo (horizontal o vertical 9:16) lo fija aplicarModoCelular().
        if (esPantallaCelular()) aplicarModoCelular()
    }

    /**
     * En vertical el visor vive DENTRO del cuerpo (así empieza debajo de la barra superior y
     * nunca la tapa) y ocupa toda la altura libre con relación 9:16, así que hay que fijarle
     * el ancho exacto al panel. En horizontal vuelve a repartirse con las fuentes por peso.
     */
    private fun ajustarLienzo() {
        uiHandler.postDelayed({ aplicarAjusteLienzo(0) }, 120)
    }

    /**
     * Fija el ancho del panel del programa (vertical) al alto que queda libre. Se repite unas
     * cuantas veces porque al cambiar el ancho la cabecera «PROGRAMA» puede pasar de una a dos
     * líneas: el ancho definitivo es el que ya no cambia, para que el lienzo llene el alto justo.
     */
    private fun aplicarAjusteLienzo(intento: Int) {
        // En el celular el tamaño del visor lo fija aplicarModoCelular(): aquí no se toca, si no
        // el lienzo se estiraba más de la pantalla y se salía del área visible (Jorge, 29/10).
        if (esPantallaCelular()) return
        if (!vertical) {
            b.contenido.setPadding(0, 0, 0, 0)
            b.canvasHost.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            b.canvasHost.requestLayout()
            return
        }
        b.canvasHost.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        )
        val ancho = anchoPrograma()
        if (ancho <= 0) return
        if (b.panelPrograma.layoutParams?.width != ancho) {
            b.panelPrograma.layoutParams = LinearLayout.LayoutParams(
                ancho, ViewGroup.LayoutParams.MATCH_PARENT
            )
            b.panelPrograma.requestLayout()
        }
        if (intento < 4) b.panelPrograma.post { aplicarAjusteLienzo(intento + 1) }
    }

    /**
     * Ancho que necesita el panel del programa en vertical: el alto del cuerpo, menos la
     * cabecera «PROGRAMA» y los rellenos del panel, pasado a 9:16, más los rellenos.
     */
    private fun anchoPrograma(): Int {
        val altoPanel = b.cuerpo.height
        if (altoPanel <= 0) return 0
        val panel = b.panelPrograma
        val cabecera = if (b.cabeceraPrograma.visibility == View.VISIBLE) {
            b.cabeceraPrograma.height
        } else {
            0
        }
        val altoLienzo = (altoPanel - panel.paddingTop - panel.paddingBottom - cabecera)
            .coerceAtLeast(1)
        val anchoLienzo = (altoLienzo * 9f / 16f).toInt()
        return anchoLienzo + panel.paddingStart + panel.paddingEnd
    }

    private fun reacomodarCuerpo() {
        // En el celular el acomodo lo manda aplicarModoCelular() (horizontal o vertical 9:16).
        if (esPantallaCelular()) return
        val cuerpo = b.cuerpo
        cuerpo.removeAllViews()
        // Los dos diseños de siempre (horizontal y 9:16) acomodan el cuerpo en fila. El Modo
        // Studio es el único que lo pone en columna, así que hay que devolverlo aquí.
        cuerpo.orientation = LinearLayout.HORIZONTAL
        (b.panelPrograma.parent as? ViewGroup)?.removeView(b.panelPrograma)
        // En vertical no hay banda: los dos bloques van a la columna.
        (zonaControles?.parent as? ViewGroup)?.removeView(zonaControles)
        if (vertical) {
            // La rejilla ocupa 3/4 del ancho y la columna de controles el cuarto que antes
            // usaba la cuarta cámara: ahí caben los botones en dos columnas y el monitor.
            cuerpo.addView(
                b.panelFuentes,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 3f)
            )
            cuerpo.addView(
                b.slotIzquierdo,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            )
            // El visor va dentro del cuerpo, no en la raíz: así la barra superior queda
            // libre y el panel empieza justo debajo de ella. El ancho definitivo lo pone
            // ajustarLienzo() en cuanto el cuerpo tiene alto medido.
            cuerpo.addView(
                b.panelPrograma,
                LinearLayout.LayoutParams(
                    anchoPrograma(),
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            b.slotIzquierdo.visibility = View.VISIBLE
        } else {
            b.slotIzquierdo.visibility = View.GONE
            b.contenido.setPadding(0, 0, 0, 0)
            cuerpo.addView(
                b.panelFuentes,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.45f)
            )
            cuerpo.addView(
                b.panelPrograma,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.55f)
            )
        }
        ubicarPanelAudio()
    }
    /** Tipo de letra y relleno originales de cada botón (para restaurarlos al volver a horizontal). */
    private val estilosOriginales = HashMap<TextView, Triple<Float, Int, Int>>()

    private fun recordarEstilo(v: TextView) {
        if (!estilosOriginales.containsKey(v)) {
            val sp = v.textSize / resources.displayMetrics.scaledDensity
            estilosOriginales[v] = Triple(sp, v.paddingStart, v.paddingEnd)
        }
    }

    private fun restaurarEstilo(v: TextView) {
        val original = estilosOriginales[v] ?: return
        v.textSize = original.first
        v.setPadding(original.second, v.paddingTop, original.third, v.paddingBottom)
    }

    /** Coloca la fila de ajustes de imagen: segunda fila en horizontal, continuación en vertical. */
    /**
     * Coloca el bloque de ajustes de imagen: debajo del visor en horizontal (botones centrados
     * y deslizable permanente abajo) y en la columna lateral en vertical (deslizable vertical).
     */
    /**
     * Coloca el bloque de ajustes: en horizontal debajo del visor con el deslizable fijo;
     * en vertical con los botones a la izquierda y el deslizable VERTICAL entre ellos y el visor.
     */
    /**
     * Ajustes de imagen. En horizontal: botones que se reparten el ancho del visor y deslizable
     * permanente debajo. En vertical: botones de tamaño uniforme y el deslizable aparece al tocar
     * uno de ellos (en un cuadro de diálogo), para no robar espacio a la columna.
     */
    private fun moverFilaVideo() {
        val caja = b.cajaVideo
        (caja.parent as? ViewGroup)?.removeView(caja)
        val botones = listOf(
            b.btnLuz, b.btnContraste, b.btnWb, b.btnColor, b.btnVineta, b.btnPoster
        )
        for (v in botones) (v.parent as? ViewGroup)?.removeView(v)

        if (vertical && !esPantallaCelular()) {
            // Dos columnas de ajustes: la columna es más ancha y así queda sitio para el monitor.
            caja.orientation = LinearLayout.VERTICAL
            b.filaVideo.removeAllViews()
            b.filaVideo.orientation = LinearLayout.VERTICAL
            botones.forEachIndexed { indice, v ->
                if (indice % 2 == 0) {
                    b.filaVideo.addView(LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = if (indice == 0) 0 else 3.dp }
                    })
                }
                recordarEstilo(v)
                v.layoutParams = LinearLayout.LayoutParams(0, 32.dp, 1f).apply {
                    if (indice % 2 == 1) marginStart = 3.dp
                }
                v.gravity = Gravity.CENTER
                v.textSize = 10f
                v.setPadding(2.dp, v.paddingTop, 2.dp, v.paddingBottom)
                (b.filaVideo.getChildAt(b.filaVideo.childCount - 1) as LinearLayout).addView(v)
            }
            // El deslizable no se muestra: cada botón abre su propio cuadro de ajuste.
            b.panelAjuste.visibility = View.GONE
            b.slotIzquierdo.addView(
                caja,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 6.dp }
            )
        } else if (esPantallaCelular()) {
            // En el celular no cabe una banda fija de ajustes: se quedan en la ventana «Img.» y
            // la banda se usa solo para el monitor de audio (se enciende con «♪ Audio»).
            ajustesEnUnaFila()
            montarZonaControles()
            b.cajaVideo.visibility = View.GONE
            asegurarZonaControles().visibility =
                if (vistaControles == "audio") View.VISIBLE else View.GONE
        } else {
            ajustesEnUnaFila()
            // Los ajustes de imagen viven en la banda fija, debajo de la fila de controles.
            montarZonaControles()
            asegurarZonaControles().addView(
                caja,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        refrescarPanelAjuste()
    }

    /**
     * Los ajustes de imagen (Luz, Contraste, WB, Color, Viñeta y Poster) en **una sola fila** de
     * seis botones, con el deslizable debajo.
     *
     * En Modo Studio se usa siempre así: antes el acomodo dependía del diseño anterior y al girar
     * la tablet quedaba a medias (dos columnas con el deslizable encima), que se veía mal.
     */
    private fun ajustesEnUnaFila() {
        val caja = b.cajaVideo
        val botones = listOf(
            b.btnLuz, b.btnContraste, b.btnWb, b.btnColor, b.btnVineta, b.btnPoster
        )
        for (v in botones) (v.parent as? ViewGroup)?.removeView(v)
        caja.orientation = LinearLayout.VERTICAL
        b.filaVideo.removeAllViews()
        b.filaVideo.orientation = LinearLayout.HORIZONTAL
        botones.forEachIndexed { i, v ->
            restaurarEstilo(v)
            v.layoutParams = LinearLayout.LayoutParams(
                0,
                34.dp,
                1f
            ).apply { setMargins(if (i == 0) 0 else 5.dp, 0, 0, 0) }
            v.gravity = Gravity.CENTER
            b.filaVideo.addView(v)
        }
        b.panelAjuste.visibility = if (modoTcg) View.GONE else View.VISIBLE
        b.panelAjuste.orientation = LinearLayout.HORIZONTAL
        (b.sbAjuste.parent as? ViewGroup)?.removeView(b.sbAjuste)
        b.sbAjuste.rotation = 0f
        b.txtAjusteNombre.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        b.sbAjuste.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { marginStart = 8.dp }
        b.txtAjusteValor.layoutParams =
            LinearLayout.LayoutParams(52.dp, ViewGroup.LayoutParams.WRAP_CONTENT)
        b.btnAjusteReset.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = 8.dp }
        b.panelAjuste.addView(b.sbAjuste, 1)
        if (b.panelAjuste.parent !== caja) {
            (b.panelAjuste.parent as? ViewGroup)?.removeView(b.panelAjuste)
            caja.addView(b.panelAjuste)
        }
    }

    // ------------------------------------------------------- controles imagen / audio

    /** «imagen» = Luz, contraste, WB… · «audio» = monitor de onda (solo en horizontal). */
    private var vistaControles = "imagen"
    private var btnVistaControles: TextView? = null
    private var panelAudio: LinearLayout? = null
    private var txtEntradaAudio: TextView? = null
    private var txtFormatoAudio: TextView? = null
    private var txtAyudaAudio: TextView? = null
    private var ondaAudio: com.jfrmedia.switcher.studio.ui.AudioWaveView? = null

    // ------------------------------------------------------- estado del equipo

    /** Últimos valores medidos, para el tablero que se muestra en vertical. */
    private var cpuActual = 0L
    private var ramActual = 0L
    private var mbpsActual = 0.0
    private var tempActual: Int? = null
    private var panelEstado: LinearLayout? = null
    private val valoresEstado = HashMap<String, TextView>()

    /** true mientras la subida esté por debajo de lo esperado: tiñe y hace parpadear SUBIDA. */
    private var redFloja = false
    private var ventanasRedFloja = 0
    private var parpadeoEncendido = true

    private val parpadeoRed = object : Runnable {
        override fun run() {
            if (!redFloja) {
                valoresEstado["red"]?.alpha = 1f
                return
            }
            parpadeoEncendido = !parpadeoEncendido
            valoresEstado["red"]?.alpha = if (parpadeoEncendido) 0.35f else 1f
            uiHandler.postDelayed(this, 450)
        }
    }

    /** Tablero con CPU, RAM, temperatura y subida de red (vertical, debajo del monitor). */
    private fun crearPanelEstado() {
        if (panelEstado != null) return
        val caja = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_tile)
            setPadding(8.dp, 6.dp, 8.dp, 6.dp)
        }

        fun celda(etiqueta: String, clave: String): LinearLayout {
            val columna = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
            }
            columna.addView(TextView(this).apply {
                text = etiqueta
                textSize = 8f
                letterSpacing = 0.08f
                setTextColor(ContextCompat.getColor(this@StudioActivity, R.color.text_dim))
            })
            val valor = TextView(this).apply {
                text = "—"
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@StudioActivity, R.color.text_primary))
            }
            valoresEstado[clave] = valor
            columna.addView(valor)
            return columna
        }

        val fila1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fila1.addView(celda("CPU", "cpu"))
        fila1.addView(celda("RAM", "ram"))
        val fila2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fila2.addView(celda("TEMP.", "temp"))
        fila2.addView(celda("SUBIDA", "red"))
        caja.addView(
            fila1,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        caja.addView(
            fila2,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 6.dp }
        )
        panelEstado = caja
        actualizarEstadoEquipo()
    }

    private fun actualizarEstadoEquipo() {
        if (valoresEstado.isEmpty()) return
        valoresEstado["cpu"]?.text = "$cpuActual%"
        valoresEstado["ram"]?.text = "$ramActual%"
        valoresEstado["temp"]?.text = tempActual?.let { "$it°" } ?: "—°"
        valoresEstado["red"]?.text = String.format(Locale.US, "%.1f Mbps", mbpsActual)
        // La temperatura alta se marca en rojo para que se note.
        valoresEstado["temp"]?.setTextColor(
            ContextCompat.getColor(
                this,
                if ((tempActual ?: 0) >= 45) R.color.danger else R.color.text_primary
            )
        )

        // Red floja: el cliente RTMP avisa de congestión (su cola de envío crece porque la
        // subida no aguanta el bitrate). Se piden dos avisos seguidos para no parpadear por
        // un bache de un instante.
        val bajaAhora = transmitiendo && emisor.congestionada
        ventanasRedFloja = if (bajaAhora) ventanasRedFloja + 1 else 0
        val floja = ventanasRedFloja >= 2
        if (floja != redFloja) {
            redFloja = floja
            val vista = valoresEstado["red"]
            if (floja) {
                vista?.setTextColor(ContextCompat.getColor(this, R.color.warn_yellow))
                parpadeoEncendido = true
                uiHandler.removeCallbacks(parpadeoRed)
                uiHandler.post(parpadeoRed)
            } else {
                uiHandler.removeCallbacks(parpadeoRed)
                vista?.alpha = 1f
                vista?.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            }
        }
    }

    /** Nombre de la entrada de audio elegida en el diálogo de Audio. */
    private var entradaAudioNombre = "Micrófono interno"

    /** Refresca la fuente de entrada mostrada mientras el monitor está abierto. */
    private val refrescoEntrada = object : Runnable {
        override fun run() {
            actualizarEntradaAudio()
            if (vistaControles == "audio") uiHandler.postDelayed(this, 1000)
        }
    }

    /**
     * Fuente que se está capturando: la elegida y, si el sistema entrega otra distinta, también
     * la real (el grabador la consulta al abrir el micrófono). Así se ve de un vistazo si se
     * está usando el micrófono correcto.
     */
    private fun actualizarEntradaAudio() {
        val real = if (recorder.isRecording) recorder.entradaReal else null
        val texto = buildString {
            append(entradaAudioNombre)
            if (real != null && !real.equals(entradaAudioNombre, ignoreCase = true)) {
                append("  →  en uso: ").append(real)
            }
        }
        txtEntradaAudio?.text = texto
    }

    /**
     * Interruptor «♪ Audio» dentro de la fila de controles y panel del monitor.
     *
     * En horizontal el hueco de Luz/Contraste se puede cambiar por el monitor de audio, que
     * muestra la onda de entrada con zonas verde/naranja/rojo para ver cuándo se está
     * acercando al recorte. En vertical el hueco no existe, así que el interruptor se oculta.
     */
    private fun montarControlesAudio() {
        val chip = TextView(this).apply {
            text = "♪ Audio"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@StudioActivity, R.color.text_primary))
            setBackgroundResource(R.drawable.bg_pill_ghost)
            setPadding(10.dp, 4.dp, 10.dp, 4.dp)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = 10.dp }
            setOnClickListener {
                mostrarVistaControles(if (vistaControles == "audio") "imagen" else "audio")
            }
        }
        btnVistaControles = chip
        chip.visibility = if (vertical) View.GONE else View.VISIBLE
        // Al principio de la fila: si se quedara al final, la barra deslizable lo escondía.
        b.controlesCapa.addView(chip, 0)

        val caja = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        // Cabecera: qué fuente de audio se está usando.
        val cabecera = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(3.dp, 0, 3.dp, 3.dp)
        }
        cabecera.addView(TextView(this).apply {
            text = "ENTRADA"
            textSize = 9f
            letterSpacing = 0.12f
            setTextColor(ContextCompat.getColor(this@StudioActivity, R.color.text_dim))
        })
        val nombre = TextView(this).apply {
            text = entradaAudioNombre
            textSize = 11f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(ContextCompat.getColor(this@StudioActivity, R.color.accent))
            setPadding(8.dp, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        txtEntradaAudio = nombre
        cabecera.addView(nombre)
        val formato = TextView(this).apply {
            text = "44,1 kHz · mono"
            textSize = 9f
            setTextColor(ContextCompat.getColor(this@StudioActivity, R.color.text_dim))
        }
        txtFormatoAudio = formato
        cabecera.addView(formato)
        caja.addView(cabecera)
        val onda = com.jfrmedia.switcher.studio.ui.AudioWaveView(this).apply {
            proveedorPico = { picoActual() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 118.dp
            )
        }
        ondaAudio = onda
        caja.addView(onda)
        val ayuda = TextView(this).apply {
            text = "Verde < −12 dB · naranja −12 a −3 · rojo > −3 · CLIP = entrada al máximo. " +
                "Se activa al grabar o transmitir."
            textSize = 9f
            setTextColor(ContextCompat.getColor(this@StudioActivity, R.color.text_dim))
            setPadding(3.dp, 3.dp, 3.dp, 0)
        }
        txtAyudaAudio = ayuda
        caja.addView(ayuda)
        panelAudio = caja
        crearPanelEstado()
        // El sitio del panel depende de la orientación; lo coloca `ubicarPanelAudio()`.
        ubicarPanelAudio()
    }

    /**
     * Coloca el monitor de audio donde corresponde: en horizontal vive en la zona de los
     * controles de imagen (oculto hasta que se elige «♪ Audio») y en vertical va siempre al
     * final de la columna de botones, debajo de ellos.
     */
    private fun ubicarPanelAudio() {
        val caja = panelAudio ?: return
        (caja.parent as? ViewGroup)?.removeView(caja)
        val estado = panelEstado
        (estado?.parent as? ViewGroup)?.removeView(estado)
        if (vertical) {
            b.cajaVideo.visibility = if (modoTcg) View.GONE else View.VISIBLE
            caja.visibility = View.VISIBLE
            txtFormatoAudio?.visibility = View.GONE
            txtAyudaAudio?.text = "Verde < −12 · naranja −12 a −3 · rojo > −3"
            ondaAudio?.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 100.dp
            )
            b.slotIzquierdo.addView(
                caja,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 10.dp }
            )
            if (estado != null) {
                estado.visibility = View.VISIBLE
                b.slotIzquierdo.addView(
                    estado,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = 8.dp }
                )
                actualizarEstadoEquipo()
            }
            actualizarEntradaAudio()
        } else {
            estado?.visibility = View.GONE
            txtFormatoAudio?.visibility = View.VISIBLE
            txtAyudaAudio?.text = "Verde < −12 dB · naranja −12 a −3 · rojo > −3 · CLIP = entrada al máximo. " +
                "Se activa al grabar o transmitir."
            // La onda se lleva el alto de la banda: así el visor no cambia al alternar vistas.
            ondaAudio?.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            montarZonaControles()
            asegurarZonaControles().addView(
                caja,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            caja.visibility = if (vistaControles == "audio") View.VISIBLE else View.GONE
        }
    }

    private fun mostrarVistaControles(vista: String) {
        val audio = vista == "audio" && !vertical
        if (esPantallaCelular()) {
            // En el celular no hay banda fija: el monitor abre su ventana desde el chip «Monitor».
            vistaControles = "imagen"
            zonaControles?.visibility = View.GONE
            b.cajaVideo.visibility = View.GONE
            btnVistaControles?.text = "Monitor"
            ponerBarraVisible(barraVisible)
            return
        }
        vistaControles = if (audio) "audio" else "imagen"
        if (!vertical) {
            // Los dos viven en la misma banda de altura fija: el visor no se mueve al cambiar.
            b.cajaVideo.visibility =
                if (!audio && !esPantallaCelular()) View.VISIBLE else View.GONE
            panelAudio?.visibility = if (audio) View.VISIBLE else View.GONE
        } else {
            b.cajaVideo.visibility = if (modoTcg) View.GONE else View.VISIBLE
            panelAudio?.visibility = View.VISIBLE
        }
        btnVistaControles?.text = if (audio) "Imagen" else "♪ Audio"
        uiHandler.removeCallbacks(refrescoEntrada)
        if (audio || vertical) {
            actualizarEntradaAudio()
            uiHandler.post(refrescoEntrada)
        }
    }

    /** Alto de la banda donde se alternan los ajustes de imagen y el monitor de audio. */
    private val ALTO_ZONA = 132

    /**
     * Banda de altura fija, debajo de la fila de controles, donde se alternan los ajustes de
     * imagen y el monitor de audio. Al no cambiar de alto, el visor del programa tampoco.
     */
    private var zonaControles: LinearLayout? = null

    private fun asegurarZonaControles(): LinearLayout {
        val zona = zonaControles ?: LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }.also { zonaControles = it }
        if (zona.layoutParams == null) {
            zona.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ALTO_ZONA.dp
            )
        }
        return zona
    }

    /** Mete la banda en el panel del programa, justo debajo de la fila de controles. */
    private fun montarZonaControles() {
        // En Modo Pokémon TCG la banda de controles no existe: le roba el alto al monitor.
        if (modoTcg) {
            zonaControles?.let { z ->
                (z.parent as? ViewGroup)?.removeView(z)
                z.visibility = View.GONE
            }
            return
        }
        val zona = asegurarZonaControles()
        if (zona.parent is ViewGroup) zona.requestLayout()
        val panel = b.panelPrograma
        if (zona.parent !== panel) {
            (zona.parent as? ViewGroup)?.removeView(zona)
            val indice = panel.indexOfChild(b.slotControles)
            panel.addView(
                zona, if (indice >= 0) indice + 1 else panel.childCount,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ALTO_ZONA.dp)
            )
        }
    }

    /**
     * Pico de entrada actual (0..1): del grabador en sesión; fuera de sesión, del micrófono que
     * abre el vúmetro. Así el monitor de onda también se mueve antes de grabar.
     */
    private fun picoActual(): Float = when {
        recorder.isRecording -> recorder.lastPeak
        transmitiendo -> emisor.nivel
        else -> audioVivo.pico
    }

    private fun moverControles() {
        val fila = b.controlesCapa
        (fila.parent as? ViewGroup)?.removeView(fila)
        val botones = listOf(
            b.btnLayerA, b.btnLayerB, b.btnPip, b.btnSplit, b.btnZoomOut, b.btnZoomIn,
            // El de la lente también se saca: en Modo Studio vive en la fila/columna de allá y
            // hay que soltarlo antes de volver a ponerlo en la fila de siempre.
            b.btnTabletLens
        )
        for (v in botones) (v.parent as? ViewGroup)?.removeView(v)

        // En el celular (también en vertical 9:16) los controles van en una fila que flota
        // sobre el visor; la columna de la izquierda es cosa de la tablet.
        if (vertical && !esPantallaCelular()) {
            b.slotIzquierdo.removeAllViews()
            // La cabecera («PROGRAMA» + 9:16 y resolución) se queda visible: ahora el visor
            // va dentro del cuerpo y no flota encima de la barra superior.
            b.txtEtiquetaPrograma.visibility = View.VISIBLE
            b.cabeceraPrograma.visibility = View.VISIBLE

            // Dos columnas: A|B, PiP|2 cam, −|+. Así la columna deja sitio al monitor.
            val rejilla = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            botones.forEachIndexed { indice, v ->
                if (indice % 2 == 0) {
                    rejilla.addView(LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = if (indice == 0) 0 else 4.dp }
                    })
                }
                recordarEstilo(v)
                v.layoutParams = LinearLayout.LayoutParams(0, 36.dp, 1f).apply {
                    if (indice % 2 == 1) marginStart = 4.dp
                }
                v.gravity = Gravity.CENTER
                v.textSize = 11f
                if (v.id == R.id.btnPip) {
                    // Sin icono: el texto queda perfectamente centrado
                    v.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
                }
                v.setPadding(3.dp, v.paddingTop, 3.dp, v.paddingBottom)
                (rejilla.getChildAt(rejilla.childCount - 1) as LinearLayout).addView(v)
            }
            b.slotIzquierdo.addView(
                rejilla,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        } else {
            // La cabecera del programa ya viene completa del XML: solo se muestra.
            b.txtEtiquetaPrograma.visibility = View.VISIBLE
            b.cabeceraPrograma.visibility = View.VISIBLE

            b.scrollControles.removeAllViews()
            fila.removeAllViews()
            fila.orientation = LinearLayout.HORIZONTAL
            // En el celular «Audio» e «Img.» viven en la barra superior: no entran aquí.
            if (!esPantallaCelular()) btnVistaControles?.let { fila.addView(it) }
            b.btnLayerA.layoutParams =
                LinearLayout.LayoutParams(40.dp, ViewGroup.LayoutParams.WRAP_CONTENT)
            b.btnLayerB.layoutParams =
                LinearLayout.LayoutParams(40.dp, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(6.dp, 0, 0, 0) }
            b.btnLayerA.gravity = Gravity.CENTER
            b.btnLayerB.gravity = Gravity.CENTER
            restaurarEstilo(b.btnLayerA)
            restaurarEstilo(b.btnLayerB)
            b.btnLayerA.setPadding(0, b.btnLayerA.paddingTop, 0, b.btnLayerA.paddingBottom)
            b.btnLayerB.setPadding(0, b.btnLayerB.paddingTop, 0, b.btnLayerB.paddingBottom)
            fila.addView(b.btnLayerA)
            fila.addView(b.btnLayerB)
            for (v in listOf(b.btnPip, b.btnSplit, b.btnZoomOut, b.btnZoomIn)) {
                restaurarEstilo(v)
                if (v.id == R.id.btnPip) {
                    v.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_pip, 0, 0, 0)
                    v.compoundDrawablePadding = 6.dp
                }
                v.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(6.dp, 0, 0, 0) }
                v.gravity = Gravity.CENTER
                fila.addView(v)
            }
            // El botón de la cámara de la tablet va al final de la fila.
            // OJO: la fila se vacía con removeAllViews() y sólo se reponen los controles de
            // arriba, así que si no se agrega aquí, quedaba fuera del árbol y "desaparecía".
            b.btnTabletLens.textSize = if (esPantallaCelular()) 9f else 12f
            b.btnTabletLens.gravity = Gravity.CENTER
            b.btnTabletLens.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = 6.dp }
            fila.addView(b.btnTabletLens)
            // En el celular la banda de ajustes de imagen no existe: «Img.» abre su ventana.
            asegurarChipImagen()
            compactarControlesCelular()
            // El medidor de audio ya no va aquí: vive en la barra superior, junto a Facebook.
            b.scrollControles.addView(
                fila,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        // El hueco de Luz/Contraste solo existe en horizontal: en vertical el interruptor se
        // oculta y el monitor vive siempre en la columna.
        btnVistaControles?.visibility = if (vertical) View.GONE else View.VISIBLE
        moverFilaVideo()
    }
    // ------------------------------------------------------------------ plantillas de escena

    private fun plantillaActual(nombre: String): Plantilla {
        val camaras = ArrayList<CamPlantilla>()
        b.canvas.scene.items.filterIsInstance<CamItem>().forEach { item ->
            camaras.add(
                CamPlantilla(
                    slot = item.slot,
                    capa = if (item.slot == camA) "A" else "B",
                    x = item.rect.left,
                    y = item.rect.top,
                    ancho = item.rect.width(),
                    alto = item.rect.height(),
                    visible = item.visible,
                    zoom = item.zoom,
                    panX = item.panX,
                    panY = item.panY
                )
            )
        }
        val imagenes = ArrayList<ImagenPlantilla>()
        b.canvas.scene.items.filterIsInstance<ImageItem>().forEach { item ->
            val uri = item.uri
            if (!uri.isNullOrBlank()) {
                imagenes.add(
                    ImagenPlantilla(
                        uri = uri,
                        nombre = item.label,
                        x = item.rect.left,
                        y = item.rect.top,
                        ancho = item.rect.width(),
                        alto = item.rect.height(),
                        visible = item.visible,
                        opacidad = item.opacity
                    )
                )
            }
        }
        // Los textos no tienen archivo: se guardan completos (contenido, letra, color, animación,
        // tamaño) y su lugar en el lienzo, para poder rehacerlos tal cual.
        val textosGuardados = ArrayList<TextoPlantilla>()
        b.canvas.scene.items.filterIsInstance<TextoItem>().forEach { item ->
            textosGuardados.add(
                TextoPlantilla(
                    texto = item.texto,
                    fuente = item.fuente,
                    color = item.color,
                    animacion = item.animacion,
                    tamano = item.tamano,
                    x = item.rect.left,
                    y = item.rect.top,
                    ancho = item.rect.width(),
                    alto = item.rect.height(),
                    visible = item.visible,
                    opacidad = item.opacity
                )
            )
        }
        val mediosGuardados = medios.map { MedioPlantilla(it.uri, it.video, it.nombre) }
        val ajustes = AjustesPlantilla(
            modo = modoSesion,
            recAlto = recAltoElegido,
            recKbps = recBitrateKbps,
            streamAlto = streamAlto,
            streamKbps = streamBitrateKbps,
            autoBitrate = bitrateAuto,
            audioMudo = recorder.silencio,
            audioDb = getSharedPreferences(PREFS, MODE_PRIVATE).getFloat(CLAVE_AUDIO_DB, 0f)
        )
        val transicion = TransicionPlantilla(b.canvas.transicion.id, b.canvas.transicionMs)
        return Plantilla(
            nombre, vertical, camaras, imagenes, ajustes, transicion, mediosGuardados,
            textosGuardados
        )
    }

    /** Aplica orientación de sesión, transición y audio guardados en la plantilla. */
    private fun aplicarAjustesDePlantilla(plantilla: Plantilla) {
        modoSesion = plantilla.ajustes.modo
        recAltoElegido = plantilla.ajustes.recAlto
        recBitrateKbps = plantilla.ajustes.recKbps
        streamAlto = plantilla.ajustes.streamAlto
        streamBitrateKbps = plantilla.ajustes.streamKbps
        bitrateAuto = plantilla.ajustes.autoBitrate
        grabarAlta = recAltoElegido >= 1080
        recorder.silencio = plantilla.ajustes.audioMudo
        val lineal = Math.pow(10.0, (plantilla.ajustes.audioDb / 20.0)).toFloat()
        recorder.ganancia = lineal
        CompositorView.Transicion.values()
            .firstOrNull { it.id == plantilla.transicion.id }
            ?.let {
                b.canvas.transicion = it
                b.canvas.transicionMs = plantilla.transicion.ms
            }
        guardarAjustes()
        actualizarInfoLienzo()
        actualizarBotonSesion()
    }

    // ------------------------------------------------------------------ gestor de plantillas

    /** Tarjeta con resumen y miniaturas de una plantilla, con acciones. */
    private fun tarjetaPlantilla(
        nombre: String,
        plantilla: Plantilla,
        alCambiar: () -> Unit,
        alCerrar: () -> Unit = {}
    ): View {
        val tarjeta = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_tile)
            setPadding(14.dp, 12.dp, 14.dp, 12.dp)
        }
        tarjeta.addView(TextView(this).apply {
            text = nombre
            textSize = 15f
            setTextColor(ContextCompat.getColor(this@StudioActivity, R.color.text_primary))
        })

        val modo = when (plantilla.ajustes.modo) {
            "grabar" -> "Grabar"
            "transmitir" -> "Transmitir"
            else -> "Grabar + transmitir"
        }
        val trans = CompositorView.Transicion.values().firstOrNull { it.id == plantilla.transicion.id }
        val overlays = plantilla.imagenes.size
        val textos = plantilla.textos.size
        val ocultos = plantilla.imagenes.count { !it.visible } + plantilla.textos.count { !it.visible }
        val resumen = buildString {
            append(if (plantilla.vertical) "9:16 vertical" else "16:9 horizontal")
            append(" · ")
            append(modo)
            append(" · grabación ")
            append(plantilla.ajustes.recAlto)
            append("p")
            if (plantilla.ajustes.modo != "grabar") {
                append(" · emisión ")
                append(plantilla.ajustes.streamAlto)
                append("p")
            }
            append(" · transición ")
            append(trans?.label ?: plantilla.transicion.id)
            append(" (")
            append(plantilla.transicion.ms)
            append(" ms)")
            append(" · ")
            append(overlays)
            append(if (overlays == 1) " overlay" else " overlays")
            if (textos > 0) {
                append(" + ")
                append(textos)
                append(if (textos == 1) " texto" else " textos")
            }
            if (ocultos > 0) {
                append(" (")
                append(ocultos)
                append(" en oculto)")
            }
            append(" · ")
            append(plantilla.medios.size)
            append(if (plantilla.medios.size == 1) " medio" else " medios")
        }
        tarjeta.addView(TextView(this).apply {
            text = resumen
            textSize = 11f
            setTextColor(ContextCompat.getColor(this@StudioActivity, R.color.text_secondary))
            setPadding(0, 4.dp, 0, 8.dp)
        })

        // Miniaturas de overlays y medios
        val filaMini = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        var pintadas = 0
        (plantilla.imagenes.map { Triple(it.uri, false, it.nombre) } +
            plantilla.medios.map { Triple(it.uri, it.video, it.nombre) })
            .take(6)
            .forEach { (uri, video, etiqueta) ->
                val mini = android.widget.ImageView(this).apply {
                    scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    setBackgroundResource(R.drawable.bg_tile)
                    setImageBitmap(miniaturaDe(uri, video))
                }
                filaMini.addView(
                    mini,
                    LinearLayout.LayoutParams(72.dp, 46.dp).apply { marginEnd = 6.dp }
                )
                pintadas++
            }
        if (pintadas > 0) tarjeta.addView(filaMini)

        // Acciones
        val filaAcciones = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 10.dp, 0, 0)
        }
        fun accion(texto: String, alTocar: () -> Unit): TextView =
            TextView(this).apply {
                this.text = texto
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(this@StudioActivity, R.color.text_primary))
                setBackgroundResource(R.drawable.bg_pill_ghost)
                setPadding(14.dp, 7.dp, 14.dp, 7.dp)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = 6.dp }
                setOnClickListener { alTocar() }
            }

        filaAcciones.addView(accion("Abrir") {
            // Se cierra el gestor para que se vea el programa con la plantilla ya puesta.
            alCerrar()
            uiHandler.postDelayed({ cargarPlantilla(nombre) }, 200)
        })
        filaAcciones.addView(accion("Duplicar") {
            val copia = Plantillas.duplicar(this, nombre)
            toast(if (copia != null) "Duplicada como «$copia»" else "No se pudo duplicar")
            alCambiar()
        })
        filaAcciones.addView(accion("Renombrar") {
            dialogoRenombrar(nombre, alCambiar)
        })
        filaAcciones.addView(accion("Borrar") {
            AlertDialog.Builder(this)
                .setMessage("¿Borrar la plantilla «$nombre»?")
                .setPositiveButton("Borrar") { _, _ ->
                    Plantillas.borrar(this, nombre)
                    toast("Plantilla borrada")
                    alCambiar()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        })
        tarjeta.addView(filaAcciones)
        return tarjeta
    }

    private fun miniaturaDe(uri: String, video: Boolean): Bitmap? = try {
        if (video) {
            val r = android.media.MediaMetadataRetriever()
            r.setDataSource(this, Uri.parse(uri))
            val marco = r.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            r.release()
            marco
        } else {
            loadScaledBitmap(Uri.parse(uri))
        }
    } catch (t: Throwable) {
        null
    }

    private fun dialogoRenombrar(nombre: String, alTerminar: () -> Unit) {
        val campo = EditText(this).apply {
            setText(nombre)
            setSelection(text.length)
            setPadding(30.dp, 20.dp, 30.dp, 20.dp)
        }
        AlertDialog.Builder(this)
            .setTitle("Renombrar plantilla")
            .setView(campo)
            .setPositiveButton("Guardar") { _, _ ->
                val nuevo = campo.text.toString().trim()
                if (nuevo.isEmpty()) {
                    toast("Escribe un nombre")
                    return@setPositiveButton
                }
                if (Plantillas.renombrar(this, nombre, nuevo)) {
                    toast("Plantilla renombrada")
                    alTerminar()
                } else {
                    toast("No se pudo renombrar")
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Gestor de plantillas con resumen, miniaturas y acciones. */
    private fun dialogoPlantillas() {
        val contenedor = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(contenedor) }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Plantillas de escena")
            .setView(scroll)
            .setNegativeButton("Cerrar", null)
            .create()

        fun refrescar() {
            contenedor.removeAllViews()
            val nombres = Plantillas.listar(this)
            if (nombres.isEmpty()) {
                contenedor.addView(TextView(this).apply {
                    text = "Todavía no hay plantillas. Guarda la escena actual abajo."
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(this@StudioActivity, R.color.text_secondary))
                    setPadding(16.dp, 16.dp, 16.dp, 16.dp)
                })
            }
            nombres.forEach { nombre ->
                val plantilla = Plantillas.cargar(this, nombre) ?: return@forEach
                val tarjeta = tarjetaPlantilla(
                    nombre,
                    plantilla,
                    alCambiar = { refrescar() },
                    alCerrar = { dialog.dismiss() }
                )
                contenedor.addView(
                    tarjeta,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(10.dp, 6.dp, 10.dp, 6.dp) }
                )
            }
            contenedor.addView(TextView(this).apply {
                text = "+ Guardar la escena actual como plantilla"
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(this@StudioActivity, R.color.bg_root))
                setBackgroundResource(R.drawable.bg_pill_accent)
                setPadding(16.dp, 12.dp, 16.dp, 12.dp)
                setOnClickListener {
                    // Se cierra el gestor ANTES de abrir el cuadro de guardar: si se abre el
                    // cuadro y enseguida el gestor, el gestor queda encima y lo tapa.
                    dialog.dismiss()
                    uiHandler.postDelayed({ dialogoGuardarPlantilla() }, 250)
                }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(10.dp, 10.dp, 10.dp, 10.dp) })
        }

        refrescar()
        dialog.show()
        cuadroALaIzquierda(dialog)
    }

    private fun dialogoGuardarPlantilla() {
        val entrada = EditText(this).apply {
            hint = "Nombre de la plantilla"
            setText(if (vertical) "Vertical" else "Horizontal")
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Guardar plantilla de escena")
            .setMessage(
                "Se guarda todo lo de la escena actual: orientación del lienzo, resolución de " +
                    "grabación y de emisión, la transición elegida, qué cámara va en A y B, la " +
                    "posición y el tamaño de cada overlay (también los que están en oculto) y los " +
                    "textos con su letra, color y animación."
            )
            .setView(entrada)
            .setPositiveButton("Guardar", null)
            .setNegativeButton("Cancelar", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val nombre = entrada.text?.toString()?.trim().orEmpty()
                if (nombre.isEmpty()) {
                    toast("Escribe un nombre para la plantilla")
                    return@setOnClickListener
                }
                if (Plantillas.guardar(this, plantillaActual(nombre))) {
                    toast("Plantilla «$nombre» guardada")
                    dialog.dismiss()
                    // Se vuelve al gestor para ver la plantilla recién guardada.
                    uiHandler.postDelayed({ dialogoPlantillas() }, 350)
                } else {
                    toast("No se pudo guardar la plantilla")
                }
            }
        }
        dialog.show()
        cuadroALaIzquierda(dialog)
    }

    private fun dialogoAbrirPlantilla() {
        val nombres = Plantillas.listar(this)
        if (nombres.isEmpty()) {
            toast("Todavía no hay plantillas guardadas")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Abrir plantilla")
            .setItems(nombres.toTypedArray()) { _, which -> cargarPlantilla(nombres[which]) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun dialogoBorrarPlantilla() {
        val nombres = Plantillas.listar(this)
        if (nombres.isEmpty()) {
            toast("Todavía no hay plantillas guardadas")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Borrar plantilla")
            .setItems(nombres.toTypedArray()) { _, which ->
                val nombre = nombres[which]
                if (Plantillas.borrar(this, nombre)) toast("Plantilla «$nombre» eliminada")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun cargarPlantilla(nombre: String) {
        val plantilla = Plantillas.cargar(this, nombre) ?: run {
            toast("No se pudo leer la plantilla")
            return
        }

        if (plantilla.vertical != vertical) {
            vertical = plantilla.vertical
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(CLAVE_VERTICAL, vertical).apply()
            aplicarOrientacion()
        }

        // Limpiar escena actual
        for (slot in 0 until 6) {
            soltar(slot, hub.session(slot)?.videoTrack)
        }
        b.canvas.scene.items.toList().forEach { b.canvas.scene.remove(it) }
        camA = null
        camB = null

        plantilla.camaras.forEach { c ->
            val item = b.canvas.scene.addCam(c.slot) ?: return@forEach
            item.rect.set(c.x, c.y, c.x + c.ancho, c.y + c.alto)
            item.visible = c.visible
            item.zoom = c.zoom
            item.panX = c.panX
            item.panY = c.panY
            if (c.capa == "A") camA = c.slot else camB = c.slot
            if (c.slot == LocalCam.SLOT) {
                localCam.track?.let { adjuntar(c.slot, it) }
            } else {
                hub.session(c.slot)?.videoTrack?.let { adjuntar(c.slot, it) }
            }
        }

        var imagenesFallidas = 0
        plantilla.imagenes.forEach { i ->
            try {
                val bitmap = loadScaledBitmap(Uri.parse(i.uri))
                if (bitmap == null) {
                    imagenesFallidas++
                    return@forEach
                }
                val rect = RectF(i.x, i.y, i.x + i.ancho, i.y + i.alto)
                val item = b.canvas.scene.addImage(bitmap, i.nombre, i.uri, rect)
                item.visible = i.visible
                item.opacity = i.opacidad
            } catch (t: Throwable) {
                imagenesFallidas++
            }
        }

        // Textos: se rehacen con su letra, color y animación, en el lugar donde estaban.
        // Los que se guardaron ocultos vuelven ocultos, pero conservan su lugar para cuando
        // se activen desde su tarjeta.
        var textosFallidos = 0
        plantilla.textos.forEach { t ->
            try {
                val mapa = Textos.rasterizar(
                    TextoGuardado(
                        id = "",
                        texto = t.texto,
                        fuente = t.fuente,
                        color = t.color,
                        animacion = t.animacion,
                        tamano = t.tamano
                    )
                )
                val item = TextoItem(
                    b.canvas.scene.nextId(), t.texto, t.fuente, t.animacion, null, mapa,
                    t.color, t.tamano
                )
                item.rect.set(t.x, t.y, t.x + t.ancho, t.y + t.alto)
                item.visible = t.visible
                item.opacity = t.opacidad
                // Sin animación de entrada al abrir la plantilla: el texto ya está puesto.
                b.canvas.scene.agregar(item)
            } catch (th: Throwable) {
                textosFallidos++
                L.e("plantillas", "texto de la plantilla: ${th.message}", th)
            }
        }

        // Ajustes, transición y medios que viajan con la plantilla
        aplicarAjustesDePlantilla(plantilla)
        medios.clear()
        plantilla.medios.forEach { m ->
            val medio = Medio(m.uri, m.video, m.nombre)
            medio.thumb = cargarMiniatura(medio)
            medios.add(medio)
        }
        refreshMediaTiles()
        // Las tarjetas de los overlays se rehacen aquí: si no, la sección OVERLAYS se quedaba
        // como estaba y los overlays ocultos de la plantilla no aparecían para activarlos.
        refreshOverlayTiles()

        b.canvas.scene.selectedId = null
        b.canvas.requestRender()
        refreshUi()
        onSelectionChanged(null)
        val ocultos = plantilla.imagenes.count { !it.visible } + plantilla.textos.count { !it.visible }
        when {
            imagenesFallidas > 0 || textosFallidos > 0 -> {
                val fallas = imagenesFallidas + textosFallidos
                toast("Plantilla «$nombre» cargada ($fallas no se pudieron leer)")
            }

            ocultos > 0 -> toast("Plantilla «$nombre» cargada · $ocultos en oculto, listo para activar")
            else -> toast("Plantilla «$nombre» cargada")
        }
    }
    private fun showPairing() {
        val db = DialogPairingBinding.inflate(layoutInflater)
        pairingBinding = db
        db.txtIp.text = Net.primaryIp() ?: "Sin WiFi"
        // QR con el enlace de emparejamiento: el celular lo escanea y no teclea nada.
        // Lleva IP y puerto, así que sigue sirviendo aunque el servidor no consiga el 8787.
        runCatching {
            val ip = Net.primaryIp()
            val enlace = ip?.let { com.jfrmedia.switcher.core.net.QrLink.armar(it, server.port) }
            db.imgQr.setImageBitmap(enlace?.let { com.jfrmedia.switcher.studio.ui.QrCode.bitmap(it, 480) })
            db.imgQr.visibility = if (enlace == null) View.GONE else View.VISIBLE
            if (enlace != null) L.i("emparejamiento", "QR: $enlace")
        }
        // Sin la coletilla «misma red WiFi»: ya lo dice la instrucción de arriba y era repetido.
        db.txtPort.text = if (server.port == Proto.DEFAULT_PORT) {
            "Puerto ${server.port}"
        } else {
            "⚠ Puerto ${server.port}: el 8787 estaba ocupado. Escanea el QR tal cual (ya trae el puerto)."
        }
        db.txtConnCount.text = "Cámaras conectadas: ${hub.count()} / 6"
        // En vertical (9:16) la pantalla es angosta: el QR arriba y los datos debajo, en UNA
        // columna; si no, la IP se cortaba y el QR se comía el ancho (Jorge, 29/10).
        if (esPantallaCelular() && vertical) {
            val fila = (db.imgQr.parent as? ViewGroup)?.parent as? LinearLayout
            fila?.let { f ->
                f.orientation = LinearLayout.VERTICAL
                for (i in 0 until f.childCount) {
                    f.getChildAt(i).layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
                (db.imgQr.parent as? LinearLayout)?.gravity = Gravity.CENTER_HORIZONTAL
                (db.imgQr.layoutParams as? LinearLayout.LayoutParams)?.let {
                    it.width = 150.dp
                    it.height = 150.dp
                    db.imgQr.layoutParams = it
                }
            }
        }
        db.btnCopyIp.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("IP", db.txtIp.text))
            toast("IP copiada")
        }
        val dialog = AlertDialog.Builder(this)
            .setView(db.root)
            .create()
        dialog.setOnDismissListener { pairingBinding = null }
        db.btnClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
        cuadroALaIzquierda(dialog)
        // Ancho justo para que la instrucción quepa en una línea (Jorge, 29/10: «la ventana con el
        // ancho hasta donde termina la palabra código»), con tope para pantallas chicas.
        dialog.window?.let { ventana ->
            val ancho = resources.displayMetrics.widthPixels
            val anchoFijo = if (esPantallaCelular() && vertical) {
                // En vertical la ventana se limita al ancho del QR: el texto se acomoda solo y no
                // se estira por una línea larga (Jorge, 29/10).
                minOf(210.dp, (ancho * 0.92f).toInt())
            } else {
                minOf(360.dp, (ancho * 0.75f).toInt())
            }
            // Alto al contenido: la ventana termina donde termina el botón «Listo».
            ventana.setLayout(anchoFijo, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun showSettings() {
        val db = DialogSettingsBinding.inflate(layoutInflater)
        db.rgModoTrabajo.check(if (modoTcg) R.id.rbModoPokemon else R.id.rbModoStudio)
        // Diseño del banner del torneo (solo aplica al Modo Pokémon TCG).
        val radioBanner = when (estadoTcg.paleta) {
            2 -> R.id.rbBanner2
            3 -> R.id.rbBanner3
            4 -> R.id.rbBanner4
            5 -> R.id.rbBanner5
            else -> R.id.rbBanner1
        }
        db.rgBanner.check(radioBanner)
        when (preset.label) {
            VideoPresets.BAJA.label -> db.rb360.isChecked = true
            VideoPresets.ALTA.label -> db.rb720.isChecked = true
            else -> db.rb540.isChecked = true
        }
        when (modoSesion) {
            "grabar" -> db.rbSoloGrabar.isChecked = true
            "transmitir" -> db.rbSoloTransmitir.isChecked = true
            else -> db.rbAmbos.isChecked = true
        }
        when (recAltoElegido) {
            360 -> db.rbRec360.isChecked = true
            480 -> db.rbRec480.isChecked = true
            1080 -> db.rbRec1080.isChecked = true
            1440 -> db.rbRec2k.isChecked = true
            2160 -> db.rbRec4k.isChecked = true
            else -> db.rbRec720.isChecked = true
        }
        fun ladoCortoGrabacion(): Int = when {
            db.rbRec360.isChecked -> 360
            db.rbRec480.isChecked -> 480
            db.rbRec1080.isChecked -> 1080
            db.rbRec2k.isChecked -> 1440
            db.rbRec4k.isChecked -> 2160
            else -> 720
        }

        fun ladoCortoEmision(): Int = when {
            db.rbStream480.isChecked -> 480
            db.rbStream1080.isChecked -> 1080
            else -> 720
        }

        // Con el automático, el bitrate sale de la resolución elegida; si no, del deslizable.
        fun refrescarBitrates() {
            val auto = db.swAutoBitrate.isChecked
            if (auto) {
                db.txtBitrateRec.text =
                    "Grabación: ${bitrateGrabacionAuto(ladoCortoGrabacion()) / 1000} Mbps (automático)"
                db.txtBitrateStream.text =
                    "Bitrate de transmisión: ${bitrateEmisionAuto(ladoCortoEmision()) / 1000} Mbps (automático)"
            } else {
                db.txtBitrateRec.text = "Grabación: ${(db.sbBitrateRec.progress + 2000) / 1000} Mbps"
                db.txtBitrateStream.text =
                    "Bitrate de transmisión: ${(db.sbBitrateStream.progress + 1000) / 1000} Mbps"
            }
            db.sbBitrateRec.isEnabled = !auto
            db.sbBitrateRec.alpha = if (auto) 0.4f else 1f
            db.sbBitrateStream.isEnabled = !auto
            db.sbBitrateStream.alpha = if (auto) 0.4f else 1f
        }

        db.sbBitrateRec.progress = (recBitrateKbps - 2000).coerceIn(0, 38000)
        db.sbBitrateRec.setOnSeekBarChangeListener(
            object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    sb: android.widget.SeekBar?,
                    value: Int,
                    fromUser: Boolean
                ) {
                    if (!db.swAutoBitrate.isChecked) {
                        db.txtBitrateRec.text = "Grabación: ${(value + 2000) / 1000} Mbps"
                    }
                }

                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
            }
        )
        when (streamAlto) {
            480 -> db.rbStream480.isChecked = true
            1080 -> db.rbStream1080.isChecked = true
            else -> db.rbStream720.isChecked = true
        }
        db.sbBitrateStream.progress = (streamBitrateKbps - 1000).coerceIn(0, 11000)
        db.txtBitrateStream.text = "Bitrate de transmisión: ${streamBitrateKbps / 1000} Mbps"
        db.sbBitrateStream.setOnSeekBarChangeListener(
            object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    sb: android.widget.SeekBar?,
                    value: Int,
                    fromUser: Boolean
                ) {
                    if (!db.swAutoBitrate.isChecked) {
                        db.txtBitrateStream.text =
                            "Bitrate de transmisión: ${(value + 1000) / 1000} Mbps"
                    }
                }

                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
            }
        )
        db.swAutoBitrate.isChecked = bitrateAuto
        // Al cambiar el modo automático o la resolución, la etiqueta muestra lo que se usará.
        db.swAutoBitrate.setOnCheckedChangeListener { _, _ -> refrescarBitrates() }
        db.rgRes.setOnCheckedChangeListener { _, _ -> refrescarBitrates() }
        db.rgStreamRes.setOnCheckedChangeListener { _, _ -> refrescarBitrates() }
        refrescarBitrates()
        db.swAudio.isChecked = recAudio
        db.txtFolder.text = "Los videos van a Movies/JFR Switcher · fotos a Pictures/JFR Switcher"
        // Versión instalada y búsqueda manual de actualización.
        db.txtVersion.text = "Versión ${versionInstalada()} · toca para buscar actualización"
        db.txtVersion.setOnClickListener { buscarActualizacion(manual = true) }

        val dialog = AlertDialog.Builder(this).setView(db.root).create()
        db.btnClose.setOnClickListener {
            // Cambio de modo de trabajo: se guarda y se vuelve a armar la pantalla.
            val pedidoTcg = db.rbModoPokemon.isChecked
            if (pedidoTcg != modoTcg) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean(CLAVE_MODO_TCG, pedidoTcg).apply()
                // El Modo Pokémon fija la pantalla en horizontal; al salir de él hay que volver a
                // permitir el giro, o la tablet se queda trabada y no entra al Modo Studio.
                requestedOrientation = if (pedidoTcg) {
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                } else {
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER
                }
                dialog.dismiss()
                toast(if (pedidoTcg) "Modo Pokémon TCG" else "Modo Studio")
                recreate()
                return@setOnClickListener
            }
            if (modoTcg) {
                val nuevaPaleta = when {
                    db.rbBanner2.isChecked -> 2
                    db.rbBanner3.isChecked -> 3
                    db.rbBanner4.isChecked -> 4
                    db.rbBanner5.isChecked -> 5
                    else -> 1
                }
                if (nuevaPaleta != estadoTcg.paleta) {
                    cambiarEstadoTcg(estadoTcg.copy(paleta = nuevaPaleta))
                }
            }
            // Elegir una calidad fija desactiva el modo automático.
            calidadAuto = false
            preset = when {
                db.rb360.isChecked -> VideoPresets.BAJA
                db.rb720.isChecked -> VideoPresets.ALTA
                else -> VideoPresets.MEDIA
            }
            modoSesion = when {
                db.rbSoloGrabar.isChecked -> "grabar"
                db.rbSoloTransmitir.isChecked -> "transmitir"
                else -> "ambos"
            }
            recAltoElegido = when {
                db.rbRec360.isChecked -> 360
                db.rbRec480.isChecked -> 480
                db.rbRec1080.isChecked -> 1080
                db.rbRec2k.isChecked -> 1440
                db.rbRec4k.isChecked -> 2160
                else -> 720
            }
            recBitrateKbps = db.sbBitrateRec.progress + 2000
            streamAlto = when {
                db.rbStream480.isChecked -> 480
                db.rbStream1080.isChecked -> 1080
                else -> 720
            }
            streamBitrateKbps = db.sbBitrateStream.progress + 1000
            bitrateAuto = db.swAutoBitrate.isChecked
            recAudio = db.swAudio.isChecked
            grabarAlta = recAltoElegido >= 1080
            guardarAjustes()
            actualizarInfoLienzo()
            actualizarBotonSesion()
            applyQualityToCameras()
            val (w, h) = tamanoGrabacion(recAltoElegido)
            val kbpsGrabacion = if (bitrateAuto) {
                bitrateGrabacionAuto(recAltoElegido)
            } else {
                recBitrateKbps
            }
            val kbpsEnvio = if (bitrateAuto) bitrateEmisionAuto(streamAlto) else streamBitrateKbps
            toast(
                when (modoSesion) {
                    "grabar" -> "Solo grabar · ${recAltoElegido}p ($w×$h) · ${kbpsGrabacion / 1000} Mbps"
                    "transmitir" -> "Solo transmitir · ${streamAlto}p · ${kbpsEnvio / 1000} Mbps"
                    else -> "Grabar + transmitir · ${recAltoElegido}p · ${kbpsGrabacion / 1000} Mbps"
                }
            )
            dialog.dismiss()
        }
        dialog.show()
        cuadroALaIzquierda(dialog)
    }

    /** Guarda los ajustes para que se recuerden al abrir la app. */
    private fun guardarAjustes() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(CLAVE_MODO, modoSesion)
            .putInt(CLAVE_REC_ALTO, recAltoElegido)
            .putInt(CLAVE_REC_KBPS, recBitrateKbps)
            .putInt(CLAVE_STREAM_ALTO, streamAlto)
            .putInt(CLAVE_STREAM_KBPS, streamBitrateKbps)
            .putBoolean(CLAVE_AUTO_KBPS, bitrateAuto)
            .putBoolean(CLAVE_REC_AUDIO, recAudio)
            .putString(CLAVE_PRESET, preset.label)
            .apply()
    }

    /** El botón principal cambia según lo que se haya elegido hacer. */
    private fun actualizarBotonSesion() {
        // Al empezar o terminar una sesión el micrófono del vúmetro se suelta o se retoma solo.
        ajustarAudioVivo()
        // Y si la tablet se giró durante la sesión, el diseño se ajusta justo al terminar.
        evaluarModoStudio()
        // Con el directo en el aire el botón debe poder cortarlo, aunque el texto del modo
        // sea «Transmitir» (antes se quedaba igual y parecía que no había nada corriendo).
        if (recorder.isRecording || transmitiendo) {
            b.btnRecord.text = "Detener"
            return
        }
        b.btnRecord.text = when (modoSesion) {
            "grabar" -> "Grabar"
            "transmitir" -> "Transmitir"
            else -> "Rec + Broadcast"
        }
    }

    /**
     * Calidad efectiva. En modo automático se pide 720p mientras haya pocas cámaras
     * (más detalle en el visor) y 480p cuando hay muchas (para que la tablet no sufra).
     */
    private fun presetEfectivo(): VideoPreset {
        if (!calidadAuto) return preset
        val cuantas = hub.all().count { it.videoTrack != null }
        return if (cuantas <= 2) VideoPresets.ALTA else VideoPresets.MEDIA
    }

    private fun applyQualityToCameras() {
        val calidad = presetEfectivo()
        hub.all().forEach { session ->
            session.preset = calidad
            session.sendCmd(
                Msg.builder(Proto.CMD)
                    .put(Proto.K_ACTION, Proto.A_QUALITY)
                    .put(Proto.K_WIDTH, calidad.width)
                    .put(Proto.K_HEIGHT, calidad.height)
                    .put(Proto.K_FPS, calidad.fps)
                    .put(Proto.K_KBPS, calidad.kbps)
                    .build()
            )
        }
    }

    // ------------------------------------------------------------------ listener del lienzo

    override fun onSelectionChanged(item: CanvasItem?) {
        val has = item != null
        refrescarPanelAjuste()
        // En Modo Pokémon TCG no hay ajustes de imagen: si se muestran, le roban alto al monitor.
        if (modoTcg) {
            b.cajaVideo.visibility = View.GONE
            b.panelAjuste.visibility = View.GONE
        }
        // Mantiene sincronizadas las miniaturas (seleccionado / oculto) con el lienzo.
        refreshOverlayTiles()
    }

    /** El texto de ayuda de la barra inferior ya no existe. */
    private fun updateTip() {
        // sin barra inferior: los avisos van por toast
    }

    override fun onSceneChanged() {
        // En Modo TCG la escena es fija: si una cámara se coloca sola, se le devuelve su sitio.
        if (!modoTcg || aplicandoRectTcg) return
        aplicarRectangulosTcg()
    }

    /** Botón de la esquina de la tarjeta 4 (para poder cambiarle el texto sin crear otro). */
    private var botonCamara4: android.widget.TextView? = null

    /** Iconos grandes del mazo debajo de la cámara de cada jugador (vista de mesa). */
    private var iconosMazoUno: List<ImageItem> = emptyList()
    private var iconosMazoDos: List<ImageItem> = emptyList()

    /**
     * Botón de la cámara 4 (tarjeta de ambiente). Ciclo de tres pasos, igual que el botón de la
     * lente en Modo Studio: `Tablet → frontal`, `frontal → trasera`, `trasera → apagada`.
     *
     * Apagada, la cámara 4 vuelve a ser la del **cuarto celular** si hay uno conectado: así se usa
     * la cámara de la tablet cuando conviene, o se apaga y se usa un celular extra (Jorge, 21/11).
     */
    private fun cicloCamaraAmbiente() {
        when {
            !localCam.encendida -> {
                encenderCamaraTablet()
                if (localCam.track == null) return   // sin permiso o la tablet no la dio
                // Se ve a pantalla completa («Cámara general»): la escena del modo no la mete como
                // ventanita. La parrilla se rehace para que la tarjeta 4 la muestre con su nombre.
                if (vistaTcg == VISTA_MESA) mostrarCamaraTcg(LocalCam.SLOT)
                runCatching { buildSourcesGrid() }
                toast("Cámara 4: cámara de la tablet (" + if (localCam.frontal) "frontal" else "trasera" + ")")
            }

            localCam.frontal -> {
                if (localCam.cambiarCamara(false)) {
                    localCam.track?.let { t ->
                        runCatching { tiles.getOrNull(3)?.bindLocal(t, "Tablet · trasera") }
                    }
                    actualizarBotonCamara4()
                    toast("Cámara 4: tablet trasera")
                } else {
                    toast("Esta tablet no tiene cámara trasera")
                }
            }

            else -> {
                // Apagada: la cámara 4 vuelve a ser la del cuarto celular, si hay uno conectado.
                apagarCamaraTablet()
                runCatching { buildSourcesGrid() }
                toast(
                    if (hub.session(3) != null) "Cámara 4: ahora la del celular conectado"
                    else "Cámara 4 apagada · conecta un cuarto celular o toca «Tablet» para volver"
                )
            }
        }
        actualizarBotonCamara4()
    }

    /** Pone el texto que toca al botón de la cámara 4 (no crea otro botón). */
    private fun actualizarBotonCamara4() {
        runCatching { botonCamara4?.text = etiquetaBotonCamara4() }
    }

    /** Texto corto del botón de la cámara 4 (tarjeta de ambiente). */
    private fun etiquetaBotonCamara4(): String = when {
        !localCam.encendida -> "Tablet"
        localCam.frontal -> "Frontal"
        else -> "Trasera"
    }

    /**
     * Ancho del banner del modo (fracción del lienzo): **58.75 %**, centrado arriba.
     *
     * Con la vista de mesa nueva las columnas de los jugadores se llevan ~19 % a cada lado, así que
     * el hueco del centro es ~62 %: el banner entra con un 1.6 % de aire por lado. Si se ensancharan
     * las columnas, el banner es lo primero que deja de caber (Jorge, 23/11/2026).
     */
    private fun anchoBannerTcg(): Float = 0.5875f

    /**
     * Alto de la franja del banner: se dibuja a 1600×360, así que su alto sale de su ancho
     * (≈23.5 % del lienzo).
     */
    private fun altoTiraTcg(): Float = anchoBannerTcg() * (16f / 9f) / (1600f / 360f)


    /**
     * Coloca el banner del modo: **arriba** con la vista de mesa, **abajo** cuando hay un jugador a
     * pantalla completa y oculto con el ambiente. Siempre queda encima de las cámaras.
     */
    private fun acomodarBannerTcg() {
        val escena = b.canvas.scene
        val banner = bannerTcg ?: return
        val alto = altoTiraTcg()
        val x1 = (1f - anchoBannerTcg()) / 2f
        val x2 = x1 + anchoBannerTcg()
        when {
            vistaTcg == VISTA_MESA -> {
                banner.visible = true
                banner.rect.set(x1, 0.02f, x2, 0.02f + alto)
            }

            vistaTcg == 0 || vistaTcg == 1 -> {
                banner.visible = true
                banner.rect.set(x1, 0.98f - alto, x2, 0.98f)
            }

            else -> banner.visible = false
        }
        if (banner.visible) {
            escena.items.remove(banner)
            escena.items.add(banner)
        }
        runCatching { b.canvas.requestRender() }
    }

    /** Marco de las cámaras: color del banner para los jugadores y gris claro para la mesa. */
    private fun actualizarMarcosTcg() {
        val enMesa = vistaTcg == VISTA_MESA
        val colorJugador = if (enMesa) Banners.paleta(estadoTcg.paleta).franjaTexto else 0
        // La mesa lleva su propio marco claro: así se ve exactamente dónde termina el video del
        // área de juego y no se confunde con el fondo (Jorge, 23/11/2026).
        val colorMesa = if (enMesa) 0xFFE8ECF2.toInt() else 0
        b.canvas.scene.camAt(0)?.marcoColor = colorJugador
        b.canvas.scene.camAt(1)?.marcoColor = colorJugador
        b.canvas.scene.camAt(2)?.marcoColor = colorMesa
        runCatching { b.canvas.requestRender() }
    }

    /**
     * Iconos del mazo **dentro del área de cámara de cada jugador, ocupando la parte de abajo**,
     * solo en la vista de mesa (Jorge, 23/11/2026: antes se montaban un 30 % sobre la cámara).
     *
     * Van centrados en la columna del jugador y pegados al pie, sin texto: así se ve de un golpe
     * qué mazo juega cada quien sin tapar la cara.
     */
    private fun actualizarIconosMazo() {
        val escena = b.canvas.scene
        (iconosMazoUno + iconosMazoDos).forEach { runCatching { escena.remove(it) } }
        iconosMazoUno = emptyList()
        iconosMazoDos = emptyList()
        if (vistaTcg != VISTA_MESA) {
            runCatching { b.canvas.requestRender() }
            return
        }
        val anchoCam = anchoJugadorTcg()
        val entre = anchoCam * 0.05f
        listOf(estadoTcg.uno to true, estadoTcg.dos to false).forEach { par ->
            val jugador = par.first
            val derecha = par.first === estadoTcg.uno
            val dibujos = com.jfrmedia.switcher.studio.tcg.Mazos.iconos(this, jugador.mazoId)
                .filter { !it.isRecycled }.take(2)
            if (dibujos.isEmpty()) return@forEach
            // Los iconos del mazo van **arriba del chip de Energy**, del mismo tamaño siempre (uno o
            // dos, no se achican), centrados sobre la pila de chips (Jorge, 24/11/2026).
            val anchoIcono = anchoCam * 0.33f
            val altoIcono = anchoIcono * (16f / 9f)
            val altoPila = 3f * altoChipTcg() + 2f * (altoChipTcg() * 0.24f)
            val techoPila = 0.975f - altoPila                 // justo encima del chip Energy
            val abajo = techoPila - altoChipTcg() * 0.10f
            val arriba = abajo - altoIcono
            val anchoTotal = anchoIcono * dibujos.size + entre * (dibujos.size - 1)
            // Centro de la pila de chips (que va asomada hacia afuera de la columna).
            val centroChips = if (derecha) anchoChipTcg() / 2f - sangradoChipTcg()
                else 1f - anchoChipTcg() / 2f + sangradoChipTcg()
            var x = centroChips - anchoTotal / 2f
            // Que no se salgan de la columna (con dos iconos, centrados sobre los chips se salían y
            // el borde del monitor los recortaba).
            x = if (derecha) {
                x.coerceIn(0.004f, anchoCam - 0.004f - anchoTotal)
            } else {
                x.coerceIn(1f - anchoCam + 0.004f, 1f - 0.004f - anchoTotal)
            }
            val items = ArrayList<ImageItem>()
            dibujos.forEach { bmp ->
                val item = ImageItem(escena.nextId(), bmp, "Mazo " + jugador.nombre, null)
                item.rect.set(x, arriba, x + anchoIcono, abajo)
                escena.agregar(item)
                items.add(item)
                x += anchoIcono + entre
            }
            if (jugador === estadoTcg.uno) iconosMazoUno = items else iconosMazoDos = items
        }
        L.i("mazos", "iconos en la mesa: uno=${iconosMazoUno.size} dos=${iconosMazoDos.size}")
        runCatching { b.canvas.requestRender() }
    }

    /**
     * Ancho de la cámara de cada jugador en la vista de mesa: **la columna entera, de arriba a abajo
     * del monitor**, mostrando solo el **60 % central** del cuadro (se recorta 20 % de cada lado).
     *
     * El celular manda el cuadro **vertical 9:16**; para que ese 60 % central llene la columna sin
     * deformar ni recortar arriba y abajo, el ancho del lienzo tiene que ser exactamente:
     * `0.60 × (9/16) × (9/16)` ≈ **19 %** del ancho (el segundo 9/16 es porque el lienzo es 16:9).
     * Si el celular mandara el cuadro horizontal, el mismo recuadro mostraría menos ancho del central
     * (se recorta más), nunca deforma (Jorge, 23/11/2026).
     */
    private fun anchoJugadorTcg(): Float = 0.60f * (9f / 16f) * (9f / 16f)

    /** La mesa ocupa lo que queda entre las dos columnas: 16:9 en un lienzo 16:9 ⇒ alto = ancho. */

    /** Grosor del marco de la mesa en px de la composición de 1080 (12 px de referencia). */
    private fun grosorMarcoMesaPx(): Float = 12f * resources.displayMetrics.density

    /**
     * Porción del lienzo donde va la mesa en la vista de mesa: centrada entre las dos columnas y
     * **debajo del banner**, con el mismo margen de arriba y de abajo.
     *
     * Lleva un **aire a los lados** para que el marco de la mesa no se pegue al de los jugadores, y el
     * alto se calcula para que el **área de adentro del marco quede 16:9 exacta**: así un celular que
     * manda 16:9 llena el recuadro completo sin recortar y sin franjas (Jorge, 23/11/2026).
     */
    private fun rectMesaTcg(): RectF {
        val aire = 0.008f
        val ancho = anchoJugadorTcg() + aire
        val altoBanner = 0.02f + altoTiraTcg()
        val disponible = 0.98f - altoBanner
        val g = grosorMarcoMesaPx()
        // Lienzo de referencia 16:9 (1920x1080): así el marco se descuenta en píxeles reales.
        val anchoPx = (1f - 2f * ancho) * 1920f
        val altoPx = ((anchoPx - 2f * g) * 9f / 16f) + 2f * g
        val alto = (altoPx / 1080f).coerceAtMost(disponible)
        val y0 = altoBanner + (disponible - alto) / 2f
        return RectF(ancho, y0, 1f - ancho, y0 + alto)
    }

    /** 30 px de redondeado en las esquinas, medidos en la composición de 1080 (Jorge, 23/11/2026). */
    private fun radioCamaraTcg(): Float = 30f / resources.displayMetrics.density

    // ---- Cronómetro de la partida (45 min, dentro de un chip bajo la mesa) ----

    /** Máximo por partida: 45 minutos. */
    private val cronoMaximoMs = 45L * 60L * 1000L

    /** Momento (elapsedRealtime) en que arrancó la partida. */
    private var cronoInicioMs = 0L

    /** El cronómetro corre desde que hay partida nueva. */
    private var cronoAndando = false

    private var cronoItem: CanvasItem? = null

    /** Último texto dibujado, para no rehacer el mapa de bits si no cambió el segundo. */
    private var cronoTexto = ""

    /** Milisegundos que lleva la partida (tope 45 min). */
    private fun cronoTranscurridoMs(): Long {
        if (!cronoAndando || cronoInicioMs == 0L) return 0L
        return (android.os.SystemClock.elapsedRealtime() - cronoInicioMs)
            .coerceIn(0L, cronoMaximoMs)
    }

    /** Arranca el cronómetro de una partida nueva. */
    private fun reiniciarCronometroTcg() {
        cronoInicioMs = android.os.SystemClock.elapsedRealtime()
        cronoAndando = true
        cronoTexto = ""
        dibujarCronometroTcg()
    }

    /** Lo llama el reloj cada segundo mientras la vista es la de mesa. */
    private fun tickCronometroTcg() {
        if (!cronoAndando) return
        if (cronoTranscurridoMs() >= cronoMaximoMs) cronoAndando = false
        dibujarCronometroTcg()
        acomodarCronometroTcg()
    }

    /** Dibuja (o redibuja) el chip del cronómetro con el color del banner elegido. */
    private fun dibujarCronometroTcg() {
        if (!modoTcg || vistaTcg != VISTA_MESA) {
            cronoItem?.let { runCatching { b.canvas.scene.remove(it) } }
            cronoItem = null
            return
        }
        val total = cronoTranscurridoMs() / 1000
        val texto = String.format(java.util.Locale.US, "%02d:%02d", total / 60, total % 60)
        if (texto == cronoTexto && cronoItem != null) return
        cronoTexto = texto
        val previo = cronoItem
        val imagen = com.jfrmedia.switcher.studio.tcg.Banners
            .dibujarCronometro(texto, estadoTcg.paleta)
        val ancho = 0.30f
        val alto = ancho * 120f / 520f
        val ref = previo?.let { RectF(it.rect) }
            ?: RectF(0.5f - ancho / 2f, 0.9f, 0.5f + ancho / 2f, 0.9f + alto)
        previo?.let { runCatching { b.canvas.scene.remove(it) } }
        val item = b.canvas.scene.addImage(imagen, "Cronómetro", null, ref)
        item.radiusDp = 0f
        // Sin esto el compositor dibuja el marco de selección (el borde rojo) encima del chip.
        b.canvas.scene.selectedId = null
        cronoItem = item
        acomodarCronometroTcg()
    }

    /** Lo deja centrado **debajo del área de mesa**, dentro del lienzo. */
    private fun acomodarCronometroTcg() {
        val item = cronoItem ?: return
        if (!modoTcg || vistaTcg != VISTA_MESA) {
            runCatching { b.canvas.scene.remove(item) }
            cronoItem = null
            return
        }
        val mesa = rectMesaTcg()
        val ancho = 0.30f
        val alto = ancho * 120f / 520f
        val margen = 0.012f
        // Se permite encimarse a la mesa: queda montado sobre su borde inferior.
        var y0 = mesa.bottom - alto * 0.45f
        if (y0 + alto > 0.995f) y0 = 0.995f - alto
        if (y0 < margen) y0 = margen
        item.rect.set(0.5f - ancho / 2f, y0, 0.5f + ancho / 2f, y0 + alto)
        b.canvas.requestRender()
    }

    // ---- Batería de cada cámara, dentro de su recuadro ----

    /** Chip de batería por cámara (clave = slot). */
    private val bateriaItems = HashMap<Int, CanvasItem>()

    /** Último nivel dibujado por slot, para no rehacer el mapa de bits sin cambios. */
    private val bateriaNiveles = HashMap<Int, Int>()

    /**
     * Dibuja el porcentaje de batería **dentro del recuadro de cada cámara** en el programa.
     * El nivel llega del propio celular en su mensaje de estado (Jorge, 25/11/2026).
     */
    private fun actualizarBateriasTcg() {
        val escena = b.canvas.scene
        for (slot in 0..3) {
            val session = hub.session(slot)
            val nivel = session?.battery ?: -1
            val cam = escena.camAt(slot)
            val visible = modoTcg && cam != null && session != null &&
                session.state == com.jfrmedia.switcher.studio.rtc.CamState.CONNECTED &&
                nivel in 1..100
            if (!visible || cam == null) {
                bateriaItems.remove(slot)?.let { runCatching { escena.remove(it) } }
                bateriaNiveles.remove(slot)
                continue
            }
            if (bateriaNiveles[slot] == nivel && bateriaItems[slot] != null) {
                acomodarBateriaTcg(slot, cam.rect)
                continue
            }
            bateriaNiveles[slot] = nivel
            val imagen = com.jfrmedia.switcher.studio.tcg.Banners.dibujarBateria(nivel)
            val alto = 0.030f
            val ancho = alto * 300f / 110f
            val ref = bateriaItems[slot]?.let { RectF(it.rect) }
                ?: RectF(
                    cam.rect.right - ancho - 0.008f, cam.rect.top + 0.008f,
                    cam.rect.right - 0.008f, cam.rect.top + 0.008f + alto
                )
            bateriaItems.remove(slot)?.let { runCatching { escena.remove(it) } }
            val item = escena.addImage(imagen, "Batería ${slot + 1}", null, ref)
            item.radiusDp = 0f
            // Sin esto el compositor dibuja el marco de selección encima del chip.
            escena.selectedId = null
            bateriaItems[slot] = item
            acomodarBateriaTcg(slot, cam.rect)
        }
        b.canvas.requestRender()
    }

    /** Coloca el chip de batería arriba a la derecha, **dentro** del recuadro de la cámara. */
    private fun acomodarBateriaTcg(slot: Int, rect: RectF) {
        val item = bateriaItems[slot] ?: return
        val alto = 0.030f
        val ancho = alto * 300f / 110f
        val margen = 0.008f
        val x2 = (rect.right - margen).coerceAtLeast(rect.left + ancho + margen)
        val y0 = rect.top + margen
        item.rect.set(x2 - ancho, y0, x2, y0 + alto)
    }

    /**
     * Coloca las tres cámaras de la vista de mesa: **jugador 1 a la izquierda**, **jugador 2 a la
     * derecha** (las dos de arriba a abajo del monitor) y **la mesa al centro** (horizontal, con el
     * área de juego de ambos). La usan tanto el armado de la escena como el reacomodo de rectángulos,
     * para que no se separen las dos versiones.
     */
    private fun colocarCamarasTcg(escena: Scene) {
        val ancho = anchoJugadorTcg()
        escena.camAt(0)?.let { cam ->
            cam.rect.set(0f, 0f, ancho, 1f)
            cam.radiusDp = radioCamaraTcg()
            cam.marcoGrosor = 5f
            cam.encajar = false
        }
        escena.camAt(1)?.let { cam ->
            cam.rect.set(1f - ancho, 0f, 1f, 1f)
            cam.radiusDp = radioCamaraTcg()
            cam.marcoGrosor = 5f
            cam.encajar = false
        }
        escena.camAt(2)?.let { cam ->
            cam.rect.set(rectMesaTcg())
            cam.radiusDp = radioCamaraTcg()
            // La mesa lleva el marco un poco más grueso (es el que tiene que verse de lejos) y NO se
            // recorta: se encaja entera en el hueco entre las columnas y debajo del banner.
            cam.marcoGrosor = 12f
            cam.encajar = true
        }
    }

    /**
     * Le pide a cada celular su orientación en el Modo Pokémon TCG: los **dos jugadores verticales**
     * (su columna va de arriba a abajo del monitor) y la **mesa y el ambiente horizontales**.
     *
     * El celular no sabe en qué modo está la tablet: se le dice con el mismo mensaje que usa el Modo
     * Studio para el lienzo 9:16, pero **por cámara**. Al recibirlo, la app del celular gira y WebRTC
     * empieza a mandar el cuadro vertical (giro 90): eso es lo que hace que la columna de 19 % muestre
     * el **60 % central** sin recortar de más (Jorge, 23/11/2026).
     */
    private fun aplicarOrientacionCamarasTcg() {
        val pedido = mapOf(0 to true, 1 to true, 2 to false, 3 to false)
        hub.all().forEach { session ->
            val vertical = pedido[session.slot] ?: false
            if (session.verticalPropio != vertical) {
                session.verticalPropio = vertical
                runCatching { session.enviarOrientacion() }
                L.i("tcg", "camara ${session.slot}: se le pide ${if (vertical) "vertical" else "horizontal"}")
            }
        }
    }

    /** Devuelve la orientación de las cámaras a la del lienzo (al salir del Modo Pokémon). */
    private fun soltarOrientacionCamaras() {
        hub.all().forEach { session ->
            if (session.verticalPropio != null) {
                session.verticalPropio = null
                runCatching { session.enviarOrientacion() }
                L.i("tcg", "camara ${session.slot}: vuelve a la orientación del lienzo")
            }
        }
    }

    /** Coloca las cámaras en su rectángulo del modo (mesa al centro, jugadores a los lados). */
    private fun aplicarRectangulosTcg() {
        val escena = b.canvas.scene
        aplicandoRectTcg = true
        try {
            colocarCamarasTcg(escena)
            // La cámara que está al aire va a pantalla completa (jugador, mesa o ambiente).
            if (vistaTcg != VISTA_MESA) escena.camAt(vistaTcg)?.rect?.set(0f, 0f, 1f, 1f)
            // Y cualquier cámara que se haya colado en la escena del modo se saca (así no queda
            // grabada una ventanita que Jorge no pidió).
            escena.cams()
                .filter { it.slot != 0 && it.slot != 1 && it.slot != 2 && it.slot != vistaTcg }
                .forEach { runCatching { quitarCamaraDelLienzo(it) } }
            escena.selectedId = null
            b.canvas.slotResaltado = if (estadoTcg.turno == 0) 0 else 1
            b.canvas.requestRender()
        } finally {
            aplicandoRectTcg = false
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------ MODO STUDIO
    //
    // Solo en la **tablet** y solo cuando está **en vertical**. Es el modo tipo OBS de dos
    // monitores: preview a la izquierda, programa a la derecha, los controles en medio y las
    // tarjetas (cámaras, overlays, medios) debajo. Todo lo que se elige abajo entra al
    // **preview**; con «Transicionar» pasa al programa y lo que estaba al aire pasa al preview.
    //
    // El monitor de programa es un segundo CompositorView: la escena del programa es la que se
    // graba, se transmite y la que dice a los celulares quién está «AL AIRE».

    /** Monitor de programa. Se crea siempre y solo se engancha al layout en Modo Studio. */
    private var monitorPrograma: CompositorView? = null

    /** true cuando el Modo Studio está activo (tablet en vertical). */
    private var modoStudio = false

    /** true cuando el layout de Modo Studio ya está armado. */
    private var layoutStudioListo = false

    /**
     * En Modo Studio se puede trabajar en los dos formatos: true = monitores y tarjetas en 9:16,
     * false = 16:9. Se cambia con el chip de la barra de arriba, sin salir del modo.
     */
    private var studioVertical = true

    private var filaMonitores: LinearLayout? = null
    private var hostPrograma: AspectFrameLayout? = null
    private var botonTransicionar: TextView? = null

    /**
     * Lo que había en el **preview** al salir del Modo Studio. Se guarda para devolvérselo al volver:
     * sin esto, el programa (que queda al aire en el lienzo) se copiaba en el preview cada vez que se
     * giraba la tablet.
     */
    private var escenaPreviewGuardada: Scene? = null

    /** Vista que va **al aire**: en Modo Studio es el monitor de programa. */
    private fun vistaAlAire(): CompositorView =
        if (modoStudio) monitorPrograma ?: b.canvas else b.canvas

    /** Escena que está al aire (la que se graba y la que va a los celulares). */
    private fun escenaAlAire(): Scene = vistaAlAire().scene

    /** Adjunta la pista a los dos monitores (preview y programa). */
    private fun adjuntar(slot: Int, track: VideoTrack) {
        // Si la pista ya se liberó (sesión cerrada), no se puede enganchar: se ignora.
        runCatching { b.canvas.attachTrack(slot, track) }
        // El monitor de programa solo se usa en Modo Studio. Fuera de él, engancharle la pista
        // duplicaba el trabajo por cada cámara (CPU altísima y, con ella, el programa se trababa).
        if (modoStudio) runCatching { monitorPrograma?.attachTrack(slot, track) }
    }

    /** Suelta la pista de los dos monitores. */
    private fun soltar(slot: Int, track: VideoTrack?) {
        runCatching { b.canvas.detachTrack(slot, track) }
        runCatching { monitorPrograma?.detachTrack(slot, track) }
    }

    /** Para no repetir el aviso de que el diseño no cambia en medio de una sesión. */
    private var avisoStudioOcupado = false

    /**
     * Decide si toca Modo Studio: **solo tablet y solo en vertical** (en el celular nunca).
     * Se llama al abrir, al girar la tablet y al terminar una sesión.
     */
    private fun evaluarModoStudio() {
        // El Modo Pokémon TCG tiene su propio diseño y **siempre** va en horizontal: aquí no entra el
        // Modo Studio. Sin este corte, al abrir la app con la tablet vertical el modo se metía un
        // momento en Studio y, al girar, se restauraba el diseño de siempre: el modo quedaba
        // «desarmado» hasta salir a Modo Studio y volver (Jorge, 21/11/2026).
        if (modoTcg) {
            if (modoStudio) salirModoStudio()
            return
        }
        val tablet = !esPantallaCelular()
        val verticalFisico =
            resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val quiere = tablet && verticalFisico
        if (quiere == modoStudio) {
            avisoStudioOcupado = false
            return
        }
        if (sesionEnCurso()) {
            if (!avisoStudioOcupado) {
                avisoStudioOcupado = true
                toast("El diseño cambia cuando termines la grabación o el directo")
            }
            return
        }
        avisoStudioOcupado = false
        if (quiere) entrarModoStudio() else salirModoStudio()
    }

    private fun entrarModoStudio() {
        val monitor = monitorPrograma ?: return
        modoStudio = true
        // El formato del lienzo se conserva tal cual: `vertical` (9:16) y `studioVertical` (9:16)
        // significan lo mismo, así que se copia, NO se invierte (invertirlo hacía que el formato
        // fuera y viniera en cada giro de la tablet).
        studioVertical = vertical
        // Lo que está en el lienzo (lo que quedó al aire en el modo de siempre) pasa a PROGRAMA, y
        // el preview recupera lo que tenía antes de salir: así, al girar varias veces, no se copia
        // el programa en el preview.
        monitor.scene = clonarEscena(b.canvas.scene)
        escenaPreviewGuardada?.let { previa ->
            b.canvas.scene = previa
            escenaPreviewGuardada = null
        }
        // Ahora el monitor sí se usa: se le enganchan las cámaras que ya estaban conectadas.
        hub.all().forEach { session ->
            session.videoTrack?.let { pista -> monitor.attachTrack(session.slot, pista) }
        }
        localCam.track?.let { pista -> monitor.attachTrack(LocalCam.SLOT, pista) }
        construirLayoutStudio()
        aplicarAspectoEstudio()
        b.canvas.requestRender()
        monitor.requestRender()
        refreshOverlayTiles()
        refreshMediaTiles()
        L.i("studio", "Modo Studio activo")
        toast("Modo Studio · preview a la izquierda, programa a la derecha")
    }

    private fun salirModoStudio() {
        val monitor = monitorPrograma
        modoStudio = false
        // El formato con el que estabas produciendo en el Modo Studio se conserva al volver al
        // diseño de siempre: 16:9 sigue en 16:9 y 9:16 sigue en 9:16.
        vertical = studioVertical
        // Lo que estaba al aire es lo que se queda en el lienzo al volver al diseño de siempre.
        monitor?.scene?.let { alAire ->
            // Se guarda lo que había en el preview para devolvérselo al volver al Modo Studio.
            escenaPreviewGuardada = b.canvas.scene
            if (alAire.items.isNotEmpty()) b.canvas.scene = alAire
        }
        monitor?.scene = Scene()
        // El monitor de programa deja de usarse: se le sueltan las pistas para no gastar CPU.
        hub.all().forEach { session ->
            session.videoTrack?.let { pista -> monitor?.detachTrack(session.slot, pista) }
        }
        localCam.track?.let { pista -> monitor?.detachTrack(LocalCam.SLOT, pista) }
        // Y al visor se le vuelven a enganchar todas las cámaras: si alguna pista se había soltado
        // al cambiar de escenario, el programa salía negro al volver al diseño de siempre.
        hub.all().forEach { session ->
            session.videoTrack?.let { pista -> b.canvas.attachTrack(session.slot, pista) }
        }
        localCam.track?.let { pista -> b.canvas.attachTrack(LocalCam.SLOT, pista) }
        restaurarLayoutNormal()
        L.i("studio", "Modo Studio apagado")
    }

    /** Copia una escena (cámaras, imágenes y textos). Los videos no se copian. */
    private fun clonarEscena(origen: Scene): Scene {
        val nueva = Scene()
        nueva.aspect = origen.aspect
        origen.items.forEach { item ->
            val copia: CanvasItem? = when (item) {
                is CamItem -> CamItem(nueva.nextId(), item.slot).apply {
                    rect.set(item.rect)
                    visible = item.visible
                    opacity = item.opacity
                    radiusDp = item.radiusDp
                    label = item.label
                    zoom = item.zoom
                    panX = item.panX
                    panY = item.panY
                    luz = item.luz
                    contraste = item.contraste
                    wb = item.wb
                    color = item.color
                    vineta = item.vineta
                    poster = item.poster
                }

                is TextoItem -> TextoItem(
                    nueva.nextId(), item.texto, item.fuente, item.animacion,
                    item.guardadoId, item.bitmap, item.color, item.tamano
                ).apply {
                    rect.set(item.rect)
                    visible = item.visible
                    opacity = item.opacity
                }

                is ImageItem -> ImageItem(nueva.nextId(), item.bitmap, item.label, item.uri).apply {
                    rect.set(item.rect)
                    visible = item.visible
                    opacity = item.opacity
                    radiusDp = item.radiusDp
                }

                is MediaItem -> null // el video se reproduce en la escena donde está, no se copia
            }
            copia?.let { nueva.agregar(it) }
        }
        return nueva
    }

    /** Los dos monitores en el formato elegido (9:16 o 16:9) y los celulares avisados. */
    private fun aplicarAspectoEstudio() {
        val aspecto = if (studioVertical) 9f / 16f else 16f / 9f
        b.canvas.scene.aspect = aspecto
        monitorPrograma?.scene?.aspect = aspecto
        b.canvasHost.aspect = aspecto
        hostPrograma?.aspect = aspecto
        hub.vertical = lienzoVertical
        // En Modo Studio manda la orientación del lienzo: se sueltan las peticiones por cámara del
        // Modo Pokémon TCG.
        soltarOrientacionCamaras()
        // Con 9:16 los monitores ocupan todo el alto disponible; con 16:9 son bajos, así que la
        // fila toma solo su alto y las tarjetas se quedan con el resto.
        filaMonitores?.let { fila ->
            val lp = fila.layoutParams as? LinearLayout.LayoutParams
            if (lp != null) {
                if (studioVertical) {
                    lp.height = 0
                    lp.weight = 1.15f
                } else {
                    lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    lp.weight = 0f
                }
                fila.layoutParams = lp
            }
            val paramsMonitor = if (studioVertical) {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            } else {
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            b.canvasHost.layoutParams = LinearLayout.LayoutParams(paramsMonitor)
            hostPrograma?.layoutParams = LinearLayout.LayoutParams(paramsMonitor)
        }
        // Los celulares tienen que capturar en el mismo formato: si no, entran recortados.
        hub.all().forEach { runCatching { it.enviarOrientacion() } }
        // Las tarjetas (cámaras, overlays y medios) toman la forma del lienzo.
        buildSourcesGrid()
        actualizarInfoLienzo()
        ajustarLienzo()
    }

    /** Columna de un monitor: rótulo arriba y el visor debajo. */
    private fun columnaMonitor(rotulo: String): LinearLayout {
        val columna = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        columna.addView(TextView(this).apply {
            text = rotulo
            textSize = 9f
            letterSpacing = 0.14f
            setTextColor(
                ContextCompat.getColor(
                    this@StudioActivity,
                    if (rotulo == "PREVIEW") R.color.text_secondary else R.color.accent
                )
            )
            setPadding(2.dp, 0, 2.dp, 3.dp)
        })
        return columna
    }

    /** Arma el layout de Modo Studio moviendo los bloques que ya existen. */
    private fun construirLayoutStudio() {
        if (layoutStudioListo) return
        val cuerpo = b.cuerpo
        cuerpo.removeAllViews()
        (b.panelFuentes.parent as? ViewGroup)?.removeView(b.panelFuentes)
        (b.panelPrograma.parent as? ViewGroup)?.removeView(b.panelPrograma)
        (zonaControles?.parent as? ViewGroup)?.removeView(zonaControles)
        b.slotIzquierdo.visibility = View.GONE
        cuerpo.orientation = LinearLayout.VERTICAL

        // 1) El grupo de la sesión (punto, estado, cronómetro y botón) ya no cabe en la barra de
        // arriba cuando la pantalla está en vertical: se pasa a una fila propia a todo lo ancho.
        val filaSesion = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12.dp, 4.dp, 12.dp, 2.dp)
        }
        listOf<View>(b.liveDot, b.txtLive, b.txtTimer, b.btnRecord).forEach { v ->
            (v.parent as? ViewGroup)?.removeView(v)
        }
        b.liveDot.layoutParams = LinearLayout.LayoutParams(8.dp, 8.dp)
        b.txtLive.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = 6.dp }
        b.txtTimer.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = 8.dp }
        b.btnRecord.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = 10.dp }
        // De izquierda a derecha: la info del sistema (se lleva el espacio libre), y **pegados a la
        // derecha** el estado de grabación, el cronómetro y el botón, como en el diseño horizontal.
        (b.cabeceraPrograma.parent as? ViewGroup)?.removeView(b.cabeceraPrograma)
        filaSesion.addView(
            b.cabeceraPrograma,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 12.dp
            }
        )
        // Una sola línea: la info del sistema se recorta con puntos suspensivos si no cabe, y el
        // formato del lienzo queda pegado a la derecha sin encimarse.
        b.txtEtiquetaPrograma.maxLines = 1
        b.txtEtiquetaPrograma.ellipsize = android.text.TextUtils.TruncateAt.END
        b.txtEtiquetaPrograma.layoutParams = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        )
        // El separador flexible del medio ya no hace falta: sobra y empuja el texto.
        (b.cabeceraPrograma.getChildAt(1) as? View)?.visibility = View.GONE
        // El formato ya se ve en el chip de la barra de arriba: aquí se quita para que la info
        // del sistema (CPU, RAM, Mbps y temperatura) quepa completa.
        b.txtCanvasInfo.visibility = View.GONE
        filaSesion.addView(b.liveDot)
        filaSesion.addView(b.txtLive)
        filaSesion.addView(b.txtTimer)
        filaSesion.addView(b.btnRecord)
        cuerpo.addView(
            filaSesion,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        // 3) Los dos monitores, uno al lado del otro y a todo lo ancho.
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(8.dp, 0, 8.dp, 0)
        }
        filaMonitores = fila
        // Los monitores se llevan la mayor parte del alto (era 1.2 contra 1 de las tarjetas).
        cuerpo.addView(
            fila,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.9f)
        )

        // Preview (es el lienzo de siempre: ahí se compone y se edita).
        val colPreview = columnaMonitor("PREVIEW")
        (b.canvasHost.parent as? ViewGroup)?.removeView(b.canvasHost)
        b.canvasHost.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        )
        colPreview.addView(b.canvasHost)
        fila.addView(
            colPreview,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        )

        // En 9:16 los monitores quedan angostos, así que los botones van en una columna entre
        // los dos. En 16:9 sobran monitores anchos y los botones pasan a una fila debajo.
        if (studioVertical) {
            val centro = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(5.dp, 0, 5.dp, 0)
            }
            fila.addView(
                centro,
                LinearLayout.LayoutParams(96.dp, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            moverBotonesAColumna(centro)
        }

        // Programa.
        val colPrograma = columnaMonitor("PROGRAMA")
        val host = AspectFrameLayout(this).apply {
            aspect = if (studioVertical) 9f / 16f else 16f / 9f
            setBackgroundResource(R.drawable.bg_canvas)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        hostPrograma = host
        monitorPrograma?.let { monitor ->
            (monitor.parent as? ViewGroup)?.removeView(monitor)
            host.addView(
                monitor,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            // El mismo marco que tiene el visor del preview.
            host.addView(
                View(this).apply { setBackgroundResource(R.drawable.bg_canvas_border) },
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            // El botón de foto dispara una captura **del programa** (lo que está al aire), así que
            // en Modo Studio se pone sobre el monitor de programa, no en el preview.
            (b.btnPhoto.parent as? ViewGroup)?.removeView(b.btnPhoto)
            host.addView(b.btnPhoto)
        }
        colPrograma.addView(host)
        fila.addView(
            colPrograma,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        )

        // 4) En 16:9 los botones van debajo de los dos monitores, con «Transicionar» en el centro.
        if (!studioVertical) {
            val filaBotones = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(8.dp, 5.dp, 8.dp, 1.dp)
            }
            cuerpo.addView(
                filaBotones,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            moverBotonesAFila(filaBotones)
        }

        // 5) Tarjetas de cámaras, overlays y medios (bajan un poco para dejar crecer los monitores).
        cuerpo.addView(
            b.panelFuentes,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.8f)
        )

        // 6) Ajustes de imagen y monitor de audio, los dos visibles.
        val baja = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8.dp, 2.dp, 8.dp, 4.dp)
        }
        cuerpo.addView(
            baja,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        (b.cajaVideo.parent as? ViewGroup)?.removeView(b.cajaVideo)
        b.cajaVideo.visibility = if (modoTcg) View.GONE else View.VISIBLE
        // En Modo Studio los ajustes de imagen van siempre en una fila, en 9:16 y en 16:9: antes
        // se quedaba el acomodo que traía del otro diseño y al girar la tablet salía a medias.
        ajustesEnUnaFila()
        baja.addView(
            b.cajaVideo,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        // El panel de audio tiene que existir: si se armó el layout antes de montarlo, se monta aquí.
        if (panelAudio == null) montarControlesAudio()
        panelAudio?.let { audio ->
            (audio.parent as? ViewGroup)?.removeView(audio)
            audio.visibility = View.VISIBLE
            ondaAudio?.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 46.dp
            )
            baja.addView(
                audio,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 92.dp
                )
            )
            L.i("studio", "studio: panel de audio en la zona de abajo")
        }
        L.i(
            "studio",
            "studio: cajaVideo=${b.cajaVideo.parent != null} audio=${panelAudio?.parent != null} " +
                "monitores=${monitorPrograma?.parent != null}"
        )
        layoutStudioListo = true
        buildSourcesGrid()
    }

    /**
     * Los botones de siempre más «Transicionar», apilados en una columna entre los dos monitores
     * (es el acomodo del 9:16, donde los monitores quedan angostos).
     */
    private fun moverBotonesAColumna(centro: LinearLayout) {
        val botones = listOf(
            b.btnLayerA, b.btnLayerB, b.btnPip, b.btnSplit,
            b.btnZoomOut, b.btnZoomIn, b.btnTabletLens
        )
        for (v in botones) {
            (v.parent as? ViewGroup)?.removeView(v)
            recordarEstilo(v)
            // 38 dp y letra de 12: con 32 dp y letra de 10 los botones quedaban chicos para el
            // dedo en la tablet (Jorge, 15/11). A y B, además, con la letra más grande.
            val esCapa = v.id == R.id.btnLayerA || v.id == R.id.btnLayerB
            v.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, if (esCapa) 42.dp else 38.dp
            ).apply { topMargin = 4.dp }
            v.gravity = Gravity.CENTER
            v.textSize = if (esCapa) 16f else 12f
            // Sin icono en la columna: con el icono, «Frontal» no cabe y sale «Fron…».
            if (v.id == R.id.btnTabletLens) v.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
            centro.addView(v)
        }
        val transicionar = TextView(this).apply {
            // Solo las flechas encontradas: en la columna angosta la palabra quedaba apretada y se
            // veía mal. Las flechas van grandes para que el botón se reconozca de un toque.
            text = "⇄"
            textSize = 28f
            gravity = Gravity.CENTER
            includeFontPadding = false
            contentDescription = "Transicionar"
            setTextColor(ContextCompat.getColor(this@StudioActivity, R.color.bg_root))
            setBackgroundResource(R.drawable.bg_pill_accent)
            setPadding(0, 0, 0, 2.dp)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 48.dp
            ).apply { topMargin = 10.dp }
            setOnClickListener { transicionar() }
        }
        botonTransicionar = transicionar
        centro.addView(transicionar)
    }

    /**
     * Los botones de siempre en una fila debajo de los dos monitores, con «Transicionar» justo
     * en el centro (debajo de donde se juntan el preview y el programa).
     */
    private fun moverBotonesAFila(filaBotones: LinearLayout) {
        val izquierda = listOf(b.btnLayerA, b.btnLayerB, b.btnPip, b.btnSplit)
        val derecha = listOf(b.btnZoomOut, b.btnZoomIn, b.btnTabletLens)

        fun grupo(alineacion: Int): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = alineacion
        }

        fun colocar(contenedor: LinearLayout, v: TextView) {
            (v.parent as? ViewGroup)?.removeView(v)
            recordarEstilo(v)
            // A y B son los que más se usan (eligen a qué capa va cada cámara): van más anchos,
            // más altos y con la letra grande. Antes medían el ancho de su propia letra y en la
            // tablet quedaban como los dos botones más chicos de la fila (Jorge, 15/11).
            val esCapa = v.id == R.id.btnLayerA || v.id == R.id.btnLayerB
            if (esCapa) {
                v.layoutParams = LinearLayout.LayoutParams(56.dp, 46.dp).apply { marginStart = 5.dp }
                v.textSize = 17f
                v.setPadding(0, 0, 0, 0)
            } else {
                // El resto: ancho natural para que se lean «PiP», «2cam» y «Frontal» completos,
                // y el mismo alto que A/B para que la fila se vea pareja.
                v.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, 46.dp
                ).apply { marginStart = 5.dp }
                v.textSize = 13f
            }
            v.gravity = Gravity.CENTER
            contenedor.addView(v)
        }

        val izq = grupo(Gravity.END or Gravity.CENTER_VERTICAL)
        izquierda.forEach { colocar(izq, it) }
        val der = grupo(Gravity.START or Gravity.CENTER_VERTICAL)
        derecha.forEach { colocar(der, it) }

        val transicionar = TextView(this).apply {
            text = "⇄  TRANSICIONAR"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@StudioActivity, R.color.bg_root))
            setBackgroundResource(R.drawable.bg_pill_accent)
            setPadding(14.dp, 6.dp, 14.dp, 6.dp)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, 44.dp
            ).apply { marginStart = 8.dp; marginEnd = 8.dp }
            setOnClickListener { transicionar() }
        }
        botonTransicionar = transicionar

        filaBotones.addView(
            izq,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        filaBotones.addView(transicionar)
        filaBotones.addView(
            der,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
    }

    /** Devuelve el diseño de siempre (horizontal) y rearma todo. */
    private fun restaurarLayoutNormal() {
        if (!layoutStudioListo) return
        monitorPrograma?.let { monitor -> (monitor.parent as? ViewGroup)?.removeView(monitor) }
        (filaMonitores?.parent as? ViewGroup)?.removeView(filaMonitores)
        // El grupo de la sesión vuelve a la barra de arriba, en su orden original.
        val barra = b.btnFacebook.parent as? LinearLayout
        if (barra != null) {
            // Hay que sacarlos de la fila del Modo Studio antes de moverlos.
            listOf<View>(b.liveDot, b.txtLive, b.txtTimer, b.btnRecord).forEach { v ->
                (v.parent as? ViewGroup)?.removeView(v)
            }
            b.liveDot.layoutParams = LinearLayout.LayoutParams(8.dp, 8.dp)
            b.txtLive.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = 6.dp }
            b.txtTimer.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = 8.dp }
            b.btnRecord.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = 12.dp }
            barra.addView(b.liveDot)
            barra.addView(b.txtLive)
            barra.addView(b.txtTimer)
            barra.addView(b.btnRecord)
        }
        b.cuerpo.removeAllViews()
        // La info del lienzo vuelve a mostrarse en el diseño de siempre.
        b.txtCanvasInfo.visibility = View.VISIBLE
        (b.cabeceraPrograma.parent as? ViewGroup)?.removeView(b.cabeceraPrograma)
        (b.canvasHost.parent as? ViewGroup)?.removeView(b.canvasHost)
        b.canvasHost.aspect = if (vertical) 9f / 16f else 16f / 9f
        b.panelPrograma.addView(
            b.cabeceraPrograma, 0,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        b.panelPrograma.addView(
            b.canvasHost, 1,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        // El botón de foto vuelve al visor del preview, que es donde estaba siempre.
        (b.btnPhoto.parent as? ViewGroup)?.removeView(b.btnPhoto)
        b.canvasHost.addView(b.btnPhoto)
        filaMonitores = null
        hostPrograma = null
        botonTransicionar = null
        layoutStudioListo = false
        aplicarOrientacion()
        refreshOverlayTiles()
        refreshMediaTiles()
        onSelectionChanged(null)
    }

    /**
     * Pasa el preview al programa: se intercambian las escenas y el cuadro que estaba al aire se
     * retira con la transición elegida (como el botón de OBS).
     */
    private fun transicionar() {
        val programa = monitorPrograma ?: return
        if (!modoStudio) return
        if (programa.width <= 0 || programa.height <= 0) return
        // Sondeo: en vertical el cambio se veía «de ida y vuelta». Este registro dice si la
        // transición se dispara dos veces y con cuánta separación (Jorge, 15/11/2026).
        L.i("trans", "transicionar() · studioVertical=$studioVertical · ${programa.width}x${programa.height}" +
            " · veloActivo=${programa.veloActivo}")
        val foto = Bitmap.createBitmap(programa.width, programa.height, Bitmap.Config.ARGB_8888)
        android.view.PixelCopy.request(
            programa, foto,
            { resultado ->
                L.i("trans", "PixelCopy resultado=$resultado (0=ok)")
                intercambiarMonitor(
                    if (resultado == android.view.PixelCopy.SUCCESS) foto else null
                )
            },
            uiHandler
        )
    }

    private fun intercambiarMonitor(foto: Bitmap?) {
        val programa = monitorPrograma ?: return
        L.i("trans", "intercambiarMonitor · foto=${foto != null}")
        val escenaPreview = b.canvas.scene
        val escenaPrograma = programa.scene
        // 1) El velo se arma PRIMERO. Si se pide el cuadro antes, el hilo de dibujo puede pintar la
        //    escena nueva sin el velo y se ve un parpadeo (Jorge, 15/11: «de ida y vuelta», solo en
        //    vertical, donde el monitor está dibujando continuamente por la cámara en vivo).
        if (foto != null) programa.prepararVelo(foto, b.canvas.transicion, b.canvas.transicionMs)
        // 2) Ahora sí, el cambio de escenas.
        b.canvas.scene = escenaPrograma
        programa.scene = escenaPreview
        escenaPreview.selectedId = null
        escenaPrograma.selectedId = null
        aplicarAspectoEstudio()
        // 3) Un solo pedido de cuadro para los dos, ya con el velo armado.
        b.canvas.requestRender()
        programa.requestRender()
        if (foto != null) {
            // El lienzo se dibuja bajo demanda: se le piden cuadros mientras dura la transición.
            val paso = object : Runnable {
                override fun run() {
                    programa.requestRender()
                    if (programa.veloActivo) uiHandler.postDelayed(this, 16)
                }
            }
            uiHandler.post(paso)
        }
        // Las tarjetas ahora muestran lo que quedó en preview.
        refreshOverlayTiles()
        refreshMediaTiles()
        refreshUi()
        onSelectionChanged(b.canvas.scene.selected())
        actualizarProgramaCamaras()
    }

    // ================================================================ Modo Pokémon TCG

    /** Carga el estado del torneo desde las preferencias. */
    private fun leerEstadoTcg(): EstadoTcg = runCatching {
        val p = getSharedPreferences(PREFS, MODE_PRIVATE)
        val texto = p.getString(CLAVE_TCG_ESTADO, null) ?: return@runCatching EstadoTcg()
        val raiz = org.json.JSONObject(texto)

        fun jugador(clave: String): JugadorTcg {
            val o = raiz.optJSONObject(clave) ?: return JugadorTcg()
            val fichas = BooleanArray(3)
            val arr = o.optJSONArray("chips")
            if (arr != null) for (i in 0 until minOf(3, arr.length())) fichas[i] = arr.getBoolean(i)
            return JugadorTcg(
                nombre = o.optString("nombre", "Jugador"),
                ganadas = o.optInt("g", 0),
                empatadas = o.optInt("e", 0),
                perdidas = o.optInt("p", 0),
                debilitados = o.optInt("deb", 0),
                chips = fichas,
                mazo = o.optString("mazo", ""),
                mazoId = o.optString("mazoId", ""),
            )
        }

        val roster = ArrayList<JugadorEvento>()
        raiz.optJSONArray("roster")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                roster.add(
                    JugadorEvento(
                        nombre = o.optString("nombre", "Jugador"),
                        ganadas = o.optInt("g", 0),
                        empatadas = o.optInt("e", 0),
                        perdidas = o.optInt("p", 0),
                    )
                )
            }
        }
        EstadoTcg(
            evento = raiz.optString("evento", "LIGA JFR"),
            roster = roster,
            uno = jugador("j1"),
            dos = jugador("j2"),
            serieUno = raiz.optInt("s1", 0),
            serieDos = raiz.optInt("s2", 0),
            turno = raiz.optInt("turno", 0),
            paleta = raiz.optInt("paleta", 1),
        )
    }.getOrDefault(EstadoTcg())

    private fun guardarEstadoTcg() = runCatching {
        fun jugador(j: JugadorTcg) = org.json.JSONObject().apply {
            put("nombre", j.nombre)
            put("g", j.ganadas)
            put("e", j.empatadas)
            put("p", j.perdidas)
            put("deb", j.debilitados)
            put("mazo", j.mazo)
            put("mazoId", j.mazoId)
            put("chips", org.json.JSONArray().apply { j.chips.forEach { put(it) } })
        }
        val raiz = org.json.JSONObject().apply {
            put("evento", estadoTcg.evento)
            put("roster", org.json.JSONArray().apply {
                estadoTcg.roster.forEach { j ->
                    put(org.json.JSONObject().apply {
                        put("nombre", j.nombre)
                        put("g", j.ganadas)
                        put("e", j.empatadas)
                        put("p", j.perdidas)
                    })
                }
            })
            put("j1", jugador(estadoTcg.uno))
            put("j2", jugador(estadoTcg.dos))
            put("s1", estadoTcg.serieUno)
            put("s2", estadoTcg.serieDos)
            put("turno", estadoTcg.turno)
            put("paleta", estadoTcg.paleta)
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(CLAVE_TCG_ESTADO, raiz.toString()).apply()
    }

    private fun cambiarEstadoTcg(nuevo: EstadoTcg) {
        estadoTcg = nuevo
        guardarEstadoTcg()
        dibujarBannerTcg()
        // El marco de las cámaras de jugador toma el color del banner: se repinta con cada cambio.
        actualizarMarcosTcg()
        // Y los iconos grandes del mazo debajo de la cámara, por si cambió el mazo elegido.
        actualizarIconosMazo()
        dibujarChipsTcg()
        panelTcg?.refrescar(nuevo)
    }

    // ------------------------------------------------ chips de la mesa (dentro de la cámara)

    /** Ancho de un chip: la mayor parte del ancho de la columna del jugador. */
    private fun anchoChipTcg(): Float = anchoJugadorTcg() * 0.74f

    /** Alto del chip (pastilla): el ancho en píxeles dividido entre 3.6, pasado a fracción. */
    private fun altoChipTcg(): Float = anchoChipTcg() * (16f / 9f) / 3.6f

    /** Cuánto se mete hacia afuera del monitor: el chip queda **asomando** en la orilla. */
    private fun sangradoChipTcg(): Float = anchoChipTcg() * 0.12f

    /** Lo que se corre hacia afuera al usarlo: deja solo la punta redondeada a la vista. */
    private fun corridoChipUsadoTcg(): Float = anchoChipTcg() * 0.62f

    /**
     * Chips **Energy / Supporter / Retreat** dentro de la cámara de cada jugador (Jorge, 23/11/2026).
     *
     * Van en **columna** (Energy arriba, Supporter en medio, Retreat abajo) del lado de afuera de la
     * columna, **asomando** por la orilla del monitor. Al usarlos se deslizan hacia afuera con una
     * animación y **queda su punta redondeada a la vista**.
     */
    private fun dibujarChipsTcg() {
        val escena = b.canvas.scene
        (chipsTcgUno + chipsTcgDos).forEach { runCatching { escena.remove(it) } }
        chipsTcgUno = emptyList()
        chipsTcgDos = emptyList()
        if (modoTcg && vistaTcg == VISTA_MESA) {
            val ancho = anchoChipTcg()
            val alto = altoChipTcg()
            val hueco = alto * 0.24f
            val abajo = 0.975f
            val nombres = listOf("ENERGY", "SUPPORTER", "RETREAT")
            val colores = listOf(0xFF58C88C.toInt(), 0xFF7A98F4.toInt(), 0xFFF2AC52.toInt())
            listOf(estadoTcg.uno to true, estadoTcg.dos to false).forEach { par ->
                val jugador = par.first
                val izquierda = par.second
                val items = ArrayList<ImageItem>(3)
                for (i in 0 until 3) {
                    val usado = !jugador.chips[i]
                    val bmp = Banners.dibujarChip(nombres[i], colores[i], activo = !usado)
                    val item = ImageItem(escena.nextId(), bmp, "Chip ${nombres[i]}", null)
                    // Energy arriba, Supporter en medio y Retreat hasta abajo.
                    val y2 = abajo - (2 - i) * (alto + hueco)
                    val y1 = y2 - alto
                    val fuera = sangradoChipTcg() + if (usado) corridoChipUsadoTcg() else 0f
                    val destino = if (izquierda) {
                        val x1 = -fuera
                        RectF(x1, y1, x1 + ancho, y2)
                    } else {
                        val x2 = 1f + fuera
                        RectF(x2 - ancho, y1, x2, y2)
                    }
                    val clave = (if (izquierda) "u" else "d") + i
                    val previa = posChipPrevia[clave]
                    item.rect.set(destino)
                    escena.agregar(item)
                    posChipPrevia[clave] = RectF(destino)
                    if (previa != null && (previa.left != destino.left || previa.right != destino.right)) {
                        animarChip(item, RectF(previa), RectF(destino))
                    }
                    items.add(item)
                }
                if (izquierda) chipsTcgUno = items else chipsTcgDos = items
            }
        }
        runCatching { b.canvas.requestRender() }
    }

    /** Desliza un chip de su lugar viejo al nuevo: al usarlo se va, dejando la punta a la vista. */
    private fun animarChip(item: ImageItem, desde: RectF, hasta: RectF) {
        val anim = android.animation.ValueAnimator.ofFloat(0f, 1f)
        anim.duration = 280
        anim.interpolator = android.view.animation.DecelerateInterpolator()
        anim.addUpdateListener { a ->
            val t = a.animatedValue as Float
            item.rect.set(
                desde.left + (hasta.left - desde.left) * t,
                desde.top + (hasta.top - desde.top) * t,
                desde.right + (hasta.right - desde.right) * t,
                desde.bottom + (hasta.bottom - desde.bottom) * t
            )
            runCatching { b.canvas.requestRender() }
        }
        anim.start()
    }

    private fun dibujarBannerTcg() {
        // Iconos de los mazos que están en uso (el banner los dibuja junto al nombre del jugador).
        val iconos = HashMap<String, List<Bitmap>>()
        listOf(estadoTcg.uno, estadoTcg.dos).forEach { jugador ->
            if (jugador.mazoId.isNotBlank()) {
                com.jfrmedia.switcher.studio.tcg.Mazos.iconos(this, jugador.mazoId)
                    .takeIf { it.isNotEmpty() }?.let { iconos[jugador.mazoId] = it }
            }
        }
        val imagen = Banners.dibujar(estadoTcg, 1600, 360, iconos)
        val previo = bannerTcg
        // Franja centrada arriba con el mismo alto que las cámaras de los jugadores.
        val ancho = anchoBannerTcg()
        val alto = altoTiraTcg()
        val x1 = (1f - ancho) / 2f
        val rect = previo?.let { RectF(it.rect) } ?: RectF(x1, 0.02f, x1 + ancho, 0.02f + alto)
        previo?.let { runCatching { b.canvas.scene.remove(it) } }
        val item = b.canvas.scene.addImage(imagen, "Banner TCG", null, rect)
        item.radiusDp = 8f
        b.canvas.scene.selectedId = null
        bannerTcg = item
        // Se coloca donde toca según la vista (arriba en la mesa, abajo con un jugador al aire,
        // oculto con el ambiente) para que un cambio de paleta no lo dibuje sobre la cara.
        acomodarBannerTcg()
    }

    // ---- Facebook: espectadores y comentarios del stream (panel de la derecha del modo TCG) ----

    private val fbHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Id del video al aire. Sale de la propia clave (`FB-<idVideo>-0-…`). */
    private var fbVideoId = ""

    /** Espectadores en texto (vacío = sin dato todavía). */
    private var fbEspectadores = ""

    /** Últimos comentarios, del más nuevo al más viejo. */
    /**
     * Comentarios del panel, del más nuevo al más viejo, **de las dos plataformas juntas**.
     * Cada uno guarda de dónde viene para poder etiquetarlo.
     */
    private val fbLista = ArrayList<com.jfrmedia.switcher.studio.tcg.ComentarioPanel>()

    /** Hay lectura en curso (se apaga al cortar el directo o si el token vence). */
    @Volatile
    private var fbLeyendo = false

    private var fbOcupadoComentarios = false
    private var fbOcupadoEspectadores = false
    private var fbOcupadoPrueba = false

    /** Comentarios cada 30 s y espectadores cada 3 min: así no se topa el límite de Facebook. */
    private val fbCadaComentarios = 30_000L
    private val fbCadaEspectadores = 180_000L

    private val fbTareaComentarios = object : Runnable {
        override fun run() {
            if (!fbLeyendo) return
            consultarComentariosAhora()
            fbHandler.postDelayed(this, fbCadaComentarios)
        }
    }

    private val fbTareaEspectadores = object : Runnable {
        override fun run() {
            if (!fbLeyendo) return
            consultarEspectadoresAhora()
            fbHandler.postDelayed(this, fbCadaEspectadores)
        }
    }

    /** Arranca la lectura: comentarios cada 30 s y espectadores cada 3 min. */
    private fun iniciarConsultasFacebook(videoDeLaClave: String = "") {
        fbHandler.removeCallbacks(fbTareaComentarios)
        fbHandler.removeCallbacks(fbTareaEspectadores)
        fbHandler.removeCallbacks(ytTareaComentarios)
        ytChatId = ""
        fbEspectadores = ""
        fbLista.clear()
        fbVideoId = videoDeLaClave
        fbLeyendo = true
        // Se repinta de inmediato: si no, el panel se queda mostrando los comentarios del directo
        // anterior hasta que llegue la primera consulta (visto el 25/11/2026).
        panelTcg?.comentarios("", emptyList())
        L.i("fb", "lectura para el directo '${videoDeLaClave.ifBlank { "(se busca en la Página)" }}'")
        // Una primera vuelta casi inmediata para que el panel no arranque vacío.
        fbHandler.postDelayed(fbTareaComentarios, 1_500L)
        fbHandler.postDelayed(fbTareaEspectadores, 2_500L)
    }

    private fun detenerConsultasFacebook() {
        fbLeyendo = false
        fbHandler.removeCallbacks(fbTareaComentarios)
        fbHandler.removeCallbacks(fbTareaEspectadores)
        fbHandler.removeCallbacks(ytTareaComentarios)
        ytChatId = ""
        runCatching { com.jfrmedia.switcher.studio.stream.FacebookLive.cortarEscucha() }
    }

    /**
     * Crea la transmisión en YouTube y la agrega como **segundo destino** (multistream).
     *
     * Va en su propio hilo porque habla con Google; cuando llega la dirección con la clave se
     * conecta el destino 1 del emisor. Si algo falla, Facebook sigue al aire y solo se avisa.
     */
    private fun crearYoutubeYConectar(indice: Int = 1) {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (!com.jfrmedia.switcher.studio.stream.YouTubeLive.conectado(this)) {
            toast("YouTube elegido pero sin conectar: toca «Conectar con YouTube»")
            return
        }
        val descripcion = prefs.getString(CLAVE_FB_DESCRIPCION, "").orEmpty().trim()
        Thread {
            com.jfrmedia.switcher.studio.stream.YouTubeLive.crearDirecto(
                this,
                "JFR Media",
                descripcion,
                alListo = { directo ->
                    prefs.edit().putString(CLAVE_YT_DIRECTO_ID, directo.id).apply()
                    // El chat se pide aparte si no vino al crear la transmisión.
                    val chat = directo.chatId.ifBlank {
                        com.jfrmedia.switcher.studio.stream.YouTubeLive.chatDe(this, directo.id)
                            .orEmpty()
                    }
                    runOnUiThread { iniciarChatYouTube(chat) }
                    runOnUiThread {
                        val ok = if (indice == 0) {
                            // Solo YouTube: es la transmisión principal, con toda la preparación.
                            iniciarTransmision(directo.url, conFacebook = false)
                            emisor.transmitiendo
                        } else {
                            emisor.transmitirAdemas(directo.url)
                        }
                        L.i("yt", "destino $indice (YouTube): ${if (ok) "conectando" else "no aceptó"}")
                        toast(if (ok) "YouTube: conectando…" else "YouTube no aceptó la conexión")
                    }
                },
                alError = { mensaje ->
                    L.w("yt", "crear: $mensaje")
                    runOnUiThread { toast("YouTube: ${mensaje.take(120)}") }
                }
            )
        }.apply { isDaemon = true }.start()
    }

    /** Paso 1 al salir al aire: ¿a dónde se transmite? */
    private fun elegirDestinoYSalir() {
        val opciones = arrayOf("Facebook", "YouTube", "Facebook + YouTube")
        AlertDialog.Builder(this)
            .setTitle("¿A dónde vas a transmitir?")
            .setItems(opciones) { _, cual -> pedirDescripcionDelDirecto(cual) }
            .setNegativeButton("Cancelar", null)
            .show()
            .also { cuadroALaIzquierda(it, altoCompleto = false) }
    }

    /** Paso 2: la descripción (la misma para Facebook y para YouTube) y salir al aire. */
    private fun pedirDescripcionDelDirecto(cual: Int) {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val caja = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22.dp, 10.dp, 22.dp, 10.dp)
        }
        caja.addView(TextView(this).apply {
            text = when (cual) {
                0 -> "Destino: Facebook."
                1 -> "Destino: YouTube."
                else -> "Destino: Facebook y YouTube a la vez."
            }
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@StudioActivity, R.color.text_primary))
        })
        caja.addView(TextView(this).apply {
            text = "Descripción del directo (se usa en las dos plataformas)"
            textSize = 11f
            setTextColor(ContextCompat.getColor(this@StudioActivity, R.color.text_dim))
            setPadding(0, 12.dp, 0, 0)
        })
        val campo = EditText(this).apply {
            setText(prefs.getString(CLAVE_FB_DESCRIPCION, ""))
            textSize = 13f
            hint = "Ej. Liga JFR · Ronda 3 · Bo3"
        }
        caja.addView(campo)
        AlertDialog.Builder(this)
            .setTitle("Descripción")
            .setView(caja)
            .setPositiveButton("Salir al aire") { _, _ ->
                val texto = campo.text.toString().trim()
                prefs.edit().putString(CLAVE_FB_DESCRIPCION, texto).apply()
                arrancarSegunDestino(cual, texto)
            }
            .setNegativeButton("Cancelar", null)
            .show()
            .also { cuadroALaIzquierda(it, altoCompleto = false) }
    }

    /** Crea lo que haga falta y sale al aire en el destino elegido. */
    private fun arrancarSegunDestino(cual: Int, descripcion: String) {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (cual == 1) {
            // Solo YouTube: la transmisión de YouTube es la principal (destino 0).
            crearYoutubeYConectar(0)
            return
        }
        val paginaId = prefs.getString(CLAVE_FB_PAGE_ID, "").orEmpty().trim()
        val tokenPagina = prefs.getString(CLAVE_FB_PAGINA_TOKEN, "").orEmpty().trim()
        if (paginaId.isBlank() || tokenPagina.isBlank()) {
            toast("Primero trae tus Páginas y elige una")
            return
        }
        toast("Creando el directo en Facebook…")
        Thread {
            var directo: com.jfrmedia.switcher.studio.stream.FacebookLive.DirectoFb? = null
            var error = ""
            try {
                directo = com.jfrmedia.switcher.studio.stream.FacebookLive
                    .crearDirecto(paginaId, tokenPagina, "JFR Media", descripcion)
            } catch (t: Throwable) {
                error = t.message ?: "error"
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val listo = directo
                if (listo == null) {
                    toast("Facebook: ${cuandoFallaFacebook(error)}")
                    return@runOnUiThread
                }
                prefs.edit().putString(CLAVE_FB_DIRECTO_ID, listo.id).apply()
                iniciarTransmision(listo.url)
                // Si eligió las dos, YouTube entra como segundo destino.
                if (cual == 2) crearYoutubeYConectar(1)
            }
        }.apply { isDaemon = true }.start()
    }

    /** Cierra la transmisión de YouTube si la app la creó. */
    private fun cerrarDirectoYouTube() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val id = prefs.getString(CLAVE_YT_DIRECTO_ID, "").orEmpty().trim()
        if (id.isBlank()) return
        prefs.edit().putString(CLAVE_YT_DIRECTO_ID, "").apply()
        Thread {
            val cerrado = com.jfrmedia.switcher.studio.stream.YouTubeLive.terminarDirecto(this, id)
            L.i("yt", if (cerrado) "transmisión $id cerrada" else "no se pudo cerrar $id")
        }.apply { isDaemon = true }.start()
    }

    /** Cierra en Facebook el directo que creó la app (si lo hubo). */
    private fun cerrarDirectoFacebook() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val id = prefs.getString(CLAVE_FB_DIRECTO_ID, "").orEmpty().trim()
        val token = tokenYpagina().first
        if (id.isBlank() || token.isBlank()) return
        prefs.edit().putString(CLAVE_FB_DIRECTO_ID, "").apply()
        Thread {
            val cerrado = com.jfrmedia.switcher.studio.stream.FacebookLive
                .terminarDirecto(id, token)
            L.i("fb", if (cerrado) "directo $id cerrado" else "no se pudo cerrar el directo $id")
        }.apply { isDaemon = true }.start()
    }

    private fun tokenYpagina(): Pair<String, String> {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        // Para leer el directo se usa el token de la **Página** elegida; si no hay, el de usuario.
        val tokenPagina = prefs.getString(CLAVE_FB_PAGINA_TOKEN, "").orEmpty().trim()
        val tokenUsuario = prefs.getString(CLAVE_FB_TOKEN, "").orEmpty().trim()
        return (tokenPagina.ifBlank { tokenUsuario }) to
            prefs.getString(CLAVE_FB_PAGE_ID, "").orEmpty().trim()
    }

    /** Resuelve el video al aire: el de la clave o, si no vino, el que se busca en las Páginas. */
    private fun videoObjetivo(token: String, pageId: String): String {
        if (fbVideoId.isNotBlank()) return fbVideoId
        val hallado = com.jfrmedia.switcher.studio.stream.FacebookLive
            .videoEnVivo(pageId, token).orEmpty()
        fbVideoId = hallado
        return hallado
    }

    private fun consultarComentariosAhora() {
        if (fbOcupadoComentarios) return
        val (token, pageId) = tokenYpagina()
        if (token.isBlank() || pageId.isBlank()) return
        fbOcupadoComentarios = true
        Thread {
            var lista: List<com.jfrmedia.switcher.studio.stream.ComentarioFb> = emptyList()
            var error = ""
            try {
                val video = videoObjetivo(token, pageId)
                if (video.isBlank()) error = "sin directo al aire (todavía)"
                else lista = com.jfrmedia.switcher.studio.stream.FacebookLive
                    .comentarios(video, token, 6)
            } catch (t: Throwable) {
                error = t.message ?: "error"
            }
            runOnUiThread {
                fbOcupadoComentarios = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (error.isNotEmpty()) {
                    L.w("fb", "comentarios: $error")
                    if (error.contains("(#4)") || error.contains("limit", ignoreCase = true)) {
                        // Exceso de consultas: se olvida el video y se deja descansar 5 minutos.
                        fbVideoId = ""
                        fbHandler.removeCallbacks(fbTareaComentarios)
                        fbHandler.postDelayed(fbTareaComentarios, 300_000L)
                    } else if (error.contains("expired", ignoreCase = true) ||
                        error.contains("\"code\":190")
                    ) {
                        fbLeyendo = false
                    }
                    return@runOnUiThread
                }
                if (fbLeyendo) actualizarComentarios(lista)
            }
        }.apply { isDaemon = true }.start()
    }

    private fun consultarEspectadoresAhora() {
        if (fbOcupadoEspectadores) return
        val (token, pageId) = tokenYpagina()
        if (token.isBlank() || pageId.isBlank()) return
        fbOcupadoEspectadores = true
        Thread {
            var numero: Int? = null
            var error = ""
            try {
                val video = videoObjetivo(token, pageId)
                if (video.isNotBlank()) {
                    numero = com.jfrmedia.switcher.studio.stream.FacebookLive
                        .espectadores(video, token)
                }
            } catch (t: Throwable) {
                error = t.message ?: "error"
            }
            runOnUiThread {
                fbOcupadoEspectadores = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (error.isNotEmpty()) {
                    L.w("fb", "espectadores: $error")
                    if (error.contains("(#4)") || error.contains("limit", ignoreCase = true)) {
                        fbVideoId = ""
                    }
                    return@runOnUiThread
                }
                if (numero != null && fbLeyendo) {
                    fbEspectadores = numero.toString()
                    panelTcg?.comentarios(fbEspectadores, fbLista.toList())
                    L.i("fb", "$numero viendo")
                }
            }
        }.apply { isDaemon = true }.start()
    }

    /**
     * Suma al panel los comentarios de Facebook que todavía no estaban (vienen del más nuevo al
     * más viejo) y respeta los de YouTube que ya estén ahí.
     */
    private fun actualizarComentarios(
        lista: List<com.jfrmedia.switcher.studio.stream.ComentarioFb>
    ) {
        if (isFinishing || isDestroyed) return
        val cambio = agregarAlPanel("Facebook", lista)
        if (cambio) L.i("fb", "comentarios en el panel: ${fbLista.size}")
    }

    // ---- Chat de YouTube: se lee del mismo panel, etiquetado como «YT» ----

    /** Id del chat de la transmisión de YouTube (vacío si no hay). */
    private var ytChatId = ""

    private var ytOcupado = false

    private val ytTareaComentarios = object : Runnable {
        override fun run() {
            if (!fbLeyendo) return
            consultarChatYouTube()
            fbHandler.postDelayed(this, 30_000L)
        }
    }

    /** Una vuelta al chat de YouTube (en su propio hilo corto). */
    private fun consultarChatYouTube() {
        val chat = ytChatId
        if (chat.isBlank() || ytOcupado) return
        ytOcupado = true
        Thread {
            var lista: List<com.jfrmedia.switcher.studio.stream.ComentarioFb> = emptyList()
            try {
                lista = com.jfrmedia.switcher.studio.stream.YouTubeLive
                    .comentariosChat(this, chat)
            } catch (t: Throwable) {
                L.w("yt", "chat: ${t.message}")
            }
            runOnUiThread {
                ytOcupado = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (lista.isNotEmpty() && fbLeyendo) {
                    if (agregarAlPanel("YouTube", lista)) {
                        L.i("yt", "comentarios de YouTube: ${lista.size}")
                    }
                }
            }
        }.apply { isDaemon = true }.start()
    }

    /** Empieza a leer el chat de YouTube (se llama al crear la transmisión). */
    private fun iniciarChatYouTube(chat: String) {
        ytChatId = chat
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(CLAVE_YT_CHAT_ID, chat)
            .apply()
        fbHandler.removeCallbacks(ytTareaComentarios)
        if (chat.isNotBlank()) fbHandler.postDelayed(ytTareaComentarios, 4_000L)
    }

    /** Suma comentarios de una plataforma al panel; devuelve si hubo algo nuevo. */    private fun agregarAlPanel(
        origen: String,
        lista: List<com.jfrmedia.switcher.studio.stream.ComentarioFb>
    ): Boolean {
        if (lista.isEmpty()) return false
        var cambio = false
        // Al revés para que el más nuevo quede arriba al insertar cada uno al principio.
        lista.reversed().forEach { comentario ->
            val item = com.jfrmedia.switcher.studio.tcg.ComentarioPanel(
                origen, comentario.autor, comentario.texto
            )
            val repetido = fbLista.any {
                it.origen == item.origen && it.texto == item.texto
            }
            if (!repetido) {
                fbLista.add(0, item)
                cambio = true
            }
        }
        while (fbLista.size > 8) fbLista.removeAt(fbLista.lastIndex)
        if (cambio) panelTcg?.comentarios(fbEspectadores, fbLista.toList())
        return cambio
    }

    /**
     * Consulta puntual (botón «Probar»): lee una vez espectadores y comentarios y llena el panel.
     */
    private fun consultarFacebook(manual: Boolean, aviso: ((String) -> Unit)? = null) {
        if (fbOcupadoPrueba) return
        val (token, pageId) = tokenYpagina()
        if (token.isBlank() || pageId.isBlank()) {
            val mensaje = "Falta el token de la Página o el ID de la Página"
            if (manual) {
                toast(mensaje)
                aviso?.invoke(mensaje)
            }
            return
        }
        fbOcupadoPrueba = true
        Thread {
            var espectadores: Int? = null
            var lista: List<com.jfrmedia.switcher.studio.stream.ComentarioFb> = emptyList()
            var error = ""
            try {
                val video = com.jfrmedia.switcher.studio.stream.FacebookLive
                    .videoEnVivo(pageId, token).orEmpty()
                if (video.isBlank()) {
                    error = "la página no tiene un video en vivo"
                } else {
                    fbVideoId = video
                    espectadores = com.jfrmedia.switcher.studio.stream.FacebookLive
                        .espectadores(video, token)
                    lista = com.jfrmedia.switcher.studio.stream.FacebookLive
                        .comentarios(video, token, 4)
                }
            } catch (t: Throwable) {
                error = t.message ?: "error de conexión"
            }
            runOnUiThread {
                fbOcupadoPrueba = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (error.isNotEmpty()) {
                    L.w("fb", "prueba: $error")
                    if (manual) {
                        val claro = cuandoFallaFacebook(error)
                        toast("Facebook: $claro")
                        aviso?.invoke("ERROR · $claro\n\n($error)")
                    }
                    return@runOnUiThread
                }
                fbEspectadores = espectadores?.toString().orEmpty()
                fbLista.clear()
                lista.forEach { fbLista.add(com.jfrmedia.switcher.studio.tcg.ComentarioPanel("Facebook", it.autor, it.texto)) }
                panelTcg?.comentarios(fbEspectadores, fbLista.toList())
                L.i("fb", "prueba: ${espectadores ?: "sin dato"} viendo · ${lista.size} comentarios")
                if (manual) {
                    val yo = com.jfrmedia.switcher.studio.stream.FacebookLive.quienSoy(token)
                    val quien = when {
                        yo == null -> "no se pudo leer /me"
                        yo.first == pageId -> "token de la Página «${yo.second}»"
                        else ->
                            "token de «${yo.second}» (id ${yo.first}), distinto del id de Página $pageId"
                    }
                    val resumen = "OK · ${espectadores?.toString() ?: "sin dato de espectadores"} " +
                        "viendo · ${lista.size} comentarios"
                    toast("Facebook $resumen")
                    aviso?.invoke("$resumen\n\n$quien")
                }
            }
        }.apply { isDaemon = true }.start()
    }

    /**
     * Traduce los errores de Facebook a algo que se pueda accionar. El `#200` es el más común y el que
     * más confunde: o el token es de usuario en vez de Página, o le falta `pages_read_engagement`.
     */
    private fun cuandoFallaFacebook(error: String): String = when {
        // Un token de Página no tiene la lista de Páginas: esa solo existe en el token de usuario.
        error.contains("nonexisting field (accounts)") ->
            "Ese token es de Página. Para traer la lista de tus Páginas hace falta uno **de usuario**: " +
                "en el Graph API Explorer deja «User or Page» en «Get User Access Token», agrega " +
                "pages_show_list y genera. (La app guarda sola el de la Página que elijas.)"

        error.contains("#200") || error.contains("Missing Permissions", ignoreCase = true) ->
            "El token no tiene permiso de Página. En el Graph API Explorer elige la Página en «User or " +
                "Page» (no tu usuario), agrega pages_read_engagement y genera el token otra vez."

        error.contains("Invalid OAuth", ignoreCase = true) ->
            "El token está mal copiado o ya venció. Copia el que empieza con EAA… completo, sin espacios."

        error.contains("Session has expired", ignoreCase = true) || error.contains("\"code\":190") ->
            "El token venció (los del Graph API Explorer duran ~1 hora). Saca uno de larga duración: " +
                "en el Debug de Facebook toca «Extend Access Token» y con ese pide me/accounts; el " +
                "token de la Página que sale ahí ya no vence."

        error.contains("Unsupported get request", ignoreCase = true) ->
            "El ID de la Página no es el correcto."

        error.contains("no tiene un video en vivo") ->
            "La Página no tiene un video transmitiendo ahora mismo: esta consulta solo funciona al aire."

        error.contains("(#4)") || error.contains("request limit", ignoreCase = true) ->
            "Facebook cortó las consultas por exceso (límite de la aplicación). Se afloja solo: " +
                "espera unos minutos y vuelve a probar, sin apretar «Probar» muchas veces seguidas."

        else -> error
    }

    // ---- acciones del modo: las usan las tarjetas de jugador y los botones de abajo de la cámara 4

    /** Cierra la partida a favor de uno (1), empate (0) o a favor del otro (2). */
    private fun registrarResultadoTcg(resultado: Int) {
        val uno = estadoTcg.uno
        val dos = estadoTcg.dos
        val vacias = booleanArrayOf(true, true, true)
        val nuevo = when (resultado) {
            1 -> estadoTcg.copy(
                serieUno = estadoTcg.serieUno + 1,
                uno = uno.copy(ganadas = uno.ganadas + 1, debilitados = 0, chips = vacias),
                dos = dos.copy(perdidas = dos.perdidas + 1, debilitados = 0, chips = vacias),
                turno = 0,
            )
            2 -> estadoTcg.copy(
                serieDos = estadoTcg.serieDos + 1,
                uno = uno.copy(perdidas = uno.perdidas + 1, debilitados = 0, chips = vacias),
                dos = dos.copy(ganadas = dos.ganadas + 1, debilitados = 0, chips = vacias),
                turno = 0,
            )

            else -> estadoTcg.copy(
                uno = uno.copy(empatadas = uno.empatadas + 1, debilitados = 0, chips = vacias),
                dos = dos.copy(empatadas = dos.empatadas + 1, debilitados = 0, chips = vacias),
            )
        }
        // El récord se guarda en los jugadores inscritos del evento.
        val roster = estadoTcg.roster.map { j ->
            when (j.nombre) {
                uno.nombre -> when (resultado) {
                    1 -> j.copy(ganadas = j.ganadas + 1)
                    0 -> j.copy(empatadas = j.empatadas + 1)
                    else -> j.copy(perdidas = j.perdidas + 1)
                }

                dos.nombre -> when (resultado) {
                    2 -> j.copy(ganadas = j.ganadas + 1)
                    0 -> j.copy(empatadas = j.empatadas + 1)
                    else -> j.copy(perdidas = j.perdidas + 1)
                }

                else -> j
            }
        }
        cambiarEstadoTcg(nuevo.copy(roster = roster))
        toast(
            when (resultado) {
                1 -> "Partida para ${uno.nombre} · serie ${nuevo.serieUno}-${nuevo.serieDos}"
                2 -> "Partida para ${dos.nombre} · serie ${nuevo.serieUno}-${nuevo.serieDos}"
                else -> "Empate · la serie queda ${nuevo.serieUno}-${nuevo.serieDos}"
            }
        )
    }

    private fun nuevaPartidaTcg() {
        cambiarEstadoTcg(
            estadoTcg.copy(
                turno = 0,
                uno = estadoTcg.uno.copy(debilitados = 0, chips = booleanArrayOf(true, true, true)),
                dos = estadoTcg.dos.copy(debilitados = 0, chips = booleanArrayOf(true, true, true)),
            )
        )
        // Cada partida nueva arranca el cronómetro de 45 minutos desde cero.
        reiniciarCronometroTcg()
        toast("Partida nueva: pokebolas, chips y cronómetro en cero")
    }

    private fun reiniciarMarcadorTcg() {
        cambiarEstadoTcg(
            estadoTcg.copy(
                serieUno = 0,
                serieDos = 0,
                turno = 0,
                uno = estadoTcg.uno.copy(debilitados = 0, chips = booleanArrayOf(true, true, true)),
                dos = estadoTcg.dos.copy(debilitados = 0, chips = booleanArrayOf(true, true, true)),
            )
        )
        toast("Marcador de la serie en 0 - 0")
    }

    private fun cambiarPaletaTcg() {
        val siguiente = if (estadoTcg.paleta >= Banners.PALETAS.size) 1 else estadoTcg.paleta + 1
        cambiarEstadoTcg(estadoTcg.copy(paleta = siguiente))
        toast("Banner $siguiente de ${Banners.PALETAS.size}")
    }

    private fun montarPanelTcg() {
        // Si ya había un panel (porque el modo se está volviendo a armar después de girar la
        // tablet), se quita antes de crear el nuevo: si no, quedarían dos columnas de botones.
        panelTcg?.let { viejo ->
            (viejo.vista.parent as? ViewGroup)?.removeView(viejo.vista)
            (viejo.vistaHerramientas.parent as? ViewGroup)?.removeView(viejo.vistaHerramientas)
        }
        val panel = PanelTcg(this, object : PanelTcg.Escucha {
            override fun onEditarNombre(lado: Int) = editarNombreTcg(lado)

            override fun onCambiarDebilitados(lado: Int, delta: Int) {
                val j = if (lado == 0) estadoTcg.uno else estadoTcg.dos
                val nuevo = (j.debilitados + delta).coerceIn(0, 6)
                cambiarEstadoTcg(
                    if (lado == 0) estadoTcg.copy(uno = j.copy(debilitados = nuevo))
                    else estadoTcg.copy(dos = j.copy(debilitados = nuevo))
                )
            }

            override fun onCambiarChip(lado: Int, chip: Int) {
                if (estadoTcg.turno != lado) return
                val j = if (lado == 0) estadoTcg.uno else estadoTcg.dos
                val fichas = j.chips.copyOf()
                fichas[chip] = !fichas[chip]
                cambiarEstadoTcg(
                    if (lado == 0) estadoTcg.copy(uno = j.copy(chips = fichas))
                    else estadoTcg.copy(dos = j.copy(chips = fichas))
                )
            }

            override fun onPasarTurno() {
                // Los chips se reinician y el control pasa al otro lado.
                cambiarEstadoTcg(
                    estadoTcg.copy(
                        turno = 1 - estadoTcg.turno,
                        uno = estadoTcg.uno.copy(chips = booleanArrayOf(true, true, true)),
                        dos = estadoTcg.dos.copy(chips = booleanArrayOf(true, true, true)),
                    )
                )
            }

            override fun onResultado(resultado: Int) = registrarResultadoTcg(resultado)

            override fun onNuevaPartida() = nuevaPartidaTcg()

            override fun onReiniciarMarcador() = reiniciarMarcadorTcg()

            override fun onCambiarPaleta() = cambiarPaletaTcg()

            override fun onAlternarAmbiente() = alternarAmbienteTcg()

            override fun onAbrirEvento() = abrirDialogoEvento()

            /** Conectar otra cámara sin salir del modo (antes había que ir a Modo Studio por el QR). */
            override fun onConectarCamara() = showPairing()

            /** Elegir el mazo del jugador de la lista del formato (Limitless). */
            override fun onElegirMazo(lado: Int) = abrirSelectorMazo(lado)
        }, { mazoId -> com.jfrmedia.switcher.studio.tcg.Mazos.iconos(this, mazoId) })
        panel.refrescar(estadoTcg)
        panelTcg = panel
        b.slotControles.addView(panel.vista, 0)
        // Espectadores y comentarios del stream en el panel de la derecha.
        iniciarConsultasFacebook()
    }

    // ------------------------------------------------ mazos del formato (Limitless)

    /**
     * Baja la lista de los mazos más usados (y sus iconos) en un hilo aparte y avisa al terminar.
     * Se llama al entrar al modo (una vez al día) y desde el selector de mazos.
     */
    private fun actualizarMazos(despues: ((Boolean) -> Unit)? = null) {
        Thread {
            val lista = com.jfrmedia.switcher.studio.tcg.Mazos.actualizar(this)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val ok = lista.isNotEmpty()
                if (ok) {
                    dibujarBannerTcg()
                    panelTcg?.refrescar(estadoTcg)
                }
                despues?.invoke(ok)
            }
        }.apply { isDaemon = true }.start()
    }

    /** Selector del mazo de un jugador: la lista del formato con icono, nombre y % de uso. */
    private fun abrirSelectorMazo(lado: Int, reintentando: Boolean = false) {
        val jugador = if (lado == 0) estadoTcg.uno else estadoTcg.dos
        val lista = com.jfrmedia.switcher.studio.tcg.Mazos.guardados(this)
        if (lista.isEmpty()) {
            if (reintentando) {
                toast("No se pudo bajar la lista de mazos (revisa la conexión)")
                return
            }
            toast("Bajando la lista de mazos…")
            actualizarMazos { ok ->
                if (ok) abrirSelectorMazo(lado, reintentando = true)
                else toast("No se pudo bajar la lista de mazos (revisa la conexión)")
            }
            return
        }

        val cuando = com.jfrmedia.switcher.studio.tcg.Mazos.cuando(this)
        val fecha = if (cuando > 0L) {
            java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(cuando))
        } else {
            "sin fecha"
        }

        val columna = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12.dp, 4.dp, 12.dp, 0)
        }
        columna.addView(TextView(this).apply {
            text = "Top del formato + ${com.jfrmedia.switcher.studio.tcg.Mazos.extras(this@StudioActivity).size} " +
                "mazos de torneo · actualizado $fecha"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(ContextCompat.getColor(context, R.color.text_dim))
            setPadding(6.dp, 0, 6.dp, 6.dp)
        })

        // Buscador: con ~80 mazos en la lista, escribir tres letras es más rápido que bajar con el dedo.
        val campo = EditText(this).apply {
            hint = "Buscar mazo (por ejemplo: Mega, Zoroark, Igglybuff)…"
            setSingleLine()
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        }
        columna.addView(campo)
        val filas = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        columna.addView(filas)

        val dialogo = AlertDialog.Builder(this)
            .setTitle("Mazo de ${jugador.nombre}")
            .setView(ScrollView(this).apply { addView(columna) })
            .setNegativeButton("Quitar", null)
            .setNeutralButton("Actualizar", null)
            .create()

        fun asignar(mazo: com.jfrmedia.switcher.studio.tcg.Mazo?) {
            val nuevo = if (mazo == null) jugador.copy(mazo = "", mazoId = "")
            else jugador.copy(mazo = mazo.nombre, mazoId = mazo.id)
            cambiarEstadoTcg(if (lado == 0) estadoTcg.copy(uno = nuevo) else estadoTcg.copy(dos = nuevo))
            runCatching { dialogo.dismiss() }
            toast(if (mazo == null) "Mazo quitado" else "Mazo: ${mazo.nombre}")
        }

        fun pintar(filtro: String) {
            filas.removeAllViews()
            val buscar = filtro.trim()
            var cuantos = 0
            lista.forEach { mazo ->
                if (buscar.isNotEmpty() && !mazo.nombre.contains(buscar, ignoreCase = true)) return@forEach
                filas.addView(filaMazoDialogo(mazo, mazo.id == jugador.mazoId) { asignar(mazo) })
                cuantos++
            }
            if (cuantos == 0) {
                filas.addView(TextView(this).apply {
                    text = "Ningún mazo se llama así"
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTextColor(ContextCompat.getColor(context, R.color.text_dim))
                    setPadding(8.dp, 10.dp, 8.dp, 10.dp)
                })
            }
        }
        campo.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) =
                pintar(s?.toString().orEmpty())
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        pintar("")
        dialogo.setOnShowListener {
            // Con el teclado abierto, la ventana se encoge para que la lista siga viéndose (si no, el
            // teclado tapaba la mitad de los mazos en la tablet).
            runCatching {
                dialogo.window?.setSoftInputMode(
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                )
            }
            dialogo.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { asignar(null) }
            dialogo.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                runCatching { dialogo.dismiss() }
                toast("Actualizando la lista…")
                actualizarMazos { ok ->
                    if (ok) abrirSelectorMazo(lado)
                    else toast("No se pudo actualizar: se queda la lista anterior")
                }
            }
        }
        dialogo.show()
    }

    /** Una fila del selector de mazos: icono, nombre, uso y resaltado si es el actual. */
    private fun filaMazoDialogo(
        mazo: com.jfrmedia.switcher.studio.tcg.Mazo,
        actual: Boolean,
        alTocar: () -> Unit,
    ): View {
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8.dp, 5.dp, 8.dp, 5.dp)
            setOnClickListener { alTocar() }
            if (actual) {
                background = ContextCompat.getDrawable(this@StudioActivity, R.drawable.bg_pill_accent)
            }
        }
        val iconosMazo = com.jfrmedia.switcher.studio.tcg.Mazos.iconos(this, mazo.id)
        iconosMazo.filter { !it.isRecycled }.take(2).forEach { bmp ->
            fila.addView(
                ImageView(this).apply {
                    setImageBitmap(bmp)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                },
                LinearLayout.LayoutParams(34.dp, 34.dp).apply { rightMargin = 6.dp }
            )
        }
        fila.addView(
            TextView(this).apply {
                text = mazo.nombre
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(
                    ContextCompat.getColor(
                        this@StudioActivity,
                        if (actual) R.color.bg_root else R.color.text_primary
                    )
                )
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        if (mazo.uso.isNotBlank()) {
            fila.addView(TextView(this).apply {
                text = mazo.uso
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(ContextCompat.getColor(this@StudioActivity, R.color.accent))
                setPadding(10.dp, 0, 0, 0)
            })
        }
        return fila
    }

    /** Escribe el nombre del jugador. */
    private fun editarNombreTcg(lado: Int) {        val actual = if (lado == 0) estadoTcg.uno else estadoTcg.dos
        val campo = EditText(this).apply {
            setText(actual.nombre)
            setSingleLine()
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        }
        val caja = android.widget.FrameLayout(this).apply {
            setPadding(18.dp, 8.dp, 18.dp, 0)
            addView(campo)
        }
        AlertDialog.Builder(this)
            .setTitle("Nombre del jugador ${lado + 1}")
            .setView(caja)
            .setPositiveButton("Guardar") { _, _ ->
                val nombre = campo.text.toString().trim().ifEmpty { "Jugador ${lado + 1}" }
                cambiarEstadoTcg(
                    if (lado == 0) estadoTcg.copy(uno = actual.copy(nombre = nombre))
                    else estadoTcg.copy(dos = actual.copy(nombre = nombre))
                )
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Nombre del evento (va en la franja del banner y se graba). */
    private fun editarEventoTcg() {
        val campo = EditText(this).apply {
            setText(estadoTcg.evento)
            setSingleLine()
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        }
        val caja = android.widget.FrameLayout(this).apply {
            setPadding(18.dp, 8.dp, 18.dp, 0)
            addView(campo)
        }
        AlertDialog.Builder(this)
            .setTitle("Nombre del evento")
            .setView(caja)
            .setPositiveButton("Guardar") { _, _ ->
                val nombre = campo.text.toString().trim().ifEmpty { "LIGA JFR" }
                cambiarEstadoTcg(estadoTcg.copy(evento = nombre))
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Aplica el modo al arrancar: 16:9 fijo, sin rotación y sin las herramientas que no van. */
    private fun aplicarModoTcg() {
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        b.btnPlantillas.visibility = View.GONE
        b.btnTransiciones.visibility = View.GONE
        b.btnOrientacion.visibility = View.GONE
        b.btnPhoto.visibility = View.GONE
        b.scrollControles.visibility = View.GONE
        // Fuera overlays y medios: en este modo solo van las 4 cámaras.
        etiquetaOverlays?.visibility = View.GONE
        overlayContainer.visibility = View.GONE
        etiquetaMedios?.visibility = View.GONE
        if (::mediosContainer.isInitialized) mediosContainer.visibility = View.GONE
        if (::bibliotecaContainer.isInitialized) bibliotecaContainer.visibility = View.GONE
        // Columna de cámaras angosta y monitor grande, como el diseño.
        (b.panelFuentes.layoutParams as? LinearLayout.LayoutParams)?.let { it.weight = 0.195f }
        (b.panelPrograma.layoutParams as? LinearLayout.LayoutParams)?.let { it.weight = 0.81f }
        b.llGrid.requestLayout()
        b.cuerpo.requestLayout()
        b.cajaVideo.visibility = View.GONE
        b.panelAjuste.visibility = View.GONE
        zonaControles?.let { z ->
            (z.parent as? ViewGroup)?.removeView(z)
            z.visibility = View.GONE
        }
        dibujarBannerTcg()
        montarPanelTcg()
        // Lista de los mazos más usados del formato: se refresca al entrar al modo, una vez al día.
        // También si la caché de iconos es de una versión anterior (por ejemplo al pasar a dos iconos).
        if (com.jfrmedia.switcher.studio.tcg.Mazos.vieja(this) ||
            com.jfrmedia.switcher.studio.tcg.Mazos.esquemaViejo(this)
        ) {
            actualizarMazos()
        }
        // Al ocultar los controles viejos el lienzo debe volver a medirse (si no, queda chico).
        // Monitor con tamaño EXACTO y la columna de herramientas a su derecha.
        // (El contenedor del monitor no admite "ancho 0 + peso": mide 0 y desaparece.)
        b.cuerpo.postDelayed({ runCatching { acomodarMonitorTcg() } }, 700)
        // La escena del modo: mesa al fondo, PiPs de los jugadores y el banner encima.
        b.cuerpo.postDelayed({ runCatching { aplicarEscenaTcg() } }, 300)
        // Si una cámara llega después, se le devuelve su hueco (y su video).
        listOf(1500L, 3000L, 6000L).forEach { espera ->
            b.cuerpo.postDelayed({
                runCatching {
                    aplicarRectangulosTcg()
                    hub.all().forEach { s -> s.videoTrack?.let { t -> runCatching { adjuntar(s.slot, t) } } }
                }
            }, espera)
        }
    }

    /**
     * Monitor del modo con tamaño EXACTO y la columna de herramientas a su derecha.
     *
     * Se puede llamar varias veces: al entrar al modo, al girar la tablet y en los reintentos. Si ya
     * hay una fila armada, la desarma y la vuelve a armar con las medidas nuevas.
     *
     * **Por qué existe este reintento:** antes era un `postDelayed` de una sola vez. Si la app se
     * abría con la tablet **vertical**, medía la pantalla en vertical (monitor de 678×381 en vez de
     * 1170×658), el modo entraba un momento en Modo Studio y, al girar a horizontal, el diseño se
     * restauraba sin volver a aplicar el modo: quedaba «desarmado» hasta salir a Modo Studio y
     * regresar (Jorge, 21/11/2026). En este modo la tablet siempre va horizontal, así que medir en
     * vertical nunca es válido: se reintenta hasta que las medidas sirvan.
     */
    private fun acomodarMonitorTcg(intento: Int = 0) {
        if (!modoTcg) return
        val verticalAhora =
            resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val anchoTotal = b.panelPrograma.width - 8.dp
        val altoTotal = b.panelPrograma.height - b.cabeceraPrograma.height -
            b.slotControles.height - 8.dp
        val anchoHerramientas = 208.dp
        var anchoC = anchoTotal - anchoHerramientas
        var altoC = anchoC * 9 / 16
        if (altoC > altoTotal) {
            altoC = altoTotal
            anchoC = altoC * 16 / 9
        }
        L.i("tcg", "monitor ancho=$anchoC alto=$altoC (total $anchoTotal x $altoTotal) intento=$intento")
        if (verticalAhora || anchoC <= 320 || altoC <= 220) {
            if (intento < 16) {
                b.cuerpo.postDelayed({ runCatching { acomodarMonitorTcg(intento + 1) } }, 250)
            }
            return
        }
        val padre = b.panelPrograma
        // Desarmar la fila anterior (si la hay) para poder reacomodar con las medidas nuevas.
        var indice = -1
        val contenedorActual = b.canvasHost.parent
        if (contenedorActual is LinearLayout && contenedorActual !== padre) {
            indice = padre.indexOfChild(contenedorActual)
            panelTcg?.let { p ->
                if (p.vistaHerramientas.parent === contenedorActual) {
                    contenedorActual.removeView(p.vistaHerramientas)
                }
            }
            contenedorActual.removeView(b.canvasHost)
            padre.removeView(contenedorActual)
        }
        if (indice < 0) indice = padre.indexOfChild(b.canvasHost)
        if (b.canvasHost.parent === padre) padre.removeView(b.canvasHost)
        val fila = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fila.addView(b.canvasHost, LinearLayout.LayoutParams(anchoC, altoC))
        panelTcg?.let { p ->
            fila.addView(p.vistaHerramientas, LinearLayout.LayoutParams(anchoHerramientas, altoC))
        }
        padre.addView(
            fila,
            indice.coerceAtLeast(0),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
    }

    /** Ventana del evento: nombre, jugadores inscritos con su récord y quién va en la mesa. */
    private fun abrirDialogoEvento() {
        val inscritos = estadoTcg.roster.toMutableList()
        if (inscritos.isEmpty()) {
            inscritos.add(JugadorEvento(estadoTcg.uno.nombre.ifBlank { "Jugador 1" }))
            inscritos.add(JugadorEvento(estadoTcg.dos.nombre.ifBlank { "Jugador 2" }))
        }

        val columna = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp, 4.dp, 20.dp, 0)
        }

        fun rotulo(t: String): TextView = TextView(this).apply {
            text = t
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(ContextCompat.getColor(context, R.color.text_dim))
            setPadding(0, 14.dp, 0, 4.dp)
        }

        columna.addView(rotulo("NOMBRE DEL EVENTO"))
        val campoEvento = EditText(this).apply {
            setText(estadoTcg.evento)
            setSingleLine()
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        }
        columna.addView(campoEvento)

        columna.addView(rotulo("JUGADORES INSCRITOS · RÉCORD V-E-D"))
        val lista = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        columna.addView(lista)

        val spUno = android.widget.Spinner(this)
        val spDos = android.widget.Spinner(this)

        fun refrescar() {
            lista.removeAllViews()
            inscritos.forEach { j ->
                val fila = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, 5.dp, 0, 5.dp)
                }
                fila.addView(
                    TextView(this).apply {
                        text = j.nombre
                        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
                        setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                )
                fila.addView(TextView(this).apply {
                    text = j.record
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTextColor(ContextCompat.getColor(context, R.color.accent))
                    setPadding(0, 0, 16.dp, 0)
                })
                fila.addView(TextView(this).apply {
                    text = "X"
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTextColor(ContextCompat.getColor(context, R.color.text_dim))
                    setPadding(8.dp, 4.dp, 8.dp, 4.dp)
                    setOnClickListener { inscritos.remove(j); refrescar() }
                })
                lista.addView(fila)
            }
            val nombres = inscritos.map { it.nombre }
            listOf(spUno, spDos).forEach { sp ->
                val adaptador = android.widget.ArrayAdapter(
                    this, android.R.layout.simple_spinner_item, nombres
                )
                adaptador.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                sp.adapter = adaptador
            }
            spUno.setSelection(nombres.indexOf(estadoTcg.uno.nombre).coerceAtLeast(0), false)
            spDos.setSelection(nombres.indexOf(estadoTcg.dos.nombre).coerceAtLeast(0), false)
        }
        refrescar()

        columna.addView(TextView(this).apply {
            text = "+ Agregar jugador"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(ContextCompat.getColor(context, R.color.accent))
            setPadding(0, 10.dp, 0, 2.dp)
            setOnClickListener {
                val campo = EditText(this@StudioActivity).apply {
                    setSingleLine()
                    setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                    hint = "Nombre del jugador"
                }
                AlertDialog.Builder(this@StudioActivity)
                    .setTitle("Jugador nuevo")
                    .setView(campo)
                    .setPositiveButton("Agregar") { _, _ ->
                        val n = campo.text.toString().trim()
                        if (n.isNotEmpty()) {
                            inscritos.add(JugadorEvento(n))
                            refrescar()
                        }
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
        })

        columna.addView(rotulo("MESA ACTUAL"))
        val filaMesa = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        filaMesa.addView(spUno, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        filaMesa.addView(TextView(this).apply {
            text = "vs"
            setTextColor(ContextCompat.getColor(context, R.color.text_dim))
            setPadding(10.dp, 0, 10.dp, 0)
        })
        filaMesa.addView(spDos, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        columna.addView(filaMesa)

        AlertDialog.Builder(this)
            .setTitle("Evento")
            .setView(android.widget.ScrollView(this).apply { addView(columna) })
            .setPositiveButton("Guardar") { _, _ ->
                val n1 = spUno.selectedItem?.toString() ?: estadoTcg.uno.nombre
                val n2 = spDos.selectedItem?.toString() ?: estadoTcg.dos.nombre
                val r1 = inscritos.firstOrNull { it.nombre == n1 } ?: JugadorEvento(n1)
                val r2 = inscritos.firstOrNull { it.nombre == n2 } ?: JugadorEvento(n2)
                if (inscritos.none { it.nombre == n1 }) inscritos.add(r1)
                if (inscritos.none { it.nombre == n2 }) inscritos.add(r2)
                cambiarEstadoTcg(
                    estadoTcg.copy(
                        evento = campoEvento.text.toString().trim().ifEmpty { "LIGA JFR" },
                        roster = inscritos.toList(),
                        uno = estadoTcg.uno.copy(
                            nombre = n1, ganadas = r1.ganadas, empatadas = r1.empatadas, perdidas = r1.perdidas
                        ),
                        dos = estadoTcg.dos.copy(
                            nombre = n2, ganadas = r2.ganadas, empatadas = r2.empatadas, perdidas = r2.perdidas
                        ),
                        serieUno = 0,
                        serieDos = 0,
                        turno = 0,
                    )
                )
                toast("Evento guardado · $n1 vs $n2")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
    /**
     * Qué cámara está al aire en el monitor del modo:
     * [VISTA_MESA] = composición de mesa (mesa + PiPs + banner), o la ranura de una cámara a pantalla
     * completa (Jugador 1 = 0, Jugador 2 = 1, Ambiente = 3 o la cámara de la tablet).
     */
    private var vistaTcg: Int = VISTA_MESA

    /**
     * Qué cámara hace de «ambiente» (la 4): la de la propia tablet si está encendida —se elige con
     * el botón de su tarjeta— y, si no, la cámara 4 conectada por WiFi.
     */
    private fun slotAmbienteTcg(): Int? = when {
        localCam.encendida -> LocalCam.SLOT
        hub.session(3) != null -> 3
        else -> null
    }

    /** ¿Esa cámara se puede poner al aire ahora mismo? */
    private fun camaraTcgDisponible(slot: Int): Boolean = when {
        slot == VISTA_MESA -> true
        slot == LocalCam.SLOT -> localCam.encendida
        else -> hub.session(slot) != null
    }

    /**
     * Pone **una cámara a pantalla completa** en el monitor del modo, o vuelve a la composición de
     * mesa con [VISTA_MESA] (mesa al fondo, los dos jugadores en PiP y el banner con el marcador).
     *
     * Así el operador puede cortar a la cara de un jugador o al ambiente sin quedarse con la ventanita
     * chica, igual que ya pasaba con la cámara general (Jorge, 21/11/2026).
     */
    private fun mostrarCamaraTcg(slot: Int): Boolean {
        if (!camaraTcgDisponible(slot)) {
            toast("Esa cámara no está conectada")
            return false
        }
        cambiarVistaTcg(slot)
        return true
    }

    /** Duración del fundido al cambiar de cámara en el modo (Jorge: 1.5 s, que se sienta suave). */
    private val FUNDIDO_TCG_MS = 1500

    /** Hay una foto de la pantalla en camino: evita dos cambios encimados si se toca muy rápido. */
    private var fundiendoTcg = false

    /**
     * Cambia la cámara al aire con un **fundido** en vez de un corte seco: se congela el cuadro que
     * está al aire (PixelCopy) y se retira encima del nuevo durante [FUNDIDO_TCG_MS].
     *
     * Es el mismo mecanismo que usa el Modo Studio para pasar de preview a programa
     * (`prepararVelo` + pedir cuadros mientras dura), no uno nuevo.
     */
    private fun cambiarVistaTcg(nueva: Int) {
        if (nueva == vistaTcg) return
        val vista = b.canvas
        if (fundiendoTcg || vista.width <= 0 || vista.height <= 0) return
        fundiendoTcg = true
        val foto = Bitmap.createBitmap(vista.width, vista.height, Bitmap.Config.ARGB_8888)
        android.view.PixelCopy.request(
            vista, foto,
            { resultado ->
                runCatching {
                    fundiendoTcg = false
                    // El velo se arma ANTES de cambiar la escena: si se pide el cuadro primero, el
                    // hilo de dibujo puede pintar la escena nueva sin el velo y se ve un parpadeo.
                    if (resultado == android.view.PixelCopy.SUCCESS) {
                        vista.prepararVelo(foto, CompositorView.Transicion.FUNDIDO, FUNDIDO_TCG_MS)
                    }
                    vistaTcg = nueva
                    aplicarEscenaTcg()
                    // El lienzo se dibuja bajo demanda: se le piden cuadros mientras dura el fundido.
                    val paso = object : Runnable {
                        override fun run() {
                            vista.requestRender()
                            if (vista.veloActivo) uiHandler.postDelayed(this, 16)
                        }
                    }
                    uiHandler.postDelayed(paso, 16)
                }
            },
            uiHandler
        )
    }

    /**
     * Escena del modo. Dos formas:
     *
     * - **Mesa** ([VISTA_MESA]): la mesa al fondo, las dos cámaras de jugador como PiP arriba y el
     *   banner encima de todo (así se graba igual que se ve).
     * - **Una cámara a pantalla completa**: sin PiPs y sin banner, para ver la cara del jugador o el
     *   ambiente. Antes solo se podía hacer con la cámara de ambiente (Jorge, 21/11/2026).
     */
    private fun aplicarEscenaTcg() {
        val escena = b.canvas.scene
        escena.cams().forEach { runCatching { quitarCamaraDelLienzo(it) } }
        // Si la cámara que estaba al aire ya no está (se desconectó o se apagó), vuelve la mesa.
        if (!camaraTcgDisponible(vistaTcg)) vistaTcg = VISTA_MESA
        val enMesa = vistaTcg == VISTA_MESA
        if (estadoTcg.generalActivo != !enMesa) {
            estadoTcg = estadoTcg.copy(generalActivo = !enMesa)
            guardarEstadoTcg()
        }

        val mesa = escena.addCam(2)
        val uno = escena.addCam(0)
        val dos = escena.addCam(1)
        // Jugadores verticales a los lados (de arriba a abajo) y la mesa horizontal al centro.
        colocarCamarasTcg(escena)

        if (enMesa) {
            uno?.visible = true
            dos?.visible = true
            mesa?.visible = true
        } else {
            // Pantalla completa: ni PiPs ni mesa. El banner sí se queda si es un jugador (abajo).
            uno?.visible = false
            dos?.visible = false
            mesa?.visible = false
            val alAire = escena.camAt(vistaTcg) ?: escena.addCam(vistaTcg)
            if (alAire != null) {
                alAire.rect.set(0f, 0f, 1f, 1f)
                alAire.visible = true
                escena.items.remove(alAire)
                escena.items.add(alAire)
            }
        }
        // El banner va donde toque (arriba con la mesa, abajo con un jugador al aire) y siempre
        // encima de las cámaras; las de los jugadores llevan marco del color del banner.
        actualizarIconosMazo()
        acomodarBannerTcg()
        actualizarMarcosTcg()
        dibujarChipsTcg()
        // Cronómetro de la partida debajo de la mesa y batería dentro de cada cámara.
        // Si no venía corriendo (por ejemplo al abrir la app con el modo ya puesto), arranca aquí.
        if (!cronoAndando) reiniciarCronometroTcg() else dibujarCronometroTcg()
        actualizarBateriasTcg()
        // El reloj que hace correr el cronómetro y refresca la batería cada segundo.
        runCatching {
            uiHandler.removeCallbacks(relojTcg)
            uiHandler.postDelayed(relojTcg, 1_000)
        }
        // Reenganchar el video de las cámaras que ya estaban conectadas (al rehacer la escena
        // se suelta la pista y sin esto el PiP quedaba en negro).
        escena.cams().forEach { c -> runCatching { engancharFuente(c.slot) } }
        hub.all().forEach { s -> s.videoTrack?.let { t -> runCatching { adjuntar(s.slot, t) } } }
        // Los celulares de los jugadores van verticales en este modo (la mesa y el ambiente no).
        aplicarOrientacionCamarasTcg()
        escena.selectedId = null   // sin marco de selección: el resaltado es del turno
        b.canvas.slotResaltado = if (estadoTcg.turno == 0) 0 else 1
        escena.touch()
        runCatching { b.canvas.requestRender() }
        runCatching { panelTcg?.refrescar(estadoTcg) }
    }

    /**
     * Botón «Cámara general» del panel (y toque de la tarjeta 4): pone el ambiente a pantalla completa
     * o, si ya hay una cámara al aire, vuelve a la mesa con el marcador.
     */
    private fun alternarAmbienteTcg() {
        if (vistaTcg != VISTA_MESA) {
            mostrarCamaraTcg(VISTA_MESA)
            return
        }
        val slot = slotAmbienteTcg()
        if (slot == null) {
            toast("Conecta la cámara de ambiente (o usa la de la tablet con «Tablet» en la tarjeta 4)")
            return
        }
        if (mostrarCamaraTcg(slot)) {
            toast(if (slot == LocalCam.SLOT) "Cámara general: tablet" else "Cámara general: celular 4")
        }
    }

    companion object {
        private const val PREFS = "jfr_studio"
        private const val CLAVE_TABLET_CAM = "tablet_cam"
        private const val CLAVE_FB_URL = "fb_url"
        private const val CLAVE_FB_CLAVE = "fb_clave"
        private const val CLAVE_FB_GRABAR = "fb_grabar"
        private const val CLAVE_FB_PAGE_ID = "fb_page_id"

        /** Token de la Página: solo para leer espectadores y comentarios (nunca se manda al canal). */
        private const val CLAVE_FB_TOKEN = "fb_token"

        /** Token **de usuario**: solo se usa para pedir la lista de Páginas (`me/accounts`). */
        private const val CLAVE_FB_USUARIO_TOKEN = "fb_usuario_token"

        /** Token de la Página elegida: con ese se crea el directo y se leen espectadores. */
        private const val CLAVE_FB_PAGINA_TOKEN = "fb_pagina_token"

        /** Nombre de la Página elegida, para mostrarlo en el cuadro. */
        private const val CLAVE_FB_PAGINA_NOMBRE = "fb_pagina_nombre"

        /** Directo creado por la app (para cerrarlo al detener la transmisión). */
        private const val CLAVE_FB_DIRECTO_ID = "fb_directo_id"

        /** Descripción que se manda al crear el directo (la que se ve en la Página). */
        private const val CLAVE_FB_DESCRIPCION = "fb_descripcion"

        /** Transmitir también a YouTube (multistream). */
        private const val CLAVE_YT_ACTIVO = "yt_activo"

        /** Transmisión de YouTube creada por la app, para cerrarla al terminar. */
        private const val CLAVE_YT_DIRECTO_ID = "yt_directo_id"

        /** Chat de la transmisión de YouTube (para leer sus comentarios). */
        private const val CLAVE_YT_CHAT_ID = "yt_chat_id"
        private const val CLAVE_PALETA = "paleta_colores"
        private const val FB_URL_POR_DEFECTO = "rtmps://live-api-s.facebook.com:443/rtmp/"
        private const val CLAVE_TABLET_FRONTAL = "tablet_cam_frontal"
        private const val CLAVE_VERTICAL = "vertical"
        private const val CLAVE_MEDIOS = "medios_recientes"

        /** Cuántos medios se recuerdan entre sesiones (los últimos que se usaron). */
        private const val MEDIOS_RECIENTES = 10
        private const val CLAVE_AUDIO_ID = "audio_id"
        private const val CLAVE_AUDIO_TIPO = "audio_tipo"
        private const val CLAVE_AUDIO_MON = "audio_mon"
        private const val CLAVE_AUDIO_MON_VOL = "audio_mon_vol"
        private const val CLAVE_EQ_ENT_0 = "eq_ent_0"
        private const val CLAVE_EQ_ENT_1 = "eq_ent_1"
        private const val CLAVE_EQ_ENT_2 = "eq_ent_2"
        private const val CLAVE_EQ_ENT_3 = "eq_ent_3"
        private const val CLAVE_EQ_ENT_4 = "eq_ent_4"
        private const val CLAVE_EQ_SAL_0 = "eq_sal_0"
        private const val CLAVE_EQ_SAL_1 = "eq_sal_1"
        private const val CLAVE_EQ_SAL_2 = "eq_sal_2"
        private const val CLAVE_EQ_SAL_3 = "eq_sal_3"
        private const val CLAVE_EQ_SAL_4 = "eq_sal_4"
        private const val CLAVE_AUDIO_DB = "audio_db"
        private const val CLAVE_AUDIO_MUDO = "audio_mudo"
        private const val CLAVE_MODO = "modo_sesion"
        private const val CLAVE_REC_ALTO = "rec_alto"
        private const val CLAVE_REC_KBPS = "rec_kbps"
        private const val CLAVE_STREAM_ALTO = "stream_alto"
        private const val CLAVE_STREAM_KBPS = "stream_kbps"
        private const val CLAVE_AUTO_KBPS = "auto_kbps"
        private const val CLAVE_REC_AUDIO = "rec_audio"
        private const val CLAVE_PRESET = "preset"
        private const val CLAVE_TRANSICION = "transicion"
        private const val CLAVE_TRANSICION_MS = "transicion_ms"
        private const val CLAVE_MODO_TCG = "modo_tcg"
        private const val CLAVE_TCG_ESTADO = "tcg_estado"

        /** Valor de [vistaTcg] para la composición de mesa (mesa + PiPs de jugadores + banner). */
        private const val VISTA_MESA = -1
    }
}


