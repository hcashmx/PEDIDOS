# Pruebas existentes

No se ejecutaron pruebas durante esta tarea de documentación porque varias escriben SQLite o PostgreSQL.

| Prueba | Alcance | Escrituras / rollback |
|---|---|---|
| `ComposeAppCommonTest` | Prueba base del proyecto multiplataforma. | Sin evidencia de integración. |
| `FirstSyncSmokeTest` | Login, sesión local, primera sincronización y segunda sincronización incremental; contabiliza páginas, registros y errores. | Escribe `UserSession` y catálogos SQLite; hace GET al backend. No usa PostgreSQL directamente. |
| `LocalPedidoCaptureSmokeTest` | Captura un pedido, folio inicial, precio, impuestos, estados, UUID y stock sin cambio. | Limpia y escribe `pedidos`, `detalle_pedidos` y `folioSeries_pedidos` en SQLite. No envía POST. |
| `PedidoCalculosTest` | IVA decimal 0.16, tasa cero, cantidades decimales, primer/segundo folio y unicidad local. | La mayoría es pura; `noDuplicaSerieFolioPorUniqueLocal` escribe y limpia SQLite. |
| `PedidoSyncPostSmokeTest` | Login, envío de un pendiente, actualización local, reinicio manual a pendiente y reintento idempotente. | Escribe SQLite y realiza POST real que escribe PostgreSQL. No tiene rollback declarado. |
| `PedidoSyncStatusSemanticsTest` | Confirma que `updatePedidoSyncOk` deja `estado` y `synced_at` en `SYNCHRONIZED`. | Modifica un pedido SQLite existente. El reporte conservado indica 1 prueba exitosa. |
| `PedidoStockNoBloqueaCaptureTest` | Verifica en Kotlin que existencia suficiente, igual, insuficiente y cero permiten agregar línea; cantidad cero/negativa se rechaza. | Usa ViewModel/repositorio reales y SQLite sincronizada; no guarda pedidos ni ejecuta POST. |
| `OfflineThreePedidosCaptureTest` | Verifica captura de tres pedidos completamente offline, UUID distintos y folios consecutivos. | Usa flujo productivo Kotlin y SQLite; no requiere POST para capturar. |
| `OfflinePendingPedidosRealSyncTest` | Verifica reconexión, envío secuencial de pendientes y que pedidos `SYNCHRONIZED` no se reenvían. | Usa backend local y POST real durante la validación anterior. |
| `PendingQueueFailureRecoveryTest` | Verifica HTTP 5xx, excepción de red, conservación de pendientes, reintento posterior e incremental sin alterar pedidos. | Usa servidor controlado de prueba y SQLite; conserva pendientes ante fallo. |
| `EffyFlowApplicationTests` | Carga el contexto Spring. | Conecta al datasource configurado; no contiene escritura explícita ni rollback. |
| `PedidoIdempotenciaFolioTest` | UUID repetido, UUID distintos, folios consecutivos, índice único, BigDecimal, impuestos JSON, stock no bloqueante y fallo sin consumir folio. | Usa PostgreSQL. La clase usa `@Transactional` y `@Rollback`; la mayoría de pruebas revierten. `falloDeGuardadoNoConsumeSiguienteFolio` usa `NOT_SUPPORTED`, pero el servicio falla dentro de su propia transacción y la prueba verifica que no persista el pedido ni avance el folio. |

## Evidencia conservada

- `Backend\effyFlow\target\surefire-reports\com.cyssoftware.effyFlow.PedidoIdempotenciaFolioTest.txt`: 12 pruebas, 0 fallos.
- `composeApp\build\test-results\jvmTest\TEST-com.cyssoftware.effyflow.PedidoStockNoBloqueaCaptureTest.xml`: 2 pruebas, 0 fallos.
- `composeApp\build\test-results\jvmTest\TEST-com.cyssoftware.effyflow.PedidoSyncStatusSemanticsTest.xml`: 1 prueba, 0 fallos.
- `composeApp\build\test-results\jvmTest\TEST-com.cyssoftware.effyflow.OfflineThreePedidosCaptureTest.xml`: captura offline validada.
- `composeApp\build\test-results\jvmTest\TEST-com.cyssoftware.effyflow.OfflinePendingPedidosRealSyncTest.xml`: reconexión y envío de cola validados.
- `composeApp\build\test-results\jvmTest\TEST-com.cyssoftware.effyflow.PendingQueueFailureRecoveryTest.xml`: fallos, reintentos e incremental posterior validados.
- Hay clases compiladas para los smoke tests Kotlin, pero no permanecen sus XML de resultado.

## Validaciones registradas sin artefacto completo

Durante la validación reciente se registraron primera sincronización, paginación, segunda sincronización incremental, captura local, primer POST real, persistencia PostgreSQL y reintento idempotente. También se reportaron aproximadamente 71,898 productos y 71,896 precios y productos-impuestos. Al no existir actualmente todos los reportes/logs correspondientes, se consideran resultados de validación registrados, no evidencia reproducible completa dentro del workspace.

Validaciones offline registradas:

- captura de varios pedidos completamente offline;
- cola procesada por `id_pedido ASC`;
- reconexión y envío secuencial;
- recuperación ante pérdida de respuesta por `uuid_local`;
- HTTP 4xx/5xx y excepciones de red conservan pedidos pendientes;
- pedidos ya `SYNCHRONIZED` no se reenvían;
- sincronización incremental posterior de catálogos no modifica pedidos locales;
- errores de red reflejados en `message`.

## Estado local de validación

Al cierre de la validación offline de una laptop:

| Recurso | Valor |
| --- | ---: |
| PostgreSQL `pedidos` | 5 |
| PostgreSQL `detalle_pedidos` | 5 |
| PostgreSQL serie 10 `siguiente_folio` | 5 |
| SQLite `pedidos` | 5 |
| SQLite `detalle_pedidos` | 5 |
| SQLite pendientes | 0 |

Estos conteos son evidencia local de pruebas; no son requisitos ni datos iniciales del sistema.
