# PED-SYNC-R1-05 — Retorno de estados ERP → Kotlin

Desarrollo/UAT Windows, 2026-10-10. Se modifica únicamente Kotlin. Login,
contrato comercial, UUID, fecha, cola, Kits y diferencias se conservan.

## Causa raíz demostrada

El pedido manual 62 (local 2, ERP 115, UUID
`dd322ccb-e5d5-4829-bb59-0e3ea31e2c23`) ya había recibido AUTORIZADO:
PostgreSQL historial 114 RECIBIDO y 115 AUTORIZADO; Sync afterId=113 devolvió
ambos eventos, untilId/lastId=115. SQLite tenía estado_id=2, marcador 115 y
cursor global 115. La UI mostraba solamente synced_at=SYNCHRONIZED.
Además el listado consultaba executeAsList una vez, sin observar las escrituras,
y el refresh global recreaba su ViewModel perdiendo filtros/selección.
No se demostró un defecto en ERP ni en Sync; no se modificaron esos proyectos.

IntelliJ devolvió configurations=[]: no había configuración para debugger.
La evidencia procede de PostgreSQL SELECT, respuestas reales HTTP, SQLite de
lectura y capturas Windows. Se empleó computer-use para operar la ventana UAT;
esto es UAT automatizada real, no aprobación PASS_MANUAL de un usuario humano.

## Persistencia y correlación

pedidos.estado_erp guarda el nombre exacto del feed y estado_id su ID. Se mantiene
synced_at/estado como estado de envío comercial, sin mezclar los dos conceptos.
La migración añade estado_erp y una bandeja pedido_estado_pendiente durable.
No se codifica un mapa fijo de IDs ERP en Kotlin.

El consumidor guarda cada evento en la bandeja, busca uuidLocal primero y luego
idPedido remoto (uuid_server), valida la identidad y actualiza el Pedido existente.
Nunca usa id_pedido local como ID ERP ni crea Pedidos a partir del feed.
Una discrepancia de identidad aborta la transacción.

Un evento aún sin correlación permanece en la bandeja. Al comenzar otro sync se
intenta aplicar esa bandeja; no depende de volver a descargarlo ni de crear otro
UUID. Eventos más antiguos no regresan el estado, y los ya aplicados se deduplican
mediante marcador por Pedido. La recuperación del prefijo existente de PED-SYNC-R1
se conserva: además permite rellenar estado_erp en dispositivos que tenían el
marcador antiguo pero no el nombre comercial.

Si la aceptación inicial llega después de un estado del feed, acceptComercial
preserva el estado_id posterior. No cambia totales/reglas/resultado comercial.

## Cursor y fallos

Se mantiene afterId/id_historial, paginación ordenada y untilId estable. En una
única transacción se guardan la bandeja, las actualizaciones correlacionadas,
marcadores/fechas y el cursor. Para un evento desconocido, el cursor confirma la
recepción durable, y su aplicación queda pendiente en la bandeja.

Fallo de escritura: rollback de todo el lote, incluido cursor y marcadores.
El siguiente intento recibe el mismo evento. Reinicio conserva tanto cursor
como eventos pendientes. Error de red mantiene el último estado conocido.

Se conserva el replay del prefijo por commits tardíos. La bandeja puede crecer
con eventos de Pedidos no capturados en este dispositivo; una política futura de
retención debe preservar la correlación tardía, sin eliminar eventos silenciosamente.

## UI y disparadores

Listado y detalle muestran estado de envío y Estado ERP por separado. Query.Listener
SQLDelight alimenta un Flow, invalidado tras el commit; el ViewModel refresca lista
y Pedido seleccionado sin reiniciar. La observación se cancela al cambiar filtros
u cerrar la sesión. El ViewModel del listado conserva selección/filtros tras sync.

