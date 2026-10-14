# COT-MOBILE-05C-3P — Resultado de integración con simulador ERP

Fecha de ejecución: 2026-10-13 (hora local del entorno; el XML de Gradle quedó fechado 2026-10-14T02:06:37Z).

## 1. Dictamen

**`05C-3P VALIDADO PARCIALMENTE`**

Se ejecutó Sync 05B real contra PostgreSQL 17.5 aislado y un consumidor Kotlin 05C-2 real contra Sync por HTTP, con SQLite SQLDelight temporal basada en archivo. El proveedor ERP fue un simulador local que validó las aserciones de lectura RS256 y respondió el contrato ERP. La prueba demuestra interoperabilidad del protocolo cubierto por ese simulador, persistencia en ambos motores, ACK y aislamiento de namespaces en los escenarios ejecutados.

Esto **no es UAT comercial del ERP real**: no se inició el ERP, no se consultó su base ni se ejecutaron operaciones comerciales. Varios escenarios de fallo enumerados abajo no se ejercitaron; por eso no se declara validación completa.

## 2. Entorno y aislamiento

- Repositorio de trabajo: `.`.
- Sync: `.\Backend\effyFlow`, código fuente en HEAD `c4687d17ab34e2bc6335c62df90717c5a0a96965`; paquete Spring Boot `effyFlow-0.0.1-SNAPSHOT.jar`, Spring Boot 3.5.16, Java 25.0.3.
- Kotlin: `.\App Pedidos\KotlinProject`.
- ERP real: no iniciado. El contrato fue servido por `uat-probe\COT-MOBILE-05C-3P\erp-contract-simulator.js`.
- Simulador HTTP: `127.0.0.1:18081` durante la prueba; detenido al finalizar.
- Sync HTTP: `127.0.0.1:18082` durante la prueba; detenido al finalizar.
- PostgreSQL: versión 17.5, PID 38660, escucha sólo en `127.0.0.1:55433`. Directorio físico verificado: `%LOCALAPPDATA%\CotizacionesUAT\05C3A\postgres\data`.
- Base usada por esta fase: `cotizaciones_uat_05c3p`, creada dentro del clúster aislado. La base preexistente `cotizaciones_uat_05c3a` se conservó sin alteraciones; su inventario previo de ocho tablas `sync_cotizacion_%` se mantuvo.
- SQLite Kotlin: archivo exclusivo `%LOCALAPPDATA%\CotizacionesUAT\05C3A\sync-kotlin-05c3p\kotlin-validated-95a555cfad8246db9114e495f84cb5f7.sqlite`; el test comprueba que no sea `%LOCALAPPDATA%\EffyFlow\effy.db`.
- No se usó PostgreSQL 5432, la base `prueba`, datos de ERP, cuentas reales, Sync VPS ni SQLite operativa. Sync y simulador terminaron; PostgreSQL UAT se dejó encendido para preservar el clúster.

## 3. Esquema sintético de identidad

La inspección de `CotizacionReadIdentityService` y de la autorización de Sync identificó las columnas y relaciones consultadas directamente. En una base nueva se preparó un esquema sintético mínimo con `users`, `agentes`, `roles`, `permissions`, `model_has_roles`, `role_has_permissions` y `model_has_permissions`; se aplicaron además los scripts reales de Sync base y v2:

- `Backend\effyFlow\db\scripts\20261013_cotizaciones_sync_durable.sql`
- `Backend\effyFlow\db\scripts\20261013_cotizaciones_sync_durable_v2.sql`

La base contiene 15 tablas públicas: siete para las consultas de identidad/autorización y ocho del subsistema Sync. No es el baseline ERP ni incluye tablas comerciales del ERP.

Fixtures sintéticos usados:

- Usuario 73001, agente 74001: permiso `cotizaciones.ver` mediante rol.
- Usuario 73002, agente 74002: permiso `cotizaciones.ver` directo.
- Usuario 73003, agente 74003: sin ese permiso, usado como identidad negativa.
- Las cuentas usan direcciones de dominio `.invalid`; sus credenciales quedaron en almacenamiento UAT local y no se incluyen en este informe.

La autenticación real de Sync aceptó la cuenta sintética autorizada y el endpoint protegido denegó al usuario 73003 con HTTP 403. No se alteró el servicio de identidad ni se introdujo un bypass.

## 4. Simulador ERP y criptografía

El simulador implementa autenticación técnica y las rutas `GET /api/cotizaciones/sync/bootstrap`, `/snapshot` y `/estados`, además de un control local para preparar fixtures. Mantiene datos y cursores deterministas: snapshot de 103 documentos en páginas de 100 y 3, marcador `SNAPSHOT_END`, y eventos sintéticos con IDs ERP 101–105.

