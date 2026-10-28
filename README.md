### Transmisión en vivo (RTMP/RTMPS)
Motor sobre **RootEncoder 2.8.1**: un solo codificador de hardware alimenta el archivo local y
la transmisión, así que grabar mientras se transmite no cuesta CPU extra.

- **Botón «Facebook»** en la barra: guarda la URL RTMPS
  (`rtmps://live-api-s.facebook.com:443/rtmp/`) y la **clave** de facebook.com/live/producer.
  «Guardar y conectar» arranca el directo; en vivo el botón pasa a «Detener».
- **Modo «solo transmitir»**: el botón de sesión conecta y corta.
- **Grabación simultánea** en `Android/data/.../files/Emision/` (video + audio). Se inicia
  **antes** de transmitir (el muxer necesita el formato del encoder) y hay que pedir
  `RecordTracks.ALL`; si no, con fuente externa graba solo audio.
- **Orientación**: el compositor dibuja en la `SurfaceTexture` del emisor; se anula la
  rotación por defecto (`setCameraOrientation(0)`, `setIsPortrait(false)`) y el volteo se
  compensa con `setIsStreamVerticalFlip`.
- Facebook requiere pulsar **«Emitir en vivo»** en el productor; la app solo entrega el video.
### Calidad de las cámaras y bitrate
- **Automática por defecto**: con 1–2 cámaras se pide **720p**; con 3 o más, **480p**. Se
  reajusta sola cuando entra o sale una cámara. En Ajustes puedes fijar 360p / 480p / 720p y
  el automático se desactiva.
- Presupuesto de bits: 360p **1.2 Mbps**, 480p **2.6 Mbps**, 720p **4.5 Mbps**.
- La app de cámara **fija ese techo en el emisor WebRTC** (`maxBitrateBps`) con prioridad
  `MAINTAIN_FRAMERATE`, y lo reaplica **cuando el ICE conecta** (antes se intentaba antes de
  que existieran los encodings y no surtía efecto). Sin esto el codificador del teléfono se
  quedaba en ~1.5 Mbps y la imagen se pixelaba con el movimiento.
- **Cuadros por segundo**: si la tarjeta marca 15 fps suele ser **poca luz** (el teléfono
  alarga la exposición). Con buena iluminación vuelve a 30 fps.
# JFR Switcher Multicam

Switcher de video multicámara para **tablet Android**, con celulares Android como cámaras
por WiFi. Alternativa propia a Switcher Studio (que es iOS y de pago).

**Estado: versión 0.1** — estructura completa y funciones base **probadas en dispositivo real**.
Todavía no transmite en vivo a Facebook/YouTube (siguiente etapa).

---

## Qué hace hoy

### Ajustes de imagen por cámara
Debajo del visor hay una segunda fila de botones: **Luz · Contraste · WB · Temp. · Color · Tinte**.
Cada uno abre un fader que ajusta la cámara seleccionada en el lienzo (−1…+1, con «Restablecer»):

| Botón | Qué hace |
|---|---|
| **Luz** | Brillo general |
| **Contraste** | Separa luces y sombras |
| **WB** | Balance de blancos (magenta ↔ verde) |
| **Temp.** | Temperatura (azul ↔ ámbar) |
| **Color** | Saturación (blanco y negro ↔ saturado) |
| **Tinte** | Gira el matiz de todos los colores |

Se aplican en el shader, así que afectan el **visor y la grabación/emisión** por igual, sin
tocar el encuadre. En vertical, esa fila se apila en la misma columna de controles.
### Audio
- **Solo los micrófonos del switcher**: las cámaras de los celulares envían únicamente video.
- **Entrada**: micrófono interno, diadema, **USB** o **Bluetooth**; ganancia (−12…+12 dB),
  silencio y **ecualizador real de 5 bandas** (80 Hz · 250 Hz · 1 kHz · 3.5 kHz · 10 kHz,
  ±12 dB por banda, filtros biquad). Presets: Plano, Voz, Música, Ambiente.
- **Salida de monitoreo**: elige bocina, audífonos, USB o Bluetooth y escucha lo mismo que se
  está grabando, con su **propio ecualizador de 5 bandas** y volumen (0–150 %). Con audífonos
  se evita el acople.
- Medidor de nivel en vivo; todo se guarda y se reaplica al abrir la app.
- **Candado de formato** mientras grabas o transmites.
### Zoom por cámara
- **Pellizco con dos dedos sobre la cámara** en el lienzo: acerca la imagen hasta 5× **recortando
  el cuadro, sin deformarla**. Mover los dos dedos desplaza el encuadre dentro del recorte.
