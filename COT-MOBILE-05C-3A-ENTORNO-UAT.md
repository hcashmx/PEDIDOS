# COT-MOBILE-05C-3A — Preparación de entorno UAT aislado

Fecha: 2026-10-13
Alcance: inventario de repositorios y preparación de PostgreSQL local desechable. No se ejecutaron escenarios comerciales ni se iniciaron ERP o Sync.

## 1. Dictamen

**05C-3A ENTORNO UAT PARCIAL.** PostgreSQL 17.5 está activo en un clúster nuevo, local y aislado, con una base exclusiva y el esquema Sync 05B base/v2 aplicado. Se verificó una conexión autenticada al host/puerto/base esperados. Se generó y comprobó un par RS256 exclusivo de UAT.

ERP y Sync no se iniciaron. El ERP exige un esquema preexistente (`ddl-auto=validate`) y sus scripts son migraciones manuales; el repositorio no contiene un baseline sintético completo para una instalación vacía. Sync resuelve usuarios, agentes y permisos con consultas SQL directas sobre las tablas ERP dentro de su propio datasource. Por ello ambos deben compartir una base UAT, pero no se puede iniciar ninguno con seguridad hasta preparar el esquema ERP completo sin copiar `prueba` ni datos operativos. Kotlin conserva una vía de aislamiento mediante `LOCALAPPDATA` por proceso, pero no se inició la aplicación ni se creó aún su SQLite UAT.

No se usaron bases, servicios Windows, credenciales, VPS ni archivos SQLite operativos. No se modificó código ni configuración de los proyectos, y no se hizo commit, push o despliegue.

## 2. Proyectos, versiones y estado Git

| Proyecto | Ruta | Versión/build del código fuente | HEAD | Cambios de trabajo relevantes |
|---|---|---|---|---|
| ERP | `checkout ERP independiente` | Maven `Administrativo:0.0.1-SNAPSHOT`, Spring Boot 3.5.16, Java 25. La fuente incluye `CotizacionFeedController`, `CotizacionFeedService` y `V20261012_01__cotizaciones_feed_estados.sql`. Está el resultado `docs/cotizaciones/COT-MOBILE-05A1B-RESULTADO.md`. | `f9dd365c0998e92c0fde98df452d7df551062a70` (`feat(cotizaciones): implementar feed seguro de estados comerciales`). | La fuente 05A1B está en HEAD; los informes de Cotizaciones listados por `git status` son no rastreados/preexistentes. No se modificaron. |
| Sync | `.\Backend\effyFlow` | Maven `effyFlow:0.0.1-SNAPSHOT`, Spring Boot 3.5.16, Java 25. Los cambios 05B-2T están presentes en el árbol, pero parte del código y los scripts todavía son no rastreados/modificados. | `c4687d17ab34e2bc6335c62df90717c5a0a96965` (`fix(kotlin): corregir presentaciones comerciales en cotizaciones`). | `pom.xml` y `application.properties` modificados; controladores, workers, repositorios, servicios, scripts SQL base/v2 e informes 05B aparecen como cambios preexistentes. Se conservaron. |
| Kotlin | `.\App Pedidos\KotlinProject` | KMP/Compose, SQLDelight 2.0.2 en JVM; el informe 05C-2 registra JDK 21 para compilación JVM. | Comparte el repo raíz de Sync: `c4687d17ab34e2bc6335c62df90717c5a0a96965`. | El resultado 05C-2, consumidor de estados y pruebas están como cambios preexistentes modificados/no rastreados. Se conservaron. |

Los commits identifican el HEAD de cada repositorio, no una versión desplegada. No se identificó ni se declaró una instalación activa de los builds 05A1B/05B/05C-2. ERP está en un repositorio separado; Sync y Kotlin comparten el repositorio Git `.`.

## 3. Arquitectura de bases UAT

La consulta de `CotizacionReadIdentityService` de Sync accede a `public.users`, `public.agentes`, `public.roles`, `public.model_has_roles`, `public.permissions`, `public.role_has_permissions` y `public.model_has_permissions` usando el datasource principal. Esas identidades y autorizaciones pertenecen al ERP. Por ello la distribución elegida es **una base PostgreSQL compartida por ERP y Sync**, más un archivo SQLite independiente de Kotlin.

Se preparó:

