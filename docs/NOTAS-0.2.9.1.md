# Notas del Release 0.2.9.1 — Arreglo de estabilidad al reconectar la cámara

> Versión de corrección sobre la 0.2.9. Incluye todo lo de la 0.2.9 y cierra un cierre
> inesperado que se detectó al instalar la versión oficial con la cámara reconectando.

## Estabilidad: la app ya no se cierra al reconectar una cámara

- Si una cámara entrega su video justo cuando la sesión se está cerrando (por ejemplo, al
  reconectar tras un corte de Wi-Fi), la app intentaba enganchar una pista ya liberada y se
  cerraba con `MediaStreamTrack has been disposed`.
- Ahora ese caso se ignora sin cerrar la app: la tarjeta se queda sin video hasta que la cámara
  vuelve, y la reconexión automática sigue trabajando.

## Lo que trae la 0.2.9 (si vienes de la 0.2.8)

- **La cámara ya no se congela al cambiar entre 9:16 y 16:9:** no se reinicia la captura; WebRTC
  actualiza la rotación solo.
- **Reconexión automática** de la cámara cada 3 s (hasta 2 min) si se cae el enlace.
- **Vista previa limpia** al desconectar: se acabó el último cuadro congelado.
- **Botón de frontal/trasera en la barra del 9:16 del celular**, con el estado `Off / F / T`.

## Probado en los equipos

- POCO X7 + Samsung S928B: cambios de formato con la cámara al aire sin cortes; corte de Wi-Fi de
  8 s con reconexión sola; botón de lente `Off → F → T → Off`; arranques y reconexiones sin cierres.

> Los dos APK van juntos y firmados con la misma llave: se instalan encima de la 0.2.8/0.2.9 sin
> desinstalar y conservan los ajustes.
