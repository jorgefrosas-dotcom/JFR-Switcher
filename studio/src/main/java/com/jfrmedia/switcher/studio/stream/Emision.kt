package com.jfrmedia.switcher.studio.stream

import android.content.Context

/**
 * El emisor vive fuera de la pantalla.
 *
 * Antes lo creaba la actividad: cuando Android la recreaba (cambio de ventana, poca memoria),
 * nacía un emisor nuevo y el anterior **seguía transmitiendo**, mientras la interfaz decía que
 * no. Se vio en la prueba del 27/10: el botón mostraba «Transmitir» y el registro seguía
 * escribiendo paquetes al servidor de Facebook.
 *
 * Aquí hay uno solo por proceso, así la pantalla siempre refleja la verdad y el botón siempre
 * puede cortar de verdad.
 *
 * Es el paso previo al servicio en primer plano: cuando exista, esta clase será el dueño del
 * envío y la interfaz solo lo observará (y el video podrá seguir con la app minimizada).
 */
object Emision {

    @Volatile
    private var instancia: Emisor? = null

    /** Emisor del proceso; se crea la primera vez que se pide. */
    fun obtener(contexto: Context): Emisor =
        instancia ?: synchronized(this) {
            instancia ?: Emisor(contexto.applicationContext) { _, _ -> }.also { instancia = it }
        }

    /** Hay una transmisión en curso, la haya iniciado la pantalla que sea. */
    val transmitiendo: Boolean get() = instancia?.transmitiendo == true

    val grabando: Boolean get() = instancia?.grabando == true

    /** Corta todo y suelta el codificador (al cerrar la app de verdad). */
    fun liberar() {
        synchronized(this) {
            instancia?.liberar()
            instancia = null
        }
    }
}
