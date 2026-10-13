# Bitácora de Viaje — Manual de usuario

> Documento derivado del plan y del estado de la app al cierre de la Fase 5 (2026-10-13).
> Se regenera cuando la app cambia; su historia está en git.

## Qué hace la app

Bitácora de Viaje registra un viaje mientras lo hacés: el recorrido (por GPS), las fotos y
videos que sacás, las notas de voz que dictás y las notas de texto que escribís. Con eso arma
una **bitácora cronológica** por etapa (cada día o tramo), la **exporta** en formatos abiertos
para verla en la computadora, en Google Earth o en Google My Maps, y genera un **video
resumen** (uno corto de 1 minuto en el teléfono, o uno más largo en el teléfono o en la PC).

Todo funciona sin conexión mientras viajás. Internet solo hace falta para dos extras
opcionales: buscar información de los lugares visitados (Wikipedia, OpenStreetMap) y, si
cargás tu propia clave, reescribir esos textos con un asistente de inteligencia artificial.

## Instalación y permisos

La app se instala directamente en el teléfono (no está en Google Play). Al abrirla por
primera vez pide los permisos que necesita, explicando para qué cada uno:

| Permiso | Para qué | Si lo negás |
|---|---|---|
| Ubicación precisa | Registrar el recorrido | No se puede grabar una etapa |
| Notificaciones | Mostrar el registro en curso y sus botones | El registro sigue, sin aviso en pantalla |
| Fotos y videos | Encontrar solas las fotos del viaje en la galería | Solo se vinculan las fotos sacadas desde la app |
| Micrófono | Notas de voz (y sonido en los videos de la cámara propia) | Sin notas de voz; los videos salen mudos |
| Cámara | Cámara integrada | Seguís usando la cámara del teléfono |

La app **nunca modifica tus fotos ni videos originales**. Las copias que exporta pueden
perder datos de ubicación si así lo elegís (ver "Zonas de privacidad").

## Registrar un viaje

1. En **Mis viajes**, tocá **Nuevo registro** (o **Excursión o trekking**).
2. Elegí qué estás haciendo (**Nueva etapa**, **Excursión o visita**, **Trekking**), el medio
   de transporte y, si querés, un nombre y una intención ("hoy vamos al glaciar").
3. Elegí el modo: **Completo** (máximo detalle del recorrido, consume más batería) o
   **Económico** (para trekkings largos sin recarga; pierde detalle en zigzags).
4. Tocá **Iniciar**. La pantalla de sesión muestra el mapa con tu posición, el tiempo, la
   distancia, la velocidad y los puntos registrados, y los botones grandes:
   **Nota de voz**, **Marcar**, **Destacar**, **Foto**, **Pausar** y **Terminar**.

Mientras la etapa está en curso aparece una notificación permanente con la distancia y el
tiempo, y botones para pausar, dictar una nota de voz o terminar. Podés apagar la pantalla.

Detalles que conviene saber:
- **Reposo automático**: si no te movés durante un rato (configurable: 3, 5, 10 o 15
  minutos), el GPS baja al mínimo y la etapa queda "en reposo"; al moverte, se reanuda sola.
  Un reposo también cuenta como una parada en la bitácora.
- **Corte diario**: una etapa que pasa la medianoche se cierra sola y abre una nueva para el
  día siguiente, así cada día tiene su propia bitácora.
- **Excursión dentro de una etapa**: si iniciás una excursión o trekking con una etapa en
  curso, la etapa queda en pausa y, al terminar la excursión, la app ofrece reanudarla.
- **Cambio de medio**: en la sesión activa, "Medio: A pie · cambiar" permite pasar a bici,
  auto, bus, tren, barco o avión; la bitácora anota el cambio.
- **Si la app se cierra o el teléfono se reinicia**: al volver a abrirla ves "Tu registro se
  interrumpió" con las opciones **Reanudar** o **Cerrar etapa**. Lo registrado hasta ese
  momento nunca se pierde.
- **Batería baja**: con menos del 15 % y sin cargar, la app pasa sola al modo económico y
  avisa.

