# COT-MOBILE-05B-2 — Resultado de implementación

**Dictamen: 05B-2 IMPLEMENTADO CON PENDIENTES.** La implementación local compila, se empaqueta y pasa las pruebas unitarias focalizadas de firma RS256 y cliente ERP. No se aplicó el SQL. La validación transaccional y de concurrencia PostgreSQL, las regresiones con base de datos y la asignación efectiva de `cotizaciones.ver` en el entorno de ejecución quedan pendientes de una base aislada y configuración verificable.

## 1. Archivos

Se añadieron el cliente ERP dedicado, emisor RS256 de lectura, resolución de identidad, servicio de orquestación, repositorio JDBC, modelos, controlador, worker de consumo, worker de retención, propiedades externalizadas, script SQL y pruebas unitarias.

- `db/scripts/20261013_cotizaciones_sync_durable.sql`
- `src/main/java/com/cyssoftware/effyFlow/config/ErpCotizacionesReadConfig.java`
- `src/main/java/com/cyssoftware/effyFlow/config/CotizacionesReadWorkerConfig.java`
- `src/main/java/com/cyssoftware/effyFlow/controller/cotizaciones/CotizacionEstadosSyncController.java`
- `src/main/java/com/cyssoftware/effyFlow/repository/CotizacionSyncRepository.java`
- `src/main/java/com/cyssoftware/effyFlow/service/cotizaciones/CotizacionReadAssertionService.java`
- `src/main/java/com/cyssoftware/effyFlow/service/cotizaciones/CotizacionReadIdentityService.java`
- `src/main/java/com/cyssoftware/effyFlow/service/cotizaciones/CotizacionStatesSyncService.java`
- `src/main/java/com/cyssoftware/effyFlow/service/cotizaciones/CotizacionStatesSyncWorker.java`
- `src/main/java/com/cyssoftware/effyFlow/service/cotizaciones/CotizacionSyncRetentionWorker.java`
- `src/main/java/com/cyssoftware/effyFlow/service/cotizaciones/CotizacionSyncRepositoryModels.java`
- `src/main/java/com/cyssoftware/effyFlow/service/cotizaciones/ErpCotizacionesReadClient.java`
- `src/test/java/com/cyssoftware/effyFlow/service/cotizaciones/CotizacionReadAssertionServiceTest.java`
- `src/test/java/com/cyssoftware/effyFlow/service/cotizaciones/ErpCotizacionesReadClientTest.java`

Se agregaron propiedades en `src/main/resources/application.properties`. Los documentos `COT-MOBILE-05B-DIAGNOSTICO.md` y `COT-MOBILE-05B-1-CONTRATO.md`, así como capturas sin relación con esta tarea, ya estaban sin seguimiento al comenzar; se conservaron y no se incluyeron en los cambios de esta fase.

## 2. Seguridad e identidad

La lectura resuelve al principal autenticado contra PostgreSQL: usuario activo, rol ERP asignado `AGENTE` o `VENDEDOR`, permiso efectivo `cotizaciones.ver` por rol o permiso directo y exactamente una relación con agente. Las operaciones de Sync exigen además `SINCRONIZAR_DATOS` y usuario autorizado. Ningún `agenteId` del cliente decide el alcance. La regla de captura `cotizaciones.crear` no se modificó.

Cada GET al ERP recibe una aserción RS256 nueva con `operation=LEER_ESTADOS_COTIZACIONES`, `sub`, `usuarioErpId`, `agenteErpId`, `dispositivoId`, `iss`, `aud`, `iat`, `exp`, `jti` UUID y `kid`. Se exige RSA de al menos 2048 bits y vigencia de 15 a 300 segundos. Se conserva el bearer técnico en paralelo. El cliente requiere credenciales explícitas de lectura y que su username coincida con el username técnico esperado; no toma credenciales genéricas como fallback. El cliente usa un `RestClient` propio para aislar timeouts y transporte de Pedidos.

`sessionId` se asocia en PostgreSQL con usuario, agente, UUID de dispositivo y generación. Cada operación posterior revalida el principal, permiso, asociación y dispositivo. El UUID de dispositivo identifica el namespace, no funciona como secreto. No se tocó login ni refresh móvil.