La sesión tiene un SyncViewModel compartido. «Actualizar estados» está disponible
en listado y detalle; consume sólo el feed y conserva la selección abierta.
«Sincronizar Pedidos» sigue consumiendo el feed después de enviar la cola y también
cuando la cola está vacía. No se añadió polling ni sincronización comercial automática.
Los callbacks de tokens siguen asociados al userId original y el scope se cierra
al abandonar la sesión. No se modificó el login offline.

## UAT Windows real

Cuenta temporal vendedor 25, agente 70 y cliente 14371 creados por APIs normales
ERP. SQLite aislado en:
`C:/Users/hcash/AppData/Local/Temp/PED-SYNC-R1-05-ttpt9jzs/EffyFlow/effy.db`.
No se alteró el SQLite personal ni se insertó negocio manualmente en PostgreSQL.

Se provisionó/enroló desde el ejecutable real, se cerró, se configuró sólo el
dispositivo temporal con Sync inaccesible (127.0.0.1:9), se reabrió y se comprobó
login local correcto. Después se recuperó conexión para capturar desde la UI.

La primera captura (UUID 34a185da-cd07-4e5e-a0af-05132bd4652f) conservó diferencias
comerciales: total local 155.47 frente a ERP 155.478048. No se aceptó, no se ajustó
su payload y quedó PENDIENTE_REVISION. Esta diferencia de redondeo es un pendiente
comercial fuera de PED-SYNC-R1-05.

La segunda captura se hizo en la UI con 25 piezas de producto 15. Fue aceptada:
local 4, ERP 116, P01/63, total 186.76, UUID
b9e8fcff-7d0a-4497-80d8-b5374da1fd60, fecha original
2026-10-10T13:45:04.718660700-06:00. El ERP asignó folio 63 a la captura que tenía
referencia local 64; se conservó la autoridad de folio del ERP.

Historial real, verificado por SELECT:

| id_historial | estado | fecha (-06) |
|---|---|---|
| 116 | RECIBIDO | 2026-10-10 13:45:23.971785 |
| 117 | AUTORIZADO | 2026-10-10 13:45:45.494738 |
| 118 | EN_SURTIDO | 2026-10-10 13:46:24.512159 |
| 119 | SURTIDO_PARCIAL | 2026-10-10 13:51:55.051142 |

POST /api/pedidos/116/autorizar reservó 25 unidades mediante el dominio ERP.
Sync afterId=116 devolvió evento 117 (untilId=117), UUID y nombre exactos.
El botón normal Sincronizar Pedidos aplicó AUTORIZADO; lista y detalle lo mostraron
sin reiniciar. Se cerró la app, se inició surtido por API ERP y se reabrió: el sync
desde cursor 117 recuperó EN_SURTIDO y cursor 118, visible en el listado.

Se cerró el ciclo de surtido mediante POST /api/pedidos/116/surtido/cerrar,
sin simular entregas. El evento 119 fue SURTIDO_PARCIAL. Con el detalle abierto,
Actualizar estados cambió el texto inmediatamente, sin navegación ni reinicio.

ERP rechazó con 409 cancelar desde EN_SURTIDO; se respetó esa regla. Para cubrir
la rama válida AUTORIZADO → CANCELADO se ejecutó una captura adicional mediante
PedidoRepository y la cola comercial reales, en el mismo SQLite UAT. El test
PedidoEstadosLocalUatTest requiere PED_R1_UAT_DB y valida el directorio aislado;
es específico de esta fixture (fuente ERP 116 y Sync local 8083), no una prueba
que deba ejecutarse contra otro dispositivo o producción.

Rama: local 5, ERP 117, P01/64, UUID e6b3577b-3b1b-425e-8a5a-40251255b795,
fecha 2026-10-10T13:52:50.565405900-06:00. Los reintentos explícitos después de
restaurar la sesión del servicio conservaron UUID, fecha y payload. No hubo
Pedidos duplicados. El flujo normal ERP autorizó y canceló, liberando 25 unidades.

