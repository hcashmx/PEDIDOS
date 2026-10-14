# COT-MOBILE-05B-2V — Validación PostgreSQL, concurrencia y recuperación

**Fecha:** 2026-10-13
**Alcance:** auditoría estática y pruebas unitarias aisladas de la implementación 05B-2. No se ejecutó SQL ni se conectó a una base de datos.

## Dictamen

**05B-2 VALIDADO PARCIALMENTE.** No está listo para 05C. La inspección del ACK encuentra una implementación monotónica razonable, pero faltan pruebas reales sobre PostgreSQL; además, la ventana entre confirmar `offer()` y enviar la respuesta permite una carrera con revocación, y la retención borra el ledger `eventId`/hash antes del cierre de sesión, en contra del contrato 05B-1. La cola de entrega no tiene límite operativo configurable y no se pudo medir su crecimiento.

## 1. Hallazgos confirmados y descartados

### Confirmados

1. **Mapeo JDBC incompatible con el tipo SQL.** `agente_id` se declara `BIGINT`; los drivers PostgreSQL devuelven normalmente `Long`, mientras el código convertía directamente el objeto a `Integer`. Una fila con agente podía causar `ClassCastException` durante lectura de snapshot/proyección/evento. Se cambió el mapeo por conversión comprobada de `Number`, incluyendo control de rango y `NULL`, y se agregó una regresión unitaria.
2. **Ledger de deduplicación con retención más corta que la aprobada.** `pruneAcknowledgedData()` elimina eventos inbox entregados al cumplir el periodo configurable de 30 días, aunque la sesión siga activa. El contrato 05B-1 requiere conservar `eventId` y hash hasta el cierre de sesión. El cursor ERP evita normalmente reprocesar páginas antiguas, pero no sustituye la deduplicación exigida ni protege ante recuperación administrativa/reinicialización accidental dentro de la generación.
3. **Revocación no tiene una barrera hasta el envío HTTP.** La entrega bloquea la fila de sesión mientras lee/ofrece la página y la revocación también bloquea esa fila; esto serializa las transacciones. Sin embargo, al terminar `offer()` la transacción confirma antes de que el controlador serialice y envíe la respuesta. Otra instancia puede confirmar la revocación en esa ventana y la respuesta ya materializada todavía podría salir después. No existe lease/in-flight acknowledgement que cubra el envío, por lo que no se demuestra la garantía fuerte de que una revocación confirmada impida toda exposición posterior.
4. **Capacidad sin límite por sesión.** La entrega pendiente no se elimina (correcto para evitar pérdida), pero no hay umbral que suspenda el consumo de una sesión saturada ni error/métrica específico. No se puede proponer un número defendible sin volúmenes reales, tasas de eventos, tamaño de fila y almacenamiento disponible.
5. **Controles de dominio SQL incompletos.** El DDL valida estados, consistencia básica de cursores y secuencias, unicidad y relaciones por sesión, pero no impone límites positivos para `generation`, `event_id`, versiones, IDs ERP, ordinales/contadores y algunos watermarks. No se interpreta como defecto de contrato porque el dominio exacto de esos valores debe cotejarse con COT-MOBILE-05A1B; sí queda como validación de esquema pendiente antes de aprobar la migración.
6. **Métrica de limpieza imprecisa.** La consulta CTE que borra entregas y actualiza floors retorna el número de filas de sesión actualizadas (`UPDATE` final), no el número de entregas eliminadas. El worker registra el retorno como filas eliminadas, por lo que la métrica puede subcontar cuando varias filas borradas corresponden a una sesión.

### Descartados por inspección estática (no por prueba PostgreSQL)

- Aislamiento: las PK/FK, consultas, deduplicación, proyección, tombstones y delivery incluyen `session_id`; la revocación/purga filtra por sesión.
- Idempotencia de sesión: la adquisición usa advisory transaction lock sobre usuario/agente/dispositivo, y la tabla de generación conserva high-water separado al eliminar sesiones cerradas.
- Atomicidad diseñada: los métodos de persistencia de página, materialización, ACK y purga son transaccionales. Las páginas ERP validan cursor esperado y lease del worker antes de actualizar inbox/proyección/tombstone/delivery/checkpoint.
- Restricciones PostgreSQL 17.5: el SQL visible usa UUID, `ON CONFLICT`, `RETURNING`, CTE de modificación, `make_interval`, índices parciales/compuestos y `hashtextextended`; estas construcciones son compatibles con PostgreSQL 17.5 por inspección. La compatibilidad de ejecución y el DDL desde una base limpia no se probaron.

## 2. Integridad del SQL

