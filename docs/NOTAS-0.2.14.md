# Notas de la versión 0.2.14 (23/11/2026)

**Modo Pokémon TCG: la lista de mazos pasa de 29 a 79, con buscador, y la vista de mesa cambia a
cámaras verticales a los lados con la mesa al centro.**

## Vista de mesa nueva (23/11/2026)

- **Jugador 1 a la izquierda y jugador 2 a la derecha, verticales y de arriba a abajo del monitor**, con
  el **60 % central** de su cuadro (se recorta 20 % de cada lado). La columna mide **19 %** del ancho:
  sale de `0.60 × 9/16 × 9/16`, que es lo justo para que el 60 % central de un cuadro vertical 9:16 llene
  la columna **sin deformar y sin cortar arriba ni abajo**.
- **La mesa al centro, horizontal (16:9)**, con el área de juego de los dos jugadores, debajo del banner.
- **El banner se queda arriba y centrado**, con el mismo tamaño (58.75 % del ancho, 23.5 % del alto):
  entra justo en el hueco entre las dos columnas.
- **Los iconos del mazo en uso van dentro del área de cámara de cada jugador, pegados al pie** (antes se
  montaban un 30 % sobre la cámara y quedaban fuera del recuadro).
- **Las tarjetas de preview** ahora van en dos columnas y con la forma de su cámara en el programa: los
  dos jugadores **verticales** (3:4) y la mesa y el ambiente **horizontales** (16:9).

## De dónde salen los 50 mazos nuevos

- El top de `limitlesstcg.com/decks` publica **solo 29 arquetipos** del Estándar (25 en la primera
  página y 4 en la segunda). Para completar la lista no se inventó nada: se leyeron **los mazos que los
  jugadores llevaron de verdad a 40 torneos Standard** por la API de Limitless, y de ahí salieron **124
  arquetipos distintos**.
- La app se queda con el top del sitio (**29**, con su % de uso y refresco diario) y le pega **50 mazos
  más** elegidos por **cuántas veces aparecieron en esos torneos**: Ogerpon Meganium Hydrapple, Alakazam
  Dudunsparce, Lopunny Dudunsparce, Dragapult Dudunsparce, Dragapult Dusknoir, Dragapult Blaziken,
  Sharpedo Toxtricity, Mega Golisopod, Lucario Hariyama, Iron Thorns, Igglybuff, Toucannon…
- Esos 50 vienen **dentro del APK** (`assets/mazos_extra.json`), así que **no gastan datos ni dependen
  de internet**: se ven desde el primer arranque. El top del sitio se sigue refrescando **una vez al día**
  como antes.
- Los iconos son los mismos sprites de Limitless y se **verificaron uno por uno** antes de meterlos (alguno
  no existe en el CDN y ese mazo se descartó).

## Buscador en el selector de mazos

- Arriba de la lista hay un campo: escribir «mega», «zoroark» o «igglybuff» deja solo los que coinciden.
  Con 79 mazos es más rápido que bajar con el dedo.
- El encabezado dice cuántos mazos extra trae la app y la fecha de la última actualización del top.

## Detalles que se cuidaron

- **No se repiten mazos.** La API usa el nombre corto («Dragapult», «Slowking») y el sitio el largo
  («Dragapult ex», «Slowking Seek Inspiration»). Por eso al armar la lista se comparó por **iconos
  exactos** y no por nombre: así «Dragapult» se descarta por ser el mismo mazo que «Dragapult ex», pero
  «Dragapult Dudunsparce» se queda, porque es otro mazo.
- El cajón de sastre «Other» de Limitless **no** entra en la lista, porque no es un mazo.
- Los mazos extra **no muestran % de uso** (Limitless no lo publica para ellos) y no se les inventó uno.
- Si el top diario falla, la lista sigue funcionando: los 50 extra están en el APK.

## Cómo se regenera la lista (para la próxima rotación)

```
powershell -File herramientas\generar-mazos-extra.ps1 -Cuantos 50
```

Baja los torneos recientes, guarda la muestra en `%TEMP%\jfr-mazos-cache.json` (con pausa de 1,5 s entre
consultas porque la API corta con 429), verifica los iconos y reescribe `studio\src\main\assets\mazos_extra.json`.
Con `-Forzar` se vuelve a bajar la muestra.

## Probado

- En el **emulador de Android 14** (1920×1200 a 280 dpi): el selector muestra los 79 mazos con icono, el
  buscador filtra y al elegir queda en la tarjeta y en el banner.
- Pendiente: probarlo en la tablet real con la partida en vivo.
