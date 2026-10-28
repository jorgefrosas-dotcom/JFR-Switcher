# Notas del Release 0.2.6 — Plantillas de escena y audio en vivo

> Texto que se subió a GitHub como notas de la versión 0.2.6 (es el que la app muestra en el aviso de
> actualización). Se guarda aquí porque `CAMBIOS-SIN-PUBLICAR.md` se vacía al publicar.

## Plantillas de escena

- **Guardar una plantilla ya no cierra la app.** El cuadro de guardar se abría detrás del gestor y la
  app se caía; ya está arreglado, y al guardar vuelves al gestor con la plantilla nueva a la vista.
- **Una plantilla guarda todo lo de la escena:** orientación (16:9 o 9:16), resolución de grabación y
  de emisión, modo de sesión, la transición elegida con su duración, la posición y el tamaño de
  **cada overlay** —también los que están en oculto— y **los textos** con su letra, color, tamaño y
  animación. Antes los textos se perdían al guardar.
- **Al abrir una plantilla** todo queda puesto en el monitor de programa: los overlays visibles en su
  lugar y tamaño, y los ocultos quedan listos en su tarjeta para activarlos cuando quieras, sin perder
  su posición.
- La tarjeta de cada plantilla resume lo que trae y cuántos overlays van en oculto.

## Audio en vivo

- **Las barras de audio se mueven desde que abres la app**, sin grabar ni transmitir: ves cómo está
  entrando el sonido antes de arrancar. Mide con tu misma entrada, mudo, ganancia y ecualizador; al
  grabar toma el nivel del grabador y al terminar vuelve a la entrada en vivo. Con la app en segundo
  plano el micrófono se cierra.
- **Las barras subieron a la barra de arriba**, junto al chip de Facebook, donde sobraba espacio.
- El monitor de onda del botón «♪ Audio» también se mueve antes de grabar, y el cuadro de Audio dice
  «en vivo».

## Cámara de la tablet

- **Vuelve el botón de la lente** (Apagada / Frontal / Trasera). Se había perdido al reacomodar los
  controles; ahora cada toque pasa de una cámara a la otra y luego apaga. Funciona también al volver
  de vertical a horizontal.

## Medios

- **El grupo MEDIOS recuerda los últimos 10** videos y fotos entre sesiones, con su miniatura, sin
  volver a la galería. Si un archivo ya no existe o perdió el permiso, se descarta solo.

## Ventanas y textos

- **Todos los cuadros** (Ajustes, Facebook, Transiciones, Audio, Texto…) estrenan tema propio: fondo
  negro, borde gris y esquinas redondeadas, y se abren cargados a la izquierda.
- **Editor de texto**: tipos de letra en lista desplegable **con su propia letra** (16, incluidas
  cursivas para eventos), **colores propios** con rueda y barras RGB, **vista previa en vivo** en el
  visor mientras editas y **animación de salida** que hace juego con la de entrada (10 animaciones).
- El editor de texto queda bloqueado mientras grabas o transmites.
