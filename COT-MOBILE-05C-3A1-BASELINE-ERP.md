# COT-MOBILE-05C-3A1 — Baseline ERP sintético para UAT

Fecha: 2026-10-13
Alcance: auditoría estática del esquema ERP y verificación PostgreSQL UAT de sólo lectura. No se ejecutaron DDL, migraciones, fixtures ni arranque de ERP/Sync.

## Dictamen

**05C-3A1 BASELINE ERP PARCIAL.** El clúster PostgreSQL de UAT y su base objetivo se verificaron de forma autenticada y de sólo lectura. El repositorio contiene entidades JPA, migraciones parciales y esquemas por módulo, pero no una fuente controlada y completa para reconstruir desde cero el esquema esperado por el ERP entero, sus objetos sólo SQL y las precondiciones de los 93 scripts. Por ello no se creó baseline, no se modificaron las ocho tablas Sync y no se inició Hibernate. La compatibilidad `ddl-auto=validate` queda pendiente.

## 1. Inventario estructural

ERP se localizó en `checkout ERP independiente` (repo Git separado, HEAD `f9dd365c0998e92c0fde98df452d7df551062a70`, `feat(cotizaciones): implementar feed seguro de estados comerciales`). En el árbol inspeccionado hay **144 archivos con `@Entity`**, **141 con `@Table`**, **140 repositorios candidatos** que extienden interfaces JPA, **61 archivos** con SQL nativo/JDBC y **93 scripts SQL manuales** bajo `src/main/resources/db/migration`. Estos conteos son inventario textual, no un conteo de tablas validado por el metamodelo Hibernate.

El `pom.xml` declara Spring Boot 3.5.16, Spring Data JPA y PostgreSQL 42.7.13. El `application.properties` común apunta a `localhost:5432/prueba` y fija `spring.jpa.hibernate.ddl-auto=validate`; no debe usarse para UAT sin overrides explícitos. No se encontró Flyway ni Liquibase declarado en el `pom.xml`: las migraciones de este repositorio son manuales.

Se encontraron definiciones SQL documentales por módulo, entre ellas `docs/compras/tablas-compras.sql`, `docs/movimientos-internos/schema.sql`, `docs/orden_de_compra/tablas-orden_de_compra.sql`, `docs/recepcion_orden_compra/tablas-recepcion_orden_compra.sql`, `docs/ventas/schema.sql`, `specs/008-modulo_cfdi_tickets/schema.sql` y `specs/016-pedidos/pedidos-schema-actual.sql`. Son útiles como evidencia de módulos, pero no forman un baseline consolidado: hay `CREATE TABLE IF NOT EXISTS`, alteraciones, snapshots documentales y versiones históricas que no se pueden concatenar suponiendo idempotencia ni equivalencia con el mapeo actual.

La auditoría estática de los scripts de migración contabilizó 53 `CREATE TABLE`, 149 `ALTER TABLE`, 131 creaciones de índices, 9 funciones y 5 triggers. En un análisis simple de nombres quedaron cuatro objetos de `CREATE TABLE` sin entidad JPA detectada: `cotizaciones_app_aserciones_sync`, `importacion_producto_filas`, `importaciones_productos` y `kit_operaciones_comerciales`. Este análisis es aproximado; confirma que Hibernate por sí solo no es fuente completa. Las consultas nativas añaden otra superficie de esquema que `ddl-auto=validate` no necesariamente verifica.

No se encontraron en los archivos SQL/documentales consultados referencias a `dblink`, `postgres_fdw`, `FOREIGN DATA WRAPPER` o URLs JDBC. Esto no constituye una auditoría de integraciones externas en código Java.

## 2. Objetos para arranque y circuito UAT

### Arranque del ERP completo

El contrato de arranque es más amplio que Cotizaciones: Hibernate valida el conjunto de entidades mapeadas del ERP. La fuente exacta debe extraerse del metamodelo de la compilación actual y compararse con catálogos PostgreSQL; no se generó contra la base UAT porque no hay baseline aceptado. Además de las tablas mapeadas, hay funciones, triggers, tablas e índices creados sólo por SQL que no son visibles en todas las entidades.