El script crea tablas de generación, sesión, snapshot, inbox, proyección, tombstone y delivery. Hay claves primarias y únicas por sesión, FK con `ON DELETE CASCADE`, índices para workers, actividad, lectura de inbox, delivery y retención. La unicidad `(session_id,event_id)` evita duplicados mientras el registro inbox existe; `(session_id,cotizacion_uuid)` evita duplicar un documento de snapshot; `(session_id,delivery_sequence)` asegura secuencia única.

No se ejecutó el DDL. `CREATE TABLE IF NOT EXISTS` hace repetible una instalación nueva parcialmente creada, pero no actualiza una tabla preexistente con columnas/restricciones antiguas. El repositorio no aporta evidencia de un versionador de esquema que haga upgrades incrementales; aplicar este script sobre una instalación existente requiere una revisión de versión y migración controlada.

No se verificó el dominio de IDs y versiones contra el contrato ERP, por lo que no se añadieron constraints especulativas. Antes de migrar deben cotejarse dominios, nulos permitidos para `visible=false`, límites de `agente_id` y las invariantes entre estado de sesión, checkpoint ERP, snapshot y secuencias.

## 3. Validación transaccional real

No realizada: no fue posible demostrar una instancia aislada desechable. El datasource resuelve por defecto a `localhost:5432/prueba`, con usuario `postgres`; existe una contraseña configurada en el entorno, omitida aquí. No se pudo probar que el servidor local fuera aislado y desechable; por tanto, se trató como base potencialmente operativa y no se ejecutó ninguna consulta, migración o prueba de escritura. Docker no está disponible (no conecta con el pipe/daemon de Docker Desktop). No se instaló ni configuró infraestructura.

Consecuentemente quedan sin validar: rollback SQL real, PK/FK y checks en servidor, advisory lock, leases entre instancias, transacciones concurrentes, retención, expiración de cursor y comportamiento del driver PostgreSQL.

## 4. ACK, revocación y cursores

### ACK (auditoría del código)

- La entrega bloquea la fila de sesión, exige `afterSequence == deliveryAckedSequence`, obtiene la página ordenada y guarda `last_offered_sequence` dentro de la transacción.
- ACK repetido con la secuencia ya confirmada retorna el mismo valor; ACK menor o mayor que el waterline ofrecido se rechaza.
- Se cuenta el tramo entre el ACK actual y el solicitado y se exige que sea contiguo; la actualización del checkpoint ocurre bajo el mismo lock. Esto evita saltos y retrocesos bajo serialización PostgreSQL si la transacción/lock se comporta como se espera.
- Para snapshot vacío, la materialización añade `SNAPSHOT_END`, y el ACK terminal debe activar la sesión. ACK perdido se recupera repitiendo desde el checkpoint; la página durable conserva secuencias hasta ACK.
- La retención borra solo delivery ya confirmado. Cuando la secuencia pedida cae por debajo del floor, `offer()` responde 410; un cursor no igual al ACK vigente, pero dentro del floor, responde 409. La reanudación real tras limpieza no se probó.
- `acknowledge()` valida la identidad vigente a través del servicio antes de llamar al repositorio; el repositorio por sí solo no verifica estado terminal. La concurrencia de revocación y ACK no fue probada.

### Revocación

La lectura revalida usuario activo, rol, permiso efectivo `cotizaciones.ver` y agente único; también contrasta usuario/agente/dispositivo almacenados con el principal. Se detecta cambio de agente y la respuesta 403 del ERP revoca y purga por sesión. Errores transitorios se traducen a 502/503 y no borran el lease offline. Estos puntos son correctos por inspección.

La carrera entre commit de `offer()` y transmisión HTTP descrita arriba sigue abierta. Tampoco se probó que una revocación ganadora en la base impida todas las rutas de lectura frente a varias instancias. La garantía de revocación inmediata debe definirse en términos de linealización de la entrega y probarse; si exige cero bytes enviados después del commit, la transacción actual no basta.

## 5. Concurrencia multiinstancia

El diseño usa PostgreSQL como coordinador: advisory lock para adquirir sesión, `FOR UPDATE` para mutar el estado de sesión/ACK/entrega, y lease de worker con owner y vencimiento. Las páginas remotas se descargan fuera de la transacción; al persistir se vuelven a comprobar propietario y cursor esperado. Esto evita mantener transacciones abiertas durante HTTP y está pensado para varias instancias.

No se levantaron dos workers ni se ejecutaron pruebas con dos conexiones. No se puede certificar aislamiento, exclusión del lease, fencing de worker vencido ni orden bajo `READ COMMITTED` sin PostgreSQL real. Se requiere prueba concurrente repetible antes de 05C.

## 6. Capacidad y retención

