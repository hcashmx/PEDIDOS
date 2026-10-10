# PED-COM-SYNC-04 — Autoridad comercial ERP en Kotlin

Estado: desarrollo/UAT. Validación automatizada JVM e integración real aprobadas; UAT manual de interfaz y build Android pendientes. No es producción. Sin cambios de código Sync/ERP ni Cotizaciones.

Referencias auditadas: Sync `584e027` y `896bbe5`, `Backend/effyFlow/docs/PED-COM-SYNC-03.md`; ERP `b8a22e7`, `6fc5390` y `docs/pedidos/PED-COM-SYNC-02-CONTRATO.md`. Se leyó el manual local compartido sin modificarlo.

## Modelo offline y auditoría

Se conserva `pedidos`, `detalle_pedidos`, SQLite/SQLDelight, autoincremento local, `uuid_local` y la cola `getPedidosPendientesSync`. `TipoDocumento` ya existía. `TipoPedido.CONTADO/CREDITO` ya se capturaba al elegir cliente; faltaba persistirlo y transportarlo. `NINGUNO` continúa como estado transitorio UI y se rechaza al guardar/enviar. No se deduce venta del documento ni del plazo.

Campos agregados a `pedidos`: `tipo_venta`, `payload_comercial`, `resultado_comercial`, `total_erp`, `serie_erp`, `fecha_recepcion`, `version_erp`. Se reutilizan `uuid_server` para idPedido ERP, `estado_id` para el catálogo comercial, `folio` y `serie_id` para identidad definitiva, `synced_at` para estados locales (uso histórico; no es una columna temporal). Estado/nombre, origen y correlaciones adicionales quedan íntegros en el JSON de respuesta.

La migración aditiva se ejecuta desde los drivers JVM y Android mediante `LocalSchemaMigrations`. Conserva todos los datos existentes, admite repetición e informa fallos reales de migración. No asigna venta ni offset a pedidos legacy. El repositorio admite una BD inyectada para pruebas aisladas; el uso normal conserva DatabaseProvider.

## Contrato enviado

Se conserva el DTO snake_case `{pedido:{...},detalles:[...]}`. `PedidoDTO` incorpora `tipo_venta` independiente. Se envían exactamente `FACTURA`/`REMISION` y `CONTADO`/`CREDITO`. IDs, producto, presentación, almacén, cantidad, descuento porcentual y observaciones conservan su significado actual. Un Pedido debe llevar un único almacén positivo; los legacy sin almacén o con varios quedan para corrección manual.

`precio_unitario`, `descuento`, `total_linea` y `impuestos` JSON con `subtotal`/`totalImpuestos` son referencias locales por renglón. El contrato Sync no usa el total global como autoridad ni acepta folio móvil como folio definitivo. No se calcula una regla nueva de precio, promoción, volumen, crédito o Kit. El ERP resuelve y compara.

La app actual no tiene un código de promoción comercial seleccionado ni ruta explícita en la captura. No se inventan campos ni se agregan llamadas a preview en producción. El preview usado por la prueba real prepara exclusivamente referencias de fixtures UAT; no forma parte del envío de la app.

## Fecha comercial y UUID

El guardado toma una sola vez `Clock.System.now()`, la zona actual y `offsetAt(instante)` de kotlinx.datetime 0.6.1. Persiste `LocalDateTime + UtcOffset` en `fecha_creacion`, incluyendo `Z` si el offset es cero. Ejemplo válido: `2026-10-09T21:35:42-06:00`. La validación usa `kotlinx.datetime.Instant.parse`. No se toma otra fecha al enviar ni se usa la hora de Sync.

El UUID se genera al crear la captura, dentro de su vida local estable. La copia JSON durable contiene tanto UUID como fecha original y se guarda antes del primer HTTP. Retries, reconexiones, pérdida de respuesta y reapertura de SQLite reutilizan exactamente esa copia. Los cambios de estado técnico no reconstruyen el payload. Un cierre durante SINCRONIZANDO sigue dejando el Pedido elegible para recuperación.