El arranque estándar no es sólo lectura. `PlantillaImpresionBootstrap` implementa `ApplicationRunner` y crea plantillas/versiones si faltan; `NccTemplateBootstrapService` escucha `ApplicationReadyEvent` y crea plantillas NCC/versiones. No se encontró en sus clases una condición de configuración para desactivarlos. `PacConfigurationStartupLogger` es otro `ApplicationRunner` y registra configuración. No se encontró `@Scheduled` en `src/main/java` durante esta búsqueda. Hasta aislar/gatear esos dos escritores, no se considera probado el requisito de iniciar ERP con escrituras automáticas innecesarias deshabilitadas.

### Circuito de estados de Cotizaciones

Sync obtiene identidad y permisos consultando directamente, en el mismo datasource, `public.users`, `public.agentes`, `public.roles`, `public.model_has_roles`, `public.permissions`, `public.role_has_permissions` y `public.model_has_permissions`. Por tanto ERP y Sync necesitan compartir la misma base UAT; no basta crear dos bases desconectadas.

El servicio de feed ERP consulta `cotizaciones` y `cotizaciones_sync_eventos`; la migración de infraestructura también crea `cotizaciones_detalle`, `cotizaciones_historial`, y altera `pedidos`, `configuracion` y `tipos_movimiento`. El circuito comercial requiere además las tablas catálogo y claves foráneas reales que el flujo Cotizaciones utiliza, permisos efectivos y usuarios/agentes sintéticos. No se insertaron fixtures porque el esquema no está validado.

El esquema de Pedidos tiene evidencia en `specs/016-pedidos/pedidos-schema-actual.sql`, y el circuito Cotizaciones añade una FK/índice hacia esa estructura. Ese archivo no prueba que cada columna y constraint coincida con todas las entidades y cambios posteriores de pedidos.

## 3. Fuentes DDL y manifiesto de objetos

| Objeto/grupo | Fuente comprobada | Dependencias/precondiciones | Situación |
|---|---|---|---|
| Entidades JPA ERP | `src/main/java/**/entity` y anotaciones JPA | Relaciones completas, enums, tipos y schema por metamodelo actual | Requieren generación controlada y posterior `validate`; no bastan para objetos SQL externos |
| `users`, `agentes`, roles y permisos | Entidades/repositorios y consulta JDBC `CotizacionReadIdentityService` de Sync; SQL de permisos en migraciones ERP | Tablas y llaves de identidad compatibles, agente asociado, relaciones polimórficas/roles exactas; mecanismo y hash de auth ERP | No hay baseline completo verificado para partir vacío; fixtures pendientes |
| Pedidos y detalle | `specs/016-pedidos/pedidos-schema-actual.sql`, entidades JPA y migraciones | Cliente, agente, usuario, serie, estado, venta/producto/almacén; las relaciones exactas varían por operación | Fuente útil parcial; debe reconciliarse con metamodelo/migraciones actuales |
| Catálogos usados por cotizaciones | entidades y DDL por módulo, p. ej. `docs/compras/tablas-compras.sql` y `docs/orden_de_compra/tablas-orden_de_compra.sql` | `productos`, `presentaciones_producto`, clientes, series/folios, configuración y claves FK | Múltiples archivos de módulo/versiones; reconciliación pendiente |
| Cotizaciones base y feed | `V20261006_01__cotizaciones_infraestructura.sql`; `V20261012_01__cotizaciones_feed_estados.sql` | Esquema anterior de `users`, `agentes`, `clientes`, `series_folios`, `productos`, `presentaciones_producto`, `pedidos`, `configuracion`, `tipos_movimiento` | DDL explícito y valioso, pero diseñado como migración sobre ERP preexistente; no aplicar aún |
| Permisos Cotizaciones | `V20261006_02__cotizaciones_permisos.sql` | `permissions`, `roles`, `role_has_permissions`; el script otorga al rol existente `super_admin` | No sustituye fixtures de usuarios/agentes; ejecutar sólo tras definir roles sintéticos controlados |
| Precisión Pedidos/Cotizaciones | `V20261006_03__pedido_precision_cotizaciones.sql` | Columnas preexistentes concretas en `pedidos` y `detalle_pedidos` | No aplicar sobre DDL Hibernate sin comparar tipos; riesgo de doble alteración |
| Snapshot kit | `V20261007_04__cotizaciones_snapshot_kit.sql` | `cotizaciones_detalle` preexistente y nombre/condición de constraint libre | Alteración post base |
| Revisiones comerciales | `V20261010_01__cotizaciones_revision_comercial.sql` | `users`, `cotizaciones`; crea función y trigger de inmutabilidad | DDL explícito posterior a la tabla de Cotizaciones |
| Aserciones Sync ERP | `V20261010_02__cotizaciones_app_aserciones_sync.sql` | `users`, `agentes`, `cotizaciones`, `cotizaciones_revisiones_comerciales` | SQL-only; el mapeo JPA no basta |
| Feed 05A1B | `V20261012_01__cotizaciones_feed_estados.sql` y código 05A1B | `cotizaciones`, `cotizaciones_sync_eventos` creados antes; columnas anteriores y constraints disponibles | Migración específica 05A1B; no aplicada |
| Esquema Sync 05B | Scripts Sync base y v2 ya aplicados en la base UAT | La misma base ERP/Sync; fixtures ERP aún faltan | Ocho tablas comprobadas y preservadas, vacías según informe 05C-3A |

