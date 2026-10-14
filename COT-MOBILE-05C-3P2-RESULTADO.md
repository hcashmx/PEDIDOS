# COT-MOBILE-05C-3P2 — Resultado de resiliencia y recuperación

Fecha: 13 de octubre de 2026 (hora local; evidencias de Gradle/Spring fechadas el 14 de octubre UTC).

## 1. Dictamen

**`05C-3P2 VALIDADO PARCIALMENTE`**

Se probaron fallos de transacción SQLite, límites de proceso, pérdida de respuesta ACK, replay, contradicción de versión, tombstones, revocación, backpressure y contención del lease con Sync real, PostgreSQL UAT y el consumidor Kotlin real sobre SQLite SQLDelight temporal. También pasaron suites deterministas Kotlin y Sync.

No se cubrió la matriz completa de indisponibilidad, los cambios de identidad/permisos durante una respuesta, ni una interrupción forzada en mitad de una petición. La fuente ERP siguió siendo el simulador de COT-MOBILE-05C-3P, no el ERP real. Hay además dos salvedades de aislamiento que impiden un dictamen completo: el puerto de PostgreSQL del entorno reutilizado difiere del indicado en esta solicitud, y una invocación amplia de pruebas JVM abrió indirectamente la SQLite operativa antes de que se detectara y detuviera esa suite.

## 2. Entorno e aislamiento

- Sync: repositorio `Backend\effyFlow`, artefacto `effyFlow-0.0.1-SNAPSHOT`, Spring Boot 3.5.16, Java 25.0.3; proceso enlazado a `127.0.0.1:18082`.
- Simulador ERP: `uat-probe\COT-MOBILE-05C-3P\erp-contract-simulator.js`, enlazado a `127.0.0.1:18081`.
- Proxy UAT de fallos/medición: `uat-probe\COT-MOBILE-05C-3P\sync-ack-drop-proxy.js`, enlazado a `127.0.0.1:18083` sólo para las pruebas que lo requerían.
- PostgreSQL 17.5, base `cotizaciones_uat_05c3p2`, PID 4352 y directorio físico `%LOCALAPPDATA%\CotizacionesUAT\05C3A\postgres\data`.
- Kotlin usó archivos SQLite únicos bajo `%LOCALAPPDATA%\CotizacionesUAT\05C3A\sync-kotlin-05c3p\sqlite-*.sqlite`; no se usó esa ruta como sustituto de SQLite operativa durante las pruebas reales de resiliencia.
- No se consultó ERP, VPS, PostgreSQL 5432 ni la base `prueba`. No se ejecutaron migraciones PostgreSQL en esta fase. Las sesiones sintéticas y sus eventos quedaron conservados; no se borraron bases ni directorios.
- Al finalizar, Sync, simulador y proxy quedaron detenidos. El clúster PostgreSQL UAT permaneció encendido.

### Diferencia de puerto

La solicitud 05C-3P2 indica PostgreSQL `127.0.0.1:55432`, mientras que el resultado 05C-3P previo identifica el clúster conservado en `127.0.0.1:55433`. La instancia reutilizada y las pruebas de esta fase usaron `55433`; el inventario final mostró `55432` sin listener y `55433` en el directorio UAT indicado arriba. La discrepancia no se resolvió antes de operar en el clúster conservado. No se conectó a PostgreSQL 5432.

### Incidente de aislamiento de una suite JVM

Una invocación amplia de pruebas Kotlin heredó el valor real de `LOCALAPPDATA`. Cuatro clases seleccionadas —`OfflineThreePedidosCaptureTest`, `PedidoSyncStatusSemanticsTest`, `PendingQueueFailureRecoveryTest` y `PedidoStockNoBloqueaCaptureTest`— usan `DatabaseProvider`, cuyo adaptador JVM apunta a `%LOCALAPPDATA%\EffyFlow\effy.db`; por eso esa invocación abrió la SQLite operativa. La suite se detuvo al detectarlo y no se inspeccionó ni se restauró el archivo. La revisión de código mostró que tres pruebas fallaron antes de guardar/actualizar pedidos y `PedidoStockNoBloqueaCaptureTest` sólo preparó ViewModels y líneas de pedido en memoria. Sin embargo, al inicializar `DatabaseProvider` se llama a `LocalSchemaMigrations`; no puedo certificar que la apertura no haya afectado metadatos del esquema sin volver a abrir la base, lo cual no hice.