El filtro por día usa los primeros diez caracteres de la fecha original. `date(fecha_creacion)` en SQLite convertiría un offset a UTC y ocultaría capturas nocturnas del día local.

Pedidos antiguos sin offset/tipo de venta quedan `ERROR_NO_REINTENTABLE`. No se inventa su zona, no se deduce venta del plazo y no se cambia su UUID. Falta aprobar una política de recuperación documentada de zona/venta o recaptura asistida para legacy; esta fase ofrece diagnóstico y revisión, no una migración semántica automática.

## Referencia comercial y precisión

La captura mantiene cálculos offline existentes con Double/REAL para referencia visual. Al congelar el JSON se convierten importes mediante BigDecimal, HALF_UP a seis decimales, y se serializan como números JSON decimales sin colas binarias; también se normaliza el JSON fiscal numérico. No se redondean cantidades ni se recalculan reglas comerciales. ERP compara precio/base/impuestos/total a seis y descuento a dos decimales según su contrato.

El total aceptado se guarda en `total_erp` como TEXT decimal exacto. UI y ticket lo muestran como definitivo y conservan el total/renglones locales identificados como referencia. El ticket usa el tipo de venta explícito y la serie/folio ERP. Deuda pendiente: cálculos y SQLite local siguen Double/REAL; formato UI puede convertir a Double para mostrar dos decimales. No se realizó una refactorización monetaria global.

## Estados locales, diferencias y confirmación

| Resultado/condición | Estado local | Elegible en cola automática de cada sincronización |
| --- | --- | --- |
| Captura nueva | PENDIENTE_SYNC | Sí |
| HTTP en curso/reinicio interrumpido | SINCRONIZANDO | Sí, siguiente ejecución |
| ACEPTADO válido | SYNCHRONIZED | No |
| DIFERENCIAS_COMERCIALES, HTTP 409 | PENDIENTE_REVISION | No |
| Otro 409/CONFLICTO UUID | CONFLICTO | No |
| Red/timeout, 408/429/5xx, éxito incompleto | ERROR_REINTENTABLE | Sí, siguiente ejecución |
| 400/401/403/422/validación local | ERROR_NO_REINTENTABLE | No |

La sincronización recorre cada Pedido una vez por invocación; no hay bucle de retry ni aceptación automática. La UI permite filtrar estos estados, revisar el detalle y preparar un reintento posterior explícito de diferencias/errores. Preparar el reintento conserva el JSON: no elimina referenciaApp ni confirma nuevas condiciones. Los conflictos UUID siempre requieren intervención manual y no admiten ese botón.

El JSON diferencial original se persiste. Se muestran renglón, precio/descuento/subtotal/impuestos/total local y ERP, promoción/motivo si existen y total propuesto ERP. No se sobrescribe la captura, no se asigna identidad ERP y no se marca sincronizado. La pantalla también muestra el total global local de referencia y los productos locales.

El contrato móvil Sync 03 no expone una operación de confirmación ni un campo de aceptación explícita. No se activa por omitir referencias. La edición comercial de pedidos existentes y la aceptación de diferencias requieren política funcional aprobada. En esta fase se entrega revisión + reintento posterior del mismo payload.

## Errores y autenticación

400/422 solicitan corregir captura; 403 muestra falta de permisos/configuración; 409 utiliza el flujo específico. HTTP 401 de origen ERP conserva la sesión móvil y no renueva tokens Sync. El 401 propio de Sync conserva el mecanismo existente de refresh, persistencia y reenvío. Si falla, se marca error no reintentable y se pide iniciar sesión. Se usa `SyncAuthenticationException` compartida, sin dependencia nueva de javax.naming.

