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

- **Ventana «Conectar cámaras» rehacida (29/10):** el **QR va primero y grande** (148 dp) en su propia
  columna; a la derecha la **IP en una sola línea a 13 sp**, el puerto, «Copiar IP» y «Conectadas: 0/6».
  **Se quitó el logo**, los textos bajaron a 10 sp y la ventana ahora tiene **ancho propio** (62 % de la
  pantalla o 560 dp) y alto según el contenido, con `ScrollView` por si algún día no cabe. Antes la IP
  salía cortada y el QR ni aparecía.
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