Esta corrida no se considera evidencia válida de regresión comercial ni de SQLite aislada. La suite selectiva posterior se ejecutó con `LOCALAPPDATA` redirigido al directorio `CotizacionesUAT\05C3A\deterministic-isolated` y excluyó las clases que acceden a `DatabaseProvider`.

## 3. Instrumentación UAT

Se amplió sólo el harness UAT:

- El simulador expone, protegido por la clave local de control, el número de lecturas demoradas activas y puede dirigir una demora al `dispositivoId` sintético elegido.
- El proxy registra hora de inicio, operación, estado HTTP, duración y si descartó la respuesta ACK. No registra tokens, credenciales ni cuerpos comerciales.
- El test Kotlin consulta el estado del lease en PostgreSQL durante la solicitud demorada y después de liberar la contención.
- Las esperas se limitaron a 5 s para el ensayo del lease. La saturación usó el límite UAT de 100 entradas, compatible con la página ERP de 100.

No se cambió la lógica funcional de Sync ni Kotlin. No se relajaron fencing, permisos, autorización ni límites de backpressure.

## 4. Fallos de entrega, persistencia y ACK

| Escenario | Resultado y evidencia |
|---|---|
| Error SQLite antes de completar una página | Aprobado. Un trigger de prueba abortó al insertar el segundo receipt; quedaron `committed=0`, cero documentos y cero receipts. Tras retirar el trigger, la misma página se aplicó dos veces sin duplicados: 100 receipts y ACK 100. Sesión `9be27a33-a58d-4a0c-bcd5-6e2bd907ecae`. |
| Kotlin detenido en límite anterior al commit | Aprobado como límite de proceso limpio: se recibió la página 1–100 y terminó esa JVM sin aplicar/ACK. Checkpoint local y ACK quedaron en 0. Tras reiniciar Sync y lanzar otra JVM con la misma SQLite, el replay completó `committed=114`, `acked=114`. Sesión `2a73c352-6df8-4023-93c1-26938c6a50d7`. No fue un `kill -9` durante código de aplicación. |
| Commit local, proceso nuevo antes del ACK | Aprobado como límite de proceso limpio: SQLite quedó en `committed=100`, 100 receipts y `acked=0`. Después de reiniciar Sync y abrir un nuevo proceso Kotlin, se reconciliaron estado remoto y replay hasta `committed=114`, `acked=114`. Sesión `4cf5da2d-e76d-46dc-9f9b-3f01d9c34120`. |
| Sync reiniciado entre procesos de recuperación | Aprobado: el servicio se detuvo y volvió a iniciar entre la observación del estado incompleto y la recuperación de los dos escenarios anteriores; se conservó la base UAT y el mismo `sessionId`/generación. No se detuvo Sync en medio de una respuesta HTTP en curso. |
| ACK confirmado por Sync pero respuesta perdida | Aprobado. El proxy recibió HTTP 200 de ACK y descartó esa respuesta (`responseDropped=true`). Antes de recuperar se observó local `100/0` y ACK remoto 100; tras consultar/continuar el consumidor quedó `114/114`. Sesión `a4c4a4e0-fb01-4d0e-948a-497c1615c092`. |
| Replay de página | Aprobado junto con el fallo transaccional: re-aplicar la página no duplicó receipts/documentos y permitió ACK sólo después del commit. |
| Corte de respuesta durante descarga | No ejecutado. |

## 5. Versiones, contradicción y tombstones

La secuencia ERP sintética procesada tuvo event IDs `101,102,103,105,106,107,108,109,110,111` y terminó con `committed=114`, `acked=114` en la sesión `55a762d1-1a42-4838-8a2e-7a40c2b5b761`.

- La versión 2 se aplicó; la versión 1 posterior se ignoró semánticamente.
- La versión 2 igual y compatible fue no-op.
- `visible=false` en versión 3 retiró el documento y dejó tombstone.
- La versión visible 2 no resucitó el documento frente al tombstone 3.
- La versión 4 autorizada permitió la reaparición; quedó documento v4 y sin tombstone.
- Para la contradicción de igual versión, el proxy alteró el estado del evento 108 en tránsito, manteniendo UUID, versión y secuencia. Kotlin bloqueó la sesión con `REQUIERE_REINICIO`/`CONFLICTO_INTEGRIDAD`; checkpoint local y ACK remoto permanecieron en 104, sin confirmar la página contradictoria. Sesión `ce788cb2-6378-49a6-b2e7-a213133c535f`.

