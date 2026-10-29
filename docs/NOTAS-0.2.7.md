# Notas del Release 0.2.7 — Modo Studio: preview y programa

> Texto que se sube a GitHub como notas de la versión 0.2.7 (es el que la app muestra en el aviso de
> actualización). Se guarda aquí porque `CAMBIOS-SIN-PUBLICAR.md` se vacía al publicar.

## Modo Studio (solo tablet, al girarla a vertical)

- **Al poner la tablet en vertical entra el «Modo Studio»**: dos monitores, **PREVIEW a la izquierda y
  PROGRAMA a la derecha**, los botones de siempre en medio y las tarjetas de cámaras, overlays y medios
  debajo. En horizontal sigue el diseño de siempre; el celular nunca entra a este modo.
- **Botón Transicionar (⇄)**: pasa lo del preview al programa y lo que estaba al aire al preview, con la
  transición que tengas elegida (el cuadro que sale se retira encima del nuevo: no salta de golpe).
- **Todo entra primero al preview**: cámaras, overlays y fotos. El programa **no se puede tocar con el
  dedo**, para no mover algo que ya está al aire. Los **videos de MEDIOS van directo al aire**.
- **Se trabaja en 9:16 y en 16:9** sin salir del modo: el chip de la barra cambia monitores, tarjetas,
  la orientación que se les pide a los celulares y la resolución de grabación (verificado en 1080×1920).
- **El formato se conserva al girar** las veces que sea: si tienes 16:9 sigue en 16:9, y solo cambia con
  el chip.
- **El preview conserva su escenario**: al girar y al cambiar de ventana no se pierde lo que tenías
  listo para entrar; lo que estaba al aire sigue al aire.
- **Monitores grandes**: se llevan la mayor parte del alto y las 6 cámaras van en una fila con tarjetas
  más bajas. El estado de grabación y el cronómetro quedan **pegados al botón Grabar**.
- **El botón de foto vive en el monitor de programa** (la foto sale de lo que está al aire).

## Cámaras

- **La app de cámara sigue la posición del teléfono** cuando está suelta, y ahora tiene **diseño propio
  en vertical** (los botones caben y la información queda acomodada).
- **Conectada, la orientación la decide el switcher**: lienzo 9:16 → teléfonos de pie; 16:9 → acostados.
- **Mientras grabas o transmites la rotación queda congelada** en los teléfonos: si alguien mueve un
  teléfono, la cámara no se voltea a media sesión.
- **Miniaturas derechas y sin zoom**, con el mismo encuadre que el monitor, y ya no se enciman al
  monitor al deslizar el panel.

## Estabilidad

- **El programa ya no se congela ni se queda negro** al pasar del Modo Studio al diseño horizontal, y
  ya no parpadean los monitores.
- **Si minimizas el switcher, la grabación sigue con video** (antes el video se quedaba congelado).
- **El audio de un video de MEDIOS se corta al quitarlo** (antes seguía sonando).
- **Menos carga en la tablet**: se dejó de procesar cada cámara dos veces cuando el monitor de programa
  no está en uso (la CPU bajó de 110–220 % a 68–85 %).

## Notas

- Los teléfonos necesitan la **app de cámara nueva**: se instala desde este mismo Release
  (`JFR-Camera.apk`) o con el enlace que da la propia app.
- El escenario del preview se conserva al girar y al cambiar de ventana, pero se pierde si **cierras**
  la app.
