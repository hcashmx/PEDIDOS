# COT-MOBILE-05C-1 — Diagnóstico Kotlin y diseño de integración de estados

Fecha de auditoría: 2026-10-13
Alcance: inspección estática de la aplicación Kotlin y contraste del contrato 05B-1 con la implementación Sync 05B-2/2C/2T. No se ejecutaron pruebas, migraciones, aplicaciones ni servicios; tampoco se inspeccionó ni modificó una base SQLite real.

## 1. Resumen ejecutivo

La aplicación es Kotlin Multiplatform con Compose, Ktor, kotlinx.serialization y SQLDelight. Tiene targets Android y JVM activos. Ya dispone de autenticación local por usuario, persistencia de access/refresh token por `userId`, refresco Bearer reutilizable, configuración persistida de URL, SQLite común y flujos separados para Pedidos, catálogos y captura de Cotizaciones.

El módulo actual de Cotizaciones es un módulo de **captura comercial y outbox**. Sus tablas `cotizaciones`, `cotizacion_renglones` y `cotizacion_outbox`, su repositorio y su pantalla no constituyen todavía un consumidor del feed de estados de 05B. La app no persiste sesión/generación/checkpoint de ese feed, no consume snapshot ni eventos incrementales, no procesa `SNAPSHOT_END`, no mantiene tombstones y no aplica el lease offline del feed.

La integración 05C-2 es viable sin cambiar el JSON comercial, la cola de capturas, Pedidos, catálogos ni el login. Requiere un subsistema nuevo de lectura, tablas nuevas y una pantalla o sección de documentos autorizados claramente separada de los borradores/capturas.

## 2. Arquitectura Kotlin encontrada

### 2.1 Proyecto, plataformas y librerías

- Proyecto: `App Pedidos/KotlinProject`.
- Módulo de aplicación: `composeApp`; no se encontraron módulos Gradle separados por dominio.
- Código compartido: `composeApp/src/commonMain`.
- Targets activos: Android y JVM. Las declaraciones iOS están comentadas en `composeApp/build.gradle.kts`.
- UI y navegación: Compose Multiplatform y Navigation Compose; `App.kt` arma un único `NavHost`.
- HTTP: Ktor Client 2.3.x con ContentNegotiation, kotlinx.serialization, Auth Bearer y timeouts.
- Persistencia: SQLDelight 2.0.2 sobre `effy.db`.
- JVM: archivo bajo `%LOCALAPPDATA%/EffyFlow/effy.db` en Windows; rutas equivalentes para macOS/Linux.
- Android: `AndroidSqliteDriver`, base `effy.db`.
- No se verificó cifrado activo de SQLite. Hay código de SQLCipher comentado; por tanto, no debe afirmarse que la base esté cifrada.

### 2.2 Módulo actual de Cotizaciones

Archivos centrales:

- `cotizacion/CotizacionRepository.kt`: revisión comercial, búsquedas de catálogo, cálculo, borradores y armado del DTO congelado.
- `cotizacion/CotizacionOutboxSync.kt`: envío/reintento de capturas y clasificación de resultados.
- `cotizacion/CotizacionPricing.kt`: reglas de cálculo local.
- `cotizacion/CotizacionEditor.kt`: captura/edición.
- `cotizacion/CotizacionesScreen.kt`: lista de borradores, detalle, descarga de revisión y envío.
- `sqldelight/.../cotizaciones.sq`: `revisiones_comerciales`, `cotizaciones`, `cotizacion_renglones`, `cotizacion_outbox`.

La entidad actual `cotizaciones` contiene el UUID local, autor, agente, cliente, revisión comercial, renglones indirectos, estado del outbox y algunos datos devueltos al crear en ERP. Su semántica es la de una captura creada localmente. No representa el conjunto autorizado que entrega el feed de lectura.

La pantalla existente muestra “Borradores locales” y estados de envío. No hay una lista separada de Cotizaciones recibidas desde Sync ni estados de inicialización, bloqueo por lease, revocación o reinicio de generación.

### 2.3 Sincronización reutilizable

- `SyncBearerAuth.kt` instala Auth Bearer, lee access/refresh token persistidos, llama `/api/auth/refresh` tras 401 y guarda los tokens renovados.
- `LoginRepository.kt` crea clientes autenticados, limita el token a la sesión local activa y contiene un `authorizationMutex` para cambios de autenticación.
- `SyncViewModel.kt` sincroniza catálogos y dispara flujos comerciales de Pedidos; su implementación es amplia y orientada a UI.
- `PedidoEstadosSync.kt` aporta un patrón útil: descarga, staging/aplicación local y transacciones. No implementa las garantías de sesión/generación/ACK requeridas por Cotizaciones y no debe reutilizarse como si fueran equivalentes.
- `CotizacionOutboxSync.kt` aporta cliente autenticado, timeouts y clasificación de 401/403/errores temporales, pero su semántica de envío es distinta al consumidor de estados.

