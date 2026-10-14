# COT-MOBILE-05C-2 — Resultado de implementación Kotlin

Fecha: 2026-10-13

## 1. Dictamen

**05C-2 IMPLEMENTADO CON PENDIENTES**

El consumidor durable quedó implementado en Kotlin Multiplatform con persistencia SQLDelight real, namespaces por sesión/generación/identidad, snapshot, `SNAPSHOT_END`, incremental, ACK posterior al commit, recuperación de ACK perdido, versiones, tombstones, revocación, lease central y UI independiente.

Las pruebas fundamentales nuevas de SQLite, ACK, revocación y aislamiento pasan. Quedan pendientes la compilación Android por ausencia de SDK en el equipo, la ejecución limpia de los smoke/UAT preexistentes que dependen de configuración/servicios locales y la UAT end-to-end contra Sync desplegado. Por estas razones no se declara completamente validado.

## 2. Archivos creados y modificados

### Creados

- `composeApp/src/commonMain/sqldelight/com/cyssoftware/effyflow/db/cotizacion_estados.sq`
- `composeApp/src/commonMain/kotlin/com/cyssoftware/effyflow/cotizacion/estados/DeviceIdentityRepository.kt`
- `composeApp/src/commonMain/kotlin/com/cyssoftware/effyflow/cotizacion/estados/AuthorizationClock.kt`
- `composeApp/src/commonMain/kotlin/com/cyssoftware/effyflow/cotizacion/estados/CotizacionEstadosDtos.kt`
- `composeApp/src/commonMain/kotlin/com/cyssoftware/effyflow/cotizacion/estados/CotizacionEstadosApi.kt`
- `composeApp/src/commonMain/kotlin/com/cyssoftware/effyflow/cotizacion/estados/CotizacionEstadosRepository.kt`
- `composeApp/src/commonMain/kotlin/com/cyssoftware/effyflow/cotizacion/estados/CotizacionEstadosSync.kt`
- `composeApp/src/commonMain/kotlin/com/cyssoftware/effyflow/cotizacion/estados/CotizacionesSincronizadasScreen.kt`
- `composeApp/src/jvmMain/kotlin/com/cyssoftware/effyflow/cotizacion/estados/AuthorizationClock.jvm.kt`
- `composeApp/src/androidMain/kotlin/com/cyssoftware/effyflow/cotizacion/estados/AuthorizationClock.android.kt`
- `composeApp/src/jvmTest/kotlin/com/cyssoftware/effyflow/CotizacionEstadosRepositoryTest.kt`
- `composeApp/src/jvmTest/kotlin/com/cyssoftware/effyflow/CotizacionEstadosApiTest.kt`
- `composeApp/src/jvmTest/kotlin/com/cyssoftware/effyflow/CotizacionEstadosSyncTest.kt`
- `composeApp/src/jvmTest/kotlin/com/cyssoftware/effyflow/DeviceIdentityRepositoryTest.kt`

### Modificados

- `LocalSchemaMigrations.kt`: migración idempotente del subsistema.
- `app_config.sq`: inserción `INSERT OR IGNORE` para inicialización concurrente del device ID.
- `App.kt`: ruta independiente y binding a identidad activa.
- `PedidoScreen.kt`: entrada “Cotizaciones sincronizadas”.
- `LoginRepository.kt`: purga selectiva del conjunto recibido al cerrar sesión.

No se modificaron Backend/Sync, ERP, el DTO comercial de captura ni el código que congela `cotizacion_outbox.payload_json`.

## 3. Migraciones SQLDelight

Se mantuvo el mecanismo existente:

1. El archivo `.sq` declara las tablas y genera queries para instalaciones nuevas.
2. `LocalSchemaMigrations.apply()` ejecuta `CREATE TABLE/INDEX IF NOT EXISTS` para instalaciones existentes.
3. No se introdujo `.sqm` ni un segundo motor de migraciones.

Tablas añadidas:

- `cot_estado_sync_session`
- `cot_estado_document`
- `cot_estado_tombstone`
- `cot_estado_delivery_receipt`

Las tablas recibidas no tienen relación con `cotizaciones`, `cotizacion_renglones` o `cotizacion_outbox`. La purga es explícita y no depende de `PRAGMA foreign_keys`, que no estaba habilitado en los drivers existentes.

## 4. Compatibilidad con datos existentes

`DeviceIdentityRepository` usa `sync_device_id`. Si no existe, normaliza un UUID canónico o extrae determinísticamente el UUID de `desktop-{UUID}`. La inicialización usa mutex multiplataforma más `INSERT OR IGNORE`, por lo que llamadas concurrentes convergen al mismo valor.

No modifica:

- `cotizaciones_dispositivo_id`;
- UUID de capturas;
- payloads congelados;
- outbox comercial;
- Pedidos, catálogos o credenciales de otros usuarios.