| id_historial | pedido ERP | estado | fecha (-06) |
|---|---|---|---|
| 120 | 117 | RECIBIDO | 2026-10-10 13:54:07.151194 |
| 121 | 117 | AUTORIZADO | 2026-10-10 13:54:27.229513 |
| 122 | 117 | CANCELADO | 2026-10-10 13:54:27.336679 |

Sync afterId=119 devolvió los tres eventos, untilId/lastId=122. Refresh normal
actualizó SQLite, listado y detalle a CANCELADO. Después se simuló Sync inaccesible
sólo en la configuración temporal: el refresh falló claramente, conservando
CANCELADO y cursor 122. Al restaurar conexión el refresh volvió a funcionar.

Se cerró la app UAT, se deshabilitaron usuario 25 y cliente 14371 por sus APIs
normales y se detuvo exclusivamente la instancia propia 8083. Las instancias del
usuario permanecieron intactas. Los Pedidos/historial no se borraron: ERP 116 queda
SURTIDO_PARCIAL con 25 pendientes/backorder y ERP 117 CANCELADO; esa huella UAT se
conserva sin forzar una transición comercial inválida ni editar tablas.
Capturas y respuestas del feed se conservan fuera de Git en el mismo directorio.

Durante UAT, la instancia de Sync del usuario recibió un 403 ERP con código
SESION_BLOQUEADA_INACTIVIDAD. Se aisló la continuación con una instancia idéntica
Sync en 8083, sin modificar ni detener la instancia 8082. Resolver la renovación/
desbloqueo de esa sesión de servicio queda fuera de esta tarea; no se ocultó el
rechazo ni se habilitó aceptación automática.

## Pruebas

60 pruebas Kotlin focalizadas: 18 PedidoEstadosSyncTest, 13 autenticación offline,
5 persistencia de tokens, 16 contrato comercial, 2 renglones, 5 crédito y 1 cálculos.
Cobertura A–L: cursor/evento nuevo, ambas correlaciones, orden, transacción/rollback
real con trigger SQLite de fallo, reinicio, persistencia, Flow/ViewModel con detalle
abierto, offline/reconexión y ausencia de duplicados. Casos adicionales: nombre exacto
de los 10 estados actuales ERP, bandeja desconocida durable, backfill y aceptación
inicial tardía que no regresa estado.

Las 60 pruebas finalizaron sin fallos; adicionalmente pasó el caso opt-in de
captura real de la rama cancelación. Build Windows createDistributable y
git diff --check pasaron. Log de validación: build/r1-05-final-check.log.
Ejecutable actualizado: composeApp/build/compose/binaries/main/app/EffyFlow/EffyFlow.exe.
No se certifica Android. No se emitieron facturas ni se ejecutaron operaciones CFDI
para demostrar nombres posteriores: todos los nombres se cubren por pruebas locales;
las transiciones UAT reales son RECIBIDO, AUTORIZADO, EN_SURTIDO, SURTIDO_PARCIAL
y CANCELADO.

## Archivos del cambio

- App.kt: ciclo de vida y SyncViewModel compartido.
- LocalSchemaMigrations.kt y pedidos.sq: nombre ERP, bandeja durable y escrituras.
- PedidoEstadosSync.kt: transacción, correlación, deduplicación y cursor.
- SyncViewModel.kt: refresh de estados sin enviar la cola.
- PedidosDiaRepository.kt y PedidosDiaViewModel.kt: observación y detalle seleccionado.
- PedidosDiaScreen.kt: estado ERP y botón Actualizar estados.
- PedidoEstadosSyncTest.kt: cobertura focalizada de retorno/rollback/UI.
- PedidoEstadosLocalUatTest.kt: captura real adicional, opt-in aislado.
- docs/PED-SYNC-R1-05.md: evidencia y límites.

Los cambios previos en build.gradle.kts, AppConfigRepository.kt,
NuevoPedidoViewModel.kt y el manual general no pertenecen a este commit.
No se modificó Sync ni ERP. No se inició Cotizaciones.
