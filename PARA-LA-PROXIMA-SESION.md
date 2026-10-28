# Para la próxima sesión — JFR Switcher

> Actualizado el **27/10/2026, 21:56** al cerrar la sesión. **Lee esto primero: tiene todo lo necesario
> para seguir sin que Jorge vuelva a explicar nada.**
> Canal de memoria de la otra PC: `%USERPROFILE%\.dsh\memoria\CAMBIOS.md` (entradas del 27/10 noche).
> Repo: `github.com/jorgefrosas-dotcom/JFR-Switcher` · Proyecto local:
> `C:\Users\noman\AndroidStudioProjects\JFR-Switcher`

## 1. Dónde estamos

- **Última versión publicada: 0.2.5** (GitHub Release + página de descargas).
- **Los equipos tienen instalado el código MÁS NUEVO que 0.2.5**, con cambios sin publicar
  (ver `CAMBIOS-SIN-PUBLICAR.md`). Tablet y POCO siguen en 0.2.5 por número de versión: es a propósito.
- **Regla de Jorge:** un cambio pequeño **no** lleva versión nueva. Se prueba en los equipos y se
  acumula. **Él avisa cuándo se publica**; ahí se sube el número, se crea el Release con las notas de
  `CAMBIOS-SIN-PUBLICAR.md` y se vacía ese archivo.
- Probado por Jorge antes de anoche: overlays de texto con cámara frontal de la tablet, animación de
  entrada y salida, selector de color con rueda, preview en vivo, cuadros a la izquierda.
- **Anoche (27/10, 21:30–22:00) quedó hecho y verificado en la tablet: los medios se recuerdan entre
  sesiones** (ver abajo, punto 2). Esa era la única tarea pendiente del traspaso anterior.

## 2. Lo último hecho: el grupo MEDIOS recuerda los últimos 10

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


## 3. La tarea pendiente (la única que queda)

**Que las capturas entren también al grupo MEDIOS.** Hoy el botón de foto del visor (`tomarFoto()`,
que captura el programa con `PixelCopy`, y `guardarFoto()`) deja el JPEG en
`Pictures/JFR Switcher`, pero **no lo pone en MEDIOS**: la foto no se puede volver a usar sin ir al
archivo. Lo natural es que al guardarla se agregue al grupo, y como el grupo ahora se recuerda, la
captura sigue ahí en la próxima sesión.

- En Android 10+ `guardarFoto()` ya tiene en la mano la `uri` que devuelve el `insert` de MediaStore
  (hoy solo la usa para escribir el JPEG): basta pasársela a `agregarMedio(uri)` antes del `return`.
- En el camino viejo (Android 9 o menos, archivo en `Fotos/`) hay que armar un `FileProvider` para
  tener una `uri` que `agregarMedio()` pueda abrir. **Antes de hacerlo, confirmar con Jorge** que
  quiere ese comportamiento y en qué equipos (la tablet es Android 13; el POCO también es reciente).

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
