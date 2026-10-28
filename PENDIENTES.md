# PENDIENTES — JFR Switcher Multicam

## Arreglado en 0.2.1 (27/10 noche) — el QR traía bien el puerto, el campo lo cortaba

- **Síntoma:** al escanear el QR, el campo de la app de cámara quedaba como `192.168.100.7:8` en vez
  de `192.168.100.7:8787`; Jorge tenía que borrar el `:8` a mano para conectar (y si la tablet usaba
  el 8788, habría conectado al 8787 y fallado).
- **Causa:** el `EditText campoIp` de `camera/res/layout/activity_camera.xml` tenía **`maxLength="15"`**
  y `digits="0123456789."`. `192.168.100.7:8787` mide **18** caracteres, así que entraban solo los 15
  primeros. **El QR siempre estuvo bien**: la tablet imprime en su log `QR: jfr://192.168.100.7:8787`.
- **Arreglo:** `digits="0123456789.:"` y `maxLength="64"`. Así admite IP sola, `IP:PUERTO` y el enlace
  completo pegado, y el teclado ya permite escribir `:`.
- **Regla para no repetirlo:** cuando un campo se llena **por código** (QR, preferencias, portapapeles),
  revisar `maxLength` y `digits`: los filtros del `EditText` también recortan lo que se escribe por código,
  no solo lo que teclea el usuario.

## Cambio grande: el proyecto ya vive en GitHub (27/10 noche, PC personal)

- Repo: **`github.com/jorgefrosas-dotcom/JFR-Switcher`**. La rutina para trabajar en las dos PC,
  la llave y cómo publicar versiones están en **`TRABAJO-EN-DOS-PC.md`**.
- **Una sola llave de firma compartida** en las dos computadoras (`keystore/jfr-upload.jks`,
  que no está en GitHub). Ya **no existe la variante `.dev`**: hay un solo paquete por app y
  cualquier compilación, de cualquier PC, se instala encima sin desinstalar nada.
- La versión se cambia en un solo lugar: `jfr.versionName` / `jfr.versionCode` en `gradle.properties`.
  Versión actual: **0.2.0** (código 2).
- Descargas (tablet y celulares): **https://jorgefrosas-dotcom.github.io/JFR-Switcher/**
- **Pendiente, una sola vez por equipo:** desinstalar la app vieja (está firmada por la PC de
  trabajo) e instalar la 0.2.0. Antes hay que **respaldar los ajustes de la tablet** (URL y clave
  de Facebook) porque se pierden al desinstalar; después se restauran.

> Última actualización: **2026-10-27 15:25** (PC CyberPower1).
> Bitácora completa y detalle del día: `%USERPROFILE%\.dsh\memoria\CAMBIOS.md` (entradas del 27/10).
> Proyecto: `C:\Users\user\Documents\DeepSeek\JFR-Switcher`

## Estado actual

- **La tablet tiene instalada la compilación completa de hoy** (`com.jfrmedia.switcher.studio.dev`):
  QR, «Abrir Live Producer», indicador **ENVIANDO**, monitor CPU/RAM/Mbps/temperatura, registro de
  sesión, `Emision` (emisor fuera de pantalla) y las mejoras de `Recorder`/`CompositorView`.
- Probado hoy por Jorge: **directo real de 4 minutos** en Facebook con 2 cámaras por WiFi.
- Respaldo anterior a este arreglo: `_material\JFR-Switcher-BUENA-20261027.apk` (no se borra).
- La app original (`com.jfrmedia.switcher.studio`) **se desinstaló**: robaba el puerto 8787 y la cámara
  no conectaba. **No reinstalarla.**
- **Barra superior en vertical: ARREGLADA** (era el pendiente 1, ver abajo). La tablet ya tiene
  instalada la compilación con el arreglo y el respaldo nuevo es
  `_material\JFR-Switcher-BUENA-20261027-1520-tamano-vertical.apk`.
  Evidencia: `_material\vertical-fix2.png` (vertical) y `_material\horizontal-fix.png` (horizontal).
- **Tamaños del vertical: ARREGLADOS** (resolución de grabación y de transmisión). Ver abajo.
- **Vertical con 3 cámaras por fila** (17:10): la cuarta columna pasó a la columna de controles, que
  ahora lleva los botones en **dos columnas** (A|B, PiP|2 cam, −|+, Luz|Contraste, WB|Color, Viñeta|Poster)
  y, al final, el **monitor de audio** siempre visible con su fuente y, debajo, el **tablero de estado**
  (CPU, RAM, temperatura y subida en Mbps). Los ajustes de imagen siguen abriendo su cuadro como antes.
- **Aviso de red floja** (17:22): si el cliente RTMP detecta congestión, SUBIDA se pone ámbar y parpadea y
  la cabecera del visor antepone ⚠ (en las dos orientaciones). Se apaga solo al recuperarse.
