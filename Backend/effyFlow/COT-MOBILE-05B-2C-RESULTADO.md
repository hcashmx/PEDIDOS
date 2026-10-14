# COT-MOBILE-05B-2C — Corrección de hallazgos y validación transaccional

**Fecha:** 2026-10-13
**Dictamen:** **05B-2C VALIDADO PARCIALMENTE**

La corrección de código quedó compilada y las pruebas unitarias focalizadas pasan. No se puede declarar lista para Kotlin 05C: no hubo PostgreSQL aislado demostrable, así que no se verificaron transacciones, ACK, rollback ni concurrencia real. La carrera entre una respuesta previamente ofrecida y una revocación posterior conserva el límite inherente al envío HTTP descrito abajo.

## 1. Defectos corregidos

- **Deduplicación tras compactación:** la fuente de idempotencia pasa a ser un ledger mínimo separado, con clave `(session_id,event_id)` y hash SHA-256. El inbox conserva el payload durante la ventana de diagnóstico/entrega, pero su limpieza exige que el evento ya tenga delivery y que ninguna entrega de ese evento siga sin ACK. Tras compactar el inbox, un replay con el mismo hash no crea otra proyección ni otra entrega; un hash distinto para el mismo `eventId` suspende la generación por conflicto.
- **Respuestas nuevas frente a revocación:** snapshot y eventos vuelven a comprobar identidad y permiso inmediatamente después de revalidar ERP y antes de llamar a `offer()`. `offer()` bloquea la fila de sesión, comprueba el estado activo y actualiza el waterline en la misma transacción. El commit de esa oferta es el punto de linealización: si la revocación de sesión confirma primero, no se crea una oferta nueva; si la oferta confirma primero, la revocación espera el lock y luego purga la sesión.
- **Presión de cola:** se añadió medición por sesión de entregas pendientes más eventos buffer aún no materializados. El control usa límites de cantidad y bytes estimados, además de umbrales de reanudación independientes. Al saturarse, la transacción de página no inserta inbox/ledger, no crea delivery y no avanza `erp_cursor`; la sesión queda marcada y se pausa sólo su ingesta ERP. El endpoint de estado informa `ingestionSuspended` y `COTIZACIONES_COLA_SATURADA`; snapshot/eventos/ACK siguen disponibles para drenar la cola. El counter `sync.cotizaciones.queue.saturation_checks` y los summaries de ocupación se registran con Micrometer; `/actuator/metrics` queda expuesto y protegido por la autenticación general del backend.
- **Límites de capacidad seguros:** los valores por defecto son cero y la lectura falla cerrada hasta que se configuren. Se exige capacidad para al menos una página ERP completa (100 eventos) y el estimador de bytes mínimo equivalente a los límites de los campos SQL. Es un requisito de progreso del protocolo, no un límite productivo recomendado. El valor definitivo debe derivarse de tamaño real de filas/índices, tasa máxima de eventos y presupuesto de almacenamiento.
- **Métrica de retención:** el conteo ahora sale de las filas retornadas por el CTE `DELETE`, no del número de filas de sesión cuyo floor se actualizó.
- **Dominios persistidos:** la migración v2 agrega checks positivos/no negativos para IDs, generaciones, versiones, ordinales y secuencias; valida consistencia de checkpoints de sesiones activas, ACK de snapshot y nulos comerciales para `visible=false`.
- **Mapeo JDBC previo:** se conserva la corrección del mapeo `BIGINT` nullable de `agente_id` a `Integer` con validación de rango.

## 2. Archivos modificados

- `src/main/java/com/cyssoftware/effyFlow/repository/CotizacionSyncRepository.java`
- `src/main/java/com/cyssoftware/effyFlow/service/cotizaciones/CotizacionStatesSyncService.java`
- `src/main/java/com/cyssoftware/effyFlow/service/cotizaciones/CotizacionSyncRepositoryModels.java`
- `src/main/java/com/cyssoftware/effyFlow/config/CotizacionesMetricsConfig.java` (nuevo)
- `src/test/java/com/cyssoftware/effyFlow/repository/CotizacionSyncRepositoryTest.java`
- `db/scripts/20261013_cotizaciones_sync_durable_v2.sql` (nuevo)
- `pom.xml`
- `src/main/resources/application.properties`
- `COT-MOBILE-05B-2C-RESULTADO.md` (este informe)

No se modificaron fuentes de ERP, Kotlin, captura comercial ni Pedidos. El SQL no se aplicó.

## 3. Persistencia y migración