### Notas, marcadores y destacados

- **Nota de voz**: tocá el botón, hablá y tocá **Listo**. La nota queda ubicada en el mapa y
  se transcribe en el teléfono (sin enviar el audio a ningún lado). Si la transcripción
  falla, la nota se guarda igual con un botón **Reintentar**. También podés dictar desde la
  notificación, desde el mosaico "Nota de voz" del panel de ajustes rápidos, desde el widget o
  desde el acceso directo del ícono, incluso con la pantalla bloqueada.
- **Marcar**: deja un marcador en el lugar actual, con un texto opcional ("acá empieza el
  sendero").
- **Destacar**: marca el momento como importante; el video lo prioriza.
- **Foto**: abre la cámara de la app. La foto (o el video) queda en tu galería, en la carpeta
  *BitacoraViaje*, con la posición exacta del momento, y vinculada a la etapa al instante.
  Después de una foto podés tocar **Dictar una nota sobre esta foto**.

### Terminar la etapa

Al tocar **Terminar**, la app confirma, calcula el resumen (distancia, tiempo en movimiento,
velocidad máxima, desnivel), busca las fotos y videos del período en la galería y los ubica
sobre el recorrido, detecta las paradas y, si hay conexión, busca información de los lugares.
En la pantalla de cierre podés **crear el video de 1 minuto** de la etapa.

## Ver la bitácora de un viaje

En **Mis viajes**, tocá el viaje. Tiene cuatro pestañas:

- **Línea de tiempo**: por etapa y por día, los eventos en orden: inicio, fotos, notas,
  paradas ("Llegada a una parada", "Salida de la parada (5 min)"), reposos, cambios de medio,
  tramos "Recorriste 3,2 km en 45 min". Tocar una foto o nota la muestra en el mapa.
- **Mapa**: el recorrido completo con las paradas, fotos y notas. El botón **Usar sin red**
  descarga el mapa de la zona del viaje para verlo sin señal (ver más abajo).
- **Fotos**: la galería del viaje. Las fotos que la app no pudo ubicar con seguridad
  aparecen en **Revisar**: ahí podés ubicarlas tocando el mapa, usar la hora del recorrido,
  excluirlas del viaje o corregir el reloj de la cámara (si una cámara aparte tenía la hora
  mal, la corrección se aplica a todas sus fotos).
- **Lugares**: la información encontrada de los lugares visitados, con su fuente (Wikipedia,
  OpenStreetMap, Wikidata). Por cada lugar podés **Usar en el video**, **Descartar** o
  **Editar el texto**. Con una clave de IA configurada aparece además **Reescribir**.

### Mapa sin red

Antes de ir a una zona sin señal, abrí el mapa del viaje y tocá **Usar sin red**. La app
muestra cuánto ocupa la zona y ofrece dos niveles de detalle: **calles y caminos** o
**senderos y detalle**, con el tamaño estimado. La descarga se hace por zonas (un viaje con
etapas en dos lugares lejanos baja una zona por cada uno). Las zonas descargadas se ven en
**Ajustes → Mapas sin red**, donde podés quitarlas para liberar espacio.

Limitación: una sola etapa que cubra una zona enorme (un vuelo, una ruta de cientos de
kilómetros) no se puede bajar entera; la app lo avisa. El mapa se descarga para la zona del
viaje ya registrado: si querés tener el mapa de un lugar antes de llegar, creá el viaje y
registrá unos metros en destino cuando todavía haya señal, o bajalo desde un viaje anterior
en la misma zona.

## Exportar el viaje a la computadora

En el detalle del viaje, tocá el ícono de compartir. Elegí si incluir las fotos y videos
originales y los audios de las notas, y a dónde mandar el paquete: **Guardar en…** (una
carpeta que elijas), **Dejar en Descargas/BitacoraViaje** o **Compartir** (Drive, correo,
Quick Share). El paquete es un archivo ZIP con:

- **LEEME.txt**: qué contiene, cuántos puntos, fotos y notas, qué faltó y los créditos.
- **bitacora.html** y **bitacora.md**: la bitácora completa para leer en cualquier
  navegador o editor, con mapa, fotos y notas.
- **tracks/**: el recorrido completo y por etapa en GPX, KML/KMZ (para Google Earth y My
  Maps) y GeoJSON.
- **tablas/**: puntos, medios, notas y lugares en CSV para hojas de cálculo.
- **trip.json**: todos los datos del viaje en un formato abierto (sirve para volver a
  importar el viaje en otro teléfono y para el generador de video de la PC).
- **media/** y **audio/**: las copias de las fotos, videos y notas de voz, si las incluiste.
- **subtitulos/notas.srt**: las notas de voz como subtítulos.

Si la exportación se interrumpe (se cierra la app, se queda sin batería), al reintentar
continúa desde donde iba sin duplicar nada. Una exportación terminada se puede repetir con
**Exportar de nuevo**.

### Zonas de privacidad

En **Ajustes → Zonas de privacidad** podés definir círculos (tu casa, tu trabajo) de 100 a
1000 metros, desde tu posición actual o con coordenadas. Al exportar, con la opción **Aplicar
zonas de privacidad** encendida, los puntos del recorrido dentro de esas zonas se omiten (el
recorrido se corta ahí, sin unir los extremos con una línea), las paradas y lugares de
adentro desaparecen, y las fotos y notas de adentro salen sin ubicación. Con **Quitar el GPS
de las fotos exportadas dentro de las zonas**, las copias de esas fotos tampoco llevan la
ubicación en sus datos internos. El diálogo y el LEEME.txt informan exactamente cuántos puntos,
paradas y fotos se omitieron. La base del teléfono conserva todo; solo cambia lo que exportás.

Atención: los videos y las fotos en formato HEIC dentro de una zona conservan sus propios
datos de ubicación; el LEEME.txt los lista por nombre para que los revises antes de
compartir.

## Importar y respaldar

- **Importar**: desde **Mis viajes** (ícono de importar) podés cargar el ZIP o el trip.json
  de un viaje exportado, por ejemplo para pasar un viaje a otro teléfono. Si el viaje ya
  existe, la app pregunta si reemplazarlo o mantener los dos.
- **Respaldos en el teléfono**: al cerrar cada etapa la app guarda sola un respaldo
  comprimido del viaje (sin fotos), que se incluye en la copia de seguridad de Android. Se
  ven y se pueden restaurar desde **Ajustes → Respaldos en el teléfono**.

## Video resumen

- **Video de 1 minuto**: al terminar una etapa (o desde su pantalla de cierre). Combina el
  mapa con el recorrido, las mejores fotos (prioriza las destacadas y evita repetidas o
  movidas), las paradas y las notas de voz como subtítulos. Queda en la galería, en
  *Movies/BitacoraViaje*, listo para compartir.
- **Video completo**: desde el detalle del viaje (ícono de reproducción). Elegís la duración
  (1, 3, 5 o 10 minutos), el formato (horizontal 16:9 o vertical 9:16), qué hacer con las
  notas de voz (subtítulos, audio original o ambos), si incluir las tarjetas de lugares, el
  mapa animado y los clips de video, y una música de fondo propia con su volumen. **Generar
  plan** arma la lista de escenas, que podés revisar, reordenar y recortar antes de
  **Renderizar**. El mismo plan se puede exportar y renderizar en la computadora con el
  generador de escritorio (ver el manual técnico), que admite videos más largos y mejor
  calidad.

## Ajustes

- **Modo de registro**: Completo o Económico (por defecto para las etapas nuevas).
- **Reposo automático**: minutos quieto antes de bajar el GPS.
- **Unidades**: métricas o imperiales.
- **Motor de transcripción**: automático, whisper en el teléfono o el reconocedor del
  sistema; idioma; **modelo whisper** (base o small, se descarga desde Ajustes con progreso y
  verificación); transcribir solo cargando.
- **Respaldos en el teléfono**.
- **Información de lugares (OpenStreetMap, Wikipedia)**: activar o no; redondear las
  coordenadas enviadas a 1 km (privacidad); servidores alternativos.
- **Mapas sin red**: zonas descargadas y su tamaño.
- **Zonas de privacidad**.
- **Reescritura con IA (opcional)**: tu clave de la API de Claude, guardada cifrada en el
  teléfono; se usa solo para reescribir los textos de los lugares con la información de
  Wikipedia y Wikidata. Nunca se envían audios, fotos ni tu recorrido.
- **Acerca de**: versión y licencias (Wikipedia CC BY-SA, OpenStreetMap ODbL, MapTiler).

## Accesos rápidos

- **Notificación** del registro en curso: Pausar/Reanudar, Nota de voz, Terminar.
- **Panel de ajustes rápidos** (deslizar desde arriba): mosaicos **Nota de voz** (activo
  solo con un registro en curso) y **Bitácora** (pausa o reanuda; sin registro abre la app
  en "Nuevo registro"; si el registro se interrumpió, vuelve a la sesión). Se agregan desde
  el lápiz de edición del panel.
- **Widget** de pantalla de inicio: estado del registro, **Nota de voz** y **Marcar** (o
  **Nuevo registro** cuando no hay registro).
- **Accesos directos** manteniendo apretado el ícono de la app: **Nota de voz** y **Nuevo
  registro**.

## Cuando algo se ve raro

- **El recorrido tiene huecos**: el GPS pierde señal bajo techo, en cañones o con el
  teléfono en el bolsillo en modo económico; la bitácora muestra "GPS perdido / recuperado".
  Un hueco con el mismo lugar antes y después se cuenta como parada.
- **Una foto quedó en otro lugar o sin lugar**: usá **Revisar** en la pestaña Fotos. Si la
  cámara aparte tenía la hora mal, "Corregir el reloj de esta cámara" arregla todas sus fotos.
- **Las fotos del viaje no aparecen**: revisá el permiso de fotos (con acceso parcial la app
  no puede buscarlas sola) y que no estén en carpetas excluidas (capturas de pantalla,
  WhatsApp, descargas).
- **La nota de voz no se transcribió**: tocá **Reintentar** en la nota; si usás whisper,
  verificá que el modelo esté descargado; con "transcribir solo cargando", espera a cargar.
- **Los lugares no aparecen**: hace falta conexión; se buscan al cerrar la etapa y se puede
  **Reintentar** en la pestaña Lugares. En zonas remotas puede no haber artículos.
- **El mapa no se ve**: sin clave de mapas configurada en la compilación, el mapa muestra un
  aviso; sin red, solo se ven las zonas descargadas.
- **El registro se cortó solo**: algunos teléfonos matan apps en segundo plano para ahorrar
  batería. Revisá en los ajustes del teléfono que Bitácora de Viaje no tenga restricciones
  de batería. Al reabrir la app, **Reanudar** continúa la etapa.
- **La exportación dice que faltan archivos**: una foto o nota se borró del teléfono después
  de registrarla; el LEEME.txt las lista.

## Limitaciones conocidas

- Las fotos de **Google Fotos** que no estén en el teléfono no se vinculan solas (Google ya
  no permite leer la biblioteca completa); importarlas desde Google Fotos queda para más
  adelante.
- La transcripción es en el teléfono: whisper tarda y consume batería (por eso la opción
  "solo cargando"); la calidad depende del modelo elegido.
- La información de lugares depende de que exista un artículo de Wikipedia o un punto de
  interés en OpenStreetMap cerca; la app solo usa hechos de fuentes citadas y mantiene el
  enlace a la fuente.
- Los mapas sin red cubren la zona del viaje ya registrado, no un rectángulo elegido a mano.
- Los videos y fotos HEIC exportados dentro de una zona de privacidad conservan sus datos de
  ubicación internos (el LEEME.txt lo avisa).
- La app está pensada para uso personal instalada directamente; no está publicada en
  Google Play.

[PENDIENTE: completar — no documentado en las fuentes] Mediciones de batería en el teléfono
(1 hora con pantalla apagada en cada modo) y tiempos reales de generación del video en el
teléfono: quedan como pruebas de campo del usuario (ver `docs/PROGRESS.md`, pendientes).