Conviene reutilizar la configuración Bearer y el acceso a tokens, pero crear `CotizacionEstadosApi`, `CotizacionEstadosRepository` y un orquestador propios. Acoplar el nuevo protocolo a `SyncViewModel` o al outbox aumentaría el riesgo de mezclar checkpoints con capturas.

### 2.4 HTTP y endpoints existentes en la app

- La URL base se persiste en `app_config` con clave `base_url` mediante `AppConfigRepository`.
- La autenticación exige HTTPS, salvo loopback permitido para UAT local.
- Login: `/api/auth/login`; refresh: `/api/auth/refresh`.
- El cliente Ktor usa `ignoreUnknownKeys=true`, útil para evolución aditiva del contrato, pero los nuevos DTO deben validar campos críticos después de deserializar.
- No existe cliente Kotlin para `/api/sync/cotizaciones/estados/**`.

### 2.5 SQLite y migraciones

SQLDelight crea el esquema en instalaciones nuevas. Después, ambos drivers llaman `LocalSchemaMigrations.apply(driver)`. Este objeto ejecuta DDL manual idempotente, varios `ALTER TABLE` protegidos por captura de columna duplicada y migraciones de autenticación.

No se encontró un esquema de migraciones SQLDelight versionadas (`.sqm`). La migración 05C-2 debe ajustarse al mecanismo actual o introducir versionado explícito de forma controlada. Las nuevas tablas deben existir tanto en `.sq` para instalaciones nuevas/generación de queries como en `LocalSchemaMigrations` para instalaciones existentes.

### 2.6 Sesión, JWT, usuario, agente y permisos

- `OfflineUser` persiste `userId`, username, roles, estado activo y material de verificación local.
- `LocalSession` admite una sola sesión activa (`id=1`) y referencia `userId`.
- `SyncCredentials` persiste access/refresh token por `userId`.
- `AuthLocalDataSource.getSession()` une sesión, usuario y credenciales; el usuario autenticado queda identificado por `usuarioId`.
- `App.kt` mantiene `usuarioIdLogueado`, recrea ViewModels dependientes del usuario y al logout borra `LocalSession`.
- El `agenteId` se resuelve mediante los datos locales usados por Pedidos/Cotizaciones; la identidad efectiva del feed la vuelve a resolver Sync desde el JWT. Kotlin no debe enviar `agenteId` al contrato de estados.
- La app admite roles locales `AGENTE`, `VENDEDOR` y `ADMIN`. No se encontró comprobación cliente de `cotizaciones.ver`; Sync exige autoridad `SINCRONIZAR_DATOS`, usuario autorizado e identidad/permiso de lectura. La UI debe reaccionar a la autorización remota, no inferirla del rol local.
- La restauración al arrancar sólo abre la UI protegida si Sync acepta la sesión remota; el login offline exige enrolamiento previo. Esta autorización general no sustituye el lease específico de lectura de Cotizaciones.

### 2.7 Identificador de dispositivo y capturas pendientes

`CotizacionRepository.dispositivoId()` usa `app_config['cotizaciones_dispositivo_id']`. Si falta, genera una vez `desktop-{uuid4()}` dentro de una transacción y lo reutiliza. No se regenera por login.

`prepararCaptura()` copia ese valor a `CotizacionCapturaPreparada`; al congelar el envío, `cotizacion_outbox.payload_json` conserva el body. Por ello, normalizar la clave de configuración futura no debe reescribir `payload_json` ni ningún borrador ya congelado.

Las capturas pendientes están separadas operacionalmente por `cotizacion_outbox`, pero comparten cabecera con `cotizaciones`. El nuevo feed debe usar tablas distintas para evitar que la mera existencia de una captura habilite su visualización como documento autorizado.

## 3. Componentes reutilizables

| Componente | Reutilización propuesta | Límite |
|---|---|---|
| `configureSyncBearer` | Autenticación JWT, refresh y persistencia de tokens | Añadir interpretación propia de errores del feed; no confundir 401 Sync con errores ERP internos |
| `LoginRepository.getSyncTokensForUser` / `updateTokensForUser` | Atar llamadas al usuario local actual | Verificar identidad antes y después de operaciones largas |
| `AppConfigRepository` | URL base persistida | El nuevo cliente debe aplicar el mismo requisito HTTPS |
| `DatabaseProvider` / SQLDelight | Base y transacciones comunes | Namespace y tablas nuevas; no mezclar con outbox |
| `CotizacionRepository.dispositivoId()` | Fuente de la clave legada a normalizar | Extraer a un almacén de identidad de dispositivo independiente de captura |
| Patrones de `PedidoEstadosSync` | Commit atómico y recuperación tras interrupción | El protocolo nuevo requiere generación, secuencia, terminal y ACK propios |
| Compose/NavHost | Entrada a lista/estado del consumidor | Debe distinguir documentos autorizados de borradores locales |

## 4. Contrato HTTP real y comparación con 05B-1

