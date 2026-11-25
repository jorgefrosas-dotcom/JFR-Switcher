# Cambios sin publicar

> Aquí se anota, **una línea por cambio**, lo que ya está hecho y probado en los equipos pero
> **todavía no tiene versión publicada**. Cuando Jorge diga «sacamos versión», esta lista se convierte
> en las **notas del Release** (que son las que la app muestra en el aviso de actualización) y el
> archivo se vacía.
>
> Referencia: la última versión publicada está en
> https://github.com/jorgefrosas-dotcom/JFR-Switcher/releases/latest

**Última versión publicada:** 0.3.2 (25/11/2026) — tag `v0.3.2` — «Modo Pokémon TCG completo y directo automático:
elige Facebook, YouTube o los dos, con los comentarios de ambas plataformas en el mismo cuadro».
Notas completas en `docs/NOTAS-0.3.0.md`.

## En 0.3.2 (compilado, instalado en la tablet y **publicado en GitHub**: tag `v0.3.2`)

**YouTube funcionando de punta a punta** (probado con el canal real «Mundo 9 channel»):

- **Cliente OAuth de tipo iOS** en lugar del de Android: los clientes «Android» solo sirven para el
  SDK de Google Sign-In; con ese tipo Google contestaba `400 invalid_request` antes de mostrar
  siquiera el selector de cuentas. El id vive en `YouTubeLive.CLIENTE_ID` y en `appAuthRedirectScheme`.
- **`select_account`** en la petición: Google siempre muestra el selector, así se puede entrar con la
  cuenta del canal y no con la que tenga la sesión del navegador.
- **La respuesta del login llega por `onNewIntent`** (la pantalla es `singleTask`). Antes Google
  autorizaba y la app se quedaba en «sin conectar».
- **El canal se consulta en su propio hilo**: en el hilo de la interfaz Android lanzaba
  `NetworkOnMainThreadException` y la app creía que la cuenta no tenía canal.
- **`liveBroadcasts/bind`**: sin atar la transmisión al stream, YouTube la deja en `created` para
  siempre y **nunca sale al aire**, aunque la app diga «enviando». También se manda
  `snippet.scheduledStartTime`, que la API exige.
- Avisos claros: si la cuenta no tiene canal (`youtubeSignupRequired`) o si Google rechaza el token,
  el mensaje dice qué hacer.

**Espectadores de las dos plataformas en el panel:**

- Número grande con el **total sumado** y, debajo, el desglose `Facebook N · YouTube M`.
- Facebook por `live_views` (cada 3 min); YouTube por `videos.liveStreamingDetails.concurrentViewers`
  (cada 90 s). El número de YouTube va 1–2 min atrasado: así lo publica Google.

**Cambios de esta tanda que también quedan aquí:**

- **Cronómetro manual**: ya **no arranca solo**; tiene ▶/⏸ y ↺ en la cabecera del panel derecho y el
  chip del programa copia lo que dice el panel. Pausar conserva el tiempo; tope 45:00.
- **La batería de cada cámara se queda solo en las tarjetas de la izquierda**; se quitaron los chips
  del programa (y su dibujante), a petición de Jorge.
- **«Traer al frente»** los adornos: al conectar una cámara su video se reacomoda al final de la
  escena y tapaba el cronómetro.
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
