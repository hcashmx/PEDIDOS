# COT-MOBILE-05B-2T — Resultado

Fecha de validación: 2026-10-13.

## 1. Clúster aislado

Se utilizó PostgreSQL **17.5**, x86_64 Windows. Los ejecutables estaban en `$env:PG_BIN`. `initdb` creó un directorio exclusivo dentro del workspace:

`.\Backend\effyFlow\.cotizaciones-pg17-2t-e128d7aa75874682988f8de222600b69`

El servidor de pruebas escuchó únicamente en `127.0.0.1:55432`. La comprobación previa a DDL por conexión devolvió: base `postgres`, dirección `127.0.0.1`, puerto `55432`, usuario `cot_sync_test`, ruta física igual al directorio recién inicializado y versión `PostgreSQL 17.5`. La autenticación se inicializó con SCRAM y contraseña aleatoria independiente. Se crearon las bases `sync_cotizaciones_test` y `sync_cotizaciones_upgrade_test`.

No se usó `prueba`, el puerto 5432, las propiedades por defecto de Spring, credenciales operativas ni datos reales de usuarios. No se modificaron el servicio existente, Docker, PATH global, firewall ni configuración global. El clúster de prueba está detenido; se conserva su directorio de datos y se retiró el archivo temporal que contenía la credencial.

## 2. Instalación limpia y actualización v2

En `sync_cotizaciones_test` se aplicaron, en orden, `db/scripts/20261013_cotizaciones_sync_durable.sql` y `db/scripts/20261013_cotizaciones_sync_durable_v2.sql`, con `ON_ERROR_STOP=1`. Ambos terminaron correctamente. Las consultas de catálogo reportaron **8 tablas, 21 índices y 34 constraints** del esquema de sincronización; la base quedó sin fixtures al terminar las pruebas.

En la segunda base se aplicó el esquema base, se insertaron fixtures propios de una sesión activa y un evento, y luego se ejecutó v2. La migración copió `event_id=12` y su hash al ledger, y cambió la sesión a `REQUIERE_REINICIO` con `LEDGER_MIGRATION_REQUIRED`. La prueba JDBC independiente verificó que esa generación no puede ofrecer datos después de la migración. No se aplicó DDL a ninguna base operativa.

## 3. Pruebas PostgreSQL y concurrencia

Se añadió `CotizacionSyncPostgresIT`, opt-in mediante variables `COTIZACIONES_TEST_JDBC_URL`, `COTIZACIONES_TEST_DB_USER` y `COTIZACIONES_TEST_DB_PASSWORD`. Usa `DriverManagerDataSource`, `TransactionTemplate` y el repositorio JDBC real; la URL se fijó explícitamente a `127.0.0.1:55432`.

Las **9 pruebas de integración** aprobaron con PostgreSQL real:

- Bootstrap multipágina, snapshot vacío y ACK terminal.
- Cursor incremental exacto, replay después de compactar inbox y ledger retenido.
- Conflicto de versión con rollback de ledger/cursor.
- Tombstone y rechazo de resurrección con versión anterior.
- ACK repetido, adelantado y no contiguo.
- Dos workers concurrentes en conexiones distintas y recuperación de lease vencido.
- Revocación que toma el lock primero: una oferta concurrente espera el commit y luego falla por estado terminal.
- Retención de delivery/inbox ya confirmados, avance de floor y preservación del ledger.
- Saturación y reanudación por sesión, con progreso de otra sesión.
- Generación nueva tras reset y aislamiento de entregas entre sesiones.

La prueba de reset/aislamiento está incluida en el conteo de nueve. La prueba de migración, ejecutada por separado contra `sync_cotizaciones_upgrade_test`, aprobó **1/1**. El replay después de compactación usa una manipulación SQL confinada al fixture para volver a presentar el `eventId`; no altera el flujo normal de producción.

## 4. Defectos corregidos

Las pruebas descubrieron que el repositorio pasaba `Instant` directamente al driver PostgreSQL en dos parámetros. El driver no puede inferir ese tipo: fallaban la inserción de una entrega y el avance/renovación del cursor. Se corrigieron ambos parámetros mediante la conversión JDBC `Timestamp` existente en `CotizacionSyncRepository`:

- `appendDelivery()` convierte `changedAt` antes de insertar delivery.
- `ingestEventPage()` convierte `leaseUntil` antes de actualizar el checkpoint.

Las pruebas de snapshot e incremental reprodujeron los defectos; ambas rutas pasaron después de la corrección. No se cambiaron contratos ni SQL.

## 5. Regresiones, empaquetado y diff

El lote focal de regresión ejecutó **57 pruebas unitarias, 0 fallidas y 0 errores**, incluyendo estados de Pedidos, captura de Cotizaciones, JWT/login, aserciones RS256 de lectura y creación, cliente ERP y repositorio. Junto con las 9 pruebas PostgreSQL, el lote completo registrado terminó con **66 aprobadas, 0 fallidas y 0 errores**. La prueba de actualización v2 aprobó aparte.

`mvn -DskipTests package`: **BUILD SUCCESS**. `git diff --check`: **sin errores**; Git mostró únicamente avisos informativos de normalización LF/CRLF en `pom.xml` y `application.properties`.

El intento de repetir el agregado después de añadir la prueba específica de migración fue interrumpido al cerrarse VS Code. No se presenta esa ejecución como aprobada; sus dos partes sí quedaron verificadas por separado: 66/66 en el agregado anterior y 1/1 en la nueva prueba de migración.

## 6. Archivos de esta fase

- `src/main/java/com/cyssoftware/effyFlow/repository/CotizacionSyncRepository.java` — conversión JDBC de los dos `Instant`.
- `src/test/java/com/cyssoftware/effyFlow/repository/CotizacionSyncPostgresIT.java` — pruebas opt-in contra PostgreSQL real y verificación de generación migrada.
- `COT-MOBILE-05B-2T-RESULTADO.md` — este informe.

No se modificaron ERP, Kotlin, Pedidos ni sus cursores. No hubo despliegue, push ni commit.

## 7. Riesgos pendientes

- Las pruebas demuestran integridad transaccional del repositorio en PostgreSQL 17.5; no son una prueba de carga ni validan el dimensionamiento productivo de límites de cola.
- El clúster de prueba confirma el comportamiento SQL de instalación y actualización con una fixture representativa. No valida despliegue operacional, respaldo/restauración ni migración sobre una base existente.
- La prueba de revocación cubre la serialización en el lock de sesión y verifica que la oferta que consulta después del commit no entrega filas. Como ya se estableció en 05B-2C, no demuestra que una respuesta preparada antes de revocar no pueda transmitir bytes durante una transmisión HTTP en curso.
- La actualización v2 solo conserva hashes presentes en inbox; por diseño, sesiones anteriores quedan obligadas a reiniciar para cubrir eventos históricos que una retención anterior ya hubiera borrado.

## Dictamen

**`05B-2T VALIDADO — LISTO PARA 05C`**

La decisión se apoya en instalación y actualización reales en PostgreSQL 17.5, 9 pruebas transaccionales/concurrentes aprobadas y la suite focal de 57 regresiones aprobada. La limitación de transmisión HTTP concurrente con una revocación ya está documentada y no invalida la garantía de persistencia/ACK probada para este hito.