## 3. Flujo ERP, snapshot e incremental

La sesión se crea o recupera de forma serializada por identidad/dispositivo. Bootstrap se reclama una vez por generación y guarda `startCursor` y `maxCotizacionId`. Cada página de snapshot se persiste con `snapshot_after_id`; se usa el límite ERP de 100 y se valida el orden/progreso. Una interrupción conserva los checkpoints. Si el resultado del único bootstrap queda ambiguo, la sesión pasa a `REQUIERE_REINICIO`; no se improvisa un cursor ni se repite un corte distinto dentro de la generación.

Una vez guardado el snapshot completo, Sync lo materializa en una secuencia `deliverySequence` durable y agrega un marcador técnico `SNAPSHOT_END`, incluso si el snapshot está vacío. Los eventos recibidos durante el snapshot permanecen en inbox hasta su materialización ordenada. La sesión sólo pasa a `ACTIVA` al confirmar el ACK contiguo que incluye el marcador terminal.

El incremental comienza en `startCursor`. Sync persiste literalmente `nextCursor` del ERP, sin incrementarlo, sin timestamps y sin usar máximos globales. Inbox, hash de deduplicación, reconciliación, tombstone, entrega y checkpoint se escriben dentro de la misma transacción por página. El cursor de Pedidos no se reutiliza.

Los workers reclaman un lease PostgreSQL antes del HTTP ERP; no mantienen una transacción abierta durante la llamada de red. Al confirmar una página, la transacción vuelve a bloquear la sesión y comprueba propietario/vigencia del lease y cursor esperado. El worker atrasado que perdió el lease no puede aplicar una respuesta. Los reintentos de fallas recuperables usan `retry_after` durable con backoff exponencial acotado. Cada instancia compite mediante la misma base.

## 4. Versiones, revocación y aislamiento

Los eventos se deduplican por `(session_id,event_id)`; las proyecciones y tombstones se particionan por `(session_id,cotizacion_uuid)`. Una versión inferior no cambia la proyección. Una versión igual compatible es idempotente; una versión igual visible con diferente UUID comercial, estado o agente es una inconsistencia que suspende la sesión. Sólo una versión posterior reemplaza una proyección visible.

`visible=false` se entrega con estado y agente nulos, bloquea la visibilidad y conserva versión/evento en tombstone. Una versión visible igual o anterior al tombstone no puede resucitar el documento. La revocación de sesión bloquea nuevas entregas y purga snapshot, proyección, inbox, tombstones y delivery sensibles, conservando sólo el registro mínimo de sesión/código de razón. La revocación comercial detectada desde ERP produce 403 y revoca la sesión. Un error técnico o temporal no se convierte en revocación; no renueva el lease offline.

Las solicitudes de snapshot/eventos/revalidación hacen una comprobación ERP durable antes de ofrecer datos y vuelven a comprobar el estado de sesión al consultar la cola. Si otro worker está usando el lease, la petición no ofrece datos y responde con error recuperable para que Kotlin reintente. Un dispositivo desconectado no recibe revocación inmediata; Kotlin debe bloquear al expirar el lease máximo configurado de 24 horas y purgar al reconectarse y recibir 403.

## 5. Persistencia y retención

El script crea tablas para sesiones/generaciones, staging de snapshot, inbox y deduplicación, proyección, tombstones y outbox de entrega, además de índices, unicidad y claves foráneas. Los ACK son acumulativos, monotónicos e idempotentes; se rechazan ACK superiores a lo ofrecido y rangos con huecos. La secuencia del dispositivo es independiente del `eventId` ERP. Páginas no confirmadas se repiten con secuencias estables.

