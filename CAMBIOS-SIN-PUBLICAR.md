# Cambios sin publicar

> Aquí se anota, **una línea por cambio**, lo que ya está hecho y probado en los equipos pero
> **todavía no tiene versión publicada**. Cuando Jorge diga «sacamos versión», esta lista se convierte
> en las **notas del Release** (que son las que la app muestra en el aviso de actualización) y el
> archivo se vacía.
>
> Referencia: la última versión publicada está en
> https://github.com/jorgefrosas-dotcom/JFR-Switcher/releases/latest

**Última versión publicada:** 0.2.7 (28/10/2026) — «Modo Studio: preview y programa»

## Pendiente de publicar

- **Celular (pantalla chica), 29/10/2026:** pestañas **Cámaras / Overlays / Medios** en la columna de
  fuentes (antes era una sola lista larga y lo de abajo quedaba fuera de la vista), columna angosta
  (205 dp) con **2 tarjetas por fila**, **controles del programa flotando encima del visor** (dejaron de
  robar 116 px de alto), ajustes de imagen en la **ventana «Img.»** (ya no hay banda fija) y **fila de
  controles completa**: la lente queda como icono con estado corto («Off» / «F» / «T») y «Img.» se ve
  sin arrastrar. Medido en el POCO X7: el visor pasó de **≈450×250 px a 1428×803** (unas 10 veces el
  área; el 16:9 ya solo está limitado por el alto de la pantalla). Probado también que **la tablet no
  cambia**: se instaló y se comprobó con captura (columnas de siempre, controles debajo del visor, sin
  pestañas).

## Pendiente de programar (no está hecho todavía)

- **Celular:** la ventana «Img.» en **dos columnas**, para que los seis ajustes entren sin desplazar.
- **Descartado con medición (29/10):** pasar la columna de fuentes a una tira de miniaturas debajo del
  visor. No habría ganado nada: en horizontal el lienzo 16:9 está limitado por el **alto**, y una tira
  abajo solo le quitaría alto. Lo que sí funcionó fue flotar los controles (eso liberó 116 px de alto).
- Nada más anotado por ahora.

### Versión de pantalla chica, muy funcional (idea de Jorge, 27/10)

La app está pensada para tablet y en el **POCO** funciona, pero apretada. Jorge quiere **conservar la
opción de una versión de celular muy funcional** (no es prioridad ahora; se ajusta después). Lo que se
vio al abrirla en el POCO el 27/10:

- La columna de fuentes ocupa media pantalla: las 6 cámaras + la cámara del equipo + OVERLAYS + MEDIOS
  quedan **debajo del pliegue** y hay que desplazarse mucho para llegar a los overlays y a los medios.
  → **Hecho el 29/10** con las pestañas.
- La fila de controles se desliza en horizontal y **el botón de la lente queda fuera de la vista**
  (hay que arrastrar la fila para encontrarlo).
  → **Hecho el 29/10**: con la columna angosta la fila cabe completa.
- El visor de programa queda chico comparado con la tablet; Img. / ♪ Audio / A / B quedan apretados y
  A y B casi no se usan con una sola cámara de celular.
  → **Mejorado el 29/10** (2,4× más grande); falta la tira de miniaturas para aprovechar el ancho.
- La barra superior sí cabe, pero con el medidor de audio ya no sobra nada.
  → **Hecho el 29/10**: barra compacta («Plant.» / «Trans.»), medidor chico y logo reducido.

Ideas que quedan: **tira deslizable de miniaturas** en vez de columna, controles del programa encima
del visor y lente/zoom/foto en un menú compacto.

> **Decisiones cerradas:**
> - Las **capturas** (botón de foto del visor) **no** se meten al grupo MEDIOS: Jorge decidió que no
>   las necesita ahí.
> - Lo de «traer a la biblioteca los medios importados» ya no aplica: la biblioteca se quitó de la
>   pantalla y los medios se recuerdan en el propio grupo MEDIOS.
