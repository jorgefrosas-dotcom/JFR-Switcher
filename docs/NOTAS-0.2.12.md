# Notas de la versión 0.2.12 (21/11/2026)

**Modo Pokémon TCG: cambio de cámaras en vivo y la cámara 4 a tu gusto.**

Esta versión es casi toda del modo para torneos: ahora se puede cortar a la cara de un jugador
mientras se transmite, elegir si la cámara 4 es la de la tablet o un cuarto celular, y se corrigieron
varios fallos que se notaban en una partida real.

## Cambio de cámaras en vivo

- **Tocar la tarjeta de Jugador 1 o Jugador 2 pone esa cámara a pantalla completa** en el monitor (y en
  lo que se graba o se transmite), para ver las reacciones de los jugadores.
- **Tocar «Mesa» devuelve la composición**: la mesa al fondo, los dos jugadores en ventanita y el
  **banner con el marcador**.
- **El cambio entra con un fundido de 1.5 segundos**, no con un corte seco.
- Tocar **otra vez la misma cámara** también regresa a la mesa.
- La tarjeta de la cámara que está al aire se marca con el **marco naranja**, y ese celular recibe
  **«AL AIRE»**; los demás quedan «FUERA DE AIRE».
- Si la cámara que está al aire se desconecta o se apaga, la escena **vuelve sola a la mesa** en vez de
  quedarse en negro.

## La cámara 4: la tablet o un cuarto celular

- La **cámara de la tablet ya no se enciende sola** en el modo: así el cuarto celular puede ocupar la
  cámara 4 cuando lo conectes.
- El botón de la tarjeta 4 hace un ciclo de tres pasos: **Tablet → Frontal → Trasera → apagada**
  (igual que el botón de la lente en Modo Studio). Apagada, la cámara 4 es la del cuarto celular.
- La tarjeta 4 **recupera su nombre** («Ambiente») cuando se apaga la cámara de la tablet.
- Si el cuarto celular conecta mientras la cámara de la tablet está encendida, la app avisa que hay que
  apagarla para usarlo.

## Arreglos del modo

- **La cámara de ambiente ya no se cuela como ventanita** en la esquina inferior derecha del monitor
  (antes se grababa ahí sin querer).
- **La cámara que se conecta con su hueco ya creado sí se ve**: antes se veía en su tarjeta pero el
  monitor quedaba en negro.
- **Al apagarse una cámara**, su hueco queda en negro y, si era la de ambiente, el monitor vuelve solo a
  la mesa con los jugadores.
- **Ya no aparece «desarmado» al abrir la app** con la tablet en vertical (el monitor chico y las
  tarjetas enormes); ahora el modo se acomoda solo.
- **Al volver de Modo Pokémon a Modo Studio ya se suelta el bloqueo de rotación**, así que la tablet
  puede girar y entrar al Modo Studio vertical (PREVIEW · PROGRAMA).

## Notas

- El switcher **no** recibe el sonido de los celulares: el audio sale del micrófono o de las entradas
  de la tablet. Las voces de los jugadores entran por micrófonos inalámbricos conectados a la tablet.
- Los cinco formatos quedaron probados: Pokémon (horizontal 16:9) y Studio en horizontal y vertical,
  en 16:9 y 9:16.