El worker de retención (30 días por defecto) elimina en lotes sólo delivery ya confirmado, staging de snapshot completo y eventos inbox ya entregados cuyo cursor ERP avanzó. No elimina entregas pendientes ni tombstones/proyecciones de sesiones activas. Las sesiones sin actividad del dispositivo por 180 días pasan a `SESSION_EXPIRADA`, se purgan datos comerciales y no se pueden descargar. Tras 90 días de cierre/expiración se elimina la fila mínima de sesión; una tabla separada conserva el high-water de generación para impedir reutilizar generaciones antiguas. Los tombstones/proyecciones activos se conservan mientras la sesión permanezca activa. No se impone un límite de cola pendiente; el umbral de capacidad y la respuesta controlada por saturación quedan como pendiente operativo.

## 6. Endpoints Sync → Kotlin

Todas las rutas requieren JWT Sync, permiso `SINCRONIZAR_DATOS` y usuario autorizado.

| Método y ruta | Uso |
|---|---|
| `POST /api/sync/cotizaciones/estados/sesion` | Crea o recupera sesión. Body `{ "dispositivoId": "UUID" }`. Responde 201 si crea, 200 si recupera. |
| `GET /api/sync/cotizaciones/estados/sesion/{sessionId}/snapshot?afterSequence=N&limit=50` | Repite la página durable de snapshot hasta `SNAPSHOT_END`. |
| `GET /api/sync/cotizaciones/estados/sesion/{sessionId}/eventos?afterSequence=N&limit=50` | Obtiene eventos pendientes después de ACK terminal del snapshot. |
| `POST /api/sync/cotizaciones/estados/sesion/{sessionId}/ack` | Body `{ "ackThroughSequence": N }`; confirmar sólo tras persistencia local. |
| `GET /api/sync/cotizaciones/estados/sesion/{sessionId}` | Consulta estado, generación, lease y watermarks. |
| `POST /api/sync/cotizaciones/estados/sesion/{sessionId}/revalidar` | Revalida autorización ERP y extiende lease sólo tras persistencia exitosa. |
| `POST /api/sync/cotizaciones/estados/sesion/{sessionId}/reiniciar` | Requiere `{ "dispositivoId": "UUID", "expectedGeneration": N }`; crea nuevo `sessionId`/generación. |

Las rutas de sesión requieren `X-Sync-Device-Id` y lo contrastan con el UUID ligado a la sesión. `limit` acepta 1–100. Los códigos incluyen `ACK_NO_CONTIGUO`, `IDENTIDAD_CAMBIADA`, `ERP_CURSOR_CONFLICT`, `SESSION_STATE_CONFLICT`, `SESSION_REQUIERE_REINICIO`, `SESSION_EXPIRADA`, `CURSOR_DE_ENTREGA_EXPIRADO`, `COTIZACIONES_SIN_PERMISO`, `COTIZACIONES_AUTORIZACION_REVOCADA`, `ERP_PROTOCOL_ERROR`, `ERP_UNAVAILABLE`, `ERP_TECHNICAL_AUTH_FAILED` y `SYNC_STORAGE_UNAVAILABLE`. La entrega de evento conserva `eventId`, `cotizacionUuid`, `erpId`, `estado`, `version`, `changedAt`, `eventType`, `agenteId`, `visible` y `deliverySequence`; para `visible=false`, estado y agente son nulos. No se expone bearer técnico, aserción, secreto ni cuerpo de autenticación. El payload comercial de captura permanece intacto.

## 7. Configuración

Se requiere la URL `ERP_BASE_URL` ya existente y, para habilitar lectura:

- `SYNC_COTIZACIONES_READ_ENABLED=true` (por defecto `false`).
- `ERP_COTIZACIONES_READ_USERNAME`, `ERP_COTIZACIONES_READ_PASSWORD` y `ERP_COTIZACIONES_READ_TECHNICAL_USERNAME`; los dos usernames deben coincidir y referirse a la cuenta técnica habilitada por ERP.
- `COTIZACION_ASSERTION_PRIVATE_KEY_BASE64`, `COTIZACION_ASSERTION_KEY_ID`, `COTIZACION_ASSERTION_ISSUER`, `COTIZACION_ASSERTION_AUDIENCE` y `COTIZACION_ASSERTION_VALIDITY_SECONDS` (15–300; defecto 60).
- `SYNC_COTIZACIONES_OFFLINE_AUTHORIZATION_HOURS` (1–24; defecto 24), `SYNC_COTIZACIONES_WORKER_LEASE_SECONDS` (defecto 60), `SYNC_COTIZACIONES_WORKER_INTERVAL_MS` (defecto 5000) y `SYNC_COTIZACIONES_DELIVERY_RETENTION_DAYS` (defecto 30).
- `spring.datasource.*` debe apuntar a PostgreSQL de Sync con permisos para las tablas.