Controlador real: `CotizacionEstadosSyncController`, base `/api/sync/cotizaciones/estados`.

| Operación | Implementación real verificada | Observación para Kotlin |
|---|---|---|
| Crear/reanudar | `POST /sesion`, body `{dispositivoId}`; 200 existente, 201 nueva | UUID canónico; no lleva header en esta llamada |
| Snapshot | `GET /sesion/{sessionId}/snapshot?afterSequence&limit` + `X-Sync-Device-Id` | Puede responder 202 mientras se inicializa; este 202 no estaba detallado en la tabla de errores 05B-1 |
| Eventos | `GET /sesion/{sessionId}/eventos?afterSequence&limit` + header | Sólo sesión `ACTIVA`; en otro estado responde 409 |
| ACK | `POST /sesion/{sessionId}/ack`, body `{ackThroughSequence}` + header | ACK repetido es la base de recuperación tras ACK perdido |
| Estado | `GET /sesion/{sessionId}` + header | Incluye uso/capacidad de cola además de lo documentado |
| Revalidar | `POST /sesion/{sessionId}/revalidar` + header | Devuelve `authorized`, `validUntil`, `state`, `lastAvailableSequence` |
| Reiniciar | `POST /sesion/{sessionId}/reiniciar`, body `{dispositivoId, expectedGeneration}` | No recibe `X-Sync-Device-Id`; responde 201 y `purgeRequired=true` |

### 4.1 DTO reales verificados

Creación/reanudación devuelve `sessionId`, `generation`, `state`, `snapshot.nextDeliverySequence`, `snapshot.hasMore`, `lastAckedSequence` y `offlineAuthorization {valid, validUntil}`.

Snapshot devuelve `sessionId`, `generation`, `items`, `fromSequence`, `toSequence`, `snapshotComplete`, `hasMore`. Cada elemento normal contiene `deliverySequence` y `document {erpId, cotizacionUuid, estado, version, agenteId, updatedAt}`. El terminal real es un elemento `{deliverySequence, type:"SNAPSHOT_END"}` sin `document`.

Eventos devuelve `sessionId`, `generation`, `events`, `fromSequence`, `toSequence`, `hasMore`. Cada evento contiene `deliverySequence`, `eventId`, `cotizacionUuid`, `erpId`, `estado`, `version`, `changedAt`, `eventType`, `agenteId`, `visible`. Para `visible=false`, backend exige `estado=null` y `agenteId=null`.

Estado añade `pendingQueueEntries`, `pendingQueueEstimatedBytes`, `ingestionSuspended`, `capacityCode`, `reasonCode` y `offlineAuthorization`. `capacityCode` es `COTIZACIONES_COLA_SATURADA` cuando corresponde y cadena vacía en caso contrario; `reasonCode` también se emite como cadena vacía si no existe, aunque 05B-1 lo describía nullable.

### 4.2 Brechas y matices entre documento e implementación

1. El contrato 05B-1 decía que el header de dispositivo era opcional en requests posteriores; la implementación lo declara obligatorio para snapshot, eventos, ACK, estado y revalidación.
2. Snapshot puede devolver HTTP 202 mientras el backend termina de materializarlo. El cliente debe consultar estado y reintentar con backoff; 202 no debe tratarse como página vacía.
3. La forma uniforme de error no está garantizada para todas las capas. Los `ResponseStatusException` del controlador sí producen `ErrorBody`, pero 401 del filtro y ciertos 403 de Spring Security/`requireSync` pasan por manejadores globales con `{error: ...}`. Kotlin debe clasificar primero por HTTP y luego usar `code` cuando esté presente.
4. UUID no canónico en el servicio produce actualmente 400, aunque el mapeador del controlador contempla `DISPOSITIVO_UUID_INVALIDO` bajo 422. La estrategia segura es aceptar 400 o 422 como error de solicitud no reintentable y registrar el cuerpo sin depender de esa distinción.
5. El handler detallado marca retryable a 429 y 504, y a ciertos 503, pero no se observó un `Retry-After` generado por este controlador. El cliente debe respetarlo si aparece y usar backoff propio si falta.
6. No se observó código 504 declarado directamente en el controlador, aunque la política de retry lo contempla. Debe manejarse como temporal.
7. `ingestionSuspended` sólo se expone en estado; una página servida puede continuar disponible aunque la ingesta ERP esté pausada. No equivale a revocación ni autoriza extender lease.
8. La implementación conserva orden de transporte con `deliverySequence`; `eventId` sólo identifica/ordena el feed fuente ERP y no sirve como checkpoint Kotlin.

### 4.3 Tratamiento recomendado por código HTTP