- **Horizontal sin saltos** (17:34): los ajustes de imagen y el monitor de audio comparten una banda de
  132 dp, así el visor del programa mide lo mismo en las dos vistas. Corregido un cuelgue del medidor
  cuando el panel se quedaba sin alto.
- **Monitor de audio en horizontal** (16:47): chip «♪ Audio» en la fila de controles que cambia
  Luz/Contraste… por un monitor estilo consola (barras finas de 6 s con punta naranja/roja, rejilla en
  dB, envolvente, placa de PICO y LED CLIP) con **cabecera de ENTRADA** («Micrófono interno», «44,1 kHz ·
  mono») que avisa «→ en uso: …» si el sistema entrega otra fuente. En vertical el chip se oculta. El
  nivel viene del micrófono mientras graba o transmite; sin sesión dice «Sin señal».
- **Respaldo más reciente (con todo lo de hoy):** `_material\JFR-Switcher-BUENA-20261027-1546-ambos.apk`.

## Lo que se hizo con los tamaños del vertical (15:20)

- **Causa 1 — grabación:** `tamanoGrabacion()` tomaba el número elegido (720, 1080…) como **alto del
  lienzo**, así que en vertical 720p salía **404×720** y 1080p salía **607×1080**: un tercio del tamaño
  pedido, aunque el comentario del código decía 720×1280 / 1080×1920.
- **Causa 2 — transmisión:** `iniciarTransmision()` calculaba el ancho fijo `alto * 16 / 9`, así que un
  lienzo 9:16 se habría enviado **dentro de un cuadro 16:9** (barras negras a los lados).
- **Arreglo:** el número elegido es siempre el **lado corto** (`tamanoGrabacion`), igual que en
  horizontal: 720 → 1280×720 / 720×1280; 1080 → 1920×1080 / 1080×1920. La transmisión usa la misma
  función, así que hereda la orientación del lienzo.
- **Probado en la tablet:** el chip del visor pasó de `404×720` a **`720×1280`**; un MP4 grabado en
  vertical sale **720×1280** (H.264 + AAC) y en horizontal sigue **1280×720** (sin cambios); el
  codificador del emisor arrancó a **720×1280** (`[emisor] emisor listo 720x1280`).
- **Falta confirmar en el aire:** que el cuadro llegue **derecho** a Facebook en vertical. El tamaño y
  el codificador están probados; la orientación usa la misma ruta que el directo horizontal ya probado.
  **Al hacer el primer directo vertical, mirarlo 10 s en el Producer antes de darlo por bueno.**
- **Ojo con 4K en vertical:** serían **2160×3840**; este equipo **no codifica ni 2K ni 4K** (ver la
  prueba de resoluciones más abajo), así que en la práctica graba en 1080 (1080×1920 en vertical) con
  aviso. La bajada automática que promete Ajustes **ya está implementada**.
- **Extra arreglado (lo destapó la prueba):** si la conexión con Facebook falla, `iniciarTransmision()`
  dejaba la **grabación de respaldo del emisor abierta y creciendo** en la carpeta `Emision`, sin que la
  pantalla la mostrara ni pudiera detenerla. Ahora, al fallar el envío, se detiene esa grabación y se
  apaga el punto rojo.

## Lo que se hizo hoy con la barra (para no volver a romperlo)

- **Causa:** en vertical `reacomodarCuerpo()` sacaba `panelPrograma` de `contenido` y lo pegaba a la
  **raíz** (`b.root`) con `match_parent` de alto, y `ajustarLienzo()` metía un `padding` derecho a
  `contenido`. Resultado: el visor tapaba el cronómetro y el botón «Transmitir» (se leía «Tr…»), y la
  cabecera «PROGRAMA» quedaba fuera de pantalla.
- **Arreglo:** el panel del programa ahora vive **dentro del cuerpo**, después de la columna de
  controles (`reacomodarCuerpo()`). El ancho del visor lo calcula `anchoPrograma()` a partir del alto
  del cuerpo y de la cabecera, y `aplicarAjusteLienzo()` lo repite hasta que el ancho ya no cambia
  (la cabecera puede pasar de una a dos líneas). Ya no se pone `padding` a `contenido`, así que la
  barra superior usa todo el ancho.
- En vertical la cabecera se acorta a **«PROGRAMA» + resolución** (el medidor CPU/RAM/Mbps/temperatura
  sigue completo en horizontal). Si se quiere ese medidor también en vertical, habría que ponerlo en
  una segunda línea y descontar su alto en `anchoPrograma()`.

## Prueba de resoluciones de grabación (27/10 15:57)

Probadas las seis opciones de «Resolución de grabación» con la cámara de la tablet en horizontal,
grabando 5–9 s cada una y midiendo el archivo con `ffprobe`:

