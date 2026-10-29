# Para la próxima sesión — JFR Switcher

> Actualizado el **27/10/2026, 22:45** al cerrar la sesión. **Lee esto primero: tiene todo lo necesario
> para seguir sin que Jorge vuelva a explicar nada.**
> Canal de memoria de la otra PC: `%USERPROFILE%\.dsh\memoria\CAMBIOS.md` (entradas del 27/10 noche).
> Repo: `github.com/jorgefrosas-dotcom/JFR-Switcher` · Proyecto local:
> `C:\Users\noman\AndroidStudioProjects\JFR-Switcher`

## 1. Dónde estamos

- **Última versión publicada: 0.2.6** (27/10/2026) — «Plantillas de escena y audio en vivo». Las notas
  de esa versión están en `docs/NOTAS-0.2.6.md`, y el Release tiene los dos APK con los nombres
  `JFR-Switcher.apk` y `JFR-Camera.apk`.
- **Tablet y POCO están actualizados a la 0.2.6** (`PROBAR.cmd` / instalación por adb).
- **Regla de Jorge:** un cambio pequeño **no** lleva versión nueva. Se prueba en los equipos y se
  acumula en `CAMBIOS-SIN-PUBLICAR.md`. **Él avisa cuándo se publica**; ahí se sube el número en
  `gradle.properties` (`jfr.versionName` y `jfr.versionCode`, un solo lugar para las dos apps), se crea
  el Release con las notas de `CAMBIOS-SIN-PUBLICAR.md` y se vacía ese archivo.
- **Nada pendiente de programar** al cerrar: `CAMBIOS-SIN-PUBLICAR.md` quedó vacío.

## 2. Lo último hecho: plantillas de escena (27/10, 22:00–22:45)

**Lo que pedía Jorge:** que guardar una plantilla no cerrara la app, y que la plantilla guarde
orientación, resolución de grabación y emisión, overlays con su posición y tamaño, la transición, y que
al abrir los overlays ocultos queden listos pero recordando su lugar.

**Causa del cierre (arreglada):** el campo de la Activity se llamaba `handler`, y dentro de un
`apply {}` de una vista ese nombre se resuelve al **`getHandler()` de la vista**, que es nulo mientras
la vista no está pegada a la ventana → `NullPointerException` en
`uiHandler.postDelayed` al tocar «Guardar la escena actual». **Se renombró a `uiHandler` en todo el
archivo**: no volver a llamarlo `handler`.

**Lo demás que se arregló de paso:**

- El gestor de plantillas se abría **encima** del cuadro de guardar (quedaba tapado). Ahora se cierra
  el gestor, se abre el cuadro, y al guardar se vuelve al gestor. «Abrir» cierra el gestor para que se
  vea el programa con la plantilla puesta.
- `cargarPlantilla()` no refrescaba la sección OVERLAYS: los overlays ocultos de la plantilla no
  aparecían para activarlos. Ahora llama a `refreshOverlayTiles()`.
- **Los textos se perdían**: ahora `Plantilla` lleva `textos: List<TextoPlantilla>` con contenido,
  fuente, color, animación, tamaño, posición, tamaño en pantalla y si estaba oculto. `TextoItem` gana
  `color` y `tamano` para poder rehacerlos. Al abrir se rasteriza otra vez con `Textos.rasterizar`.
- La tarjeta de la plantilla resume también la emisión, los textos y cuántos overlays van en oculto.

**Cómo se probó (en la tablet, con capturas comparadas por píxeles):** se armó una escena con 2
overlays de imagen (uno oculto) y 1 texto, transición «Deslizar desde arriba», modo «Grabar +
transmitir», 720p; se guardó, se **borraron todos los overlays**, se cambió a «Solo grabar» + 480p +
«Corte directo», y al abrir la plantilla volvió todo: overlays en su sitio (diferencia de 0.76 sobre
255 con la escena de referencia), el oculto con su marca «Oculto», el texto en su lugar, la transición,
el modo y las dos resoluciones. Al terminar se borró la plantilla y los overlays de prueba y se dejó la
transición en «Desvanecer», que es el valor por defecto.

