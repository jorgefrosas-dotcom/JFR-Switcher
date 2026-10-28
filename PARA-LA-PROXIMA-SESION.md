# Para la próxima sesión — JFR Switcher

> Escrito el 27/10/2026 al cerrar la sesión. **Lee esto primero: tiene todo lo necesario para seguir
> sin que Jorge vuelva a explicar nada.**
> Canal de memoria de la otra PC: `%USERPROFILE%\.dsh\memoria\CAMBIOS.md` (entradas del 27/10 noche).
> Repo: `github.com/jorgefrosas-dotcom/JFR-Switcher` · Proyecto local:
> `C:\Users\noman\AndroidStudioProjects\JFR-Switcher`

## 1. Dónde estamos

- **Última versión publicada: 0.2.5** (GitHub Release + página de descargas).
- **Los equipos tienen instalado el código MÁS NUEVO que 0.2.5**, con cambios sin publicar
  (ver `CAMBIOS-SIN-PUBLICAR.md`). Tablet y POCO, ambos en 0.2.5 por número de versión.
- **Regla de Jorge:** un cambio pequeño **no** lleva versión nueva. Se prueba en los equipos y se
  acumula. **Él avisa cuándo se publica**; ahí se sube el número, se crea el Release con las notas de
  `CAMBIOS-SIN-PUBLICAR.md` y se vacía ese archivo.
- Probado por Jorge hoy: overlays de texto con cámara frontal de la tablet, animación de entrada y
  salida, selector de color con rueda, preview en vivo, cuadros a la izquierda.

## 2. La tarea pendiente (la única)

**Guardar entre sesiones los 10 medios usados recientemente** para que al abrir la app ya estén en la
sección `MEDIOS · VIDEOS Y FOTOS`, sin ir a la galería del equipo.

### Cómo está hoy el código (leer antes de tocar)

- `StudioActivity.kt`: lista `private val medios = ArrayList<Medio>()`, con
  `data class Medio(val uri: String, val video: Boolean, val nombre: String)` (tiene un `thumb` en el
  cuerpo).
- `private fun agregarMedio(uri: Uri)` agrega a esa lista y llama a `refreshMediaTiles()`.
- `private fun refreshMediaTiles()` reconstruye las tarjetas del grupo MEDIOS (se llama en cada cambio).
- La **biblioteca** que existía se quitó de la pantalla (quedan funciones muertas:
  `refreshBiblioteca`, `tarjetaTextoBiblioteca`, `tarjetaGrabacion`, `grabacionesDeLaApp`, y la
  propiedad `bibliotecaContainer`). **Se pueden borrar** en esta tarea, pero antes confirmar que nada
  más las usa (el editor de texto las llama en «Solo guardar» y «Al programa»: hay que quitar esas
  llamadas).

### Plan propuesto

1. `private var mediosListos = false` para que el primer `refreshMediaTiles()` (que ocurre antes de
   cargar) **no borre** lo guardado.
2. `guardarMediosRecientes()`: serializar `medios` a JSON en `SharedPreferences` (clave nueva, p. ej.
   `medios_recientes`), recortando a **10** los últimos.
3. `cargarMediosRecientes()`: leer el JSON al arrancar y llenar `medios`; después marcar
   `mediosListos = true` y llamar a `refreshMediaTiles()`.
4. Llamar `guardarMediosRecientes()` al agregar un medio y al quitar uno (o al final de
   `refreshMediaTiles()` con el candado de `mediosListos`).
5. Probar: agregar 2–3 medios, cerrar la app por completo (`am force-stop`), abrirla y ver que siguen.

### Cuidado

- Los `Uri` de la galería necesitan permiso persistente (`takePersistableUriPermission`), que
  `addOverlay` ya hace para imágenes; revisar que `agregarMedio` lo haga también, o al reabrir la app
  la miniatura no cargará.
- Si un archivo ya no existe, saltearlo al cargar (no reventar).

## 3. Cómo se trabaja (no repetir errores)

- **Compilar y probar:** doble clic en **`PROBAR.cmd`** (compila las dos apps, busca los equipos y las
  instala; en el POCO usa `-i com.android.vending` porque MIUI rechaza el install normal).
- **Nunca editar código con PowerShell.** Hoy destrozó todos los acentos de `StudioActivity.kt`
  (doble codificación). Se recuperó con `git checkout`. Usar **solo la herramienta de edición**.
- **Comentarios con `/*` dentro de KDoc rompen el archivo** (Kotlin anida comentarios): escribir las
  rutas sin `*.json`, p. ej. «un archivo JSON por texto».
- **Coordenadas para tocar la tablet:** las capturas se ven a 1610×1006 pero la pantalla es 1920×1200.
  **Multiplicar por ~1,19**, o mejor sacar los límites reales con
  `adb shell uiautomator dump` y leer `bounds` del nodo. A ojo se falla (ya pasó dos veces).
- **Equipos:** tablet Cubot `192.168.100.7` (puerto adb cambia: `adb mdns services` →
  `_adb-tls-connect._tcp`), POCO X7 `192.168.100.3:5555` y por USB.
- **Publicar un Release:** credenciales por `git credential fill` (cuenta `jorgefrosas-dotcom`), subir
  los APK con nombres exactos `JFR-Switcher.apk` y `JFR-Camera.apk`.

## 4. Lo que ya está hecho (no rehacer)

Editor de texto (tarjeta «T Texto», primera de OVERLAYS) con 16 tipos de letra en lista desplegable,
10 animaciones de entrada con su salida correspondiente, paleta de colores propia con **selector de
rueda** (tono/saturación + brillo + hex + RGB), **preview en vivo en el visor** mientras se edita, y
**bloqueo del editor durante grabación/transmisión**. Cuadros con fondo negro y borde gris, cargados a
la izquierda (9 de ellos). Sin la API de Meta: el directo es Live Producer + clave.
Todos los detalles en `CAMBIOS-SIN-PUBLICAR.md`.
