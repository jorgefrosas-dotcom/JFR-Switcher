# Notas del Release 0.2.9 — La cámara ya no se congela al cambiar a 9:16

> Texto que se sube a GitHub como notas de la versión 0.2.9 (es el que la app muestra en el aviso de
> actualización). Se guarda aquí porque `CAMBIOS-SIN-PUBLICAR.md` se vacía al publicar.

## Cámara extra: se acabó el congelamiento al cambiar de formato

- **La app de cámara ya no detiene y reinicia la captura** al pasar de 9:16 a 16:9 (o al revés).
  Antes ese reinicio bloqueaba la vista previa y cortaba el video justo en el cambio; ahora la app
  solo gira y **WebRTC actualiza la rotación de los cuadros por su cuenta**. El video sigue sin
  cortarse.
- **Reconexión automática:** si se cae el Wi-Fi, la tablet pasa a segundo plano o el router corta,
  la cámara **reintenta la última dirección cada 3 segundos durante 2 minutos** y vuelve sola. Ya no
  hace falta tocar «Conectar» a mano ni queda el último cuadro congelado.
- **Vista previa honesta:** al perder la conexión se limpia la imagen y el aviso «Sin señal» dice la
  verdad; antes se quedaba el último cuadro como si siguiera en vivo.

## Botón de cámara frontal/trasera en el 9:16 del celular

- En el **celular en vertical**, la barra deslizable de abajo lleva ahora **el mismo botón de la
  barra horizontal** (`Off / F / T`), **primero y visible**.
- Un toque enciende la cámara propia en **F** (frontal), otro la cambia a **T** (trasera) y otro la
  apaga (`Off`). En la tablet vertical sigue oculto, porque ahí no existe cámara propia.

## Probado en los equipos

- **POCO X7 + Samsung S928B:** 9:16 → 16:9 → 9:16 con la cámara del Samsung al aire; **cero**
  «captura detenida» y el programa siguió con video.
- **Corte de Wi-Fi de 8 segundos:** la cámara reconectó sola y el programa volvió sin tocar nada.
- **Botón de lente:** `Off → F (Tablet · frontal) → T (Tablet · trasera) → Off`, con el video al aire.

> Los dos APK del Release (`JFR-Switcher.apk` y `JFR-Camera.apk`) van juntos y firmados con la misma
> llave: se instalan encima de la 0.2.8 sin desinstalar y conservan los ajustes.