- Directorio nuevo: `%LOCALAPPDATA%\CotizacionesUAT\05C3A\postgres\data`.
- PostgreSQL 17.5 del binario `$env:PG_BIN`.
- Listener limitado a `127.0.0.1:55433`.
- Base: `cotizaciones_uat_05c3a`; bases visibles: `cotizaciones_uat_05c3a`, `postgres`, `template0` y `template1`. No existe una base `prueba` en este clúster.
- Rol PostgreSQL de aplicación `cotizaciones_uat_app`, con DML sobre las tablas actuales y privilegios por defecto para tablas/secuencias nuevas creadas por el administrador dentro de la base UAT. No tiene permisos concedidos sobre los objetos de la base de mantenimiento `postgres`.
- Los archivos de contraseñas UAT se guardaron fuera del repo, bajo `%LOCALAPPDATA%\CotizacionesUAT\05C3A`, con ACL protegida para el usuario actual. No se incluyeron sus valores en este informe.

El nuevo clúster no es el servicio Windows de PostgreSQL y no usa 5432. La consulta final autenticada devolvió `cotizaciones_uat_05c3a | cotizaciones_uat_app | 127.0.0.1 | 55433 | PostgreSQL 17.5`.

## 4. Clúster 05B-2T anterior

Se inspeccionó sin conectarse ni iniciarlo el directorio `Backend\effyFlow\.cotizaciones-pg17-2t-e128d7aa75874682988f8de222600b69`. `PG_VERSION` indica 17; `postmaster.opts` apunta a esa ruta y a `127.0.0.1:55432`. El informe 05B-2T documenta que se creó exclusivamente para las bases de prueba `sync_cotizaciones_test` y `sync_cotizaciones_upgrade_test`.

No se reutilizó porque el puerto 55432 está libre, pero conserva un `postmaster.pid` cuyo PID no corresponde a un proceso actual y `pg_controldata` indica estado `in production` (apagado no limpio; requiere recuperación). Para evitar recuperación o cambios de ese artefacto previo se conservó intacto y se inicializó un clúster nuevo. El servicio existente en 5432 permanece escuchando en `0.0.0.0` y `::`; no se conectó ni se modificó.

## 5. Esquemas y migraciones

En la base recién creada se aplicaron únicamente estos scripts Sync:

1. `Backend/effyFlow/db/scripts/20261013_cotizaciones_sync_durable.sql`
2. `Backend/effyFlow/db/scripts/20261013_cotizaciones_sync_durable_v2.sql`

Se usó `psql` con `ON_ERROR_STOP=1`. La consulta de catálogo confirmó ocho tablas públicas: `sync_cotizacion_delivery`, `sync_cotizacion_event_inbox`, `sync_cotizacion_event_ledger`, `sync_cotizacion_generation`, `sync_cotizacion_projection`, `sync_cotizacion_sesion`, `sync_cotizacion_snapshot` y `sync_cotizacion_tombstone`.

No se aplicó la migración ERP `V20261012_01__cotizaciones_feed_estados.sql` ni se creó esquema ERP. El ERP configura `spring.jpa.hibernate.ddl-auto=validate`; las 93 migraciones SQL del repositorio son de aplicación manual y no constituyen por sí solas un bootstrap de todas las tablas de la aplicación. No se encontró un baseline sintético completo. Los SQL encontrados son migraciones de módulos y esquemas específicos de pruebas, no una instalación ERP integral. No se consultó ni se copió la base `prueba`, `administrativo_test`, dumps ni datos reales.

## 6. Fixtures e identidades

No se insertaron fixtures comerciales ni de identidad. La base contiene sólo las tablas de sincronización vacías. No están creados el usuario técnico de lectura ERP, usuarios/agentes comerciales, roles ni permiso efectivo `cotizaciones.ver`; para crearlos hace falta primero un esquema ERP compatible y fixtures sintéticos comprobables.

El rol PostgreSQL `cotizaciones_uat_app` no es la cuenta técnica ERP del feed. No se usaron cuentas comerciales reales.

## 7. HTTP, puertos y configuración

En el momento de la comprobación:

- `55433`: PostgreSQL UAT escuchando sólo en `127.0.0.1`, PID 38660.
- `18081` (candidato ERP) y `18082` (candidato Sync): libres, sin listener.
- ERP y Sync: no iniciados; no hay endpoints UAT que consultar.
- El listener operativo en `5432` no se usó. `8080`/`8081` tampoco se usaron.