Una prueba de migración sobre SQLite elimina las tablas nuevas para simular el esquema anterior, ejecuta `LocalSchemaMigrations` y verifica que se crean las cuatro tablas conservando una fila centinela comercial.

## 5. Cliente HTTP

`CotizacionEstadosApi` implementa las rutas reales:

- `POST /sesion`
- `GET /sesion/{id}`
- `GET /sesion/{id}/snapshot`
- `GET /sesion/{id}/eventos`
- `POST /sesion/{id}/ack`
- `POST /sesion/{id}/revalidar`
- `POST /sesion/{id}/reiniciar`

Envía `X-Sync-Device-Id` en estado, snapshot, eventos, ACK y revalidación; creación y reinicio usan el body real. Reutiliza `configureSyncBearer` y la persistencia de refresh existente.

Clasifica 202, 400/422, 401, 403, 404, 409, 410, 429 y 502/503/504. Acepta tanto `ErrorBody` como `{error:...}`. No registra tokens ni cuerpos comerciales.

## 6. Snapshot y ACK

El flujo implementado:

1. Comprueba que la identidad local siga activa.
2. Obtiene el device ID persistente.
3. Recupera sesión o abre una nueva.
4. Persiste sesión/generación bajo usuario/agente/device.
5. Solicita snapshot desde `last_committed_sequence`.
6. Valida envoltorio, continuidad y discriminación documento/terminal.
7. Aplica página, receipts y checkpoint en una transacción SQLite.
8. Envía ACK después del commit.
9. Mantiene `last_committed_sequence` y `last_acked_sequence` separados.
10. Entra a incremental sólo al observar `ACTIVA`.

HTTP 202 activa espera exponencial acotada, consulta de estado y nuevo intento. Un snapshot vacío se completa mediante una entrega `SNAPSHOT_END` real.

Si se pierde la respuesta ACK, la siguiente ejecución consulta estado, conserva el checkpoint local y repite el ACK sólo cuando Sync sigue detrás. Nunca retrocede datos locales ni inventa secuencias.

## 7. Incremental, versiones y tombstones

El checkpoint usa exclusivamente `deliverySequence`. `eventId` se conserva como metadato del evento/tombstone.

- Versión mayor: sustituye proyección o tombstone.
- Versión menor: no cambia la proyección, pero el transporte se registra.
- Igual versión y fingerprint compatible: no-op.
- Igual versión contradictoria: rollback completo, no ACK y estado de reinicio/integridad.
- `visible=false`: elimina documento y crea tombstone en la misma transacción.
- Tombstone bloquea versiones antiguas.
- Reaparición exige versión superior y evento visible.

Los fingerprints se calculan sobre una representación canónica de campos ordenados, independiente del orden textual JSON. Los receipts incluyen tipo y metadatos de transporte para detectar una secuencia repetida con contenido distinto.

## 8. Lease offline

El repositorio, no sólo la UI, bloquea consultas si:

- el usuario/agente/device no coincide;
- sesión o bootstrap no está `ACTIVA`;
- falta o vence `validUntil`;
- el lease supera el máximo aceptado de 24 horas;
- el reloj de pared retrocede más de cinco minutos respecto al máximo observado;
- el tiempo monotónico demuestra que el lease ya venció.

`AuthorizationClock` es inyectable. JVM usa `System.nanoTime`; Android usa `SystemClock.elapsedRealtime`. Errores de red y revalidaciones fallidas no cambian el lease.

Este control detecta retrocesos accidentales o evidentes; no constituye protección frente a manipulación deliberada del sistema.

## 9. Revocación y reinicio

Un 403 bloquea la sesión, limpia el lease y purga exclusivamente documentos, tombstones y receipts recibidos del namespace. Se conserva una marca mínima de sesión revocada para impedir reapertura accidental.

Un 410 marca reinicio requerido, llama el endpoint aprobado, verifica `purgeRequired`, un `sessionId` nuevo y una generación superior, purga el namespace anterior e inicia snapshot desde cero.

En logout se purgan todos los namespaces recibidos del usuario saliente. La captura/outbox, Pedidos, catálogos y credenciales de otros usuarios permanecen intactos.

Las respuestas recibidas después de logout/cambio de identidad se descartan antes de escribir mediante `identityStillActive()`.

## 10. UI implementada

Se agregó una pantalla independiente llamada **Cotizaciones sincronizadas**, accesible desde el módulo de Pedidos. Muestra:

- inicialización/estado remoto;
- documentos autorizados;
- estado, versión y fecha de actualización;
- última sincronización;
- vencimiento de autorización offline;
- errores recuperables;
- revocación y reinicio requerido;
- saturación temporal reportada por Sync;
- acciones de sincronizar y revalidar.

La pantalla actual “Cotizaciones” continúa dedicada a borradores y captura. No se usa coincidencia de UUID local como autorización.

## 11. Pruebas ejecutadas y resultados