## 3. Para seguir

- **Lo primero que Jorge pidió dejar anotado (27/10, al cerrar):** la app está pensada para tablet y en
  el POCO funciona pero apretada. **Quiere conservar la opción de una versión de pantalla chica muy
  funcional**, aunque **no es prioridad ahora**: se ajusta después. El detalle de lo que se vio en el
  teléfono y las ideas propuestas están en `CAMBIOS-SIN-PUBLICAR.md` (sección «Versión de pantalla
  chica»). Cuando lo retome, empezar por ahí.
- **Lo más probable en el corto plazo:** que Jorge pruebe las plantillas con sus escenas reales y pida
  ajustes (por ejemplo, que la plantilla guarde también la calidad de las cámaras, el PiP o las cámaras
  conectadas). Todo eso hoy **no** viaja en la plantilla.
- **Cómo se prueba sin gastar datos:** `adb shell uiautomator dump` para las coordenadas reales,
  `input tap/swipe` y capturas comparadas con Pillow; ojo con el teclado en pantalla, que tapa los
  botones del cuadro (cerrarlo con `input keyevent 4` antes de tocar «Guardar»).

## 4. Modo Studio (28/10, probado en la tablet)

**Qué es:** al girar la tablet a **vertical** entra un diseño tipo OBS con **preview (izquierda) y
programa (derecha)**; en horizontal queda el diseño de siempre y **el celular nunca entra a este
modo** (su diseño se verá aparte).

**Cómo está hecho (lo importante, para no romperlo):**

- **Dos compositores.** `b.canvas` es el de siempre y en Modo Studio pasa a ser el **PREVIEW**;
  `monitorPrograma` es un segundo `CompositorView` que va **al aire**. Así **todo el código de
  edición sigue actuando sobre `b.canvas` sin tocarlo** (tarjetas, overlays, zoom, PiP, sliders), y lo
  que se graba/transmite se toma con `vistaAlAire()` / `escenaAlAire()`.
- `CompositorView.scene` ahora es `var` (se intercambian las escenas al transicionar) y tiene
  `editable = false` en el programa para que no se pueda mover nada al aire.
- **`transicionar()`**: captura el cuadro del programa (PixelCopy), intercambia las escenas y el
  cuadro viejo se retira encima con `animarVelo()` (la misma transición elegida, invertida).
- **`evaluarModoStudio()`** decide según `resources.configuration.orientation == PORTRAIT` y
  `!esPantallaCelular()`. Se llama en `onCreate`, en `onConfigurationChanged` y al terminar una
  sesión. Mientras hay sesión la orientación se **congela** (`SCREEN_ORIENTATION_LOCKED`).
- El manifiesto **ya no fuerza horizontal**; el celular se fija por código en
  `SCREEN_ORIENTATION_USER_LANDSCAPE`.
- `lienzoVertical` es el formato del lienzo (en Modo Studio manda `studioVertical`, que el chip
  alterna entre 9:16 y 16:9). Lo usan `tamanoGrabacion`, `emisor.preparar`, las tarjetas y
  `hub.vertical`.
- **`columnasFuentes()` devuelve 4 en Modo Studio** (tarjetas más chicas) y 3 en los demás.
- Al salir (`restaurarLayoutNormal`) se devuelven la cabecera, el visor, las tarjetas, los ajustes, el
  audio y el grupo de sesión a la barra de arriba, y se llama a `aplicarOrientacion()`. **Ojo:** hay
  que **soltar cada vista de su padre antes de `addView`** (Android truena con «child already has a
  parent») y `reacomodarCuerpo()` debe devolver `cuerpo.orientation = HORIZONTAL`. Los dos fallos
  salieron al probar en la tablet; no volver a introducirlos.

**Para cuando Jorge lo use con sus cámaras reales:** preguntarle si quiere ajustar el tamaño de los
monitores, ver el nombre de la cámara en el preview, o atajos (por ejemplo, doble toque en una tarjeta
para mandarla directo al aire).

## 5. Lo anterior: el grupo MEDIOS recuerda los últimos 10

