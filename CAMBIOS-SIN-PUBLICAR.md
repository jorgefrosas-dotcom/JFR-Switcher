# Cambios sin publicar

> Aquí se anota, **una línea por cambio**, lo que ya está hecho y probado en los equipos pero
> **todavía no tiene versión publicada**. Cuando Jorge diga «sacamos versión», esta lista se convierte
> en las **notas del Release** (que son las que la app muestra en el aviso de actualización) y el
> archivo se vacía.
>
> Referencia: la última versión publicada está en
> https://github.com/jorgefrosas-dotcom/JFR-Switcher/releases/latest

**Última versión publicada:** 0.2.10 (15/11/2026) — «Audio sincronizado, botones A/B grandes y
transición arreglada en vertical». Notas completas en `docs/NOTAS-0.2.10.md`.

Sin cambios pendientes: la 0.2.10 se publicó con seis arreglos probados en la tablet Cubot
(audio sincronizado, A y B grandes en Modo Studio, transición sin flash en vertical, medidor de CPU en
% del total de los núcleos, vúmetro más barato en silencio y la cabecera del programa según el formato
que se ve).

## Pendiente de publicar (sin versión todavía)

- **Modo Pokémon TCG: la cámara de ambiente ya no se cuela como ventanita.** Antes salía un cuadrito
  en la esquina inferior derecha del monitor (y se grababa ahí) en cuanto la cámara de la tablet o una
  cuarta cámara entraban en la escena. Ahora la ambiente solo aparece a pantalla completa con
  «Cámara general», y al volver a la mesa sale de la escena.
- **Modo Pokémon TCG: tocar una tarjeta ya no desarma el monitor.** Antes, tocar por ejemplo «Mesa»
  borraba del monitor las otras tres cámaras (quedaba una sola a pantalla completa). Ahora la escena
  del modo es fija; la tarjeta 4 es la que pone o quita la cámara general.
- **La cámara que se conecta con su hueco ya creado sí se ve.** La escena del modo deja los cuatro
  huecos hechos (en negro) antes de que las cámaras lleguen; al conectar, el video no se enganchaba a
  ese hueco y la cámara se quedaba en negro en el programa aunque la tarjeta sí la mostrara.
- **Al apagarse una cámara**, su hueco del modo se queda en negro y, si se fue la de ambiente, el
  monitor vuelve solo a la mesa con los jugadores (antes quedaba la pantalla en negro).