Los puertos HTTP se reservaron como candidatos locales para futuras instancias exclusivas; aún no se enlazaron procesos ni se verificaron endpoints.

La configuración fuente de ERP tiene `server.port=8081` y datasource por defecto `jdbc:postgresql://localhost:5432/prueba`. Sync tiene `server.port` por defecto `8080`, datasource por defecto `jdbc:postgresql://localhost:5432/prueba`, y puede importar `.env.local` relativa al directorio de trabajo. Ninguna aplicación se ejecutó con esos defaults. Para UAT deberán suministrarse datasource y puertos explícitos por proceso y ejecutar Sync desde el directorio UAT, no desde su carpeta fuente, para que no recoja `.env.local` operativo/local.

No hay configuración UAT de backpressure efectiva todavía. Los defaults fuente de Sync para máximos y umbrales de reanudación son `0`; no se interpretan como límites aplicados ni se declara saturación configurada. `SYNC_COTIZACIONES_READ_ENABLED` tampoco se habilitó: su default fuente es `false`.

## 8. RS256 UAT

Se generó fuera del repositorio un par RSA-2048 exclusivo de UAT en `%LOCALAPPDATA%\CotizacionesUAT\05C3A`:

- `kid`: `uat-cot-05c3a`.
- Emisor y audiencia previstos según ambos códigos: `effyflow-sync` y `erp-cotizaciones`.
- La clave privada está en formato Base64 PKCS#8 para Sync; la pública, Base64 X.509 en el formato `kid=base64` que consume ERP.
- Se comprobó localmente lectura de ambos formatos y una firma/verificación SHA-256 con RSA. Las ACL de los archivos no heredan permisos y permiten acceso al usuario actual.

No se configuraron las claves en procesos ERP/Sync, ni se emitieron tokens o aserciones HTTP. No se copiaron claves operativas.

## 9. Kotlin y SQLite temporal

El archivo operativo `%LOCALAPPDATA%\EffyFlow\effy.db` existe; no se abrió ni alteró. `DatabaseDriver.kt` JVM deriva su directorio de `System.getenv("LOCALAPPDATA")` y usa `EffyFlow\effy.db`. Por tanto, sin cambiar código se puede iniciar Kotlin con `LOCALAPPDATA` redefinido **sólo para ese proceso** a `%LOCALAPPDATA%\CotizacionesUAT\05C3A\kotlin-profile`; la ruta resultante sería `%LOCALAPPDATA%\CotizacionesUAT\05C3A\kotlin-profile\EffyFlow\effy.db`, distinta de la SQLite operativa. Esa SQLite temporal todavía no existe: Kotlin no se inició en esta fase.

La URL Sync se guarda en la configuración local de la aplicación. Cuando exista Sync UAT, debe configurarse en la instancia con SQLite temporal como `http://127.0.0.1:18082`. No se creó conexión a servicios externos ni se configuró VPS.

## 10. Verificaciones ejecutadas

- Se comprobó la ruta nueva antes de inicializarla y la ausencia de listener en 55433.
- `postgres --version`: PostgreSQL 17.5.
- `pg_ctl status`: clúster UAT activo, PID 38660, directorio bajo `%LOCALAPPDATA%\CotizacionesUAT\05C3A`.
- `psql` con `cotizaciones_uat_app`: conexión autenticada a 127.0.0.1:55433, base `cotizaciones_uat_05c3a`, versión 17.5; lectura de las ocho tablas Sync.
- Conteo de filas de las ocho tablas Sync: cero en todas; no hay eventos, sesiones, Cotizaciones ni fixtures.
- RSA: clave UAT compatible con el formato privado PKCS#8/público X.509 y firma/verificación RS256 local.
- Se comprobó que 18081 y 18082 estaban libres al preparar los candidatos.

Esto valida PostgreSQL local y la clave UAT, no comunicación HTTP ERP↔Sync ni autenticación comercial. No se ejecutaron health checks, login, lectura del feed o cliente Kotlin porque los servicios no están iniciados.

## 11. Comandos reproducibles de PostgreSQL

El clúster UAT queda activo. Para verificarlo y detenerlo:

```powershell
$uatRoot = Join-Path $env:LOCALAPPDATA 'CotizacionesUAT\05C3A'
$pgData = Join-Path $uatRoot 'postgres\data'
$pgCtl = (Join-Path $env:PG_BIN 'pg_ctl.exe')
& $pgCtl status -D $pgData
& $pgCtl stop -D $pgData -m fast -w
```

Para iniciarlo después de detenerlo:

```powershell
$uatRoot = Join-Path $env:LOCALAPPDATA 'CotizacionesUAT\05C3A'
$pgData = Join-Path $uatRoot 'postgres\data'
$log = Join-Path $uatRoot 'postgres\postgres-uat.log'
$pgCtl = (Join-Path $env:PG_BIN 'pg_ctl.exe')
& $pgCtl start -D $pgData -o '-h 127.0.0.1 -p 55433 -c listen_addresses=127.0.0.1' -l $log -w
```

Para conectividad SQL sin revelar la contraseña:

```powershell
$uatRoot = Join-Path $env:LOCALAPPDATA 'CotizacionesUAT\05C3A'
$env:PGPASSWORD = (Get-Content -Raw (Join-Path $uatRoot 'postgres-app.pw')).Trim()
& (Join-Path $env:PG_BIN 'psql.exe') -X -h 127.0.0.1 -p 55433 -U cotizaciones_uat_app -d cotizaciones_uat_05c3a -c 'SELECT current_database(), current_user, inet_server_addr(), inet_server_port();'
Remove-Item Env:\PGPASSWORD
```

La parada anterior afecta exclusivamente el PID/directorio de este clúster UAT. No detener ni iniciar el servicio que escucha en 5432.

## 12. Bloqueos y condiciones para 05C-3B

1. Obtener del repositorio ERP un bootstrap reproducible de esquema vacío o generar y revisar uno desde el código/mapeos; no usar dumps ni datos de `prueba`. Debe satisfacer el `ddl-auto=validate`, migraciones 05A1B y consultas de identidad.
2. Aplicar el baseline y luego las migraciones ERP requeridas sólo a `cotizaciones_uat_05c3a`, verificando antes cada destino.
3. Crear fixtures sintéticos de usuario técnico, usuarios/agentes, roles y `cotizaciones.ver`; confirmar que Sync puede resolver las identidades desde el datasource compartido.
4. Crear configuración UAT separada para los dos procesos: URL PostgreSQL 55433, rol DB UAT, ERP 18081, Sync 18082, clave RSA generada, issuer/audience, lector habilitado, credenciales técnicas de prueba y límites de backpressure. Los valores efectivos aún no existen.
5. Iniciar primero ERP y verificar identidad/configuración y health; iniciar Sync desde working directory UAT y verificar que accede al ERP por `127.0.0.1:18081` usando sólo la cuenta técnica de prueba; validar health y autenticación Sync real.
6. Iniciar Kotlin con `LOCALAPPDATA` apuntando a `kotlin-profile`, verificar que su SQLite no coincide con la operativa y configurar la URL local Sync. No usar aún operaciones de Cotizaciones: eso corresponde a 05C-3B después de cumplir estas condiciones.

Mientras no haya baseline ERP sintético no se deben aplicar migraciones ERP, preparar usuarios ni arrancar servicios que validen el esquema. La base PostgreSQL aislada permanece disponible para continuar.

## 13. Artefactos y cambios

Los datos PostgreSQL UAT, las contraseñas y el par RSA residen fuera del repositorio en `%LOCALAPPDATA%\CotizacionesUAT\05C3A`. El clúster 05B-2T anterior y todos los cambios Git preexistentes se conservaron. Esta fase añadió únicamente este informe al repositorio. No se alteró SQLite operativa, `prueba`, servicios Windows, VPS ni código fuente.

## 14. Dictamen final

**05C-3A ENTORNO UAT PARCIAL.** Existe PostgreSQL UAT aislado con esquema Sync y RS256 de prueba verificados; falta el esquema/base ERP sintético, los fixtures de identidad, el arranque/validación HTTP ERP↔Sync y la verificación de Kotlin con SQLite temporal. Por ello todavía no se cumplen las condiciones de `ENTORNO UAT LISTO` ni se debe ejecutar 05C-3B.