- 200/201: validar `sessionId`, `generation` y coherencia con el namespace antes de persistir.
- 202 snapshot: no aplicar/ACK; consultar estado, esperar con backoff y repetir desde el checkpoint local.
- 400/422: solicitud o UUID inválido; bloquear el ciclo y requerir corrección/migración local.
- 401: permitir una única renovación mediante el plugin Bearer; si persiste, bloquear acceso remoto y solicitar login. No purgar capturas.
- 403: bloquear y purgar inmediatamente los datos recibidos del namespace afectado; limpiar lease. Conservar outbox.
- 404: tratar como sesión no utilizable para el actor/device actual; nunca intentar abrirla bajo otro usuario. Abrir una sesión propia tras purgar/bloquear el namespace huérfano.
- 409: consultar estado. Para ACK repetido, reconciliar `lastAckedSequence`; para conflicto de estado/generación no forzar cursor.
- 410: marcar `REQUIERE_REINICIO`, bloquear vista, ejecutar el flujo explícito de reinicio cuando haya autenticación y autorización.
- 429/502/503/504 y fallos de red: temporales según cuerpo/código; no renovar lease. Mostrar datos sólo mientras el lease local continúe válido.

## 5. Modelo SQLite recomendado

Los nombres son propuesta para 05C-2; no existen todavía.

### 5.1 `cot_estado_sync_session`

Una fila por namespace remoto:

```text
session_id TEXT PRIMARY KEY
user_id INTEGER NOT NULL
agent_id INTEGER NOT NULL
device_id TEXT NOT NULL
generation INTEGER NOT NULL
remote_state TEXT NOT NULL
bootstrap_state TEXT NOT NULL
last_committed_sequence INTEGER NOT NULL DEFAULT 0
last_acked_sequence INTEGER NOT NULL DEFAULT 0
snapshot_end_sequence INTEGER NULL
offline_valid_until_utc TEXT NULL
last_server_observed_at_utc TEXT NULL
last_elapsed_observed_ms INTEGER NULL
revocation_reason_code TEXT NULL
ingestion_suspended INTEGER NOT NULL DEFAULT 0
capacity_code TEXT NULL
created_at_utc TEXT NOT NULL
updated_at_utc TEXT NOT NULL
UNIQUE(user_id, agent_id, device_id, generation)
```

`bootstrap_state`: `INICIALIZANDO`, `SNAPSHOT_APLICADO_PENDIENTE_ACK`, `ACTIVA`, `REVOCADA`, `REQUIERE_REINICIO`, `SESSION_EXPIRADA`. `last_committed_sequence` es el único `afterSequence` permitido para solicitar la siguiente página. `last_acked_sequence` refleja confirmación observada del servidor y puede quedar detrás tras un fallo entre commit y ACK.

### 5.2 `cot_estado_document`

```text
session_id TEXT NOT NULL
generation INTEGER NOT NULL
cotizacion_uuid TEXT NOT NULL
erp_id INTEGER NOT NULL
version INTEGER NOT NULL
estado TEXT NOT NULL
agent_id INTEGER NULL
updated_at_utc TEXT NULL
last_delivery_sequence INTEGER NOT NULL
payload_fingerprint TEXT NOT NULL
PRIMARY KEY(session_id, generation, cotizacion_uuid)
UNIQUE(session_id, generation, erp_id)
```

Debe contener sólo documentos visibles entregados por Sync. No debe enlazar por FK ni hacer upsert sobre la tabla comercial `cotizaciones`.

### 5.3 `cot_estado_tombstone`

```text
session_id TEXT NOT NULL
generation INTEGER NOT NULL
cotizacion_uuid TEXT NOT NULL
erp_id INTEGER NOT NULL
version INTEGER NOT NULL
event_id INTEGER NULL
changed_at_utc TEXT NULL
last_delivery_sequence INTEGER NOT NULL
payload_fingerprint TEXT NOT NULL
PRIMARY KEY(session_id, generation, cotizacion_uuid)
```

Un `visible=false` elimina/oculta la fila visible y conserva el tombstone. El tombstone impide que snapshot/eventos de versión menor o igual resuciten el documento.

### 5.4 `cot_estado_delivery_receipt`

```text
session_id TEXT NOT NULL
generation INTEGER NOT NULL
delivery_sequence INTEGER NOT NULL
kind TEXT NOT NULL
payload_fingerprint TEXT NOT NULL
applied_at_utc TEXT NOT NULL
PRIMARY KEY(session_id, generation, delivery_sequence)
```

Esta tabla permite reconocer una página repetida y detectar si la misma secuencia reaparece con contenido contradictorio. Puede retenerse al menos durante la vida de la generación. No sustituye la versión por UUID.

### 5.5 Identidad de dispositivo

Usar una clave global separada, por ejemplo `sync_device_id`, en `app_config`, o una tabla de metadatos equivalente. Durante migración se lee primero la clave nueva y luego `cotizaciones_dispositivo_id`. La identidad de dispositivo no debe formar parte de una fila de usuario ni regenerarse al cambiar login.

### 5.6 Atomicidad y aislamiento

Cada página se procesa en una sola transacción SQLDelight:

1. Verificar `sessionId` y `generation` contra la fila activa del usuario/agente/device actuales.
2. Exigir continuidad desde `last_committed_sequence + 1` para páginas no vacías.
3. Validar toda la página antes de mutar documentos.
4. Aplicar documentos/eventos/tombstones.
5. Insertar receipts por secuencia.
6. Actualizar `last_committed_sequence` al `toSequence` y, si aplica, `snapshot_end_sequence`/estado.
7. Commit.

El POST ACK ocurre después del commit. No se debe persistir una fila de otra sesión o generación, aunque el UUID documental coincida. Las consultas UI siempre deben filtrar por sesión/generación autorizada activa.

## 6. Estrategia de migración del dispositivo

Algoritmo recomendado, ejecutado una vez y transaccionalmente:

1. Si `sync_device_id` contiene UUID canónico válido, usarlo sin cambios.
2. Si no existe y `cotizaciones_dispositivo_id` coincide exactamente con `desktop-{UUID canónico}`, extraer el UUID, normalizar a minúsculas mediante parseo/serialización UUID y persistirlo en `sync_device_id`.
3. Si la clave legada ya contiene UUID canónico, copiarla normalizada.
4. Si falta o es inválida, generar un único UUID v4, persistirlo y releerlo dentro de la transacción para resolver concurrencia de inicialización.
5. No modificar `cotizaciones_dispositivo_id` en la primera implementación si aún lo consume el armado de capturas. Cambiar el feed para usar `sync_device_id`; decidir por separado si nuevas capturas deben conservar el formato contractual histórico.
6. Nunca recorrer ni reserializar `cotizacion_outbox.payload_json`; esos cuerpos y UUID quedan congelados.

El ID es estable entre reinicios y común al dispositivo, pero las sesiones remotas/locales siguen ligadas a usuario y agente. Al cambiar usuario no se reutiliza `sessionId`, lease, checkpoint ni documentos del usuario anterior.

## 7. Flujo de bootstrap, snapshot, incremental y ACK

### 7.1 Apertura y recuperación

1. Obtener usuario local activo y tokens sólo para ese `userId`.
2. Resolver UUID canónico estable.
3. Buscar localmente un namespace no revocado del mismo usuario/agente/device.
4. Si existe, consultar estado remoto con su `sessionId`; si no existe o ya no es utilizable, `POST /sesion` con `dispositivoId`.
5. Persistir respuesta sólo si la identidad local no cambió durante la llamada.
6. Si el servidor devuelve una sesión/generación distinta, crear el namespace correspondiente; no trasplantar documentos/checkpoints.

### 7.2 Snapshot

1. GET snapshot con `afterSequence=last_committed_sequence`, `limit` entre 1 y 100 y header de dispositivo.
2. En 202, consultar estado y reintentar con backoff.
3. Validar sesión, generación, rangos, secuencias y forma discriminada `document`/`SNAPSHOT_END`.
4. Aplicar la página completa en una transacción, incluido el checkpoint.
5. Después del commit, POST ACK por `toSequence`.
6. Guardar `last_acked_sequence` al observar éxito. Si el ACK falla, no revertir documentos ni checkpoint.
7. Sólo pasar a incremental después de persistir y confirmar el `SNAPSHOT_END`, y verificar `ACTIVA` en la respuesta ACK o estado remoto.

Snapshot vacío sigue teniendo una entrega terminal `SNAPSHOT_END`; se aplica su secuencia, se confirma y se activa. Una respuesta con `items=[]` sin terminal no completa el snapshot.

### 7.3 Incremental

Solicitar `/eventos` desde `last_committed_sequence`. Validar y aplicar la página completa en una transacción; confirmar sólo después del commit. Una página vacía no avanza checkpoint y no requiere inventar ACK.

### 7.4 Casos de fallo

- **Página repetida:** si todas las secuencias ya tienen receipt con la misma huella, no reaplicar; reenviar ACK por el máximo comprometido. Huella diferente para igual secuencia es contradicción y exige bloqueo/reinicio, no last-write-wins.
- **Fallo antes del commit:** rollback completo; al reiniciar se pide la misma página desde el checkpoint anterior.
- **Fallo después del commit y antes del ACK:** se conserva checkpoint/documentos; al reiniciar se envía ACK del checkpoint o se vuelve a pedir desde él según el estado remoto. El ACK idempotente permite cerrar la ventana.
- **ACK perdido con respuesta desconocida:** consultar estado. Si `lastAckedSequence` alcanzó el checkpoint local, actualizar la marca local; si quedó detrás, repetir ACK. Nunca retroceder documentos.
- **Reinicio durante sincronización:** no guardar estado sólo en memoria. El namespace y el checkpoint permiten reanudar sin duplicar.
- **Cambio de usuario durante llamada:** descartar la respuesta antes de escribir si la sesión local ya no coincide.

## 8. Reglas de versiones, duplicados y tombstones

Para cada UUID dentro de la misma sesión/generación:

- Versión entrante mayor: aplicar el documento visible o reemplazar por tombstone si `visible=false`.
- Versión entrante menor: ignorar semánticamente, pero registrar/avanzar su `deliverySequence` dentro de la transacción de página.
- Misma versión y misma representación canónica: duplicado compatible; no mutar proyección y sí avanzar transporte.
- Misma versión con distinto `visible`, `erpId`, `estado`, `agenteId` o datos temporales relevantes: contradicción. Marcar sesión local `REQUIERE_REINICIO`, bloquear visualización y no ACKear la página contradictoria hasta reconciliar con estado/reset.
- Tombstone con versión N domina cualquier documento de versión `<= N`.
- Documento posterior con versión `> N` puede volver a ser visible sólo si Sync lo entrega explícitamente como visible y pertenece a la misma generación; registrar esa transición sustituyendo el tombstone.
- Un evento posterior al snapshot se compara con la versión materializada por el snapshot. El checkpoint de transporte se decide por `deliverySequence`, nunca por `eventId`.
- Toda la página se valida antes de modificar filas para evitar aplicación parcial.

La huella local debe derivarse de una serialización canónica definida por la app, no del orden textual JSON recibido. Para snapshots, `visible` se considera verdadero por definición.

## 9. Autorización offline y controles de reloj

Persistir `offline_valid_until_utc` exactamente desde Sync. Antes de consultar o mostrar documentos, exigir:

- sesión local del mismo usuario;
- namespace en estado permitido;
- generación activa;
- lease presente y no vencido;
- ausencia de revocación/reinicio pendiente.

Una revalidación exitosa actualiza lease y estado después de persistir la respuesta. Errores de red, 429 o 5xx no extienden el lease. Al vencer, se bloquean lista, búsqueda y detalle hasta revalidar; no basta con ocultar una ruta de navegación si el repositorio aún devuelve filas.

Controles razonables contra retroceso del reloj:

1. Guardar, junto al último `validUntil`, la hora UTC observada y un reloj monotónico/elapsed disponible por plataforma.
2. Mientras el proceso viva, calcular el avance con reloj monotónico y bloquear si el reloj de pared retrocede más allá de una tolerancia pequeña respecto a la última observación.
3. Entre reinicios, persistir `max_observed_wall_utc`; si la hora actual es anterior de forma material, tratar el lease como no confiable y exigir revalidación online.
4. Nunca extender `validUntil` localmente para compensar cambios de reloj.

Esto reduce retrocesos accidentales. No impide manipulación deliberada del dispositivo, extracción de la base o alteración del binario; no debe presentarse como attestation.

## 10. Revocaciones y reinicio

### 10.1 Revocación documental

Al recibir `visible=false`, en la misma transacción de página se elimina la fila visible, se guarda/actualiza tombstone y se avanza checkpoint. La UI debe dejar de mostrarla después del commit, antes del ACK remoto.

### 10.2 Revocación de sesión

Ante 403 clasificado, estado `REVOCADA` o cambio de identidad:

1. Marcar inmediatamente el namespace como revocado y limpiar el lease.
2. Purgar `cot_estado_document`, tombstones y receipts comerciales de esa sesión/generación en una transacción; conservar sólo metadatos mínimos necesarios para no reabrirla por accidente.
3. Invalidar caches/ViewModels y bloquear UI.
4. No borrar `cotizaciones`, `cotizacion_renglones`, `cotizacion_outbox`, revisiones comerciales, Pedidos ni catálogos.

### 10.3 Reinicio de generación

Ante 410 o estado `REQUIERE_REINICIO`, bloquear la vista. Con JWT vigente, llamar `/reiniciar` con el `sessionId`, device y `expectedGeneration` actuales. La respuesta crea nuevo `sessionId`/generación y declara `purgeRequired=true`. Purgar el namespace anterior, crear el nuevo vacío y volver a snapshot. No copiar versión, tombstone, receipt o checkpoint anterior.

`SESSION_EXPIRADA` también exige reinicio explícito; no se debe simular reanudación cambiando sólo el cursor local.

## 11. Convivencia con captura comercial, Pedidos y catálogos

- Mantener intactos `CotizacionCapturaPreparada`, el endpoint `POST /api/sync/cotizaciones`, la serialización del payload y la huella/revisión comercial.
- Mantener UUID de borradores y payloads congelados.
- Mantener la semántica y estados de `cotizacion_outbox`, incluidos reintentos e idempotencia.
- Mantener `SyncCredentials`, refresh y login existentes; el nuevo cliente los consume por usuario.
- No alterar tablas, estados ni sincronización de Pedidos.
- No alterar sincronización de catálogos ni usarla como prueba de permiso `cotizaciones.ver`.
- No unir automáticamente `cotizaciones.uuid_local` con `cot_estado_document.cotizacion_uuid` para conceder acceso. Puede presentarse una conciliación visual cuando ambos existen, pero la autorización de lectura procede exclusivamente de sesión/lease/feed vigentes.
- La purga de datos recibidos nunca debe usar `ON DELETE CASCADE` que alcance el outbox comercial.

