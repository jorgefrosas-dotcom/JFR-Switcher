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
- **Si minimizas el switcher, la grabación ya no se queda con el video congelado**: mientras hay
  sesión, cada segundo se comprueba que el video al aire siga corriendo y **se reanuda solo** si el
  equipo lo pausó. Antes, en el Modo Studio, el video se grababa congelado al pasar a segundo plano.
- **Al girar varias veces ya no se copia el programa en el preview**: al salir del Modo Studio se
  **guarda el escenario del preview** y al volver se **restaura**, mientras lo que quedó al aire pasa
  al programa. Antes el programa se clonaba del lienzo en cada entrada, así que el preview terminaba
  mostrando lo mismo que el programa.
- **El formato del lienzo se conserva al girar la tablet, sin importar cuántas veces**: 16:9 entra en
  16:9 y 9:16 en 9:16, en los dos sentidos (estándar ⇄ Modo Studio). Solo cambia cuando se elige con
  el chip. Antes se **invertía** en cada giro y el formato iba y venía.
- **El programa ya no se congela en el modo estándar**: fuera del Modo Studio el monitor de programa
  (que está oculto) **seguía enganchado a las mismas cámaras**, duplicando el trabajo de cada pista y
  saturando la CPU. Ahora solo se engancha en Modo Studio (al entrar se engancha y al salir se
  suelta). Medido en la tablet: la CPU bajó de 110–220 % a **68–85 %**. Además, cuando una miniatura se
  oculta por el scroll ahora **borra su cuadro**, para que no parezca congelada.
- **Al girar la tablet ya no se desacomodan los botones**: los ajustes de imagen (Luz, Contraste, WB,
  Color, Viñeta y Poster) van **siempre en una sola fila** en el Modo Studio, sea 9:16 o 16:9. Antes
  se quedaba el acomodo del diseño anterior y, al girar, aparecían en dos columnas con el deslizable
  encima. Además, **al volver a horizontal se entra al diseño ancho de siempre** (antes se quedaba el
  9:16 y se veía apretado).
- En Modo Studio 9:16 el botón de **Transicionar** quedó **solo con las flechas encontradas (⇄), más
  grandes** (antes decía «TRANSICIONAR» en letra chica y se veía apretado).
- En Modo Studio, el **botón de foto está sobre el monitor de programa** (la foto sale de lo que
  está al aire). Al volver al diseño de siempre regresa al visor.
- El **estado de grabación y el cronómetro** quedan pegados al botón de grabar, en los dos formatos
  (9:16 y 16:9), con la info del sistema a la izquierda.
- Al **deslizar el panel de tarjetas** ya no se enciman al monitor de preview (el video se oculta en
  las tarjetas que dejan de verse completas).
- En Modo Studio 9:16 las **6 cámaras van en una sola fila**, con tarjetas más chicas y **más bajas**
  (3:4), y los **monitores se llevan la mayor parte del alto** (peso 1.9 contra 0.8 de las tarjetas):
  los dos monitores quedaron notablemente más grandes. Los overlays y medios quedan un poco más abajo
  (el panel se desplaza).
- **Miniaturas bien encuadradas**: la orientación del cuadro la aplica el renderizador de WebRTC; la
  tarjeta **no debe girarse ni escalarse a mano** (hacerlo recortaba y ampliaba la imagen: se veía con
  zoom y estirada). Ahora la miniatura queda **igual que el monitor de preview**.
- **La app de cámara tiene diseño propio en vertical**: la barra se parte en dos renglones (marca y
  estado arriba; IP, QR y Conectar abajo) y los botones de abajo se reparten el ancho, así **caben y
  la información queda acomodada** cuando el teléfono está de pie.
- **Las tres apps quedaron con este build** (tablet, POCO y Samsung). En el Samsung había una cámara
  0.1.0 con firma vieja: se desinstaló (solo guardaba la IP del switcher, ya respaldada) y se puso la
  nueva.

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