La retención es configurable por días (30 por defecto), borra en lotes de hasta 1.000 y conserva delivery sin ACK. Sesiones inactivas 180 días se expiran y purgan; las filas terminales se eliminan tras 90 días y el high-water de generación se mantiene. El comportamiento de retención no se ejecutó.

No se implementó límite de cola: sin medir almacenamiento y carga no se puede justificar un umbral, y no se debe borrar una entrega pendiente. Recomendación para una fase posterior: dimensionar por tamaño observado y tasa de crecimiento, configurar límite por sesión en bytes y número de eventos, detener únicamente la lectura ERP de la sesión que lo exceda, registrar métrica/código tipado recuperable y reanudar al bajar de un umbral de recuperación. El cursor ERP debe permanecer en el último evento persistido y ninguna otra sesión/Pedidos debe verse afectada. También debe conservarse un ledger compacto de `eventId`/hash hasta el fin de la sesión aunque se compacte el payload.

## 7. Pruebas ejecutadas y omitidas

Ejecutadas (unitarias, sin contexto Spring de base de datos; fixtures y mocks):

- `CotizacionSyncRepositoryTest`, `CotizacionReadAssertionServiceTest`, `ErpCotizacionesReadClientTest`, `PedidoEstadosServiceTest`, `CotizacionIdentityAssertionServiceTest`, `CotizacionSyncServiceTest`: **22 casos aprobados**.
- `LoginServiceSecurityTest` (7), `JwtTokenProviderTest` (5), `ErpPedidoClientTest` (21): **33 casos aprobados** de login/JWT/cliente ERP; sin acceso a DB real.
- Total: **55 pruebas unitarias aprobadas, 0 fallidas** en las nueve clases indicadas.
- La regresión nueva reproduce `Long` para `agente_id`, verifica conversión a `Integer`, preserva `NULL` y rechaza desbordamiento.

Omitidas por no disponer de PostgreSQL aislado: DDL, creación/recuperación real de sesión, bootstrap y snapshot vacío/multipágina, incremental y cursor exacto, duplicados/conflicto de versiones, tombstone y revocación, ACK repetido/adelantado/no contiguo, rollback, dos workers, expiración/recuperación de lease, limpieza inbox/delivery, desconexión prolongada, reset de generación y aislamiento usuario/dispositivo. También se omitieron pruebas `@SpringBootTest`, integración local ERP y cualquier prueba que pudiera conectarse a `prueba`.

La regresión de Pedidos `PedidoEstadosServiceTest` fue aprobada. No se ejecutó captura de Cotizaciones contra ERP ni contra una base real. Las pruebas unitarias de creación/aserción y cliente ERP existentes sí pasaron; no cubren la nueva ruta de lectura completa con servidor y persistencia.

## 8. Archivos modificados

- `src/main/java/com/cyssoftware/effyFlow/repository/CotizacionSyncRepository.java`: mapeo seguro de `BIGINT` nullable a `Integer` con comprobación de rango.
- `src/test/java/com/cyssoftware/effyFlow/repository/CotizacionSyncRepositoryTest.java`: regresión de mapeo.
- `COT-MOBILE-05B-2V-VALIDACION.md`: este informe.

No se modificó el SQL, configuración, ERP, Kotlin ni código de Pedidos. No hubo migración, despliegue, reinicio, commit o push.

## 9. Configuración de prueba sin secretos

Configuración resoluble observada: `jdbc:postgresql://localhost:5432/prueba`, usuario `postgres`; contraseña presente en entorno pero redactada. No se imprimió ni copió ningún secreto. No hay evidencia suficiente de que `prueba` sea aislada y desechable; no se envió SQL a ese destino. Docker no estaba disponible.

## 10. Bloqueos y riesgos para Kotlin 05C

- Prueba en PostgreSQL 17.5 aislado y desechable, incluyendo fallas/rollback y concurrencia real.
- Resolver la barrera de revocación/entrega para la ventana después del commit y antes de completar la respuesta HTTP.
- Preservar deduplicación `eventId`/hash hasta cierre de sesión conforme al contrato 05B-1.
- Dimensionar y definir el control de saturación por sesión sin descartar eventos pendientes.
- Definir dominio de valores y completar checks/índices de acuerdo con el contrato real; corregir el conteo de filas de retención para que la métrica sea verdadera.
- Validar reset de generación, aislamiento de identidades y recuperación tras desconexión con cliente real/integración, antes de conectar Kotlin.

No hay evidencia de regresión funcional de Pedidos; la prueba unitaria del servicio de estados pasó y no se modificó ese código. El riesgo principal está en semánticas aún no verificadas con PostgreSQL y en la garantía de revocación.