La contradicción se inyectó entre Sync y Kotlin; no fue una contradicción emitida por el ERP simulado ni prueba de detección de contradicción en el ledger de Sync. El orden de la prueba se verificó por secuencias de entrega; los event IDs ERP sólo identifican los fixtures.

## 6. HTTP 503 del lease

Se reprodujo contención esperada del worker sin modificar autorización ni fencing. La lectura ERP se demoró 5 s para el mismo dispositivo de la sesión. Durante la demora, PostgreSQL mostró lease activo con 60 s restantes. El proxy y el test registraron:

| Operación | HTTP | Duración |
|---|---:|---:|
| Primera revalidación, propietaria del lease | 200 | 5,049 ms |
| Revalidación competidora | 503 | 43 ms |
| Revalidación posterior a liberar el lease | 200 | 32 ms |

Al final el lease quedó liberado, la sesión siguió `ACTIVA`, el cursor ERP fue 111 y el ACK delivery 114. El simulador registró la lectura ERP demorada en 5,008 ms. Sesión `c9f72afe-5ee4-4573-a01a-6f339e80bd33`.

El 503 observado corresponde al contrato de contención documentado por Sync: otra operación mantenía el lease. La prueba hizo una revalidación posterior explícita; no midió una política automática de backoff del consumidor. No se encontró defecto reproducible ni se cambió la lógica del worker.

Dos intentos previos del test de medición no son resultados aprobados: uno compitió antes de que la lectura demorada del mismo dispositivo comenzara y el otro esperó `ACTIVA` sin haber aplicado/ACK el snapshot. Se corrigió el orden/instrumentación del test y la corrida descrita arriba pasó.

## 7. Backpressure

Con el máximo UAT de 100 entradas, el test afirmó saturación (`pendingQueueEntries >= 100`) y `queue_saturated=true`. Mientras estaba suspendida, el cursor ERP se mantuvo en 111 aunque se añadió el event 112; no hubo avance artificial. El ACK de snapshot/eventos pudo continuar por la ruta de lectura. Al bajar la cola, la sesión reanudó ingesta: cursor ERP 112 y ACK delivery 115. Sesión grande `eaad48d8-25e5-41e2-b86b-9c32e2f4fa5e`.

Una sesión pequeña de otro usuario terminó con estado activo y `committed=7` (`b7ee1164-40ac-440d-95e1-ecf0a312240a`). Se comprobó después de que la sesión grande reanudó; no se afirma aquí que esa segunda sesión avanzara mientras la primera seguía suspendida. Los límites 100/50 y 120,000/100,000 bytes fueron exclusivamente UAT.

## 8. Revocación

El simulador negó la aserción ERP del usuario sintético 73001 con 403 durante revalidación. El consumidor marcó `REVOCADA`, purgó documentos recibidos de esa sesión y el repositorio bloqueó su lectura. Una marca `capture_pending_sentinel` en `app_config` se conservó. Sesión `b9ff408f-8abd-4074-afd6-d309decd65c9`.

Esto prueba una revocación de autorización respondida por el ERP simulado. No se revocó `cotizaciones.ver` en las tablas PostgreSQL de identidad, ni se probó logout/cambio de usuario mientras una respuesta quedaba pendiente. El sentinel confirma una clave de configuración de captura; no sustituye una aserción integral sobre `cotizacion_outbox.payload_json`.

## 9. Suites y resultados

### Integración real dirigida

11 métodos de `CotizacionEstadosResilienceRealSyncIntegrationTest` terminaron aprobados: rollback/replay, límite antes del commit, replay en nueva JVM tras reinicio Sync, límite después del commit, reconciliación tras reinicio, ACK perdido, versiones/tombstones, contradicción, backpressure, revocación y contención 503. Cada prueba usó Sync HTTP real, PostgreSQL UAT y SQLite temporal SQLDelight. Las pruebas individuales se ejecutaron por filtro Gradle y el XML de la última ejecución se archivó antes de reemplazarlo por la siguiente.

### Kotlin determinista aislado