- **Doble toque** sobre la cámara devuelve el plano completo.
- Alternativa sin gestos: menú ☰ → «Cámara: acercar (zoom +0.5×)» y «Cámara: quitar zoom».
- El zoom se guarda en las plantillas de escena.

### Grabar o transmitir (por separado)
- **Modo de sesión** en Ajustes: **Solo grabar**, **Solo transmitir** o **Grabar + transmitir**.
  El botón principal cambia de nombre según el modo («Grabar», «Transmitir», «Rec + Broadcast»).
- **Resolución de grabación**: 360p, 480p, 720p, 1080p, **2K (1440p)** y **4K (2160p)**. El lienzo
  se dibuja a ese tamaño (reescalando la fuente si hace falta); si el equipo no puede codificar
  esa resolución, la app **baja sola** a la anterior y avisa.
- **Bitrate de grabación** ajustable (2–40 Mbps) con barra.
- **Transmisión**: 480p / 720p / 1080p y bitrate propio (1–12 Mbps) con **ajuste automático**
  previsto. *El envío RTMP se implementa al final; por ahora estos valores se guardan.*
- **Foto instantánea**: botón «Foto» en los controles: captura el visor y la guarda en
  `Pictures/JFR Switcher`. No incluye el marco de selección.

### App de la tablet — `JFR Switcher Multicam`
- Recibe **hasta 6 cámaras** de celulares por WiFi local (sin internet, sin servidores, sin nube).
- **La cámara de la propia tablet** como fuente local seleccionable, **solo en modo horizontal**
  (la tablet no está pensada para girarse). Aparece como 7ª tarjeta «Tablet», se asigna a A o B
  igual que las demás y sirve para probar el PiP con dos cámaras sin un segundo teléfono.
  En vertical se apaga sola y la tarjeta desaparece.
  - **Encendido opcional**: arranca apagada; se enciende al tocar la tarjeta o desde el menú
    («Tablet: encender / apagar cámara»). La app **recuerda** si la dejaste encendida.
  - **Frontal o trasera**: el menú cambia de lente sin cortar la pista
    («Tablet: usar cámara trasera / frontal») y también lo recuerda.
- Parrilla de fuentes con **3 tarjetas por fila**: 2 filas con las 6 cámaras y, debajo, el grupo
  **OVERLAYS** con la misma forma de tarjeta (miniatura, marca de seleccionado y botón para quitar).
- **Tocar una tarjeta de overlay lo muestra u oculta** en el lienzo (switch rápido), igual que se
  cambia de cámara. La tarjeta «+ Overlay» agrega una imagen nueva.
- **Dos modos de lienzo**: horizontal 16:9 o **vertical 9:16** para historias y transmisión vertical.
  La tablet sigue en horizontal; al pasar a vertical el lienzo se va a la derecha, las cámaras y los
  overlays ocupan el espacio libre al centro, y **todos los controles** (A, B, PiP, Imagen, Quitar,
  Al frente, Al fondo) pasan a una columna pegada al borde izquierdo del lienzo, de arriba hacia abajo.
- **Plantillas de escena con nombre** desde el menú: guardan la orientación, qué cámara va en A y B,
  la posición de cada overlay y qué está visible. Se abren y se borran desde el mismo menú.
- **Lienzo de programa** con **2 cámaras a la vez**, en dos modos:
  - **PiP**: una cámara de fondo (capa A) y otra en ventana sobre una esquina (capa B).
    Pulsación larga en el botón PiP cambia la esquina.
  - **2 cam (multicámara)**: pantalla partida 50/50. En horizontal el corte es vertical
    (**A izquierda, B derecha**); en vertical el corte es horizontal (**A arriba, B abajo**).
    Al activarlo rellena solo con las dos primeras cámaras disponibles; luego puedes tocar
    una cámara y elegir A o B para cambiar cada mitad, e intercambiar lados desde el menú
    («Multicámara: intercambiar lados»).
  Sin PiP ni multicámara, **tocar una tarjeta pone esa cámara al aire** a pantalla completa
  y las demás salen del lienzo: es el switch rápido con varias cámaras.
