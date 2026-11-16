# Cambios sin publicar

> Aquí se anota, **una línea por cambio**, lo que ya está hecho y probado en los equipos pero
> **todavía no tiene versión publicada**. Cuando Jorge diga «sacamos versión», esta lista se convierte
> en las **notas del Release** (que son las que la app muestra en el aviso de actualización) y el
> archivo se vacía.
>
> Referencia: la última versión publicada está en
> https://github.com/jorgefrosas-dotcom/JFR-Switcher/releases/latest

**Última versión publicada:** 0.2.9 (04/11/2026) — «La cámara ya no se congela al cambiar a 9:16»

## En prueba (0.2.9.4, compilada e instalada el 15/11/2026 en la tablet Cubot)

- **Modo Studio con lienzo 16:9: los botones A y B ya son grandes.** Medían el ancho de su propia
  letra y 36 dp de alto; ahora van a **56 × 46 dp con letra de 17**. El resto de la fila (PiP, 2cam,
  −, +, Frontal) subió a **46 dp de alto y letra de 13** para que la fila quede pareja.
- **Modo Studio con lienzo 9:16:** la columna de botones pasó de 32 a **38 dp** de alto (A y B a
  **42 dp**) y la letra de 10 a **12** (A y B en **16**), porque quedaban chicos para el dedo.
- **En Modo Studio el formato manda para la cabecera.** Con el lienzo en 16:9 dentro del Modo Studio,
  la cabecera del programa se quedaba en «PROGRAMA» a secas y escondía CPU/RAM/Mbps/temperatura, porque
  el código miraba el formato guardado y no el que se estaba viendo. Ahora la info aparece en 16:9 y se
  oculta solo en 9:16, que es donde no cabe.
- **El medidor del visor ya no pasa de 100 % y ahora sí se entiende.** Mostraba el CPU «por núcleo»
  (100 % = un núcleo), así que en reposo marcaba 120–150 % y parecía que la tablet estaba al tope
  cuando en realidad usaba un núcleo de ocho. Ahora **100 % = todos los núcleos al máximo**:
  `CPU 7%` = el 7 % del total de los 8 núcleos (medido en la tablet: en reposo da 6–8 %, que es medio
  núcleo). Comprobado contra `top` de Android (`adb`): 65 % por núcleo → 8 % en el visor.
- **El total de la tablet no se puede mostrar:** Android 13 le niega `/proc/stat` a las apps
  (`open failed: EACCES`), así que el «TAB» que se probó un rato se quitó en vez de dejar un «—».
- **El termómetro ya no insiste de gusto:** `/sys/class/thermal` también está negado y se reintentaba
  cada 2 s (30 negativas por minuto en el registro). Ahora se anota una vez y se usa el de la batería.

