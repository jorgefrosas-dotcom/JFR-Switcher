package com.jfrmedia.switcher.cam

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.webrtc.RendererCommon
import com.jfrmedia.switcher.cam.databinding.ActivityCameraBinding
import com.jfrmedia.switcher.core.media.VideoPresets
import com.jfrmedia.switcher.core.net.QrLink
import com.jfrmedia.switcher.core.proto.Proto
import com.jfrmedia.switcher.core.upd.Actualizaciones
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

/**
 * Pantalla única de la app de cámara (celular en horizontal).
 *
 * Solo pinta el estado: toda la lógica de red y de WebRTC vive en [CamEngine].
 */
class CameraActivity : AppCompatActivity(), CamEngine.Escucha {

    private lateinit var binding: ActivityCameraBinding
    private lateinit var motor: CamEngine
    private lateinit var prefs: SharedPreferences

    private var permisoCamara = false

    /** Si el usuario pulsó Conectar sin permiso, se reintenta al concederlo. */
    private var conectarAlConceder = false

    private val solicitudCamara =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { concedido ->
            permisoCamara = concedido
            if (concedido) {
                if (conectarAlConceder) {
                    conectarAlConceder = false
                    conectar()
                }
            } else {
                conectarAlConceder = false
                mostrarEstado(EstadoEnlace.ERROR, getString(R.string.sin_permiso_camara))
                Toast.makeText(this, R.string.permiso_camara_explicacion, Toast.LENGTH_LONG).show()
            }
        }

    /**
     * Lector del QR de emparejamiento. La tablet codifica `jfr://IP:PUERTO`, así que se copian
     * los dos: el campo queda como `IP:PUERTO` (el puerto puede no ser el 8787 si en la tablet
     * estaba ocupado) y se conecta con ese puerto.
     */
    private val escanearQr =
        registerForActivityResult(ScanContract()) { resultado ->
            val contenido = resultado.contents?.trim().orEmpty()
            if (contenido.isEmpty()) return@registerForActivityResult
            val ip = QrLink.ipDe(contenido)
            if (ip == null) {
                Toast.makeText(this, R.string.qr_ajeno, Toast.LENGTH_LONG).show()
                return@registerForActivityResult
            }
            val puerto = QrLink.puertoDe(contenido)
            val destino = if (puerto != null) "$ip:$puerto" else ip
            binding.campoIp.setText(destino)
            prefs.edit().putString(CLAVE_IP, destino).apply()
            if (puerto != null && puerto != Proto.DEFAULT_PORT) {
                Toast.makeText(this, getString(R.string.qr_puerto, puerto), Toast.LENGTH_LONG).show()
            }
            conectar()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Android 15+ dibuja de borde a borde: sin esto la UI queda debajo de las barras.
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val barras = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(barras.left, barras.top, barras.right, barras.bottom)
            insets
        }