| Opción | Archivo real | fps | Bitrate | Resultado |
|---|---|---|---|---|
| 360p | 640×360 | 30 | 2,3 Mbps | ✓ |
| 480p | 852×480 | 30 | 2,4 Mbps | ✓ |
| 720p | 1280×720 | 30 | 2,8 Mbps | ✓ |
| 1080p | 1920×1080 | 30 | 5,4–5,6 Mbps | ✓ |
| 2K (1440) | **no lo acepta el equipo** | — | — | ✗ → cae a 1920×1080 |
| 4K (2160) | **no lo acepta el equipo** | — | — | ✗ → cae a 1920×1080 |

- El codificador MediaTek del equipo rechaza 2560×1440 y 3840×2160: `MediaCodec.native_configure` falla
  y el grabador devolvía «No se pudo iniciar la grabación: null».
- **Arreglado:** la app ahora **baja sola** a la resolución anterior hasta que una arranque (usa la
  lista `resolucionesGrabacion()` que estaba muerta), avisa con un toast
  («El equipo no acepta 2160p: se graba en 1080p») y **el chip del visor muestra la resolución real**
  mientras graba. Probado con 2K y 4K: los dos graban en 1920×1080.
- El bitrate configurado en Ajustes era **2 Mbps**; el equipo entrega más, pero a 1080p eso se queda
  corto. **Arreglado:** el interruptor ahora se llama **«Bitrate automático por resolución»** y calcula
  el bitrate de la resolución elegida: 360→2,5 · 480→4 · 720→7 · 1080→12 · 2K→22 · 4K→45 Mbps
  (emisión: 480→2,5 · 720→4,5 · 1080→8). Con el automático apagado mandan los deslizables de siempre.
  Medido en la tablet: 720p → **7,7 Mbps**, 1080p → **13,3 Mbps**.
- Nota: en vertical las mismas opciones dan 360×640 … 1080×1920 y el respaldo funciona igual (a 720p
  se comprobó 720×1280 ✓).

## Prueba «grabar mientras se transmite» (27/10 15:35)

**Cómo se probó sin publicar nada:** se apuntó el envío a un servidor RTMP propio en esta PC por el
túnel de `adb reverse` (`rtmp://127.0.0.1:1935`), con ffmpeg de receptor. Así corre la ruta real
(`iniciarTransmision` → codificador → RTMP → grabación del emisor) sin tocar Facebook. Al terminar se
restauró la URL de Facebook y la clave guardada (verificado por SHA-256 de las dos).

- **Horizontal 1280×720, 62 s, 1 cámara WiFi + cámara de la tablet:**
  - El compositor dibujó **30 fps** y entregó **1500/1500** cuadros al codificador.
  - El codificador sacó **1585 cuadros en 62 s ≈ 25,5 fps** (pierde ~15 % de los dibujos).
  - **El MP4 grabado tiene exactamente los mismos 1585 cuadros que el envío**: la grabación es copia
    fiel de lo transmitido, no un problema aparte.
  - Audio 61,9 s vs video 62,1 s (sincronía bien).
  - Carga: **CPU 143-148 %** (1,4 de 8 núcleos), RAM 51 %, batería 32,9 °C, **RAM libre ~96 MB**.
- **Vertical 720×1280:** 30 s → grabación **899 cuadros (29,9 fps)**, audio 30,0 s, envío 906 cuadros.
- **Fallos que destapó y quedaron arreglados** (`Emisor.kt`, `StudioActivity.kt`):
  1. **Barras negras en el directo vertical:** RootEncoder calculaba el *viewport* suponiendo entrada
     horizontal (`SizeCalculator.calculateViewPortEncoder` → banda de 720×405). Ahora se le pasa
     `setIsPortrait(vertical)` y el cuadro llena 720×1280.
  2. **La segunda transmisión del mismo proceso salía muerta:** al parar, RootEncoder suelta su GL pero
     el emisor seguía con `listo=true` y devolvía una superficie muerta (`EGL_BAD_SURFACE`, 0 cuadros,
     MP4 de 0 bytes). Ahora se olvida la superficie al parar y al fallar.
  3. **Fallo de conexión asíncrono dejaba la grabación de respaldo abierta y creciendo.** Ahora se
     cierra también en `onConnectionFailed`/`onDisconnect`.
  4. **Cronómetro y botón:** en «Solo transmitir» el reloj se quedaba en 00:00 y el botón seguía diciendo
     «Transmitir» con el directo en el aire. Ahora cuenta y dice «Detener».