- **Arrastrar y pellizcar** cualquier elemento del lienzo para moverlo y escalarlo a mano.
- Orden de capas: al frente / al fondo, quitar.
- **Grabación MP4** (H.264 + AAC) del lienzo compuesto, con cronómetro y vúmetro de audio.
  El video queda en la galería, en `Movies/JFR Switcher`, y se comparte desde «Compartir video».
- Ajustes: calidad de las cámaras (360p / 480p / 720p) y resolución de grabación (720p / 1080p).

### App del celular — `JFR Camera`
- Publica la cámara del celular a la tablet (solo cámara, sin controles de producción).
- **Orientación automática según el lienzo**: al conectarse, el switcher le dice si el lienzo es
  horizontal (16:9) o vertical (9:16); la app **se bloquea en esa orientación** y reinicia la
  captura para que WebRTC recalcule la rotación. Si el lienzo cambia en vivo, la cámara se ajusta;
  al desconectarse vuelve a horizontal libre.
- **Video a pantalla completa con el cuadro entero** (escala FIT: no recorta nada de lo que envía).
- Controles **flotantes y compactos encima del video** (arriba: IP/Conectar/estado; abajo:
  voltear, linterna, calidad y el punto «En vivo»), para tener el mayor campo visual posible.
- **Emparejamiento por QR**: toca **QR**, apunta al código que muestra la tablet
  (`jfr://IP:PUERTO`) y la app rellena la IP y conecta sola. Si el código no es del switcher, avisa.
- También se puede escribir la IP y tocar **Conectar**. Recuerda la última IP usada.
- Voltear cámara, linterna y calidad (360p / 480p / 720p).
- Espejo automático solo con la cámara frontal.

---

## Cómo compilar

Requisitos ya instalados en esta PC: Android Studio, SDK 37 (Android 17), JDK incluido en Studio.

**Opción A — Android Studio**
1. Abre Android Studio → *Open* → carpeta `C:\Users\noman\AndroidStudioProjects\JFR-Switcher`.
2. Arriba a la izquierda, en el selector de módulos, elige `studio` y dale ▶ (necesita la tablet).
3. Elige `camera` y dale ▶ con el celular conectado.

**Opción B — línea de comandos**
```powershell
cd C:\Users\noman\AndroidStudioProjects\JFR-Switcher
.\gradlew.bat :studio:assembleDebug :camera:assembleDebug
```
Los APK quedan en:
- `studio\build\outputs\apk\debug\studio-debug.apk`
- `camera\build\outputs\apk\debug\camera-debug.apk`

