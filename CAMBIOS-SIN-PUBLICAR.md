# Cambios sin publicar

> Aquí se anota, **una línea por cambio**, lo que ya está hecho y probado en los equipos pero
> **todavía no tiene versión publicada**. Cuando Jorge diga «sacamos versión», esta lista se convierte
> en las **notas del Release** (que son las que la app muestra en el aviso de actualización) y el
> archivo se vacía.
>
> Referencia: la última versión publicada está en
> https://github.com/jorgefrosas-dotcom/JFR-Switcher/releases/latest

**Última versión publicada:** 0.2.5

## Pendiente de publicar

### Barras de audio y cámara de la tablet

- **Las barras de audio suben a la barra de arriba, junto al chip de Facebook**, donde sobraba
  espacio. En la fila de controles quedaban apretadas contra el borde.
- **Vuelve el botón de la cámara de la tablet** (icono de cámara + **Apagada / Frontal / Trasera**),
  que se había perdido al reacomodar los controles: cada toque pasa de una lente a la otra y luego
  apaga la cámara. Funciona también volviendo de vertical a horizontal.

### Los medios se recuerdan entre sesiones

- **El grupo MEDIOS recuerda los últimos 10** videos y fotos: al abrir la app otra vez ya están ahí,
  con su miniatura, **sin volver a la galería**. Si alguno ya no existe o perdió el permiso, se
  descarta solo. Al entrar uno nuevo con la lista llena, sale el más viejo.

### Ventanas con carácter propio (27/10 noche)

- **Todos los cuadros** (Ajustes, Facebook, Transiciones, Audio, Texto, emparejamiento…) estrenan tema:
  **fondo negro, borde gris y esquinas redondeadas**, con el naranja de acento. Es un tema de diálogo en
  `themes.xml`, así que aplica a los 16 cuadros de una vez.

### Editor de texto mejorado

- **Tipos de letra en lista desplegable**: cada nombre se ve **con su propia letra**, así se elige viendo
  el resultado. **16 letras**, incluidas las de eventos: *Serif cursiva*, *Cursiva*, *Cursiva fina*,
  *Invitación*, *Sans versalitas* y *Serif mono*.
- **Colores propios**: botón **＋** junto a la paleta que abre un cuadro con barras de rojo, verde y azul.
  Los colores que agregues **se guardan** y aparecen siempre.
- **Animación con entrada y salida que hacen juego**: la misma que elijas (fundido, subir, zoom, rebote…)
  se usa para salir. La salida se reproduce al **ocultar** el texto (tocando su tarjeta) o al **borrarlo**
  (con la ×), y hasta que termina no desaparece.

### Editor de texto y biblioteca (27/10 noche)

- **Editor de texto para el programa**: tarjeta **«T Texto»** como primera opción en OVERLAYS. Abre un
  cuadro para escribir (varias líneas), elegir **tipo de letra** (8), **color** (6), **tamaño** y
  **cómo entra**.
- **10 animaciones de entrada**: fundido, subir, bajar, desde la izquierda, desde la derecha, zoom,
  pop, rebote, deslizar con fundido y caída con rebote.
- El texto se agrega al programa como un overlay más (se mueve, se escala, se quita) y **se guarda en
  la biblioteca**.
- **Sección BIBLIOTECA** nueva: muestra los textos guardados y las **grabaciones de la app**
  (Movies/JFR Switcher). Un toque los pone en el programa; mantener pulsado un texto lo borra.

## Pendiente de programar (no está hecho todavía)

- **Capturas (screenshots) durante la grabación**: guardarlas también en la biblioteca.

> Lo de «traer a la biblioteca los medios importados» ya no aplica: la biblioteca se quitó de la
> pantalla (27/10) y los medios ahora se recuerdan en el propio grupo MEDIOS, que es más directo.
