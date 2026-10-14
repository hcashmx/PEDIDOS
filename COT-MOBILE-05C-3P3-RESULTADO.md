# COT-MOBILE-05C-3P3 — Resultado de resiliencia

Fecha de ejecución: 14 de octubre de 2026 (hora local/UAT).

## Dictamen

**`05C-3P3 VALIDADO PARCIALMENTE`**

Se ejecutaron fallos HTTP y de transporte entre Kotlin, Sync y el simulador ERP, con Sync real, PostgreSQL UAT y SQLite SQLDelight temporal. Se comprobaron checkpoints, ACK, tombstones, recuperación tras caída de procesos, revocación efectiva del permiso en PostgreSQL y cambio de agente durante una respuesta demorada. Se corrigió un defecto reproducible de clasificación retryable en Kotlin y se agregaron regresiones.

La resiliencia de este protocolo quedó validada para los escenarios descritos abajo. El dictamen permanece parcial porque el origen ERP fue el simulador del contrato, no ERP 05A1B real; esta ejecución no sustituyó la UAT comercial ni repitió la expiración real de 24 horas y el retroceso del reloj.

## 1. Entorno y aislamiento

| Componente | Identidad verificada |
|---|---|
| PostgreSQL UAT | 17.5, `127.0.0.1:55433`, base `cotizaciones_uat_05c3p2`, rol `cotizaciones_uat_app` |
| Clúster | Identificador `7696257406651362612`; directorio `%LOCALAPPDATA%\CotizacionesUAT\05C3A\postgres\data`; PID 4352 |
| Sync real | JAR `effyFlow-0.0.1-SNAPSHOT`, Java 25.0.3, `127.0.0.1:18082`, PID 33916 durante las pruebas |
| Simulador ERP | `erp-contract-simulator.js`, sólo loopback `127.0.0.1:18081`, PID 8240 en la última ejecución |
| Proxy de fallos | `sync-ack-drop-proxy.js`, sólo loopback `127.0.0.1:18083`, PID 32276 |
| Kotlin | SQLite SQLDelight temporal bajo `%LOCALAPPDATA%\CotizacionesUAT\05C3A\sync-kotlin-05c3p3\sqlite\`, archivos únicos por ejecución |

La identidad PostgreSQL se comprobó con `pg_controldata` y con una conexión real a la base, que devolvió `cotizaciones_uat_05c3p2|cotizaciones_uat_app|127.0.0.1|55433|17.5`. El puerto 55432 no tenía listener. No se conectó a PostgreSQL 5432, a `prueba`, al VPS ni a servicios externos. No se abrió, respaldó, examinó ni recuperó `%LOCALAPPDATA%\EffyFlow\effy.db`.

No se ejecutaron migraciones ni DDL. Las escrituras de PostgreSQL se limitaron a sesiones y fixtures sintéticos de resiliencia; las mutaciones temporales de permiso y agente se restauraron al cerrar cada prueba.

Cada corrida Kotlin usó un archivo SQLite nuevo o uno temporal creado por la prueba previa que formaba parte del mismo escenario de recuperación. El runner comprueba que la ruta esté debajo del directorio UAT, que no sea la SQLite operativa y que el destino PostgreSQL sea el clúster, puerto y base esperados. No se borraron bases ni directorios.

Al concluir, los procesos HTTP de Sync, simulador y proxy se detuvieron después de comprobar sus PID y líneas de comando. PostgreSQL UAT quedó encendido como estaba. Se verificó que las identidades de prueba estaban asociadas de nuevo a sus agentes iniciales (`73001→74001`, `73002→74002`, `73003→74003`) y que el usuario 73003 no conservaba el permiso directo de prueba `72001`.

## 2. Fallos HTTP y timeout

Se inyectaron desde el simulador ERP respuestas 429, 502, 503 y 504. En este contrato, Sync devolvió HTTP 503 con `retryable=true` para los cuatro errores transitorios. Kotlin mantuvo checkpoint, ACK y lease; el cursor ERP no avanzó. La sesión volvió a `ACTIVA` al retirar el fallo. En la observación registrada antes de la recuperación, el checkpoint local era `114/114` y el cursor ERP 111; son contadores distintos y no se compararon como si fueran el mismo cursor.

El simulador demoró una lectura más allá del timeout del cliente. Sync respondió 503 tras aproximadamente **15,039 ms**. El lease se liberó, el checkpoint permaneció estable y la sesión pudo revalidarse y continuar.

La prueba HTTP encontró un defecto Kotlin: el cuerpo real de Sync describía `ERP_UNAVAILABLE` como retryable, pero el cliente clasificaba esa respuesta 503 como no reintentable. Se ajustó la clasificación para tratar HTTP 503 como transitorio salvo `ERP_TECHNICAL_AUTH_FAILED`; se conserva el tratamiento no reintentable de errores de autenticación técnica. Se añadió cobertura de regresión para esa regla y para el código 502 no parseable. No se alteró el contrato ni la lógica de Sync.

## 3. Indisponibilidad y recuperación automática

- **Sync inaccesible:** se detuvo el proceso Java identificado por el JAR UAT. Kotlin obtuvo un error temporal; el checkpoint y el lease autorizado se conservaron y los 101 documentos continuaron disponibles mientras el lease seguía vigente. Tras reiniciar Sync, el mismo archivo SQLite reanudó la sesión y quedó confirmado `116/116` con 101 documentos.
- **ERP inaccesible:** se detuvo sólo el proceso del simulador. Kotlin conservó el lease y los documentos; cursor ERP y ACK no avanzaron. `retry_count` de la sesión aumentó. Al reiniciar el simulador, el worker de Sync limpió automáticamente el backoff **en 391 ms**, sin una petición Kotlin. La prueba observó `retryCount=0`, cursor ERP 113 y ACK delivery 116, iguales a los valores remotos previos a la recuperación. Una llamada posterior del consumidor confirmó `116/116` y 101 documentos.
- **Caída durante respuesta:** el proxy cortó la respuesta real de una página de eventos. No se creó receipt ni se adelantaron checkpoint/ACK (`114/114`); el documento visible v4 permaneció hasta recibir la página completa. Al repetirla, Kotlin persistió el tombstone versión 5 y confirmó la secuencia 115 (`115/115`).

Los cortes de procesos se hicieron entre peticiones, no mediante `kill -9` del proceso Kotlin mientras ejecutaba una transacción. El corte de cuerpo sí ocurrió durante una respuesta HTTP real.

## 4. Identidad, revocación y respuestas tardías

- **Cambio de usuario:** se demoró una respuesta de Sync y se cambió la identidad mientras estaba pendiente. Kotlin descartó la respuesta antes de persistirla o ACKearla. El segundo usuario obtuvo otra sesión y no pudo leer los documentos del namespace anterior.
- **Revocación efectiva de `cotizaciones.ver`:** la prueba habilitó y después eliminó la fila directa de `model_has_permissions` para el usuario sintético 73003 en PostgreSQL UAT. Sync respondió HTTP 403 en revalidación; Kotlin marcó `REVOCADA`, purgó el estado comercial recibido de esa sesión y el repositorio negó la lectura. El sentinel local de captura pendiente permaneció intacto.
- **Cambio de agente:** durante una respuesta `EVENTOS` demorada se cambió en PostgreSQL la asociación de 73003 de agente 74003 a 74004. La página tardía fue descartada sin commit ni ACK. La sesión terminó revocada y sus documentos recibidos fueron purgados; el sentinel de captura siguió presente. El `finally` del test restauró la asociación original.

La comprobación posterior confirmó la restauración de las asociaciones sintéticas y la ausencia del permiso directo de prueba para 73003. No se modificaron filas de identidades comerciales reales.

## 5. Checkpoints, ACK y estado local

| Caso | Evidencia observada |
|---|---|
| Fallo upstream 429/502/503/504 | Sync HTTP 503 retryable; checkpoint/ACK local estable; cursor y ACK PostgreSQL sin avance indebido; recuperación a `ACTIVA` |
| Timeout de lectura | Respuesta tras 15.039 s; lease liberado; checkpoint y cursores estables; revalidación posterior exitosa |
| Sync detenido | Error temporal; lease aún válido y 101 documentos disponibles; mismo SQLite se recuperó tras reiniciar Sync |
| ERP detenido | Backoff registrado; datos y cursores estables; worker limpió backoff sin llamada Kotlin; consumidor recuperó `116/116` |
| Cuerpo de eventos cortado | Sin receipt ni avance desde `114/114`; replay conservó tombstone v5 y confirmó `115/115` |
| Cambio de usuario/agente con respuesta pendiente | Respuesta tardía descartada antes de commit/ACK; namespaces separados; purga selectiva en revocación |

En las caídas temporales la lectura se permitió sólo mientras el lease local seguía vigente. No se extendió el lease por el error de red.

## 6. Pruebas ejecutadas

Las ejecuciones dirigidas de `CotizacionEstadosRemainingResilienceRealSyncIntegrationTest` usaron HTTP real entre el consumidor y Sync, PostgreSQL UAT y archivos SQLite temporales. Pasaron los métodos de matriz HTTP/timeout, semillas de caída, indisponibilidad/reanudación de Sync, indisponibilidad/reanudación de ERP, recuperación automática del worker, corte y replay de página con cambio de usuario, y revocación/cambio de agente. Cada filtro Gradle terminó con `BUILD SUCCESSFUL` y cero fallos.

Resultados adicionales de regresión ejecutados durante esta fase:

- **Kotlin determinista aislado:** 106 pruebas, 14 clases, 0 fallos, 0 errores, 0 omitidas. Incluye cliente/API, repositorios SQLDelight temporales, sync/outbox de Cotizaciones, identidad, permisos/offline, Pedidos y refresh de token.
- **Captura de Cotizaciones y Pedidos:** 36 pruebas en cuatro clases, 0 fallos, errores u omisiones. Se usaron SQLite en memoria o temporales.
- **Sync determinista:** 82 pruebas en 14 clases, 0 fallos, errores u omisiones. No incluyó `CotizacionSyncPostgresIT`.
- `:composeApp:compileTestKotlinJvm`: compilación exitosa tras las modificaciones.

La suite JVM amplia no se repitió: hay clases preexistentes que inicializan `DatabaseProvider` y no permiten garantizar que usen SQLite UAT. Se mantuvo la ejecución acotada y aislada solicitada; esta suite selectiva no equivale a toda la suite JVM.

## 7. Cambios de esta fase

- Clasificación retryable de errores en `CotizacionEstadosApi.kt` conforme a la respuesta HTTP real de Sync, más una prueba de regresión.
- Integración dirigida `CotizacionEstadosRemainingResilienceRealSyncIntegrationTest.kt` para los escenarios reales anteriores.
- Inyección UAT por dispositivo y controles del proxy para fallos, cortes y respuestas tardías; sin registrar credenciales ni cuerpos comerciales.
- Runner Kotlin y script de arranque del proxy con comprobaciones del destino PostgreSQL y de la ruta SQLite.
- Este informe.

Los cambios UAT sólo instrumentan el simulador/proxy. No se modificó ERP ni Sync, ni se alteró el payload comercial, la cola de capturas, Pedidos o catálogos. Se preservaron los cambios previos del repositorio. No hubo commit, push ni despliegue.

## 8. Escenarios pendientes y límites

- No se ejecutó UAT comercial contra ERP 05A1B real. Las respuestas ERP y los documentos de prueba provienen del simulador firmado; por tanto, estos resultados no prueban los controladores, permisos ni DDL del ERP real.
- No se esperaron 24 horas para vencer un lease ni se inyectó un retroceso material del reloj en esta fase.
- No se repitió la inspección visual de Compose ni la compilación Android; estos escenarios no forman parte de esta corrida de resiliencia JVM.
- No se ejecutó `CotizacionSyncPostgresIT` ni la suite JVM completa, debido a las dependencias que requieren servicios o `DatabaseProvider` no aislado.
- Un HTTP ya entregado puede terminar de transmitirse después de una revocación iniciada en el servidor; el test demostró descarte local de una respuesta pendiente al cambiar identidad y no elimina esa limitación del transporte.

## Dictamen final

**`05C-3P3 VALIDADO PARCIALMENTE`**

La matriz de fallos y recuperación indicada en esta fase quedó ejercitada con Sync real y persistencia PostgreSQL/SQLite aislada. La siguiente validación debe ser UAT end-to-end con ERP real y cubrir vencimiento de 24 horas y retroceso de reloj antes de emitir un dictamen integral de producción.
