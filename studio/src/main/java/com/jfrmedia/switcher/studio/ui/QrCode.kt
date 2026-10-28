package com.jfrmedia.switcher.studio.ui

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/** Dibuja el código QR de emparejamiento que se muestra en la tablet. */
object QrCode {

    /**
     * QR cuadrado de [lado] px con el [texto] dado.
     *
     * Siempre **negro sobre blanco**: aunque la app es oscura, los lectores de QR necesitan
     * contraste real, y un QR "invertido" no lo lee la mitad de los celulares.
     */
    fun bitmap(texto: String, lado: Int = 512): Bitmap? = runCatching {
        val matriz = QRCodeWriter().encode(
            texto,
            BarcodeFormat.QR_CODE,
            lado,
            lado,
            mapOf(
                EncodeHintType.MARGIN to 1,
                EncodeHintType.CHARACTER_SET to "UTF-8",
            ),
        )
        val ancho = matriz.width
        val alto = matriz.height
        val pixeles = IntArray(ancho * alto)
        for (y in 0 until alto) {
            val fila = y * ancho
            for (x in 0 until ancho) {
                pixeles[fila + x] = if (matriz[x, y]) Color.BLACK else Color.WHITE
            }
        }
        Bitmap.createBitmap(pixeles, ancho, alto, Bitmap.Config.ARGB_8888)
    }.getOrNull()
}
