# Notas de la versión 0.3.0 (25/11/2026)

**Modo Pokémon TCG completo, y el directo ahora se crea solo: elige Facebook, YouTube o los dos a la
vez, y los comentarios de las dos plataformas aparecen en el mismo cuadro.**

## Transmitir sin abrir el Live Producer

- Con un **token de usuario** de Facebook, la app trae la **lista de tus Páginas** y le da a cada una
  su propio token: eliges con un toque y ya.
- La app **crea el directo ella misma** en la Página elegida (estado «al aire») y recibe de Facebook la
  dirección con la clave incluida. Ya no hay que copiar claves.
- Al detener, el directo **se cierra en Facebook**, así no quedan transmisiones «fantasma».
- **Campo de descripción** del directo, que se manda a Facebook y a YouTube.

## YouTube y multistream

- **«Conectar con YouTube»**: login de Google dentro de la app (código de autorización + PKCE). La
  contraseña nunca pasa por la app.
- La app **crea la transmisión en YouTube** con arranque y corte automáticos y recibe su clave.
- **Multistream**: el mismo video se codifica una vez y se reparte a **dos destinos a la vez**
  (Facebook y YouTube). Al detener, se cierran las dos transmisiones.
- Botón **«Salir al aire»** que pregunta primero el destino: **Facebook, YouTube o Facebook + YouTube**.
- Título fijo «JFR Media»; la descripción es la que escribas.

## Comentarios de las dos plataformas en el mismo cuadro

- El panel de la derecha muestra **espectadores de Facebook** y **los comentarios de Facebook y de
  YouTube juntos**, cada uno con **su etiqueta de origen**: `FB` en azul, `YT` en rojo.
- Los de YouTube traen **el nombre real** de quien escribe; los de Facebook llegan sin autor (Meta
  reserva ese dato para apps con permiso revisado), así que se muestra solo el texto.
- Se consultan **cada 30 segundos** y solo **mientras estás al aire**; los espectadores, cada 3
  minutos. Así no se topa el límite de la aplicación de Facebook (que llegamos a provocar el 24/11).

## Modo Pokémon TCG

- Vista de mesa: **jugadores verticales a los lados** mostrando el **60 % central** de su cámara, la
  **mesa al centro encajada sin recortes** y el ambiente debajo, con marcos y **esquinas de 30 px**.
- **Chips de Energy / Supporter / Retreat dentro de la cámara de cada jugador**, asomando por la orilla
  y deslizándose con animación al usarlos.
- **Banner** sin chips: las **6 pokebolas más grandes en 2 columnas × 3 filas** junto al marcador,
  con nombre, mazo y récord más grandes.
- **La lista de mazos pasa a 79** (top del sitio + 50 de torneo dentro del APK) y hay **buscador**.
- Los botones se reacomodaron: **6 debajo de Cámara 4** en 3×2, **Pasar turno** y **Ganó** dentro de la
  tarjeta de cada jugador, y la cámara general se abre **tocando la tarjeta de Mesa**.

## Arreglos de emisión

- **Si el equipo no tiene micrófono usable, transmite igual sin audio** (antes abortaba todo).
- **Detener y volver a transmitir** ya no falla con «must be stopped before prepareVideo»: se limpia la
  sesión anterior y, sin audio, la grabación de respaldo va solo con video.
- El compositor **entrega cuadros continuos mientras graba o transmite**, para que el directo no se
  congele si la escena está quieta.
- El cuadro de Facebook quedó **grande y ordenado**, con las opciones del Live Producer (ID de Página,
  servidor, clave y la prueba del token) dentro de **«Configurar respaldo»**.

## Lo que hay que saber

- **Lo nuevo de YouTube y el multistream se probó compilando y con el login, pero no con un directo real
  todavía**: el emulador no logra sacar video. La primera prueba en la tablet es la definitiva.
- Para el multistream conviene empezar en **720p en cada destino**: subir dos veces 1080p pide ~9 Mbps
  sostenidos.