La tabla `sync_cotizacion_event_ledger` conserva sólo sesión, `eventId`, hash y fecha. Su FK con cascada la elimina al finalizar la retención de la sesión (el borrado de la fila terminal ocurre después de 90 días); la revocación/expiración purga datos comerciales, pero conserva este mínimo hasta ese cierre. La limpieza del inbox puede borrar el payload a los 30 días solamente si está marcado entregado y no existe delivery correspondiente por encima del ACK.

La migración versionada v2 parte de `20261013_cotizaciones_sync_durable.sql`, importa al ledger los hashes aún presentes en inbox y marca las sesiones preexistentes vivas como `REQUIERE_REINICIO` (`LEDGER_MIGRATION_REQUIRED`). La retención anterior pudo borrar hashes históricos, por lo que no es posible certificar deduplicación completa de esas generaciones; deben crear una generación nueva por snapshot antes de reanudar el consumo. Las sesiones antiguas no entregan datos después de la migración y Kotlin debe atender el 410/reset mediante el flujo ya contratado.

El proyecto no muestra un runner de migraciones que controle versión automáticamente. Instalación nueva: aplicar script base y después v2. Actualización: respaldar, detener el consumidor de Sync, revisar el estado/datos previos, ejecutar v2 una sola vez como migración aprobada, comprobar restricciones y crear generación nueva antes de reanudar. El script incluye constraints validadas contra los datos existentes; si hay filas fuera de dominio, la transacción falla completa y requiere diagnóstico. No aplicar ambos scripts a una base existente sin ese procedimiento.

## 4. Revocación y su límite HTTP

El punto de linealización de la **oferta durable** es el commit de `CotizacionSyncRepository.offer()`, que mantiene el lock de sesión sólo durante la consulta y actualización del waterline. No se abre una transacción durante JSON serialization ni transmisión. Una petición cuya comprobación de estado/oferta ocurra después del commit de revocación verá la sesión terminal y no recibirá filas. La revalidación inmediata del actor vuelve a comprobar usuario, rol, agente único y `cotizaciones.ver` antes de la oferta; los headers de dispositivo y principal se contrastan con la sesión.

No se promete cero bytes transmitidos después del commit de revocación: si la oferta ya confirmó y la respuesta quedó preparada, el proceso HTTP puede comenzar o continuar la escritura mientras otra transacción revoca la sesión. Sin mantener una transacción/barrera durante el socket —prohibido por el requisito— no existe forma de retirar bytes ya serializados ni de cerrar atómicamente el socket con la revocación. El contrato operativo es que revocación impide ofertas nuevas, bloquea solicitudes iniciadas después de su confirmación y purga la copia de Sync; la entrega previamente linealizada puede terminar en tránsito. Los dispositivos offline siguen dependiendo del vencimiento offline de 24 horas.

Las pruebas solicitadas de revocación antes/durante/después de `offer()` requieren conexiones PostgreSQL concurrentes. No se ejecutaron ni se simularon como si fueran equivalentes con mocks.

## 5. Saturación, métricas y recuperación

La ocupación se estima con `pg_column_size` de filas delivery sin ACK y filas inbox aún no materializadas. Para el evento entrante se añade una estimación conservadora basada en el tamaño UTF-8 de estado/tipo y una base fija para UUID, IDs, hash, timestamps y encabezado de fila. No incluye crecimiento de índices, TOAST ni overhead de almacenamiento; los bytes de política son una estimación y deben contrastarse con métricas de almacenamiento reales.

Al llegar al máximo, se registra `queue_saturated` y timestamp. El worker no consulta ERP hasta que la cola queda en o debajo de **ambos** límites de reanudación. Si una página descargada excedería el máximo, se descarta en memoria y no se persiste ni avanza el cursor. Una página ERP puede quedar detenida si supera el máximo configurado incluso con cola vacía; la configuración valida espacio mínimo para una página de hasta 100 eventos. La creación inicial del snapshot puede exceder temporalmente los límites porque el snapshot completo es necesario para iniciar la sesión; en ese caso se siguen permitiendo sus páginas/ACK, y la ingesta incremental queda pausada hasta drenar.

El outbox ERP no declara política de retención en el contrato. Una pausa larga puede hacer que un `startCursor` deje de estar disponible en ERP; este riesgo necesita un SLA/retención operativo confirmado con el equipo ERP. La capa Sync no inventa esa retención.

## 6. Integridad SQL y concurrencia

La migración añade constraints para dominios confirmados por el código/contrato: IDs ERP y usuario/agente positivos; generación positiva; `eventId` positivo; versión no negativa (la captura inicia en versión 0); secuencias y ordinales positivos; watermarks/checkpoints no negativos; sesiones `INICIALIZANDO`/`ACTIVA` con cursores de bootstrap; snapshot completo sólo después de snapshot fuente; sesión activa sólo después de ACK snapshot. Para `visible=false`, estado y agente deben ser nulos en inbox/proyección/delivery; agente nulo sigue permitido para documentos visibles sin asignación.

