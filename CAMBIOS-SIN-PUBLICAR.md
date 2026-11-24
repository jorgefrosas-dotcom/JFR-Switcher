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

Pendiente antes de publicar: **probarlo en la tablet real** en una partida (el emulador no puede probar
cámaras ni red).