El manifiesto anterior es de fuentes y dependencias observadas. No presenta como definiciones validadas estructuras que sólo fueron inferidas desde entidades o queries.

## 4. Separación baseline/migraciones

Plan recomendado para una fase de construcción posterior:

1. Tratar como baseline sólo el DDL de instalación inicial que se pueda derivar reproduciblemente del metamodelo actual más DDL inicial controlado, con versiones/fuentes fijadas. No incluir fixtures.
2. Para cada una de las 93 migraciones, registrar precondiciones, objetos/tipos esperados, si se incluye ya en el baseline y si sus efectos son requeridos por el módulo habilitado. No encadenarlas por orden lexicográfico sin análisis; el conjunto contiene ramas y scripts que pueden asumir datos u objetos anteriores.
3. Crear una base ERP de construcción nueva, vacía y exclusiva dentro del clúster UAT. Mantener intacta la actual `cotizaciones_uat_05c3a` y sus ocho tablas Sync hasta que el baseline pase todas las verificaciones. No renombrar ni borrar la actual como atajo.
4. En esa base nueva, aplicar baseline y migraciones seleccionadas una vez, en orden de dependencias documentado; luego comparar catálogos, ejecutar Hibernate `validate` y revisar objetos SQL requeridos.
5. Sólo cuando todo pase, preparar explícitamente la base compartida UAT final, preservando el esquema Sync mediante creación en una base nueva o aplicación controlada validada. El procedimiento debe declarar cómo se reinstalan los scripts Sync base/v2, sin operar sobre bases externas.

No se generó un script baseline especulativo ni se ejecutó una migración. `ddl-auto=create` no se considera suficiente: omite o degrada funciones, triggers, constraints SQL/manuales, tablas nativas y datos semilla requeridos, y crea un esquema sin demostrar la compatibilidad de las migraciones.

### Dependencias relevantes de 05A1B

- `V20261006_01` crea infraestructura Cotizaciones, pero agrega columnas/FK a `pedidos` y `configuracion`, inserta `COT` en `tipos_movimiento` y depende de catálogos y usuarios/agentes preexistentes.
- `V20261006_02` requiere tablas de permisos/roles y `super_admin` para la asignación prevista.
- `V20261006_03` altera precisión de columnas existentes en Pedidos.
- `V20261007_04` modifica detalle Cotizaciones creado antes.
- `V20261010_01` depende de Cotizaciones/users y añade una columna, FK, función y trigger.
- `V20261010_02` depende de revisiones, cotizaciones, usuarios y agentes.
- `V20261012_01` añade campos/constraints/índices al ledger de eventos y trigger para exigir snapshot.