## 12. Archivos candidatos a modificar en 05C-2

Existentes:

- `composeApp/src/commonMain/kotlin/com/cyssoftware/effyflow/db/LocalSchemaMigrations.kt`: migración idempotente de tablas nuevas y device ID.
- `composeApp/src/commonMain/kotlin/com/cyssoftware/effyflow/App.kt`: lifecycle por usuario, navegación e invalidación en logout/cambio de identidad.
- `composeApp/src/commonMain/kotlin/com/cyssoftware/effyflow/cotizacion/CotizacionRepository.kt`: extraer/compatibilizar la identidad de dispositivo sin cambiar payloads congelados; evitar añadir aquí la proyección recibida.
- `composeApp/src/commonMain/kotlin/com/cyssoftware/effyflow/cotizacion/CotizacionesScreen.kt`: separar claramente captura de documentos autorizados o enlazar a una pantalla nueva.
- `composeApp/src/commonMain/kotlin/com/cyssoftware/effyflow/login/data/LoginRepository.kt`: exponer de forma controlada identidad/tokens y eventos de logout si el nuevo orquestador lo requiere.
- `composeApp/src/commonMain/kotlin/com/cyssoftware/effyflow/pedido/sincronizacion/viewModel/SyncBearerAuth.kt`: sólo si hace falta compartir mejor la clasificación; preservar el refresh existente.
- `composeApp/src/commonMain/sqldelight/com/cyssoftware/effyflow/db/app_config.sq`: queries específicas para migración/identidad si se decide no usar las genéricas.

Nuevos sugeridos:

- `.../sqldelight/.../cotizacion_estados.sq`.
- `.../cotizacion/estados/CotizacionEstadosDtos.kt`.
- `.../cotizacion/estados/CotizacionEstadosApi.kt`.
- `.../cotizacion/estados/CotizacionEstadosRepository.kt`.
- `.../cotizacion/estados/CotizacionEstadosSync.kt`.
- `.../cotizacion/estados/DeviceIdentityRepository.kt`.
- `.../cotizacion/estados/CotizacionEstadosViewModel.kt`.
- `.../cotizacion/estados/CotizacionEstadosScreen.kt`.
- Tests JVM específicos para protocolo, persistencia y regresión.

Los drivers de base no necesitan lógica de negocio nueva si la migración común sigue siendo compatible. Cualquier soporte de reloj monotónico sí requerirá `expect/actual` o una abstracción inyectable para Android/JVM.

## 13. Plan de implementación por etapas

1. **Esquema y device ID:** crear tablas/querys, migración idempotente y normalización sin tocar outbox.
2. **DTO/API:** modelar exactamente respuestas reales, discriminador de snapshot, errores flexibles y header obligatorio donde aplica.
3. **Repositorio transaccional:** namespace, receipts, versiones, tombstones, checkpoint y purga selectiva.
4. **Bootstrap/snapshot/ACK:** recuperación de 202, multipágina, `SNAPSHOT_END` y ventana commit-ACK.
5. **Incremental:** páginas completas, deduplicación, contradicciones, reanudación y saturación.
6. **Lease/revocación/reset:** bloqueo central en repositorio, reloj, 403/410, cambio de usuario/agente y nueva generación.
7. **UI:** estado de inicialización, documentos autorizados, bloqueo/revalidación y separación visual de captura.
8. **Regresión:** confirmar que creación, outbox, tokens, Pedidos y catálogos conservan contratos y estados.

Cada etapa debe dejar las tablas comerciales actuales sin transformación de contenido.

## 14. Matriz de pruebas propuesta para 05C-2

