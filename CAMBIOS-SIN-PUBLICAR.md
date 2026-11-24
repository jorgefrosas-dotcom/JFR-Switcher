# Cambios sin publicar

> Aquí se anota, **una línea por cambio**, lo que ya está hecho y probado en los equipos pero
> **todavía no tiene versión publicada**. Cuando Jorge diga «sacamos versión», esta lista se convierte
> en las **notas del Release** (que son las que la app muestra en el aviso de actualización) y el
> archivo se vacía.
>
> Referencia: la última versión publicada está en
> https://github.com/jorgefrosas-dotcom/JFR-Switcher/releases/latest

**Última versión publicada:** 0.2.13 (22/11/2026) — «Modo Pokémon TCG: los mazos de cada jugador y la
franja de arriba más ordenada». Notas completas en `docs/NOTAS-0.2.13.md`.

## En 0.2.14 (compilado y probado en el emulador, **sin publicar**)

- La lista de mazos del Modo Pokémon pasa de **29 a 79**: el top del sitio (25 + 4 de la página 2) más
  **50 mazos de torneo** que vienen dentro del APK (`assets/mazos_extra.json`), sacados de 124 arquetipos
  reales de 40 torneos Standard. Iconos verificados uno por uno. Notas: `docs/NOTAS-0.2.14.md`.
- **Buscador** arriba del selector de mazos (escribe «mega», «zoroark»…) y, con el teclado abierto, la
  ventana se encoge para que la lista no quede tapada.
- Los mazos extra **no muestran % de uso** (Limitless no lo publica) y no se repiten con el top.
- Herramienta nueva para regenerar la lista cuando rote el formato:
  `herramientas\generar-mazos-extra.ps1` (con caché y pausa porque la API corta con 429).
- **Vista de mesa nueva:** jugadores **verticales a los lados, de arriba a abajo**, mostrando el **60 %
  central** del cuadro (se recorta 20 % de cada lado); **la mesa al centro en horizontal**; el banner se
  queda arriba con el mismo tamaño; los **iconos del mazo dentro del área de cámara del jugador**, al pie.
  Las **tarjetas de preview** pasan a dos columnas con la forma de cada cámara (jugadores verticales,
  mesa y ambiente horizontales).
- La columna de cada jugador mide **19 %** del ancho porque `0.60 × 9/16 × 9/16` es lo que hace que el
  60 % central de un cuadro vertical 9:16 llene la columna sin deformar ni cortar arriba/abajo.
  **Si el celular mandara el cuadro horizontal, el mismo recuadro mostraría menos ancho del central**
  (se recorta más, nunca deforma) — hay que verlo con las cámaras de verdad.
- **La tablet le pide a cada celular su orientación** en este modo: jugadores verticales, mesa y
  ambiente horizontales (el mismo mensaje que usa el Modo Studio para el lienzo 9:16, pero por cámara).
  Verificado: los celulares pasan a `giro=90` y el cuadro llega vertical (proporción 0.563).
- **Mesa sin recortar**: se encaja completa en el hueco entre columnas y debajo del banner, con marco
  claro y **esquinas redondeadas de 30 px** (también en los jugadores). El área de adentro del marco
  queda 16:9 exacta.
- **Chips Energy / Supporter / Retreat dentro de la cámara de cada jugador**: en columna, asomando por
  la orilla, y al usarlos se deslizan con animación dejando la punta redondeada a la vista. Los
  **iconos del mazo** van arriba del chip de Energy, del mismo tamaño tengan uno o dos.
- **Banner sin chips**: las 6 pokebolas ahora son más grandes y van en **2 columnas × 3 filas** junto
  al marcador; nombre, mazo y récord más grandes.
- **Columna derecha del modo = espectadores y comentarios de Facebook**; los botones se reacomodaron
  (6 debajo de Cámara 4 en 3×2, Pasar turno y Ganó dentro de cada tarjeta, Cámara general se quitó y se
  abre tocando la tarjeta de Mesa).

Pendiente antes de publicar:

1. **Probar en la tablet real en una partida**, sobre todo el recorte de los jugadores con el celular
   vertical y la mesa conectada.
2. **Terminar de probar el panel de Facebook** con un directo de verdad (el 24/11 funcionó pero
   Facebook bloqueó por exceso de consultas). Detalle y aprendizajes en
   `memoria\JFR-Switcher-FACEBOOK-20261124.md`.