        // El celular queda montado como cámara: la pantalla no debe apagarse.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        motor = CamEngine(this, this)
        motor.adjuntarPreview(binding.preview)
        // FIT = se ve el cuadro completo de la cámara (sin recortes); el resto se ve en negro.
        binding.preview.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)

        prepararControles()
        mostrarAjustes(motor.ajustes)
        mostrarEstado(EstadoEnlace.SIN_CONECTAR, "")
        consultarPermiso()

        binding.campoIp.setText(prefs.getString(CLAVE_IP, ""))

        // Versión a la vista (en el rótulo) y aviso si hay una nueva. Tocar el rótulo busca a mano.
        binding.txtMarca.text = "JFR Camera · v${versionInstalada()}"
        binding.txtMarca.setOnClickListener { buscarActualizacion(manual = true) }
        buscarActualizacion(manual = false)
    }

    // ------------------------------------------------------- versión y actualizaciones

    private fun versionInstalada(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName
    }.getOrNull() ?: "?"

    /** Consulta GitHub; si hay una versión más nueva, avisa. [manual] = lo pidió el usuario. */
    private fun buscarActualizacion(manual: Boolean) {
        val instalada = versionInstalada()
        if (manual) Toast.makeText(this, "Buscando actualización…", Toast.LENGTH_SHORT).show()
        Thread {
            val ultima = Actualizaciones.ultima()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when {
                    ultima == null -> if (manual) Toast.makeText(
                        this, "No se pudo consultar: revisa la conexión", Toast.LENGTH_LONG
                    ).show()

                    !Actualizaciones.hayNueva(instalada, ultima.version) -> if (manual) Toast.makeText(
                        this, "Estás en la última versión ($instalada)", Toast.LENGTH_LONG
                    ).show()

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
            setTextColor(ContextCompat.getColor(context, R.color.texto_principal))
            val pad = (18 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        AlertDialog.Builder(this)
            .setTitle("Versión ${ultima.version} disponible")
            .setView(android.widget.ScrollView(this).apply { addView(vista) })
            .setPositiveButton("Descargar") { _, _ -> abrirEnNavegador(ultima.apkCamara) }
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
            .onFailure {
                Toast.makeText(this, "No hay navegador para abrir la descarga", Toast.LENGTH_LONG).show()
            }
    }

    override fun onDestroy() {
        if (::motor.isInitialized) {
            binding.preview.let {
                motor.separarPreview(it)
                it.release()
            }
            motor.liberar()
        }
        super.onDestroy()
    }

    // =====================================================================
    // Controles
    // =====================================================================

    private fun prepararControles() {
        binding.btnConectar.setOnClickListener { conectarODesconectar() }
        binding.btnQr.setOnClickListener { escanearQrConPermiso() }

        binding.campoIp.setOnEditorActionListener { _, accion, _ ->
            if (accion == EditorInfo.IME_ACTION_GO || accion == EditorInfo.IME_ACTION_DONE) {
                conectarODesconectar()
                true
            } else {
                false
            }
        }

        binding.btnVoltear.setOnClickListener { motor.alternarCamara() }
        binding.btnLinterna.setOnClickListener { motor.fijarLinterna(!motor.ajustes.linterna) }
        binding.btnCalidad.setOnClickListener { abrirDialogoCalidad() }
    }

    private fun conectarODesconectar() {
        val conectado = motor.estado == EstadoEnlace.CONECTADA ||
            motor.estado == EstadoEnlace.CONECTANDO
        if (conectado) {
            motor.desconectar()
            mostrarEstado(EstadoEnlace.SIN_CONECTAR, "")
            return
        }
        conectar()
    }

    private fun conectar() {
        if (!permisoCamara) {
            conectarAlConceder = true
            solicitudCamara.launch(Manifest.permission.CAMERA)
            return
        }
        val texto = binding.campoIp.text?.toString()?.trim().orEmpty()
        // El campo acepta «192.168.1.7», «192.168.1.7:8788» o el enlace completo del QR.
        val ip = QrLink.ipDe(texto)
        if (ip == null) {
            mostrarEstado(EstadoEnlace.ERROR, getString(R.string.falta_ip))
            return
        }
        val puerto = QrLink.puertoDe(texto) ?: Proto.DEFAULT_PORT
        // Se recuerda la última dirección usada para no teclearla en cada evento.
        prefs.edit().putString(CLAVE_IP, texto).apply()
        ocultarTeclado()
        motor.conectar(ip, puerto)
    }

    /** Abre el lector de QR; si falta el permiso de cámara, lo pide primero. */
    private fun escanearQrConPermiso() {
        if (!permisoCamara) {
            solicitudCamara.launch(Manifest.permission.CAMERA)
            return
        }
        escanearQr.launch(
            ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setPrompt(getString(R.string.qr_prompt))
                setBeepEnabled(false)
                setBarcodeImageEnabled(false)
                setOrientationLocked(false)
            }
        )
    }

    private fun abrirDialogoCalidad() {
        val presets = VideoPresets.all
        val etiquetas = presets
            .map { "${it.label}   ${it.width}x${it.height} · ${it.fps} fps" }
            .toTypedArray()
        val activo = presets.indexOfFirst { it.label == motor.ajustes.preset.label }
        AlertDialog.Builder(this)
            .setTitle(R.string.calidad_titulo)
            .setSingleChoiceItems(etiquetas, activo) { dialogo, cual ->
                dialogo.dismiss()
                motor.fijarCalidad(presets[cual])
            }
            .setNegativeButton(R.string.cancelar, null)
            .show()
    }

    private fun ocultarTeclado() {
        val teclado = getSystemService(InputMethodManager::class.java) ?: return
        teclado.hideSoftInputFromWindow(binding.campoIp.windowToken, 0)
    }

    // =====================================================================
    // Permisos
    // =====================================================================

    private fun consultarPermiso() {
        permisoCamara = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (!permisoCamara) solicitudCamara.launch(Manifest.permission.CAMERA)
    }

    // =====================================================================
    // Callbacks del motor
    // =====================================================================

    override fun onEstado(estado: EstadoEnlace, mensaje: String) {
        runOnUiThread {
            mostrarEstado(estado, mensaje)
            // Al terminar la conexión se libera la orientación (vuelve a horizontal libre).
            if (estado == EstadoEnlace.SIN_CONECTAR || estado == EstadoEnlace.ERROR) {
                if (requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                }
            }
        }
    }

    override fun onVivo(enVivo: Boolean) {
        runOnUiThread { mostrarVivo(enVivo) }
    }

    /** El switcher avisa si esta cámara está en el visor. */
    override fun onAlAire(enAire: Boolean) {
        runOnUiThread {
            val chip = findViewById<TextView>(R.id.chipAlAire) ?: return@runOnUiThread
            chip.text = if (enAire) "AL AIRE" else "FUERA DE AIRE"
            chip.setTextColor(
                if (enAire) 0xFF0C0E11.toInt() else 0xFF9AA4B2.toInt()
            )
            chip.setBackgroundResource(
                if (enAire) R.drawable.bg_badge_aire else R.drawable.bg_chip
            )
        }
    }

    override fun onAjustes(ajustes: AjustesCamara) {
        runOnUiThread { mostrarAjustes(ajustes) }
    }

    /**
     * El switcher indica la orientación del lienzo. Se bloquea la app en esa orientación
     * mientras dure la conexión (el aviso se manda al emparejar y si el lienzo cambia).
     */
    override fun onOrientacion(vertical: Boolean) {
        runOnUiThread {
            val objetivo = if (vertical) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
            if (requestedOrientation != objetivo) {
                requestedOrientation = objetivo
            }
            Toast.makeText(
                this,
                if (vertical) "Lienzo vertical: cámara en vertical"
                else "Lienzo horizontal: cámara en horizontal",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // =====================================================================
    // Pintado
    // =====================================================================

    private fun mostrarEstado(estado: EstadoEnlace, mensaje: String) {
        val texto = when (estado) {
            EstadoEnlace.SIN_CONECTAR ->
                mensaje.ifBlank { getString(R.string.estado_sin_conectar) }

            EstadoEnlace.CONECTANDO -> getString(R.string.estado_conectando)

            EstadoEnlace.CONECTADA -> getString(R.string.estado_camara, motor.indiceCamara)

            EstadoEnlace.ERROR ->
                mensaje.ifBlank { getString(R.string.estado_error) }
        }
        val color = when (estado) {
            EstadoEnlace.SIN_CONECTAR -> R.color.estado_gris
            EstadoEnlace.CONECTANDO -> R.color.estado_amarillo
            EstadoEnlace.CONECTADA -> R.color.estado_verde
            EstadoEnlace.ERROR -> R.color.estado_rojo
        }

        binding.chipEstado.text = texto
        binding.chipEstado.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, color))

        val conectado = estado == EstadoEnlace.CONECTADA
        val enCurso = conectado || estado == EstadoEnlace.CONECTANDO
        binding.btnConectar.text =
            getString(if (enCurso) R.string.desconectar else R.string.conectar)
        binding.btnVoltear.isEnabled = conectado
        binding.btnLinterna.isEnabled = conectado && motor.hayLinterna()

        // Sin enlace no hay video: se muestran las instrucciones.
        binding.overlayInstrucciones.visibility = if (conectado) View.GONE else View.VISIBLE
        if (!conectado) mostrarVivo(false)
    }

    private fun mostrarVivo(enVivo: Boolean) {
        val color = ContextCompat.getColor(
            this,
            if (enVivo) R.color.live_green else R.color.estado_gris
        )
        binding.puntoVivo.backgroundTintList = ColorStateList.valueOf(color)
        binding.txtVivo.text =
            getString(if (enVivo) R.string.en_vivo else R.string.sin_senal)
        binding.txtVivo.setTextColor(color)
        // Si llega video real, las instrucciones ya no hacen falta.
        if (enVivo) binding.overlayInstrucciones.visibility = View.GONE
    }

    private fun mostrarAjustes(ajustes: AjustesCamara) {
        binding.btnCalidad.text = getString(R.string.calidad_con_preset, ajustes.preset.label)

        val colorIcono = ContextCompat.getColor(
            this,
            if (ajustes.linterna) R.color.accent else R.color.texto_principal
        )
        binding.btnLinterna.iconTint = ColorStateList.valueOf(colorIcono)
        binding.btnLinterna.setTextColor(colorIcono)
        binding.btnLinterna.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(
                this,
                if (ajustes.linterna) R.color.linterna_encendida else R.color.bg_panel_2
            )
        )
    }

    private companion object {
        const val PREFS = "jfr_cam"
        const val CLAVE_IP = "ultima_ip"
    }
}