> **Firmas entre PCs.** Cada equipo tiene su propio `debug.keystore`, así que un APK compilado en una
> PC **no se puede instalar encima** del que compiló la otra (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`).
> Por eso los dos módulos compilan la variante debug con el sufijo **`.dev`**
> (`com.jfrmedia.switcher.studio.dev`, `com.jfrmedia.switcher.cam.dev`, versión `0.1.0-dev`, nombre
> «JFR Switcher · dev» / «JFR Cámara · dev»): se instala **al lado** de la app de la otra PC, sin
> desinstalar nada. Está en el bloque `debug { }` de `studio\build.gradle.kts` y
> `camera\build.gradle.kts`, más `src\debug\res\values\strings.xml`. Si las dos PCs llegan a
> compartir el mismo `debug.keystore`, se quita ese bloque y los paquetes vuelven a ser los de antes.

---

## Cómo instalar en los equipos

**Antes de nada, en cada equipo:** activar opciones de desarrollador y **Depuración USB**:
1. Ajustes → Información del teléfono/tablet → toca **Número de compilación** 7 veces.
2. Ajustes → Sistema → Opciones de desarrollador → activa **Depuración USB**.
3. Conecta el cable y acepta «Permitir depuración USB» en la pantalla del equipo.

Después, en la PC:
```powershell
C:\Users\noman\AppData\Local\Android\Sdk\platform-tools\adb.exe devices
# debe aparecer tu tablet/celular como "device"

cd C:\Users\noman\AndroidStudioProjects\JFR-Switcher
.\instalar.ps1 -Todo
```
Con dos equipos conectados a la vez hay que indicar el serial:
`adb -s <serial> install -r <apk>` (el serial lo da `adb devices`).

---

## Cómo se conectan (uso normal)

1. Abre **JFR Switcher Multicam** en la tablet.
2. Toca **Conectar cámaras**. Aparece la IP de la tablet (ej. `192.168.1.42`).
3. En cada celular abre **JFR Camera** y toca **QR**: apunta al código de la tablet y se
   conecta sola. (También sirve escribir la IP y tocar **Conectar**.)
   El celular aparece en la parrilla como Cámara 1, 2, 3…
4. En la tablet toca la tarjeta de una cámara: entra a la capa activa (A o B).
   Con los botones **A** / **B** eliges a qué capa entra la próxima cámara.
5. **Imagen** agrega un PNG/JPG de overlay. Arrástrala y pellizca para acomodarla.
6. **Rec + Broadcast** empieza la grabación. Vuelve a tocarlo para detener.

Condición indispensable: tablet y celulares en **la misma red WiFi**, y que el router **no tenga
aislamiento de clientes** (AP isolation), porque el video va directo de celular a tablet.

---

## Cómo está hecho (para que lo puedas mantener)

```
JFR-Switcher/
├─ core/      Protocolo compartido: mensajes JSON, canal TCP, red, presets, muxing H.264
├─ studio/    App de la tablet (director, lienzo, compositor OpenGL, grabador)
└─ camera/    App del celular (captura y publicación de cámara)
```

**Transporte de video:** WebRTC punto a punto sobre la WiFi local.
- La tablet abre un socket TCP en el puerto **8787** y ahí se conectan los celulares.
- Por ese canal va la **señalización** (JSON con longitud prefijada): saludo, oferta/respuesta
  SDP, candidatos ICE, estado y métricas. Los envíos pasan por un hilo escritor propio porque
  Android prohíbe escribir en sockets desde el hilo de UI.
- El video va **directo** celular → tablet, sin pasar por ningún servidor.
- Se fuerza **H.264** en la oferta SDP (`SdpMunger.h264Only`): es el códec que casi todo Android
  decodifica por hardware. Con VP8/VP9 la tablet tendría que decodificar 6 flujos por software.

**Compositor:** OpenGL ES 2.0 (`CompositorView` + `Gfx.kt`).
- Cada cámara sube su cuadro I420 a 3 texturas (Y, U, V) y un shader lo convierte a RGB.
- Ojo con la orientación: al subir el plano I420, la fila 0 del cuadro queda en `v=0` (arriba).
  Invertir la Y en el shader (que fue un error de la primera versión) deja todo el video de cabeza;
  la corrección fue quitar ese volteo y aplicar solo el metadato `frame.rotation` cuando exista.
- Se dibuja la lista de elementos (atrás → adelante) con esquinas redondeadas y opacidad.
- El mismo dibujo se repite en la **superficie del codificador** (`EglCore`) para grabar
  exactamente lo que se ve, sin el recuadro de selección.
- El toque se convierte a coordenadas normalizadas 0..1 y se modifica el rectángulo del elemento.
- El ritmo de grabación usa un planificador de 30 fps (no la duración del cuadro anterior, que
  incluía las esperas de `swapBuffers` y bajaba la tasa a 16 fps).

**Grabación:** `MediaCodec` H.264 (superficie) + `MediaCodec` AAC → `MediaMuxer` → MP4,
con marca de tiempo real por cuadro. La pista de audio se agrega al muxer solo cuando llega su
primera muestra; si se agrega antes y no entra audio, `MediaMuxer.stop()` falla y el MP4 queda
sin índice (archivo que ningún reproductor abre).

---

## Probado en hardware (25/10/2026)

**Tablet CUBOT TAB KINGKONG (Android 13, arm64, 8 GB, 1920×1200) + POCO 2412DPC0AG (Android 16).**

Por WiFi real (tablet 192.168.100.7, celular 192.168.100.3):
- Señalización completa: HELLO → WELCOME → oferta SDP (H.264) → respuesta → candidatos ICE.
- **ICE conectado** y **pista de video recibida**; video del celular visible en la tarjeta y
  pintado por el compositor en el lienzo (24 fps reportados por la cámara).
- **Imagen de overlay** agregada desde la galería, dibujada encima del video y arrastrada con el dedo.
- Grabación de 11 s a 720p: MP4 válido (266 cuadros de video + 469 muestras de audio),
  reproducido en Google Photos con la composición exacta (video + overlay) y su audio.

También probado por loopback (ambas apps en el mismo equipo), que confirma que el pipeline
completo funciona sin depender de la red.

**Rendimiento medido:** el compositor entrega 30 fps estables (8-10 ms por cuadro en la tablet),
pero el codificador H.264 de la tablet procesa ~24 fps y el del POCO ~19-25, así que el MP4 sale
en tasa variable (los reproductores lo muestran a velocidad correcta, con algo de cadencia).
Si hace falta 30 fps clavados, la vía es bajar a 360p o grabar con un equipo con mejor codificador.

## Limitaciones de esta versión

- El audio que se graba es el **micrófono de la tablet**, no el de los celulares.
- Probado con **2 celulares a la vez** (POCO X7 y Samsung S24 Ultra) más la cámara de la tablet;
  falta medir el rendimiento con los 6 decodificadores juntos.
- En la tablet el codificador entrega ~24 fps al MP4 (el compositor sí produce 30).
- Las cámaras entran a 16:9 y la app de celular trabaja en horizontal.
- Sin textos ni croma. Las transiciones, las plantillas de escena y el emparejamiento por QR sí están.

Los videos van a `Movies/JFR Switcher` y los overlays de prueba están en la galería, dentro de
`Pictures` (archivos `overlay-logo-jfr.png` y `overlay-lower-third.png`). También quedaron copias
en `Documents\deepseek-harness\entrega-jfr-switcher\overlays\` con una previsualización.

## Overlays de prueba incluidos

| Archivo | Qué es |
|---|---|
| `overlay-logo-jfr.png` (1440×705) | El logotipo de JFR Media Lab con fondo transparente. |
| `overlay-lower-third.png` (1750×430) | Lower third: placa blanca con el logo, barra coral, nombre **Jorge Rosas** y el rol «JFR Media Lab · Producciones D'Angel». |
| `preview-overlays.png` | Muestra de cómo se ven montados sobre un video. |

Se agregan desde la tarjeta **«+ Overlay»** del grupo OVERLAYS. Los gráficos anchos (relación de
aspecto ≥ 3) nacen colocados abajo al centro, listos para un lower third; el resto aparece centrado.
Cualquiera se arrastra y se escala con dos dedos en el lienzo.

### Transiciones

Menú ☰ → «Transición» abre un selector con **vista previa animada**: eliges una de las 12, ves cómo
entra con la **duración que marques en la barra** (200–1600 ms) y puedes repetir la vista previa.
La transición se aplica al cambiar de cámara y al entrar un medio de MEDIOS.

## Barra superior y controles

Arriba: **logo JFR**, **Ajustes**, **Plantillas**, **Transiciones**, **orientación 16:9/9:16**, **Audio** con
medidor de nivel y, a la derecha, temporizador y el botón único de sesión. Sobre el visor: **foto flotante** (icono de cámara) y, debajo, los controles
**A · B · PiP · 2 cam · − · + · Tablet (frontal/trasera)**. El zoom y el cambio de lente de la tablet
son botones; ya no están en el menú.

Todo el control vive arriba: menú ☰, «Director», **Ajustes**, medidor de audio, temporizador y el
botón único de sesión (**Grabar / Transmitir / Rec + Broadcast** según el modo). La barra inferior se
eliminó: para conectar cámaras basta tocar una tarjeta vacía.

## Plantillas de escena

El menú (☰ arriba a la izquierda, o el chip «Director») tiene:

| Opción | Qué hace |
|---|---|
| Conectar cámaras | Muestra la IP y el puerto para los celulares. |
| Guardar plantilla de escena… | Pide un nombre y guarda orientación, capas A/B, posiciones y visibilidad de overlays. |
| Abrir plantilla… | Lista las plantillas guardadas; al elegir una se aplica completa (incluido si es vertical). |
| Borrar plantilla… | Elimina la plantilla elegida. |
| Cambiar a horizontal / vertical | Alterna el lienzo; también con el chip 16:9 / 9:16. |

Se guardan como JSON en el almacenamiento interno de la app (`files/plantillas/<nombre>.json`).
Los overlays se guardan por su URI con permiso persistente, así que siguen disponibles al reabrir
la app. El modo (horizontal/vertical) se recuerda al cerrar.

## Siguientes etapas sugeridas

1. **Transmisión en vivo** (RTMP a Facebook/YouTube con la llave del usuario) reutilizando el
   mismo codificador de la grabación.
2. **Audio de los celulares** mezclado en la tablet.
3. **QR de emparejamiento** (la tablet muestra un QR con IP y puerto; el celular lo escanea).
4. **Escenas, transiciones y textos** con tipografía.
5. **Croma key** en el compositor (el shader ya se puede extender).
6. Versión iOS de la app de cámara (requiere Xcode; el protocolo ya está definido).