El baseline no debe incluir estas transformaciones y a la vez volver a aplicarlas. Se necesita un registro de migración UAT propio que anote cada script aplicado y su checksum. El repositorio no ofrece un runner/versionador automático confirmado en la inspección.

## 5. Destino y seguridad verificados

Verificación de sólo lectura completada el 2026-10-13:

- Conexión autenticada a `127.0.0.1:55433`, base `cotizaciones_uat_05c3a`.
- La consulta como administrador UAT devolvió versión PostgreSQL `17.5` y `data_directory = C:/Users/hcash/AppData/Local/CotizacionesUAT/05C3A/postgres/data`.
- `pg_ctl status` identificó PID 38660 y el mismo directorio; `postgresql.conf`/opciones del proceso especifican host `127.0.0.1`, puerto `55433` y `listen_addresses=127.0.0.1`.
- El listener existente en 5432 corresponde a otro proceso (PID 7140); no se conectó a 5432.
- El catálogo de la base objetivo mostró sólo estas ocho tablas públicas: `sync_cotizacion_delivery`, `sync_cotizacion_event_inbox`, `sync_cotizacion_event_ledger`, `sync_cotizacion_generation`, `sync_cotizacion_projection`, `sync_cotizacion_sesion`, `sync_cotizacion_snapshot`, `sync_cotizacion_tombstone`.
- No se ejecutó DDL ni escrituras; las ocho tablas permanecen intactas.
- Los scripts ERP inspeccionados no contenían primitivas de conexión externa (`dblink`, FDW o URL JDBC). La revisión fue estática y acotada a SQL/documentación; no sustituye un escaneo automatizado de cada script de cualquier repositorio.

La primera consulta como rol de aplicación autenticó contra el destino, pero no pudo leer `data_directory` por privilegios PostgreSQL. No se concedió privilegio adicional. La verificación definitiva del parámetro se realizó después, con credencial administrativa exclusiva UAT, sin imprimirla.

## 6. Scripts ejecutados y validación

- Scripts ERP ejecutados: **ninguno**.
- DDL baseline generado/aplicado: **ninguno**.
- Scripts Sync base/v2: ya estaban aplicados antes de esta tarea, según `COT-MOBILE-05C-3A-ENTORNO-UAT.md`; no se repitieron.
- Fixtures: **ninguno**.
- Comprobaciones posteriores de PK/FK, secuencias, índices, funciones y triggers ERP: no aplican porque el esquema ERP no fue creado.
- Hibernate `ddl-auto=validate`: **no ejecutado**; no existe esquema completo contra el cual validarlo.
- Servicios iniciados: ninguno.

## 7. Fixtures sintéticos pendientes

Después de validar esquema, preparar fixtures exclusivamente UAT, de forma transaccional y repetible:

- Cuenta técnica ERP con mecanismo/hash válido para la autenticación real configurada en Sync.
- Vendedor y segundo usuario comercial, cada uno asociado al agente correcto.
- Roles mínimos y permisos efectivos `cotizaciones.ver`; añadir `cotizaciones.crear` sólo donde la fase comercial lo necesite. No dar `super_admin` a usuarios de prueba.
- Catálogos mínimos requeridos por constraints/servicios: clientes, productos, presentaciones, serie/folio y cualquier relación obligatoria verificada en entidades y servicios.

La migración `V20261006_02` asigna el conjunto aprobado de permisos Cotizaciones a `super_admin`; eso no es motivo para convertir usuarios de prueba en administradores. Antes de fixture se debe verificar la autenticación ERP y resolver si la cuenta técnica debe ser un registro `users` sintético compatible con el mecanismo de password/login actual o una credencial técnica separada documentada.

## 8. Bloqueos estructurales y correcciones necesarias