- **Cambios de esta ronda (15:46):**
  - **«Grabar y transmitir a la vez» ya transmite.** Antes ese modo solo grababa local; ahora su
    botón abre el directo **y** graba con el mismo codificador (un solo encode). Al detenerlo, el MP4 se
    **publica en la galería** (`Movies/JFR Switcher`) y se borra la copia interna. Antes de copiar se
    comprueba que el MP4 ya tenga su índice (`moov`): la primera versión copió demasiado pronto y el
    video no abría en ningún reproductor. Probado: 17,26 s, 720×1280, 517 cuadros, audio sincronizado.
- **Sigue abierto:** con **~96 MB de RAM libre** durante una sesión, Android puede matar la app en
  segundo plano (es el mismo terreno del pendiente de audio).

## Tareas abiertas (en orden)

### 1. Audio y sesión en segundo plano — HECHO (27/10 16:16)

- **Qué faltaba:** `stream/EmisionService.kt` ya existía y estaba declarado en el manifiesto, pero
  **nadie lo arrancaba**: por eso Android silenciaba el micrófono (`App op 27 missing`).
- **Arreglo:** se arranca al empezar una sesión (directo **o** grabación) y se detiene al terminarla.
  Tipo **`microphone|camera`** (la lente de la tablet también se cortaba fuera de pantalla).
  Notificación en canal de **importancia normal** con botón **«Detener»** (antes caía en «Silenciadas»
  y el botón no se podía tocar), y ese botón cierra envío, grabación y servicio. Atrás ya no cierra la
  pantalla con el directo en el aire.
- **Probado en la tablet (25 s en la pantalla de Inicio):**
  - **1050/1050** cuadros al codificador, el envío creció **16 MB**, `isForeground=true`.
  - **El audio siguió:** el tramo grabado en segundo plano mide **media −46 dB y pico −10 dB** (voz
    real, no silencio).
  - «Detener» desde la cortina corta todo y, al volver, la pantalla muestra «Sin transmisión».
- **Límite conocido:** si Android **destruye** la pantalla (no solo la oculta) ese `onDestroy` suelta
  cámaras y servidor. El servicio protege el proceso, pero mover el dueño de la sesión al servicio
  sería el siguiente paso si alguna vez se ve en la práctica.

### 2. La app de cámara usa el puerto del QR — HECHO (27/10 16:23)

- **Antes:** la cámara llamaba fijo al **8787**; si el servidor subía a 8788, «la cámara no entraba».
- **Arreglo:** `CamEngine.conectar(ip, puerto)` usa el puerto recibido, y la pantalla de la cámara
  acepta y muestra `IP:PUERTO` (al escanear el QR se copian los dos). Se recuerda la última dirección.
- **Aviso en la tablet:** si el 8787 está ocupado, al arrancar sale un aviso y la ventana de
  «Conectar cámaras» lo dice («⚠ Puerto 8788: el 8787 estaba ocupado…»). El QR ya llevaba el puerto.
- **Probado en la tablet:** ocupé el 8787 a propósito → servidor en **8788**, QR
  `jfr://192.168.1.7:8788` → la app de cámara (instalada aparte en la misma tablet) conectó por
  `127.0.0.1:8788` y el switcher mostró **1 / 6 · Cámara 1 · En vivo**. Después todo volvió al 8787.
- **APK de cámara nueva:** `_material\JFR-Camera-0.2-puerto-QR.apk` (release firmada). Si el celular
  la rechaza por firma distinta, desinstalar la anterior antes (solo se pierde la última IP).

## Tareas abiertas

No queda ninguna de la lista original. Lo siguiente, si aparece: pasar el dueño de la sesión al
servicio en primer plano (ver límite en el punto 1) y probar un directo vertical real completo.

## Datos operativos

- **La clave del directo caduca** al cerrar el directo y **depende del destino** (perfil o página).
  Copiarla del Producer con el destino ya elegido, antes de cada transmisión.
- **El botón principal no pregunta:** si el modo guardado es «Solo transmitir», tocarlo **abre el
  directo** en el momento (hoy se disparó sin querer durante una prueba y Facebook rechazó la clave).
- El Producer solo abre en **versión de escritorio** (Chrome, ya configurado) y la app fuerza Chrome
  porque el enlace lo capturaba la app de Facebook.
- El cronómetro de la app mide **tiempo enviando**; el tiempo de «en vivo» real solo lo da el Producer.
- Equipos: tablet Cubot TAB KINGKONG `192.168.1.7` (serial `adb-TKK241024013152-O1DHrU`), POCO X7
  `192.168.1.2:5555`, Samsung SM-S928B `192.168.1.8:38579`.
- Compilar: `$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat :studio:assembleDebug`

## Regla aprendida (importante)

**Editar archivos solo con la herramienta de edición.** Nunca `Set-Content -NoNewline` ni redirecciones
que reescriban el archivo completo: hoy borró los saltos de línea de `StudioActivity.kt` (168 KB en una
línea) y hubo que restaurar desde el zip, perdiendo el código de la clase.