La validación RS256 verifica algoritmo, tipo, `kid`, firma, issuer, audience, operación, vigencia, `jti` UUID, concordancia de sujeto/usuario, UUID canónico del dispositivo y asociación permitida usuario/agente. Las claves son exclusivas de UAT. Una aserción inválida/tamperada, presentada con autenticación técnica sintética válida, fue rechazada con HTTP 401; las aserciones válidas generadas por Sync fueron aceptadas.

La simulación cubre el contrato HTTP esperado, pero no demuestra que ERP real use exactamente la misma configuración, serialización, transacciones, consultas, orden, paginación ni semántica ante fallos. Tampoco prueba el login técnico real ni el feed ejecutándose en ERP.

## 5. Sync real

Sync arrancó desde el paquete construido en este repositorio, con directorio de trabajo aislado sin `.env.local`, PostgreSQL `cotizaciones_uat_05c3p`, lectura habilitada, endpoint del simulador, claves RSA de UAT, límites de cola y worker configurados. La configuración sensible se suministró al proceso y no se reprodujo en logs ni en este informe. Se comprobó el listener `127.0.0.1:18082` antes de enviar tráfico.

Durante una primera apertura de sesión, un paquete anterior falló por bytecode ECJ obsoleto en `target/classes` (`Unresolved compilation problem` al ejecutar `List.getFirst()`). Se respaldó el `target` previo fuera del repositorio, se reconstruyó con `mvnw.cmd clean package -DskipTests` y se comprobó el bytecode nuevo. No se modificó código funcional de Sync. También se reinició el proceso para limitar el bind a loopback. La prueba final corrió con el paquete limpio.

Las sesiones confirmadas quedaron registradas en PostgreSQL con sus generaciones, ACKs y cursores. Para el usuario 73001, el dispositivo principal terminó en generación 2 y secuencia 108 confirmada; el segundo dispositivo tuvo sesión independiente en generación 1 y secuencia 108. El usuario 73002 terminó en su propia sesión/dispositivo lógico con secuencia 5.

## 6. Kotlin real, HTTP y SQLite

Se añadió un test de integración JVM que invoca el consumidor existente: `CotizacionEstadosApi`, `CotizacionEstadosSync`, `CotizacionEstadosRepository`, `DeviceIdentityRepository`, `EffyDatabase` y migraciones SQLDelight existentes. El test crea una SQLite temporal respaldada por archivo y usa Ktor CIO sobre HTTP real; no sustituye los repositorios ni el motor de Sync por mocks.

El inicio de sesión obtiene credenciales y refresh token sintéticos de Sync. Para comprobar el refresco, la prueba inicializa el cliente con access token caducado y el refresh token real, y verifica que el callback de refresh reemplace el access token antes de continuar.

Comando ejecutado desde `App Pedidos\KotlinProject` con `JAVA_HOME` limitado al proceso a JDK 21:

```powershell
.\gradlew.bat :composeApp:jvmTest --tests com.cyssoftware.effyflow.CotizacionEstadosRealSyncIntegrationTest --no-daemon
```

Resultado registrado en `composeApp\build\test-results\jvmTest\TEST-com.cyssoftware.effyflow.CotizacionEstadosRealSyncIntegrationTest.xml`: 1 prueba, 0 omitidas, 0 fallos, 0 errores.

## 7. Escenarios ejecutados y evidencia

| Escenario | Resultado observado |
|---|---|
| Sesión nueva y bootstrap | Aprobado; Sync generó sesiones y el simulador respondió bootstrap. |
| Snapshot multipágina y `SNAPSHOT_END` | Aprobado; 103 registros en 2 páginas, marcador y checkpoint final 108. |
| Commit y ACK | Aprobado para flujo normal; al terminar `last_committed_sequence = last_acked_sequence = 108`. |
| Incremental/versiones | Aprobado para la secuencia determinista cubierta; cuatro eventos procesados para el agente anterior. |
| Tombstones/reasignación | Aprobado para dos documentos ocultados/reasignados; quedan 101 documentos visibles de los 103 iniciales. |
| Identidad negativa sin permiso | Aprobado; HTTP 403. |
| Segundo usuario autorizado | Aprobado; namespace separado y tres documentos visibles para usuario 73002. |
| Dos dispositivos | Aprobado; archivo SQLite y `deviceId` distintos, sesión independiente y ACK 108. |
| Cambio de generación | Aprobado; reset real por API, purga del namespace, generación 2 reconstruida desde snapshot, checkpoint/ACK 108. |
| Lease vencido y revalidación | Aprobado; el repositorio bloqueó lectura con reloj futuro; revalidación Sync renovó el lease y permitió lectura. |
| Refresh JWT | Aprobado con refresh token real de Sync. |
| Aserción de lectura inválida | Aprobado en simulador; HTTP 401. |
| Preservación de outbox | La prueba verifica que se opera sobre SQLite de UAT y el flujo de Sync separado; no fue una prueba exhaustiva de regresión comercial del outbox. |

