# Notas del Release 0.2.8 — Diseño móvil: el celular como switcher de mano

> Texto que se sube a GitHub como notas de la versión 0.2.8 (es el que la app muestra en el aviso de
> actualización). Se guarda aquí porque `CAMBIOS-SIN-PUBLICAR.md` se vacía al publicar.

## El celular ahora es un switcher de mano (nuevo diseño, solo celular)

- **Modo 9:16: el teléfono se pone de pie.** Al elegir 9:16 el equipo **rota a vertical** y entra un
  acomodo pensado para una mano: **tarjetas de 2 por fila a la izquierda**, **monitor de programa arriba
  a la derecha**, información del sistema y **ondas de audio** debajo, y **todos los botones al fondo de la
  pantalla**.
- **La barra de botones queda siempre visible** en vertical: si la habías ocultado en horizontal, al
  entrar al 9:16 se vuelve a mostrar (ahí no hay flecha para recuperarla).
- **Barra superior más cómoda**: botones **más grandes, más separados y todos del mismo ancho**, con el
  nombre completo (Ajustes, Plant., Trans., 9:16, Audio, Conect.). Pensada para dedos grandes.
- **El cronómetro de grabación/emisión vuelve al 9:16**, pegado a la derecha del último botón.
- **Cámara propia del teléfono**: se enciende **tocando el botón de cámara** y, ya encendida, **ese mismo
  toque rota entre frontal y trasera**; mantener pulsado la apaga. Funciona también en vertical.
- **Tarjetas más legibles**: el nombre de la cámara se lee completo y el estado es un **punto de color**
  (verde = conectada, gris = desconectada) en vez del texto que no cabía.
- **Ondas de audio fijas** debajo del monitor, sin ventana ni botón extra: el audio siempre a la vista.
- **Espacio aprovechado**: la información del sistema va en su propia tarjeta y el gráfico de audio quedó
  más bajo, para que el monitor respire.

## Ventanas del celular

- **Ajustes de imagen**: deslizadores **finos** (línea con perilla), filas separadas y letra más grande;
  la ventana es **baja y va pegada abajo**, así se ve el monitor mientras se ajusta.
- **Conectar cámaras**: en vertical el QR manda el ancho y el texto se acomoda debajo, en una columna; la
  IP ya se lee completa.

## Horizontal del celular

- Al volver del 9:16 se **restaura todo** lo que el modo vertical esconde: la información del sistema
  vuelve solo a la cabecera (sin tarjeta duplicada), la **flecha para ocultar la barra** reaparece y se
  recuperan separador, espacio y tamaños originales.

## Tablet

- **Mismo diseño de siempre**: en horizontal las 6 cámaras en tres columnas, overlays y medios abajo, el
  monitor a la derecha; en vertical el acomodo de 9:16 con su columna de controles y su tarjeta de estado.
- **Lo único nuevo son los botones de la barra superior**, más grandes y parejos (96 dp), con
  «Plantillas» y «Transiciones» completos y el botón «Grabar» entero.

## Notas

- Probado en POCO X7 (celular) y en la tablet TAB KINGKONG, en los dos modos.
- Las dos cámaras del teléfono **al mismo tiempo** no quedaron: el equipo solo entrega una a la vez con
  esta app. En su lugar, un toque rota entre frontal y trasera.