Entorno JVM: JDK 21 (`$env:JAVA_HOME (JDK 21)`). El JDK 25 predeterminado no es aceptado por la versión de Gradle/Kotlin del proyecto, por lo que las verificaciones se ejecutaron explícitamente con JDK 21.

### Verificación nueva y regresión determinista

Comando: `:composeApp:jvmTest` filtrando pruebas nuevas y suites unitarias de Cotizaciones, Pedidos, autenticación y refresh.

Resultado final: **97 pruebas aprobadas, 0 fallos**.

Cobertura relevante:

- SQLite nueva y migración existente;
- normalización legacy, estabilidad y concurrencia del device ID;
- payload congelado/clave legacy intactos;
- snapshot vacío y multipágina;
- `SNAPSHOT_END`;
- HTTP 202 y ambos formatos de error;
- rutas/header reales;
- commit antes del ACK y ACK perdido;
- respuesta tardía después de logout;
- replay y contradicción por secuencia;
- versiones viejas, iguales y superiores;
- tombstone y no resurrección;
- lease vencido y retroceso de reloj;
- aislamiento por usuario, agente y generación;
- revocación y purga selectiva;
- regresiones de captura/outbox, pricing, Pedidos, autenticación offline y refresh.

Compilación JVM: `:composeApp:compileKotlinJvm` — **exitosa**.

### Suite JVM completa

Se ejecutó inicialmente `:composeApp:jvmTest`: 117 pruebas en ese punto, 105 aprobadas y 12 fallidas. Dos fallos pertenecían a pruebas nuevas y fueron corregidos. Los 10 restantes corresponden a smoke/UAT existentes que dependen de configuración o servicios locales: `FirstSyncSmokeTest`, `LocalPedidoCaptureSmokeTest`, `OfflinePendingPedidosRealSyncTest`, `OfflineThreePedidosCaptureTest`, `PedidoSyncPostSmokeTest`, `PedidoSyncStatusSemanticsTest`, `PendingQueueFailureRecoveryTest` y `SegundoPedidoFullFlowTest`. No se atribuyeron al nuevo subsistema; las suites unitarias deterministas relacionadas pasan en la ejecución final.

No se declara verde la suite completa mientras esas dependencias no estén disponibles y se vuelva a ejecutar.

## 12. Regresiones

Pasaron las suites deterministas existentes:

- `CotizacionRepositoryTest`
- `CotizacionOutboxSyncTest`
- `CotizacionPricingTest`
- `PedidoComercialSyncTest`
- `PedidoEstadosSyncTest`
- `OfflineAuthenticationTest`
- `TokenRefreshPersistenceTest`

No se cambió la serialización comercial, el endpoint de creación, los UUID, los estados del outbox ni los refresh tokens.

## 13. Limitaciones Android/JVM

La compilación Android se intentó con `:composeApp:compileDebugKotlinAndroid` y no inició porque no hay SDK Android disponible. `local.properties` apunta a `<ruta local omitida>`, ruta inexistente para el usuario actual, y tampoco existe `%LOCALAPPDATA%\Android\Sdk`.

Los adaptadores Android comparten la lógica común y usan `SystemClock.elapsedRealtime`, pero deben compilarse y ejecutarse cuando se configure un SDK válido.

JVM compila y las pruebas SQLite reales pasan. La base JVM sigue ubicada bajo el directorio de datos de EffyFlow definido previamente.

## 14. Seguridad local y cifrado pendiente

No se activó ni cambió cifrado de `effy.db`. El código inspeccionado mantiene SQLCipher comentado tanto en Android como en la configuración JVM; por tanto, el archivo SQLite no tiene cifrado en reposo verificado.

El acceso funcional está condicionado en el repositorio por identidad, namespace y lease. Esto no cifra el archivo ni protege contra extracción directa.

Antes de producción se recomienda una fase específica para:

1. seleccionar almacenamiento cifrado compatible por plataforma;
2. respaldar y verificar capturas/outbox pendientes;
3. migrar la base de forma recuperable;
4. validar rollback y espacio insuficiente;
5. probar actualización desde bases reales Android/JVM;
6. documentar administración de claves.

## 15. Preparación para UAT end-to-end

La implementación está preparada para UAT una vez que:

- exista SDK Android para compilar ese target;
- se configure una instancia Sync 05B con usuario/agente y permisos reales;
- se prueben 202, snapshot multipágina, ACK perdido, revocación, reset y saturación contra HTTP real;
- se repita la suite completa con los servicios requeridos por los smoke tests existentes;
- se inspeccione visualmente la nueva pantalla en JVM y Android.

No se ejecutaron migraciones sobre datos operativos ni se realizó despliegue.

## 16. Cambios pendientes de commit

Todos los archivos enumerados permanecen sin commit. No se hizo commit ni push. Los cambios preexistentes fuera del proyecto Kotlin fueron preservados y no se modificaron.