Con `LOCALAPPDATA` redirigido al directorio UAT aislado y sin clases que usan `DatabaseProvider`: 105 pruebas de 14 clases, 0 omitidas, 0 fallidas. Incluyó API, repositorios SQLDelight temporales, sync/outbox de Cotizaciones, identidad de dispositivo, autenticación offline con fixtures, pedidos y persistencia de refresh.

No es la suite JVM completa. Se excluyeron pruebas que requieren cuentas/servicios externos, fixtures UAT con `DatabaseProvider` y las clases señaladas en el incidente de aislamiento.

### Kotlin amplio, no admisible como validación aislada

La primera invocación amplia completó 111 pruebas con 4 fallos:

- `OfflineThreePedidosCaptureTest`: la consulta esperaba un solo detalle y recibió más de uno.
- `PedidoSyncStatusSemanticsTest`: el conjunto local no contenía exactamente el único pedido que el test exige.
- `PendingQueueFailureRecoveryTest`: sus dos casos fallaron al esperar un solo detalle en el fixture compartido.

Las aserciones fallaron por estado/forma del fixture compartido antes de los guardados o actualización comercial previstos. La corrida también abrió `DatabaseProvider` en la SQLite operativa según lo descrito arriba; por ello no la clasifico como prueba segura ni como regresión válida del producto.

### Sync determinista

14 clases unitarias seleccionadas ejecutadas con JDK 25: 82 pruebas, 0 fallos, 0 errores, 0 omitidas. Incluyó contratos, ledger/capacidad, aserciones RS256, identity sync, cliente ERP y seguridad. Una primera invocación con JDK 21 no llegó a ejecutar pruebas: encontró bytecode Java 25 (`class file version 69`, 0 tests). Se repitió con Java 25 y pasó. No se incluyó `CotizacionSyncPostgresIT` en esta suite.

## 10. Casos pendientes

- ERP inaccesible, Sync inaccesible, timeout de lectura y recuperación automática.
- Matriz de respuestas ERP 429/502/503/504; corte de cuerpo HTTP.
- Reinicio de Sync durante una descarga/página activa y recuperación del mismo request.
- Snapshot vacío, página incompleta y secuencias discontinuas.
- Revocación efectiva quitando `cotizaciones.ver` en PostgreSQL, cambio de agente y cambio de usuario con respuesta tardía.
- Lease offline vencido por reloj durante esta fase, revalidación posterior y retroceso material del reloj; el resultado 05C-3P previo cubre sólo sus propios escenarios con reloj inyectado.
- Dos dispositivos del mismo usuario, duplicación del mismo event envelope, permiso negativo y regressión completa de outbox comercial no se repitieron aquí.
- Pruebas con ERP 05A1B real y fixtures comerciales; esta fase usa un simulador y no es UAT comercial.

## 11. Cambios y defectos

Cambios de esta fase limitados a pruebas e instrumentación UAT:

- Modificación del simulador y del proxy UAT para faults dirigidos, estado de demoras y marcas temporales.
- Nuevo test `CotizacionEstadosResilienceRealSyncIntegrationTest.kt`.
- Scripts locales UAT en `%LOCALAPPDATA%\CotizacionesUAT\05C3A\sync-kotlin-05c3p\`, incluido el arranque del simulador con secretos leídos desde el archivo local protegido.
- Este informe.

No se modificó código productivo ERP, Sync o Kotlin. No se alteraron contratos comerciales ni se desplegó. No hubo commit ni push. Permanecen en PostgreSQL UAT las sesiones sintéticas incompletas de los ensayos; no se limpiaron.

## 12. Riesgos antes de ampliar la validación

1. Resolver el destino de PostgreSQL: la instrucción de esta fase nombra 55432, pero el entorno heredado y ejecutado está en 55433.
2. Aclarar/verificar el estado de la SQLite operativa tras la invocación JVM que la abrió indirectamente. No inspeccioné ni restauré ese archivo y no puedo certificar su integridad byte a byte; no repetir esa suite sin redirigir `LOCALAPPDATA` o inyectar explícitamente un driver temporal.
3. Ejecutar los fallos de red/HTTP, revocación de identidad y reinicio durante petición aún pendientes en el clúster/puertos acordados.
4. Repetir regresiones comerciales sobre SQLite temporal, incluyendo contenido congelado del outbox, antes de concluir la protección de capturas.
5. Validar con ERP real y no inferir de estos resultados la equivalencia del simulador.

## Dictamen final

**`05C-3P2 VALIDADO PARCIALMENTE`**