**Qué hace ahora:** al cerrar la app y volverla a abrir, en `MEDIOS · VIDEOS Y FOTOS` siguen los
últimos 10 videos y fotos que se usaron, **ya con su miniatura**, sin ir a la galería del equipo.
Al agregar uno con la lista llena, sale el más viejo (y también sale de la pantalla, para que lo que
se ve sea exactamente lo que se guarda). Quitar con la **✕** también se recuerda. Un medio que ya no
se puede abrir (archivo borrado o permiso perdido) **se descarta solo, sin reventar**.

**Cómo quedó en el código** (todo en `StudioActivity.kt`, sección «medios (videos y fotos)»):

- `mediosListos` (candado): el primer `refreshMediaTiles()` ocurre en `buildSourcesGrid()`, **antes**
  de cargar. Mientras el candado esté apagado **no se guarda nada**, así no se borra lo guardado.
- `refreshMediaTiles()`: recorta la lista a `MEDIOS_RECIENTES` (10) y **al final guarda** si el candado
  está puesto. Es el **único** punto de guardado: agregar, quitar y cargar plantilla pasan por ahí.
- `guardarMediosRecientes()`: arma un JSON y lo deja en `SharedPreferences` (`jfr_studio`, clave nueva
  `medios_recientes`) con `commit()` y **no** `apply()`, para que un cierre de golpe no lo pierda. De
  cada medio se guarda `uri`, `video` y `nombre`; nunca el archivo.
- `cargarMediosRecientes()`: se llama en `onCreate` **después de `aplicarOrientacion()`** (la parrilla
  ya existe y la orientación ya está puesta). Valida cada medio con `medioDisponible()` (abre el
  archivo con `openFileDescriptor`), descarta los que ya no abren y al final enciende el candado.
- `cargarMiniaturasDeMedios()`: las miniaturas se sacan en un **hilo aparte** y la parrilla se repinta
  una sola vez al terminar, para que la app abra rápido aunque haya videos guardados.
- El permiso persistente ya lo pedía `agregarMedio()` con `takePersistableUriPermission`, así que las
  fotos y videos elegidos de la galería siguen abriendo después de cerrar la app.

**Cómo se probó** (por si hay que repetirlo): con la tablet conectada por adb,
`adb shell am force-stop com.jfrmedia.switcher.studio`, abrir la app, tocar «+ Medio», elegir un
video, y volver a hacer force-stop + abrir → la tarjeta sigue ahí con su miniatura (captura revisada).
Quitar con la ✕ y repetir el force-stop → ya no está y no vuelve.

## 2b. Arreglos de la misma noche: barras de audio y cámara de la tablet

- **Las barras de audio subieron a la barra de arriba**, justo después del chip de Facebook (ahí sobra
  espacio). En `activity_studio.xml` el `AudioMeterView` ya vive en la barra superior; se quitó del
  final de la fila de controles.
- **El botón de la cámara de la tablet había desaparecido** (icono de cámara + **Apagada / Frontal /
  Trasera**). **Causa raíz: `moverControles()` vacía la fila con `fila.removeAllViews()` y sólo vuelve
  a agregar los controles de su lista**; el botón de la lente no estaba en esa lista, así que quedaba
  fuera del árbol de vistas en cada arranque (por eso «desaparecía», y no lo rompió el cambio de los
  medios: ese cambio sólo agregó líneas, no borró ninguna). Ya se vuelve a agregar al final de la fila.
  **LECCIÓN: cualquier control que se agregue a `controlesCapa` en el XML tiene que agregarse también
  en `moverControles()`, o se pierde.**
- La etiqueta del botón quedó corta (`Frontal` / `Trasera` / `Apagada`, con icono de cámara) porque con
  «Tablet: frontal» empujaba el medidor de audio fuera del borde derecho. Ahora que el medidor vive
  arriba, la etiqueta corta se queda por gusto: se ve ordenada.

## 2c. El nivel de audio se ve desde que abres la app

**Qué hace:** el vúmetro de arriba (y el monitor de onda del botón «♪ Audio», y el nivel del cuadro de
Audio) se mueven **sin grabar ni transmitir**, para ver cómo entra el sonido antes de arrancar. Mide
igual que se va a grabar: misma entrada, mismo mudo, misma ganancia, mismo ecualizador.

