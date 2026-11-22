# Cambios sin publicar

> Aquí se anota, **una línea por cambio**, lo que ya está hecho y probado en los equipos pero
> **todavía no tiene versión publicada**. Cuando Jorge diga «sacamos versión», esta lista se convierte
> en las **notas del Release** (que son las que la app muestra en el aviso de actualización) y el
> archivo se vacía.
>
> Referencia: la última versión publicada está en
> https://github.com/jorgefrosas-dotcom/JFR-Switcher/releases/latest

**Última versión publicada:** 0.2.12 (21/11/2026) — «Modo Pokémon TCG: cambio de cámaras en vivo y la
cámara 4 a tu gusto». Notas completas en `docs/NOTAS-0.2.12.md`.

Sin cambios pendientes: la 0.2.12 salió con los nueve arreglos del Modo Pokémon TCG probados en la
tablet (cambio de cámaras con fundido de 1.5 s, cámara 4 = tablet o cuarto celular, la ambiente ya no
se cuela como ventanita, la cámara que conecta con su hueco ya se ve, el modo ya no se abre desarmado,
la rotación se suelta al volver a Modo Studio y la tarjeta 4 recupera su nombre).

## Pendiente de publicar (sin versión todavía) — ajustes de la primera partida real

- **Lista de los mazos más usados del formato, con sus iconos (de Limitless).** El modo trae el
  **top 25 de `limitlesstcg.com/decks`** con el nombre del mazo, su **icono** (los mismos sprites que usa
  la página) y el % de uso. Se baja **una vez al día** al entrar al modo, se guarda en la tablet (la
  lista y los iconos, así que se ve **sin internet**) y si falla la descarga **se queda la última lista
  buena**. Tocar la fila del mazo en la tarjeta del jugador abre el selector (con botón «Actualizar» y
  «Quitar»).
- **El mazo se ve en el video**: debajo del nombre de cada jugador en el banner, con su icono y el
  nombre; y en la tarjeta de control, arriba de las pokebolas.
- **Los mazos con dos Pokémon muestran los dos iconos** (en el banner, en la tarjeta y en la mesa).
- **Iconos grandes debajo de la cámara de cada jugador** en la vista de mesa: cada icono mide **la mitad
  del ancho del recuadro de la cámara**, van centrados debajo y sin texto, para identificar de un golpe
  qué mazo juega cada quien.

- **Modo Pokémon TCG: se puede conectar una cámara sin salir del modo.** Hay un botón **«＋ Conectar
  cámara»** al final de la columna de herramientas, y tocar la tarjeta de un jugador **sin cámara**
  también abre el cuadro del QR/IP. Antes había que irse a Modo Studio a buscar el QR.
- **Pokebolas: el «−» las quita y el «+» las devuelve.** Estaban invertidos (había que apretar «+» para
  gastar una), tanto en el banner como en los cuadros de abajo.
- **Marco de color en las cámaras de los jugadores** (en la vista de mesa), con el **color de acento del
  banner en uso**: así las dos ventanitas se separan de la imagen de la mesa. El marco cambia solo al
  cambiar el diseño del banner.
- **Con un jugador a pantalla completa el banner se queda, abajo** (antes desaparecía). Con la cámara de
  ambiente sigue oculto y con la mesa va arriba, como estaba.
- **La franja de arriba del modo quedó pareja**: el banner y las **cámaras de los jugadores tienen la
  misma altura** y arrancan a la misma altura. Las cámaras quedaron en **4:3** (se recorta ~25 % del
  ancho del cuadro del celular) y eso deja el banner más ancho: **58.75 % del lienzo** (antes 60 % con
  cámaras 16:9 y 54 % con 16:10), con **los nombres, el récord, las pokebolas y los chips un poco más
  grandes**. La franja mide 23.5 % del alto del video.