| Caso | Verificación principal |
|---|---|
| Instalación nueva | Esquema completo, UUID canónico único, namespace vacío y snapshot inicial |
| Actualización `desktop-{UUID}` | Conserva UUID subyacente; no cambia payload congelado |
| Device legado inválido | Genera una sola vez UUID nuevo; no regenera al reiniciar/login |
| Dos usuarios en un dispositivo | Sesiones, leases, documentos y checkpoints aislados; cero exposición cruzada |
| Dos dispositivos del mismo usuario | Device/session distintos; no comparten checkpoint |
| Cambio de agente | Sesión anterior bloqueada/purgada; no se reutiliza namespace |
| Snapshot vacío | Aplica y ACKea `SNAPSHOT_END`; pasa a incremental |
| Snapshot multipágina | Commit y ACK por página; secuencia contigua |
| Página snapshot repetida | Misma huella idempotente; contradicción bloquea |
| Fallo antes del commit | Rollback; misma página se reaplica completa |
| Fallo después del commit antes de ACK | Datos/checkpoint permanecen; ACK se reconcilia/repite |
| ACK perdido | Consulta estado o repite ACK idempotente sin duplicar |
| Reinicio durante sync | Reanuda desde checkpoint SQLite, no desde memoria |
| Duplicado compatible | Misma versión/contenido no muta proyección |
| Versión antigua | No sobrescribe documento/tombstone; secuencia sí avanza |
| Igual versión contradictoria | No ACK, bloquea y requiere reconciliación/reinicio |
| Tombstone | Oculta/elimina visible y evita resurrección antigua |
| Evento posterior al snapshot | Aplica sólo si versión es superior |
| Revocación documental | Desaparece tras commit local, antes del ACK |
| Revocación de sesión | Bloqueo y purga sólo del namespace recibido; conserva outbox |
| Lease vigente sin red | Permite lectura hasta `validUntil` |
| Lease vencido | Repositorio y UI bloquean lista/detalle hasta revalidar |
| Retroceso de reloj | Detecta retroceso material y exige revalidación |
| Error de red/429/5xx | Backoff; no extiende lease ni purga por error temporal |
| Token expirado | Un refresh, tokens nuevos persistidos, request reintentado |
| Refresh fallido | Solicita login; no cruza tokens de usuario |
| 202 snapshot | Espera/consulta estado; no interpreta como snapshot vacío |
| Reinicio de generación | Purga namespace anterior y bootstrap desde cero |
| Saturación backend | Muestra/telemetría `ingestionSuspended`; conserva lease sin ampliarlo |
| Logout/cambio de usuario en vuelo | Descarta respuesta tardía y bloquea datos anteriores |
| Captura pendiente | UUID/body/idempotencia intactos durante normalización, revocación y purge |
| Regresión Pedidos | Captura, estados y cola mantienen comportamiento previo |
| Regresión catálogos | Sincronización y checkpoints existentes no cambian |

Esta matriz es diseño; no representa pruebas ejecutadas en esta fase.

## 15. Riesgos y decisiones pendientes

1. **Cifrado local:** no hay cifrado SQLite activo verificado. Antes de producción debe decidirse la protección en reposo para documentos autorizados, especialmente en JVM/Windows.
2. **Migraciones:** el mecanismo actual es DDL manual sin versión formal. Debe decidirse si 05C-2 continúa ese patrón con una marca de versión o introduce `.sqm`; mezclar ambos sin una estrategia puede divergir entre instalación nueva y actualización.
3. **Agente local:** Sync liga sesión a agente resuelto en servidor. Debe definirse la fuente local canónica para etiquetar el namespace y cómo se notifica su cambio; el servidor sigue siendo autoridad.
4. **Retención de receipts/tombstones:** conservarlos toda la generación es lo más seguro. Si el volumen obliga a compactar, se necesita una regla posterior al ACK y compatible con retención remota.
5. **Contradicción de igual versión:** se recomienda fallo cerrado y reinicio explícito. Falta acordar telemetría/UI para este estado de integridad.
6. **Errores no uniformes:** 401/403 pueden llegar como `{error}` y otros como `ErrorBody`. Kotlin debe tolerar ambos; sería conveniente alinear Sync en una fase posterior, sin bloquear 05C-2.
7. **HTTP 202:** definir backoff máximo, cancelación y mensaje de “inicializando” para evitar polling agresivo.
8. **Reloj monotónico multiplataforma:** definir abstracción e inyección de reloj para pruebas. El control no elimina manipulación deliberada.
9. **Presentación de captura conciliada:** decidir si una captura local y un documento autorizado con el mismo UUID se muestran relacionados. Esa relación nunca debe conceder autorización.
10. **Logout:** el contrato 05B-1 menciona purga local; debe decidirse si ocurre al logout siempre o se conserva cifrada pero inaccesible por usuario. La exigencia de impedir exposición cruzada y la política de revocación favorecen purga del namespace recibido, preservando outbox.
11. **Android/JVM:** ambas plataformas comparten lógica pero difieren en almacenamiento seguro y reloj. La fase debe validar ambas rutas si ambas continúan soportadas.

## 16. Dictamen de preparación para 05C-2

**Dictamen: PREPARADO CON DECISIONES CONTROLADAS para iniciar la implementación Kotlin COT-MOBILE-05C-2.**

El backend real expone las operaciones, secuencias, generación, lease, revocación, terminal de snapshot y reinicio necesarios. La app cuenta con autenticación, refresh, configuración HTTP, SQLite transaccional y separación de outbox suficientes como base. Las brechas Kotlin están identificadas y no requieren modificar el contrato comercial existente.

Antes de considerar 05C-2 listo para producción deben resolverse dentro de esa implementación: tablas/namespaces nuevos, normalización estable del device ID, aplicación transaccional con checkpoint, ACK posterior al commit, tombstones/versiones, bloqueo central por lease, purga selectiva y tolerancia a los cuerpos de error reales. El cifrado local y la política exacta de retención son decisiones pendientes relevantes; no invalidan el inicio del desarrollo, pero sí su liberación productiva.
