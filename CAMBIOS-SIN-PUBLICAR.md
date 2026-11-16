# Cambios sin publicar

> Aquí se anota, **una línea por cambio**, lo que ya está hecho y probado en los equipos pero
> **todavía no tiene versión publicada**. Cuando Jorge diga «sacamos versión», esta lista se convierte
> en las **notas del Release** (que son las que la app muestra en el aviso de actualización) y el
> archivo se vacía.
>
> Referencia: la última versión publicada está en
> https://github.com/jorgefrosas-dotcom/JFR-Switcher/releases/latest

**Última versión publicada:** 0.2.9 (04/11/2026) — «La cámara ya no se congela al cambiar a 9:16»

## En prueba (0.2.9.2, compilada el 15/11/2026 — falta que Jorge la pruebe en la tablet)

- **Modo Studio con lienzo 16:9: los botones A y B ya son grandes.** Medían el ancho de su propia
  letra y 36 dp de alto; ahora van a **56 × 46 dp con letra de 17**. El resto de la fila (PiP, 2cam,
  −, +, Frontal) subió a **46 dp de alto y letra de 13** para que la fila quede pareja.
- **Modo Studio con lienzo 9:16:** la columna de botones pasó de 32 a **38 dp** de alto (A y B a
  **42 dp**) y la letra de 10 a **12** (A y B en **16**), porque quedaban chicos para el dedo.
- **El medidor del visor ya no pasa de 100 %.** Mostraba el CPU de la app «por núcleo» (sumando
  hilos), así que en reposo marcaba 120–150 % y parecía que la tablet estaba al tope. Ahora muestra
  el **% del equipo** (0–100) dividiendo entre los núcleos, que es comparable con la RAM. El detalle
  sin dividir (cuántos núcleos usa) se sigue anotando en el registro cada 10 s.