IDs sintéticos de sesión y dispositivo de la corrida final:

- Usuario 73001, generación 2: sesión `639cd6f5-5950-4f92-b186-43a24efe2b85`, dispositivo `6edf6389-a4c9-4f43-b0ce-86c858cceb80`, committed/ACK 108, 101 visibles.
- Dispositivo secundario, generación 1: sesión `2c0968f9-bf4a-47ea-ac05-9bca1d9622ba`, dispositivo `1ae8e74d-4dbf-4d28-a9db-794b384efa95`, committed/ACK 108.
- Usuario 73002: sesión `ecc7caa4-36c7-4d3c-bdf4-9622f2ff98d2`, namespace separado, ACK final 5.

## 8. Casos pendientes

No se ejecutaron en esta corrida, por lo que quedan sin validar:

- Snapshot vacío.
- ACK perdido o respuesta ACK perdida después del commit SQLite y recuperación por replay.
- Caída forzada de Kotlin antes/después del commit, o reinicio de Sync entre páginas.
- Replay duplicado, versión antigua y contradicción de igual versión.
- Saturación real de cola y reanudación por backpressure.
- Corte temporal del simulador/ERP y recuperación.
- Revocación de sesión durante una petición activa y cancelación ante logout tardío.
- Expiración por transcurso real de 24 horas; se comprobó el bloqueo usando reloj inyectado.
- Ejecución de la suite JVM completa, regresiones de UI o inspección visual.
- Arranque/compilación Android.
- Escenarios comerciales reales en ERP (creación, edición, emisión, cancelación, conversión a Pedido y cambios de agente persistidos por el ERP).

## 9. Defectos y correcciones

- Defecto del artefacto Sync inicial: clases compiladas con error y stub de ECJ. Se corrigió regenerando el paquete después de respaldar `target`; no se cambió lógica fuente.
- Bind inicial de Sync no restringido a loopback. Se detuvo y relanzó con dirección `127.0.0.1`, verificada en el listener.
- Una corrida de depuración encontró HTTP 503 temporal de Sync por lease de worker durante revalidación. El test de integración reintenta errores HTTP transitorios y la corrida final pasó. No se cambió el worker ni se concluye que los fallos 503 estén exhaustivamente caracterizados.
- Los fallos de intentos previos dejaron sesiones sintéticas incompletas/requieren reinicio en la base exclusiva `cotizaciones_uat_05c3p`; no se borraron. Las sesiones de la corrida final descritas arriba terminaron activas y confirmadas.

## 10. Cambios y artefactos

Archivos nuevos del repositorio:

- `uat-probe\COT-MOBILE-05C-3P\erp-contract-simulator.js`
- `App Pedidos\KotlinProject\composeApp\src\jvmTest\kotlin\com\cyssoftware\effyflow\CotizacionEstadosRealSyncIntegrationTest.kt`
- Este informe: `COT-MOBILE-05C-3P-RESULTADO.md`

No se modificó ERP, el código funcional de Sync, el código funcional Kotlin, payloads comerciales ni la base SQLite operativa. El artefacto Sync previo se conservó en el directorio UAT local antes de regenerar `target`. No se hizo commit, push ni despliegue.

## 11. Riesgos pendientes y diferencia frente a UAT ERP

El simulador valida una implementación propia del contrato con criptografía real, pero no verifica el comportamiento del ERP desplegado. La integración tampoco cubre todavía todos los escenarios de pérdida, replay, contradicción, saturación y revocación concurrente. La suite reportada es sólo el test dirigido de integración, no la suite JVM completa.

Antes de afirmar integración end-to-end con ERP se requiere una UAT aislada con la versión 05A1B real, su cuenta técnica y aserción RS256 aceptadas por ERP, fixtures comerciales no productivos, pruebas de escritura autorizadas, recuperación ante fallos y validación visual/Android según plataforma objetivo. La protección/cifrado local de SQLite continúa siendo una condición pendiente para liberación productiva conforme al resultado 05C-2.
