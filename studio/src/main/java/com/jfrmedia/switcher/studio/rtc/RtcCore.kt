package com.jfrmedia.switcher.studio.rtc

import android.content.Context
import com.jfrmedia.switcher.core.util.L
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory

/**
 * Fábrica única de WebRTC para toda la app.
 * EglBase se comparte (la app tiene un solo contexto GL raíz) para poder reutilizar
 * texturas entre el compositor y los previews.
 */
object RtcCore {

    private var initialized = false
    lateinit var factory: PeerConnectionFactory
        private set
    lateinit var eglBase: EglBase
        private set

    fun init(context: Context) {
        if (initialized) return
        val app = context.applicationContext
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(app)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
        eglBase = EglBase.create()
        factory = PeerConnectionFactory.builder()
            .setOptions(PeerConnectionFactory.Options())
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .createPeerConnectionFactory()
        initialized = true
        L.i("rtc", "PeerConnectionFactory lista")
    }
}
