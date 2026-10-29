# Cambios sin publicar

> Aquí se anota, **una línea por cambio**, lo que ya está hecho y probado en los equipos pero
> **todavía no tiene versión publicada**. Cuando Jorge diga «sacamos versión», esta lista se convierte
> en las **notas del Release** (que son las que la app muestra en el aviso de actualización) y el
> archivo se vacía.
>
> Referencia: la última versión publicada está en
> https://github.com/jorgefrosas-dotcom/JFR-Switcher/releases/latest

**Última versión publicada:** 0.2.6 (27/10/2026) — «Plantillas de escena y audio en vivo»

## Pendiente de publicar

### Orientación de las cámaras: la manda el switcher (28/10)

- **La app de cámara ya sigue la posición del teléfono** cuando está suelta (antes quedaba clavada
  en horizontal aunque el teléfono estuviera de pie). Con eso su propia vista previa se ve derecha.
- **Conectada, la orientación la decide el switcher**: si el lienzo es 9:16 los teléfonos se ponen
  de pie y capturan vertical; si es 16:9, acostados. Se manda al conectar y al cambiar de formato.
- **Mientras grabas o transmites la rotación queda congelada** en los teléfonos (aviso nuevo del
  switcher a cada cámara): si alguien mueve un teléfono, la cámara no se voltea a media sesión. Al
  terminar vuelve a obedecer al switcher.
- **Miniaturas de las tarjetas**: antes solo se estiraban (salían acostadas y derramadas fuera de la
  tarjeta). Ahora se **giran** con la rotación real que reporta el celular y se escalan para que
  cubran la tarjeta.
- **Al rehacer la parrilla** (cambio de formato o entrar al Modo Studio) las tarjetas vuelven a
  enganchar el video solas: antes quedaban en negro aunque la cámara estuviera conectada.
- En Modo Studio 9:16 las **6 cámaras van en una sola fila**, con tarjetas más chicas.

### Modo Studio: dos monitores en la tablet (28/10)

- **Al girar la tablet a vertical entra el «Modo Studio»** (en horizontal queda el diseño de
  siempre; el celular nunca entra a este modo). Es tipo OBS: **preview a la izquierda y programa a la
  derecha**, con los botones de siempre en una columna en medio (en 9:16) o en una fila debajo con
  «Transicionar» al centro (en 16:9). Las tarjetas de cámaras, overlays y medios van debajo,
  **4 por fila** para que quepan más.
- **Botón «⇄ TRANSICIONAR»**: pasa lo del preview al programa y lo que estaba al aire al preview,
  **con la transición elegida** (el cuadro que sale se retira encima del nuevo: no salta de golpe).
- **Todo entra primero al preview**: cámaras, overlays y fotos. El programa **no se puede tocar con
  el dedo** (se compone en el preview). Los **videos de MEDIOS van directo al aire**, porque su
  reproductor vive en el monitor.
- **Se trabaja en los dos formatos**: el chip de la barra alterna **9:16 y 16:9** sin salir del Modo
  Studio, y los monitores, las tarjetas y la grabación cambian juntos (grabación verificada en
  1080×1920).
- **La grabación y el directo salen del monitor de programa**, y el aviso de «AL AIRE» a los
  celulares también. Al volver a horizontal, **lo que estaba al aire es lo que queda**.
- La información del sistema (CPU, RAM, Mbps y temperatura) va ahora en la fila de estado, junto a
  «Sin grabación», el contador y el botón de grabar, en su tamaño normal.
- Mientras grabas o transmites la orientación queda **congelada**, para que el diseño no se mueva a
  media sesión.

## Pendiente de programar (no está hecho todavía)

- Nada anotado por ahora.

### Versión de pantalla chica, muy funcional (idea de Jorge, 27/10)

La app está pensada para tablet y en el **POCO** funciona, pero apretada. Jorge quiere **conservar la
opción de una versión de celular muy funcional** (no es prioridad ahora; se ajusta después). Lo que se
vio al abrirla en el POCO el 27/10:

- La columna de fuentes ocupa media pantalla: las 6 cámaras + la cámara del equipo + OVERLAYS + MEDIOS
  quedan **debajo del pliegue** y hay que desplazarse mucho para llegar a los overlays y a los medios.
- La fila de controles se desliza en horizontal y **el botón de la lente queda fuera de la vista**
  (hay que arrastrar la fila para encontrarlo).
- El visor de programa queda chico comparado con la tablet; Img. / ♪ Audio / A / B quedan apretados y
  A y B casi no se usan con una sola cámara de celular.
- La barra superior sí cabe, pero con el medidor de audio ya no sobra nada.

Ideas para cuando se retome (ninguna está hecha): pestañas **Cámaras / Overlays / Medios** en vez de
una sola lista larga; controles del programa encima del visor y no en otra fila; lente, zoom y foto en
un menú compacto; visor más grande con las cámaras en una tira deslizable de miniaturas.

> **Decisiones cerradas:**
> - Las **capturas** (botón de foto del visor) **no** se meten al grupo MEDIOS: Jorge decidió que no
>   las necesita ahí.
> - Lo de «traer a la biblioteca los medios importados» ya no aplica: la biblioteca se quitó de la
>   pantalla y los medios se recuerdan en el propio grupo MEDIOS.