Un éxito se acepta sólo con `resultado=ACEPTADO`, id/folio/serie/estado/total/fecha de recepción y uuidLocal correlacionado. Una respuesta incompleta queda recuperable con el mismo UUID. Total, versión, recepción y JSON completo se guardan mediante un único UPDATE SQLDelight junto a la identidad final. El folio sólo cambia tras aceptación válida.

## PED-SYNC-R1 y Kits

`PedidoEstadosSync` permanece intacto. Continúa por `uuidLocal` o idPedido remoto (`uuid_server`), cursor `id_historial`, límite estable de feed, replay y marcadores por Pedido. La aceptación no reinicia el cursor. El feed RECIBIDO y cambios posteriores actualiza `estado_id`, conserva datos/captura y no crea pedidos desconocidos ni convierte cotizaciones.

Se envía el Kit como un renglón de producto/presentación/cantidad. La consulta existente de componentes es informativa UI. No se agregan componentes al DTO ni se reserva inventario en Kotlin. El snapshot del Kit observado en PostgreSQL lo genera ERP.

## Pruebas e integración real

Pruebas focalizadas: **39 aprobadas** en PedidoComercialSyncTest (16), PedidoEstadosSyncTest (11), TokenRefreshPersistenceTest (4), PedidoRenglonContractTest (2), PedidoCreditoRulesTest (5) y PedidoCalculosTest (1). Cubren A–R: cuatro combinaciones documento/venta, fecha original con offset, congelamiento decimal, UUID/retry/reinicio, aceptación normal/volumen/promoción, diferencias fuera de cola, conflicto sin duplicación, errores HTTP, pérdida de respuesta, Kit sin expansión, feed RECIBIDO y cursor, migración legacy y filtro nocturno.

**Una prueba integral real aprobada, sin omisión, recorre ocho escenarios** con SQLite aislada + clase de cola Kotlin real + HTTP autenticado Sync local 8082 + ERP local 8081 + PostgreSQL `prueba`. No utiliza MockMvc ni mocks ERP. JDBC hace sólo SELECT, con conexión readOnly y consultas acotadas por UUID/ID. La pérdida de respuesta se provoca después de que HTTP confirma el commit real, antes de persistir aceptación local; después se cierra/reabre SQLite y se recupera el mismo ID/folio por retry. No es una prueba de desconexión física ni de dispositivo Android.

Se verifican UUID único, documento, instante fechaCapturaComercial, total definitivo, estado e historial RECIBIDO; replay y conflicto con captura distinta. El esquema ERP real **no tiene columna tipo_venta**: el propio ERP persiste plazo y sus métodos de consulta/idempotencia recuperan CONTADO/CREDITO por plazo efectivo. La prueba verifica esa representación real en SQL (`CASE WHEN plazo>0 ...`) contra la venta enviada; Kotlin nunca usa esa deducción para capturar/enviar.

Corrida final aprobada (segunda validación tras ajustes de fecha/autenticación):

| Escenario | idPedido ERP | Folio P01 | Total ERP | Historial |
| --- | --- | --- | --- | --- |
| normal | 106 | 53 | 176.6796 | 105 |
| factura | 107 | 54 | 176.6796 | 106 |
| remision | 108 | 55 | 176.6796 | 107 |
| volumen | 109 | 56 | 841 | 108 |
| promocion | 110 | 57 | 1252.8 | 109 |
| diferencia | No creado | - | Ver PostgreSQL | Ver feed |
| kit | 112 | 59 | 1392 | 111 |
| timeout/retry/reinicio | 111 | 58 | Ver PostgreSQL | Ver feed |

Todos los aceptados quedaron RECIBIDO. UUID diferencial final `175efd9e-efa9-424b-9bf0-f2dfd9be85e5`: cero pedidos ERP. Recuperación timeout `d4067903-adc1-4a8f-91c1-d0452f4e010a`: sólo Pedido 111/folio 58. Kit 112: un detalle comercial y snapshot fiscal kit. Primera corrida aprobada: Pedidos 99–105/P01 46–52; también se conservaron como evidencia UAT. No se eliminaron pedidos ni se insertó negocio manualmente.

