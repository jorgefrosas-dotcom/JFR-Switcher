# Cambios sin publicar

> Aquí se anota, **una línea por cambio**, lo que ya está hecho y probado en los equipos pero
> **todavía no tiene versión publicada**. Cuando Jorge diga «sacamos versión», esta lista se convierte
> en las **notas del Release** (que son las que la app muestra en el aviso de actualización) y el
> archivo se vacía.
>
> Referencia: la última versión publicada está en
> https://github.com/jorgefrosas-dotcom/JFR-Switcher/releases/latest

**Última versión publicada:** 0.3.0 (25/11/2026) — «Modo Pokémon TCG completo y directo automático:
elige Facebook, YouTube o los dos, con los comentarios de ambas plataformas en el mismo cuadro».
Notas completas en `docs/NOTAS-0.3.0.md`.

## En 0.3.1 (compilado e **instalado en la tablet**, sin publicar)

- **Cronómetro de partida**: chip en forma de píldora (la misma forma y proporción que los chips de
  Energy/Supporter/Retreat) con el **color del banner elegido** y los números grandes en amarillo.
  Va **debajo del área de mesa, encimado a su borde inferior**. Máximo **45:00** por partida y se
  reinicia con «Partida nueva»; arranca solo al entrar al modo.
- **Batería de cada cámara dentro de su recuadro** en el programa: barrita y porcentaje, verde/ámbar/
  rojo según el nivel. El dato ya viajaba desde la app del celular.
- **Mazos personalizados**: nombre libre y **hasta dos iconos** elegidos de un catálogo de **1203
  Pokémon** (con buscador por nombre). Se guardan en la tablet y aparecen en la lista como un mazo
  más. Catálogo generado con `herramientas\generar-iconos-pokemon.ps1` (PokeAPI + verificación contra
  Limitless) y empaquetado en `assets/iconos_pokemon.json` (39 KB).
- **Redondeo de chips unificado**: cronómetro y chips de la mesa salen de la misma función de dibujo,
  así los extremos son idénticos.
- La versión sube a **0.3.1 / 28**.

Pendiente antes de publicar:

1. **Probar en la tablet el envío de video y el multistream** (YouTube incluido).
2. Probar la **batería** con cámaras conectadas.
3. Decidir si se publica la 0.3.1 como versión nueva.
