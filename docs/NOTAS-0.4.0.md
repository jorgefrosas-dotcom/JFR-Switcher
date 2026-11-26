# Notas de la versión 0.4.0 (25/11/2026)

**El Modo Pokémon ahora se transmite en vertical 9:16, listo para TikTok, Reels y Shorts, y el
multistream sale a Facebook + YouTube (y queda preparado para TikTok) con el mismo botón.**

## Modo Pokémon en 9:16 (vertical)

- **Chip 9:16 / 16:9 dentro del Modo Pokémon**, igual que el del Modo Studio: antes ahí se ocultaba.
  Se recuerda entre sesiones y queda **bloqueado mientras grabas o transmites**.
- La pantalla se pone de pie y el panel se reacomoda solo:
  - a la **izquierda**: las 4 cámaras, los **6 botones de la partida** (en un bloque fijo, siempre a
    la vista) y la caja de **PARTIDA / ESPECTADORES / COMENTARIOS**;
  - a la **derecha**: el monitor 9:16 y las **dos tarjetas de jugador** apiladas debajo.
  - El panel del programa se ajusta al ancho del monitor, así que el resto del ancho es para la
    columna de la izquierda.
- La barra de arriba se reparte en **dos filas** cuando no cabe: los botones completos arriba y el
  medidor de audio, «Sin transmisión» y el cronómetro en una tira delgada.
- Grabación y transmisión salen en **720×1280 o 1080×1920** según la calidad elegida.

## Composición pensada para TikTok, Reels y Shorts

- **Márgenes de seguridad** arriba y abajo para que el texto de las plataformas (descripción, nombre
  de la página) no tape nada importante.
- **Cámaras de jugador en 3:4 arriba** (recortan un poco arriba y abajo), **la mesa al centro**, en la
  zona más libre, y **el banner del torneo abajo**.
- **Iconos del mazo** en la unión de la cámara con la mesa, montados sobre los dos marcos.
- **Fondo del programa**: degradado con **manchas difusas del color del banner**, que se repintan
  cuando cambias de banner. El cronómetro también toma el color nuevo.
- **Marco de la mesa** del mismo grosor que el de las cámaras: se gana área de juego.

## Multistream

- **Casillas al salir al aire**: puedes elegir **Facebook, YouTube y TikTok** en cualquier
  combinación. El mismo video se codifica una vez y se reparte a los destinos elegidos.
- **TikTok por RTMP**: en «Conectar» hay dos campos para pegar la **dirección del servidor** y la
  **clave de transmisión** que da TikTok. La app las une sola.
  *Ojo: TikTok solo habilita RTMP a las cuentas que tienen ese permiso; si tu cuenta no lo tiene, el
  campo queda guardado pero no se puede transmitir por ahí todavía.*
- **Arreglo importante de YouTube**: la sesión guardada se armaba incompleta, así que al volver a
  abrir la app **no podía renovar el token** y la app **se cerraba** al tocar «Conectar» teniendo
  YouTube conectado. Ahora se guarda completa y las sesiones viejas se reparan solas.

## Lo probado en la tablet

- Multistream **Facebook + YouTube** al aire: YouTube `lifeCycle=live`, `recording`, público a los 20
  y 50 segundos; Facebook con directo creado y cerrado solo al cortar. **18 Mbps** de subida, 30 fps
  del compositor, CPU 27 %. Panel de espectadores sumando las dos plataformas.
- El modo vertical completo, con las cámaras reales y los mazos asignados.
- **TikTok**: el envío por RTMP está implementado, pero **no probado** todavía porque la cuenta no
  tiene el permiso de RTMP.