Evidencia adicional fuera del repositorio: `%TEMP%/PED-COM-SYNC-04/evidence.json` y SQLite temporal `ped-com-04-uat-*.sqlite`. La prueba deja su ruta en el reporte JUnit. Secretos/JWT no se guardan en documentación ni se registran.

### Preparación local y bloqueo real de roles

El login del usuario ERP local original falla en Sync con HTTP 400. Evidencia runtime: `IllegalArgumentException: No enum constant com.cyssoftware.effyFlow.security.Rol.VENDEDOR`. Sync admite AGENTE/COBRANZA/ADMIN; las cuentas originales ERP tienen VENDEDOR. No se cambió código Sync, roles de usuarios existentes ni permisos ERP existentes.

El runner creó mediante API ERP roles fixture ADMIN **sin permisos ERP** y cuentas técnicas temporales compatibles para el lado móvil. La autoridad Sync→ERP sigue usando la credencial ERP ya preparada en `.env.local`. Tras cada intento, las cuentas nuevas 17/18/19 quedaron inactivas y sus roles 8/9/10 se renombraron `PED_COM_SYNC04_INACTIVE_<id>`. Las cuentas/roles originales permanecen iguales. Los fixtures propios de 03 (promoción 724 y volumen 779) estaban inactivos: se activaron por API sólo durante la prueba y volvieron inactivos, incluso en el intento de aserción fallido previo. No se modificó el código comercial del ERP.

El debugger IntelliJ no tenía configuraciones/runpoints del backend, por lo que la habilidad ij-debugger no pudo iniciar una sesión. La excepción se obtuvo con logging de ExceptionHandlerExceptionResolver; no se activó logging de cuerpos/headers. El bloqueo de roles del usuario original sigue siendo una condición UAT que debe resolverse de forma aprobada, fuera del alcance que prohíbe modificar Sync.

### Repetir validaciones

Con JDK 21 y los servicios locales preparados (ERP con su configuración habitual sandbox, Sync 8082 con las variables DB/JWT/ERP existentes):

```powershell
$env:JAVA_HOME='C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot'
.\gradlew.bat :composeApp:jvmTest --tests '*PedidoComercialSyncTest' --tests '*PedidoEstadosSyncTest' --tests '*TokenRefreshPersistenceTest' --tests '*PedidoRenglonContractTest' --tests '*PedidoCreditoRulesTest' --tests '*PedidoCalculosTest' :composeApp:jvmJar --console=plain
python scripts/run-ped-com-sync-04-uat.py
git diff --check
```

El runner requiere `DB_USERNAME`/`DB_PASSWORD` del ambiente existente, `.env.local` Sync y fixtures externos `target/ped-com-uat-scenarios.json`/`target/ped-com-uat-catalog.json` de 03. Las referencias de promoción/volumen deben corresponder al catálogo de prueba propio. Admite overrides SYNC_PROJECT_DIR, SYNC_TEST_BASE_URL, ERP_TEST_SCENARIOS_FILE, ERP_TEST_CATALOG_FILE, ERP_TEST_DB_URL y POSTGRES_JDBC_JAR. Carga JDBC desde el jar Maven local; no modifica dependencias Gradle ajenas. Credenciales temporales se pasan en memoria mediante el entorno al worker. `--rerun` obliga a ejecutar la tarea de integración aunque Gradle tenga resultados previos.

Build `compileKotlinJvm`/`jvmJar`: aprobado. `git diff --check`: aprobado. El intento adicional `assembleDebug` está **bloqueado por SDK Android ausente**: `local.properties` apunta a `C:/Users/servidor/AppData/Local/Android/Sdk`, carpeta inexistente, y no hay ANDROID_HOME configurado. No se cambió configuración local ajena ni se descargó un SDK.