1. No existe baseline completo único. Las entidades permiten describir gran parte del DDL mapeado, pero faltan/son incompletos objetos nativos, funciones/triggers y cambios históricos que afectan constraints y lógica de base.
2. Las migraciones manuales tienen alteraciones sobre tablas preexistentes, datos semilla y dependencias. No se puede certificar que una secuencia sea válida sólo por ordenar los 93 nombres.
3. Hay documentación DDL por módulo con diferencias temporales; debe reconciliarse contra las entidades y migraciones actuales, no concatenarse.
4. Las dos inicializaciones automáticas de plantillas escriben durante el arranque estándar. No se encontró switch de configuración para apagarlas. Antes de iniciar ERP se requiere perfil/cambio aislado que las desactive, o una justificación documentada y aceptación expresa de esas escrituras sintéticas luego de validar el esquema. Esta fase no modificó código.
5. Un baseline JPA generado con `create` no validaría la parte SQL manual, y arrancar la app con `create` activaría código de aplicación/bootstrap; no se empleó esa vía.
6. El uso compartido de DB ERP/Sync está demostrado por el código JDBC de Sync; separar identidades en otra base rompería el contrato operativo.

Corrección requerida para desbloquear: preparar un artefacto versionado de baseline UAT desde fuentes repositorio actuales y un manifiesto de scripts migratorios incluidos/excluidos/checksums; resolver las inicializaciones de plantilla sin cambiar lógica comercial; validar el resultado en una base vacía distinta y exclusiva del mismo clúster; promoverlo a una DB nueva compartida con reinstalación explícita de Sync base/v2; sólo entonces insertar fixtures e iniciar ERP con perfiles UAT.

## 9. Condiciones para iniciar ERP UAT

- Baseline completo, revisado y ejecutado únicamente en base nueva/vacía del clúster UAT.
- Migraciones aplicadas una sola vez, con precondiciones verificadas; la migración 05A1B incluida.
- Catálogo comprobado para columnas/tipos/PK/FK/índices/secuencias, y funciones/triggers requeridos.
- Ocho tablas Sync preservadas o recreadas en la nueva base exclusivamente con scripts base/v2 verificados.
- Fixtures de identidad sintéticos y autenticación/hash confirmados; permisos mínimos efectivos.
- Configuración por perfil/variables UAT apuntando sólo a `127.0.0.1:55433/cotizaciones_uat_05c3a`; sin `prueba`, 5432, VPS ni secretos operativos.
- Identificar/deshabilitar explícitamente los bootstrap escritores no necesarios, además de verificar ausencia de integraciones/envíos automáticos relevantes.
- Ejecutar `ddl-auto=validate` contra la DB final y registrar identidad, configuración efectiva y resultados antes de iniciar Sync.

## 10. Procedimiento reproducible desde base vacía

Este procedimiento es **plan**, no comandos aplicados:

1. Verificar `pg_ctl status` en `%LOCALAPPDATA%\CotizacionesUAT\05C3A\postgres\data`, listener `127.0.0.1:55433` y ejecutar consulta de identidad (`current_database`, host, puerto y `data_directory`) como administrador UAT. Rechazar cualquier diferencia.
2. Crear una base nueva con nombre UAT exclusivo dentro de ese clúster; no eliminar ni renombrar `cotizaciones_uat_05c3a`.
3. Aplicar baseline ERP revisado con manifiesto/checksums y verificar todo el catálogo antes de continuar.
4. Aplicar migraciones seleccionadas en orden de dependencia y con registro de ejecución; no correr los 93 scripts a ciegas.
5. Aplicar, en esa base, scripts Sync base y v2 desde el repositorio y verificar ocho objetos esperados.
6. Crear fixtures mínimos mediante SQL reproducible que respete autenticación y relaciones; comprobar lectura de usuario/agente/roles/permisos desde el datasource compartido.
7. Arrancar ERP con variables de UAT y acciones automáticas de arranque controladas; ejecutar Hibernate validate y health checks no destructivos. Si una condición falla, detener el proceso y conservar la DB UAT para diagnóstico.

## 11. Cambios y pendientes

Esta tarea sólo produjo este informe. No se modificó ERP, Sync, Kotlin, migraciones productivas ni datos PostgreSQL. No hubo commit, push ni despliegue.

Pendiente para 05C-3A1: baseline sintético de ERP, ejecución/validación Hibernate, fixtures sintéticos y validación de configuración de arranque segura. Hasta completarlos, el entorno sigue parcial y no debe iniciarse Sync ni Kotlin para UAT integrada.