La propiedad de lock de sesión y transacción se conserva para inserción de ledger, inbox, proyección, tombstone, delivery y cursor ERP. La detección de ledger ocurre antes de nuevas inserciones; las páginas se prevalidan completas antes de escribir y la saturación no produce avance parcial. Los métodos de ACK, lease y revocación conservan su estructura transaccional previa.

La compatibilidad con PostgreSQL 17.5 se revisó por sintaxis (`ON CONFLICT`, CTE de modificación con `RETURNING`, `pg_column_size`, constraints, `make_interval` y `hashtextextended`), pero no se confirmó ejecutando el DDL. No se certifica concurrencia multiinstancia sin prueba real.

## 7. PostgreSQL aislado y pruebas transaccionales

No disponible. El datasource configurado sigue siendo `localhost:5432/prueba`, explícitamente excluido. Docker no puede conectar con Docker Desktop. Se detectó un servicio Windows `postgresql-x64-17` activo, pero no se demostró que su clúster/base esté vacío, aislado o sea desechable; no se conectó a él, no se ejecutó SQL, no se creó una base y no se inspeccionaron datos. No se modificó la configuración global ni se instaló infraestructura.

Por ese bloqueo no se ejecutaron: bootstrap vacío/multipágina, cursor incremental, deduplicación durable tras compactación, conflicto de versión, tombstone/resurrección, ACK real repetido/adelantado/no contiguo, rollback, lease vencido, dos workers, carreras de revocación, reset, retención/cursor expirado, saturación/reanudación SQL, aislamiento entre usuarios/dispositivos ni prueba del DDL. La regresión unitaria del replay valida la decisión de hash y no constituye evidencia de persistencia SQL.

## 8. Pruebas y regresiones ejecutadas

Comando focalizado:

```text
mvn -q -Dtest=CotizacionSyncRepositoryTest,CotizacionReadAssertionServiceTest,ErpCotizacionesReadClientTest,PedidoEstadosServiceTest,CotizacionIdentityAssertionServiceTest,CotizacionSyncServiceTest,LoginServiceSecurityTest,JwtTokenProviderTest,ErpPedidoClientTest test
```

**57 pruebas aprobadas, 0 fallidas, 0 errores.** Incluye estados de Pedidos; captura/aserción de creación; permiso JWT/login; firma RS256 de lectura y creación; clientes ERP; mapeo JDBC; replay por hash retenido; política local de umbrales y reanudación. Son pruebas unitarias con fixtures/mocks salvo cliente HTTP simulado; no prueban SQL real.

- `mvn -q clean test-compile`: **PASS**.
- `mvn -q -DskipTests package`: **PASS**.
- `git diff --check`: **PASS**, con avisos informativos de normalización LF/CRLF en `pom.xml` y `application.properties`.

En el primer lote Maven utilizó artefactos `target` antiguos y devolvió errores `getFirst()/getLast()` en pruebas de Pedidos; se limpió y recompiló el target con JDK 25.0.3, después el lote final completo pasó. No se modificaron fuentes de Pedidos.

## 9. Riesgos y bloqueos pendientes

- Prueba transaccional/concurrente en PostgreSQL 17.5 aislado, incluyendo instalación de base limpia y migración desde esquema 05B-2.
- Probar que una revocación concurrente serializa ofertas y documentar el resultado con dos conexiones; confirmar el límite residual de respuesta en tránsito.
- Confirmar operación de Micrometer/Actuator y protección de `/actuator/metrics` en el perfil de despliegue.
- Dimensionar límites por volumen, índices, almacenamiento y pico de consumo; el valor productivo permanece sin decidir y el default `0` deja lectura deshabilitada de forma segura.
- Acordar con ERP cuánto tiempo permanece consultable el feed durante una pausa por saturación.
- Completar prueba real de reanudación de sesiones antiguas tras `LEDGER_MIGRATION_REQUIRED` y revisar que las instalaciones sigan el orden base + v2.

No se hizo commit porque faltan pruebas fundamentales de persistencia y concurrencia, y el script no se validó en PostgreSQL real. No hubo despliegue, reinicio, push ni SQL aplicado.

## 10. Dictamen para Kotlin 05C

**05B-2C VALIDADO PARCIALMENTE.** Las correcciones de lógica, backpressure, métricas y migración están implementadas localmente y pasan el lote unitario/compilación/empaquetado. No conectar Kotlin ni declarar `LISTO PARA 05C` hasta demostrar transacciones, ACK, retención y concurrencia/revocación sobre una instancia PostgreSQL desechable.