No se ejecutó la suite completa: contiene pruebas históricas que operan sobre la SQLite de usuario y requieren fixtures UAT específicos; las focalizadas de esta fase usan SQLite aislada.

## Archivos modificados

Todos relativos al proyecto Kotlin:

- `composeApp/src/commonMain/sqldelight/com/cyssoftware/effyflow/db/pedidos.sq`
- `composeApp/src/commonMain/kotlin/com/cyssoftware/effyflow/db/LocalSchemaMigrations.kt`
- `composeApp/src/commonMain/kotlin/com/cyssoftware/effyflow/pedido/nuevoPedido/PedidoRepository.kt`
- `composeApp/src/commonMain/kotlin/com/cyssoftware/effyflow/pedido/nuevoPedido/viewmodel/NuevoPedidoViewModel.kt`
- `composeApp/src/commonMain/kotlin/com/cyssoftware/effyflow/pedido/nuevoPedido/ui/NuevoPedidoScreen.kt`
- `composeApp/src/commonMain/kotlin/com/cyssoftware/effyflow/pedido/sincronizacion/dto/PedidoConDetallesDTO.kt`
- `composeApp/src/commonMain/kotlin/com/cyssoftware/effyflow/pedido/sincronizacion/viewModel/PedidoComercialSync.kt`
- `composeApp/src/commonMain/kotlin/com/cyssoftware/effyflow/pedido/sincronizacion/viewModel/SyncViewModel.kt`
- `composeApp/src/commonMain/kotlin/com/cyssoftware/effyflow/pedido/sincronizacion/viewModel/SyncBearerAuth.kt`
- `composeApp/src/commonMain/kotlin/com/cyssoftware/effyflow/pedido/pedidosDia/viewmodel/PedidosDiaViewModel.kt`
- `composeApp/src/commonMain/kotlin/com/cyssoftware/effyflow/pedido/pedidosDia/ui/PedidosDiaScreen.kt`
- `composeApp/src/commonMain/kotlin/com/cyssoftware/effyflow/ticket/TicketGenerator.kt`
- `composeApp/src/jvmTest/kotlin/com/cyssoftware/effyflow/PedidoComercialSyncTest.kt`
- `composeApp/src/jvmTest/kotlin/com/cyssoftware/effyflow/PedidoComercialLocalIntegrationTest.kt`
- `scripts/run-ped-com-sync-04-uat.py`
- `docs/PED-COM-SYNC-04.md`

Se preservan y excluyen del commit los cambios previos ajenos en `composeApp/build.gradle.kts` (módulos Java empaquetados) y el manual compartido `C:/BDS/PEDIDOS/docs/12-manual-ejecucion-local.md`.

## Pendientes antes de Cotizaciones móviles

1. Aprobar política de confirmación/revisión comercial y edición posterior. No implementar aceptación automática ni omisión de referencias.
2. Aprobar recuperación de pedidos legacy sin offset/venta/almacén. No migrarlos deduciendo zona o reglas ERP desde Kotlin.
3. Resolver compatibilidad de roles/autenticación del usuario UAT original sin asumir que cuentas fixture resuelven el uso diario.
4. Preparar SDK y validar Android/dispositivo; realizar UAT manual de captura, estados, revisión y ticket. Estado manual: PENDIENTE_UAT.
5. Decidir transporte de ruta/código de promoción cuando exista captura funcional de esos datos; migración monetaria completa sigue como deuda acotada.
6. Diseñar Cotizaciones como otro flujo/contrato/outbox, con identidad y cursores propios si el contrato lo requiere. No reutilizar estados/idPedido ni agregar Cotizaciones al feed PED-SYNC-R1 por inferencia.

Commit local de esta fase: `feat(pedidos): integrar autoridad comercial ERP en la app`. Validación de este commit corresponde al target JVM disponible; Android sigue bloqueado por entorno. Sin push.
