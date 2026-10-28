package com.jfrmedia.switcher.studio.stream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.jfrmedia.switcher.core.util.L
import com.jfrmedia.switcher.studio.R
import com.jfrmedia.switcher.studio.StudioActivity

/**
 * Mantiene la transmisión viva con la app fuera de la pantalla.
 *
 * **Por qué existe:** Android silencia el micrófono a las apps que no están visibles
 * (`AudioPolicyService: App op 27 missing, silencing record`) y además puede matar el proceso.
 * Un servicio en primer plano de tipo **micrófono** es la forma legítima de seguir grabando y
 * enviando mientras se revisa el Live Producer: sin él, el video sigue pero el audio se corta.
 *
 * El envío en sí vive en [Emision]; este servicio solo le da al proceso la categoría que Android
 * exige para no silenciarlo, y ofrece una notificación con **Detener emisión**.
 */
class EmisionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        crearCanal()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACCION_DETENER) {
            L.i("emisor", "detener pedido desde la notificación")
            // Se avisa a la pantalla (si sigue viva) para que también cierre su grabación
            // local y actualice la interfaz, y se corta el envío.
            sendBroadcast(Intent(ACCION_SESION_DETENIDA).setPackage(packageName))
            Emision.liberar()
            stopSelf()
            return START_NOT_STICKY
        }
        // Micrófono (para que Android no silencie la entrada) y cámara (para que la lente de
        // la tablet no se corte al cambiar de app). En Android 14 el tipo debe estar declarado
        // en el manifiesto y solo se puede pedir con la app visible, que es justo cuando se
        // arranca este servicio.
        val tipo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var tipos = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                checkSelfPermission(android.Manifest.permission.CAMERA) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                tipos = tipos or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
            tipos
        } else {
            0
        }
        runCatching {
            ServiceCompat.startForeground(this, ID_NOTIFICACION, construirNotificacion(), tipo)
            L.i("emisor", "servicio en primer plano (micrófono${if (tipo and ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA != 0) " + cámara" else ""})")
        }.onFailure { L.e("emisor", "no se pudo pasar a primer plano: ${it.message}") }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        L.i("emisor", "servicio de emisión detenido")
        super.onDestroy()
    }

    private fun crearCanal() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val gestor = getSystemService(NotificationManager::class.java) ?: return
        if (gestor.getNotificationChannel(CANAL) != null) return
        gestor.createNotificationChannel(
            NotificationChannel(
                CANAL,
                "Transmisión en vivo",
                // Importancia normal: con la baja el aviso caía en «Silenciadas» y su botón
                // «Detener» no se podía tocar desde la cortina.
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Aviso con «Detener» mientras el switcher graba o transmite"
                setShowBadge(false)
                setSound(null, null)
            }
        )
    }

    private fun construirNotificacion(): Notification {
        val abrir = PendingIntent.getActivity(
            this,
            0,
            Intent(this, StudioActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val detener = PendingIntent.getService(
            this,
            1,
            Intent(this, EmisionService::class.java).setAction(ACCION_DETENER),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CANAL)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("JFR Switcher · sesión activa")
            .setContentText("Sigue grabando y transmitiendo aunque salgas de la app")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(abrir)
            .addAction(0, "Detener", detener)
            .build()
    }

    companion object {
        const val ACCION_DETENER = "com.jfrmedia.switcher.studio.DETENER_EMISION"

        /** Aviso a la pantalla de que la sesión se cerró desde la notificación. */
        const val ACCION_SESION_DETENIDA = "com.jfrmedia.switcher.studio.SESION_DETENIDA"
        // Canal nuevo: la importancia de un canal no se puede cambiar después de crearlo.
        private const val CANAL = "emision_v2"
        private const val ID_NOTIFICACION = 4321

        /** Arranca (o mantiene) el servicio que protege la emisión. */
        fun iniciar(contexto: Context) {
            runCatching {
                val intent = Intent(contexto, EmisionService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    contexto.startForegroundService(intent)
                } else {
                    contexto.startService(intent)
                }
            }.onFailure { L.w("emisor", "no se pudo iniciar el servicio: ${it.message}") }
        }

        /** Lo para cuando la transmisión termina. */
        fun detener(contexto: Context) {
            runCatching { contexto.stopService(Intent(contexto, EmisionService::class.java)) }
        }
    }
}
