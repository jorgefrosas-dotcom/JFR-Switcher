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

- **Celular 9:16 en vertical (29/10):** al elegir 9:16 el teléfono **rota a vertical**. Acomodo propio:
  barra superior pareja (sin logo/medidor/cronómetro), **tarjetas de 2 por fila a la izquierda**,
  **monitor de programa arriba a la derecha** (58 % del alto, siempre dentro de la vista),
  **tarjeta con la info del sistema** debajo (CPU, RAM, Mbps, °C), **ondas de audio fijas** debajo
  (sin ventana ni botón de audio en la barra), y **botones al fondo de la pantalla** con los
  triángulos de deslizar dentro de la barra y la **cámara propia como primer botón** (tocar
  enciende/apaga, mantener cambia de lente). El estado de cada tarjeta es un **punto verde/gris**
  en vez del texto «Sin conectar», y el nombre de la cámara ya se lee completo. El botón de foto
  quedó en la esquina inferior derecha del monitor.
- **Celular: tarjetas más grandes, 2 por fila (29/10):** la columna de fuentes pasó de 3 a **2
  tarjetas por fila** (cámaras, overlays y medios) y recuperaron el **tamaño de letra normal** (ya no
  el compacto). Para ver el resto se desliza la columna, como antes.
- **Ventana «Conectar cámaras», segunda vuelta (29/10):** quedó **compacta y ajustada al contenido**:
  ancho fijo de **360 dp** (75 % de la pantalla como tope) para que la instrucción quepa en **una sola
  línea**, **alto según el contenido** (termina donde termina el botón), **«Listo» ya no ocupa todo el
  ancho** (`wrap_content`, mínimo 140 dp) y se quitó la coletilla repetida del puerto («Puerto 8787» a
  secas). El QR sigue primero y grande (148 dp); la IP completa en una línea a 13 sp; sin logo.
- **Marco naranja de «en programa» (tablet y celular, estándar y Modo Studio):** la tarjeta de la
  **cámara**, del **overlay** y del **medio** que están al aire llevan un marco naranja de 3 dp
  (`bg_marco_programa`), como `foreground` para no pelear con el fondo de capa A/B. En Modo Studio
  manda la escena del **monitor de programa**.
- **Barra superior estandarizada:** Ajustes, Plant., Trans., 16:9, Audio y **Conectar** (antes
  «Facebook») miden lo mismo (94 dp en tablet, 66 dp en celular, 34 dp de alto). El botón pasa a
  llamarse **Conectar** porque pronto servirá para más plataformas.
- **Celular:** la línea informativa («CÁMARAS · PROGRAMA · CPU…») bajó a 9 sp; el botón de la flecha
  es más grande (54 dp), con **triángulo gordo** (▼/▲) y **aspecto de vidrio** (`bg_glass_redondo`:
  degradado translúcido + borde claro + reflejo); la barra flotante también es de vidrio con reflejo.
- **Celular (ronda anterior del día):** pestañas Cámaras / Overlays / Medios, 3 tarjetas por fila,
  controles flotando sobre el monitor, barra colapsable, triángulos ◀ ▶ de deslizamiento, botones de
  56×48 dp, audio/imagen/PiP/2 cam con icono y monitor de audio en ventana a la izquierda.

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
