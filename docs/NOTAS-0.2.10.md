# Notas del Release 0.2.10 — Audio sincronizado, botones A/B grandes y transición arreglada

> Texto que se sube a GitHub como notas de la versión 0.2.10 (es el que la app muestra en el aviso de
> actualización). Se guarda aquí porque `CAMBIOS-SIN-PUBLICAR.md` se vacía al publicar.

## El audio ya no se adelanta a la imagen

- En las grabaciones el sonido iba **340 ms adelantado**: se oía antes de que se movieran los labios.
  La causa era que **el audio y el video se medían con relojes distintos** (el video con el reloj del
  equipo y el audio con su propio cero), y el primer cuadro del video llega unas décimas después.
- Ahora la primera muestra de audio se marca **en el mismo reloj que el video**, con la marca de
  tiempo del micrófono (`AudioRecord.getTimestamp`) cuando el equipo la da; si no, con el instante de
  la primera lectura menos la duración del bloque.
- **Medido:** antes `audio 0.000 / video 0.340`; ahora las dos pistas arrancan en el mismo entorno
  (13 ms de diferencia en la prueba). Jorge lo probó grabándose y lo dio por bueno.

## Botones A y B grandes en Modo Studio

- En el **lienzo 16:9** los botones A y B medían el ancho de su propia letra y 36 dp de alto: eran los
  dos más chicos de la fila, al lado de un «⇄ TRANSICIONAR» de 44 dp. Ahora van a **56 × 46 dp con
  letra de 17**, y el resto de la fila (PiP, 2cam, −, +, Frontal) subió a **46 dp con letra de 13**
  para que quede pareja.
- En la **columna del 9:16** también estaban chicos: pasaron de 32 a **38 dp** de alto (A y B a 42) y
  la letra de 10 a **12** (A y B en 16).

## La transición ya no hace un «flash de ida y vuelta» en vertical

- Con la tablet en vertical, al pasar de una cámara a otra la transición se veía **dos veces**: un
  cambio seco y después la transición buena. **No eran dos disparos** (se comprobó con registro): era
  una **carrera** entre el hilo de dibujo y el principal. El cuadro que sale (el «velo») se armaba
  *después* de pedir el cuadro, y en vertical el monitor está dibujando todo el tiempo por la cámara
  en vivo, así que a veces alcanzaba a pintarse la escena nueva sin el velo.
- Ahora **el velo se arma primero**, después se cambian las escenas y se pide **un solo cuadro**. No
  hay ventana para el parpadeo.

## El medidor del visor ya no pasa de 100 %

- Mostraba el CPU de la app «por núcleo» (100 % = un núcleo), así que en reposo marcaba 120–150 % y
  parecía que la tablet estaba al tope. Ahora **100 % = todos los núcleos al máximo**: la app usa
  **6–8 %** en reposo, que es medio núcleo de los ocho.
- El consumo de **la tablet completa** no se puede mostrar: Android 13 le niega `/proc/stat` a las
  apps. Tampoco deja leer el termómetro del procesador (`/sys/class/thermal`), así que se usa el de la
  batería y ya no se reintenta cada 2 segundos.

## Menos consumo en reposo

- El **vúmetro** se repintaba 40 veces por segundo aunque no hubiera sonido. Ahora baja a **10 fps en
  silencio** y vuelve a 40 en cuanto el nivel se mueve (o mientras el LED CLIP está encendido). El
  hilo de dibujo bajó de **38 % a 23 %** de un núcleo.

## Probado en los equipos

- **Tablet CUBOT TAB KINGKONG** (Android 13, 8 núcleos) con las cámaras **Samsung SM-S928B** y
  **POCO 2412DPC0YA**: grabación de prueba, transiciones entre cámaras en vertical y en horizontal,
  botones A/B y medidor de CPU, todo con `adb` y capturas de pantalla.
- Las **miniaturas de MEDIOS siguen reproduciéndose en vivo**, a propósito: Jorge las usa para decidir
  el cambio de cámara.

> Los dos APK del Release (`JFR-Switcher.apk` y `JFR-Camera.apk`) van juntos y firmados con la misma
> llave: se instalan encima de la versión anterior sin desinstalar y conservan los ajustes.