La clave privada y credenciales no están en el repositorio. El permiso `cotizaciones.ver` está definido en el contrato/ERP de referencia; la documentación consultada indica que su asignación inicial se limita a `super_admin`. No se consultó una base de producción ni se alteraron permisos. Los vendedores que no tengan permiso efectivo recibirán 403 hasta que el administrador del entorno les otorgue la política aprobada.

## 8. Validaciones y límites

Resultados ejecutados:

- `mvn -q -DskipTests compile`: **PASS**.
- `mvn -q -DskipTests package`: **PASS**.
- `mvn -q -Dtest=CotizacionReadAssertionServiceTest,ErpCotizacionesReadClientTest test`: **PASS**, cuatro casos unitarios.
- Revisión del cambio rastreado: sólo agrega propiedades; no cambia `PedidoEstadosService`, `PedidoEstadosController`, historial/cursor de Pedidos, captura Cotizaciones, login/refresh, ERP o Kotlin.

PostgreSQL aislado no disponible: Docker reportó acceso denegado al pipe/daemon de Docker. No se ejecutó el script ni se probó transacción, restricciones, SQL de retención, ACK, leases, cursor de entrega expirado, carreras de dos instancias o rollback. Tampoco se ejecutaron regresiones de Pedidos/captura que pudieran apuntar a la base `prueba` compartida. Estas pruebas no se consideran aprobadas. El SQL queda para revisión y aplicación posterior en una base desechable o entorno autorizado.

La integración requiere revisar que el `DataSource` efectivo de Sync y el ERP consultado para identidad/permisos pertenecen al entorno esperado. La documentación disponible afirma que Sync usa PostgreSQL compartido con el esquema de usuarios ERP, pero la asignación comercial real de `cotizaciones.ver` no se verificó. Tampoco se verificó compatibilidad de claves y `kid` con producción. No hubo despliegue, reinicio, commit ni push.

## 9. Plan de validación siguiente

En una base PostgreSQL aislada se debe aplicar el script y cubrir: bootstrap vacío/no vacío, snapshot de varias páginas y reinicio, evento solapado con snapshot, cursor exacto/página vacía, deduplicación y versiones, tombstone/resurrección, ACK repetido/salto/no ofrecido, desconexión, revocación concurrente, expiración/robo del lease, workers concurrentes, rollback de página, retención sin borrar pendientes y nueva generación. Después correr las regresiones focalizadas de Pedidos, captura Cotizaciones y autenticación móvil contra su entorno de pruebas. Revisar asignación efectiva de `cotizaciones.ver`, cuenta técnica, clave pública `kid` y secretos externalizados antes de habilitar el flag.

## 10. Contrato para Kotlin 05C

Kotlin generará un UUID de dispositivo persistente y autenticará cada llamada con el access JWT existente más `X-Sync-Device-Id`. Guardará `sessionId` y `generation`; solicitará snapshot desde la secuencia ACK vigente, persistirá cada página en almacenamiento local y sólo entonces confirmará `toSequence`. Debe procesar `SNAPSHOT_END` (también en snapshot vacío), luego solicitar incremental. Cada evento se reconcilia por UUID y versión; `visible=false` oculta y purga el documento correspondiente antes del ACK. Si una respuesta se pierde, repetirá la misma página usando el último ACK.

Kotlin debe bloquear Cotizaciones al expirar el lease de autorización offline; ante 403 revocará/purgará la copia comercial autorizada y mantendrá operaciones locales pendientes en el almacén separado protegido del contrato 05B-1. Ante 409 reconsultará estado y repetirá desde el ACK; ante 410 solicitará reinicio y purgará datos de la generación anterior; ante error temporal no tratará al usuario como revocado ni extenderá el lease.