**Cómo quedó (clase nueva `audio/AudioVivo.kt`):**

- Abre su propio `AudioRecord` (44,1 kHz mono) y calcula RMS y pico con la **misma calibración** que
  `Recorder.computeLevel` (RMS × 4,5). Antes de medir aplica mudo, ecualizador de entrada y ganancia,
  leyéndolos del propio `Recorder`, así no hay dos copias de los ajustes que se puedan desincronizar.
- `StudioActivity.ajustarAudioVivo()` decide: micrófono abierto solo si la app está **a la vista**
  (`enPrimerPlano`, de `onResume`/`onPause`) y **no hay sesión** (`sesionEnCurso()`).
- **El orden importa:** `startRecording()` e `iniciarTransmision()` cierran el micrófono del vúmetro
  **antes** de que el grabador lo abra (`AudioVivo.detener()` espera al hilo: hace `join` y libera).
  Si no, el grabador se queda sin audio y **graba solo video sin avisar**. Para volver a abrirlo está
  `actualizarBotonSesion()`, que se llama en cada cambio de estado; si el aparato todavía está ocupado
  reintenta 5 veces cada 400 ms.
- Al cambiar la entrada en el cuadro de Audio, el vúmetro se reabre con la entrada nueva.
- Verificado en la tablet: el micrófono abre al arrancar, las barras se mueven, y una grabación de
  prueba de 7 s salió con **pista de audio AAC y video H.264** (el MP4 se revisó por dentro).

## 3. La tarea pendiente

**Nada pendiente de programar.** Jorge decidió que **las capturas NO se meten al grupo MEDIOS**, así
que ese pendiente se cerró el 27/10.

## 4. Cómo se trabaja (no repetir errores)

- **Compilar y probar:** doble clic en **`PROBAR.cmd`** (compila las dos apps, busca los equipos y las
  instala; en el POCO usa `-i com.android.vending` porque MIUI rechaza el install normal).
  Tarda ~10 s si no cambió nada.
- **Nunca editar código con PowerShell.** Una vez destrozó todos los acentos de `StudioActivity.kt`
  (doble codificación). Se recuperó con `git checkout`. Usar **solo la herramienta de edición**.
- **Comentarios con `/*` dentro de KDoc rompen el archivo** (Kotlin anida comentarios): escribir las
  rutas sin `*.json`, p. ej. «un archivo JSON por texto».
- **Coordenadas para tocar la tablet:** las capturas se ven a 1610×1006 pero la pantalla es 1920×1200.
  **Multiplicar por ~1,19**, o mejor sacar los límites reales con `adb shell uiautomator dump` y leer
  `bounds` del nodo. A ojo se falla (ya pasó dos veces).
- **Equipos:** tablet Cubot `192.168.100.7` (el puerto adb cambia: `adb mdns services` →
  `_adb-tls-connect._tcp`), POCO X7 `192.168.100.3:5555` y también por USB.
- **Publicar un Release:** credenciales por `git credential fill` (cuenta `jorgefrosas-dotcom`), subir
  los APK con nombres exactos `JFR-Switcher.apk` y `JFR-Camera.apk`.

## 5. Lo que ya está hecho (no rehacer)

**Medios:** los últimos 10 se recuerdan entre sesiones (punto 2).

**Editor de texto** (tarjeta «T Texto», primera de OVERLAYS) con 16 tipos de letra en lista
desplegable, 10 animaciones de entrada con su salida correspondiente, paleta de colores propia con
**selector de rueda** (tono/saturación + brillo + hex + RGB), **preview en vivo en el visor** mientras
se edita, y **bloqueo del editor durante grabación/transmisión**.

**Ventanas:** cuadros con fondo negro y borde gris cargados a la izquierda (9 de ellos); el tema de
diálogo aplica a los 16 cuadros de una vez.

**Directo:** sin la API de Meta; es Live Producer + clave.

Todos los detalles, en `CAMBIOS-SIN-PUBLICAR.md` (de ahí salen las notas del Release).
