# COT-MOBILE-05C-5 — Plan de commits del protocolo Sync/Kotlin

Fecha: 2026-10-13
Repositorio: raíz compartida del checkout (`.`), rama `main`, HEAD observado: `c4687d1`.

## 1. Inventario Git

Se ejecutaron `git status --short`, `git diff --stat`, `git diff --check`, `git ls-files --others --exclude-standard` y `git diff --cached --name-only` desde la raíz compartida.

- Antes de crear este informe: siete archivos tracked modificados, 1,706 rutas sin seguimiento (incluidos 1,634 archivos del clúster físico PostgreSQL UAT), cero rutas staged.
- `git diff --stat`: siete archivos, 90 inserciones y una eliminación.
- `git diff --check`: sin errores de whitespace. Git mostró sólo avisos de conversión LF/CRLF en los siete archivos modificados.
- La inspección del diff confirma que los cambios Kotlin tracked conectan la pantalla sincronizada, la migración local, la identidad y la purga selectiva de estados recibidos. `PedidoScreen.kt` sólo añade el acceso a Cotizaciones sincronizadas; no cambia la lógica de Pedidos ni de captura. Los dos cambios tracked de Sync añaden dependencia/configuración de métricas y lectura.
- Los cambios ya estaban sin commit al iniciar este plan. Git no identifica por sí solo su autoría ni la tarea exacta de origen. No se alteraron archivos preexistentes durante la preparación.

El `git diff --check` no inspecciona archivos untracked mientras sigan sin preparar. Los comandos de staging de abajo usan listas cerradas de archivos para evitar incorporar otros cambios.

## 2. Grupos propuestos

Los grupos A y B son independientes. C depende de A y B porque sus pruebas usan el consumidor Kotlin y el protocolo HTTP de Sync. D va al final para versionar los informes con el alcance de código ya definido.

### Grupo A — Kotlin

Incluye el consumidor, SQLDelight/migración local, interfaz, integración con la sesión existente y pruebas deterministas.

Archivos tracked modificados:

- `App Pedidos/KotlinProject/composeApp/src/commonMain/kotlin/com/cyssoftware/effyflow/App.kt`
- `App Pedidos/KotlinProject/composeApp/src/commonMain/kotlin/com/cyssoftware/effyflow/db/LocalSchemaMigrations.kt`
- `App Pedidos/KotlinProject/composeApp/src/commonMain/kotlin/com/cyssoftware/effyflow/login/data/LoginRepository.kt`
- `App Pedidos/KotlinProject/composeApp/src/commonMain/kotlin/com/cyssoftware/effyflow/pedido/ui/PedidoScreen.kt`
- `App Pedidos/KotlinProject/composeApp/src/commonMain/sqldelight/com/cyssoftware/effyflow/db/app_config.sq`

Archivos nuevos de producción:

- `App Pedidos/KotlinProject/composeApp/src/androidMain/kotlin/com/cyssoftware/effyflow/cotizacion/estados/AuthorizationClock.android.kt`
- `App Pedidos/KotlinProject/composeApp/src/commonMain/kotlin/com/cyssoftware/effyflow/cotizacion/estados/AuthorizationClock.kt`
- `App Pedidos/KotlinProject/composeApp/src/commonMain/kotlin/com/cyssoftware/effyflow/cotizacion/estados/CotizacionEstadosApi.kt`
- `App Pedidos/KotlinProject/composeApp/src/commonMain/kotlin/com/cyssoftware/effyflow/cotizacion/estados/CotizacionEstadosDtos.kt`
- `App Pedidos/KotlinProject/composeApp/src/commonMain/kotlin/com/cyssoftware/effyflow/cotizacion/estados/CotizacionEstadosRepository.kt`
- `App Pedidos/KotlinProject/composeApp/src/commonMain/kotlin/com/cyssoftware/effyflow/cotizacion/estados/CotizacionEstadosSync.kt`
- `App Pedidos/KotlinProject/composeApp/src/commonMain/kotlin/com/cyssoftware/effyflow/cotizacion/estados/CotizacionesSincronizadasScreen.kt`
- `App Pedidos/KotlinProject/composeApp/src/commonMain/kotlin/com/cyssoftware/effyflow/cotizacion/estados/DeviceIdentityRepository.kt`
- `App Pedidos/KotlinProject/composeApp/src/commonMain/sqldelight/com/cyssoftware/effyflow/db/cotizacion_estados.sq`
- `App Pedidos/KotlinProject/composeApp/src/jvmMain/kotlin/com/cyssoftware/effyflow/cotizacion/estados/AuthorizationClock.jvm.kt`

Pruebas deterministas:

- `App Pedidos/KotlinProject/composeApp/src/jvmTest/kotlin/com/cyssoftware/effyflow/CotizacionEstadosApiTest.kt`
- `App Pedidos/KotlinProject/composeApp/src/jvmTest/kotlin/com/cyssoftware/effyflow/CotizacionEstadosOfflineLeaseTest.kt`
- `App Pedidos/KotlinProject/composeApp/src/jvmTest/kotlin/com/cyssoftware/effyflow/CotizacionEstadosRepositoryTest.kt`
- `App Pedidos/KotlinProject/composeApp/src/jvmTest/kotlin/com/cyssoftware/effyflow/CotizacionEstadosSyncTest.kt`
- `App Pedidos/KotlinProject/composeApp/src/jvmTest/kotlin/com/cyssoftware/effyflow/DeviceIdentityRepositoryTest.kt`

Comando propuesto, no ejecutado:

```powershell
git add -- "App Pedidos/KotlinProject/composeApp/src/commonMain/kotlin/com/cyssoftware/effyflow/App.kt" "App Pedidos/KotlinProject/composeApp/src/commonMain/kotlin/com/cyssoftware/effyflow/db/LocalSchemaMigrations.kt" "App Pedidos/KotlinProject/composeApp/src/commonMain/kotlin/com/cyssoftware/effyflow/login/data/LoginRepository.kt" "App Pedidos/KotlinProject/composeApp/src/commonMain/kotlin/com/cyssoftware/effyflow/pedido/ui/PedidoScreen.kt" "App Pedidos/KotlinProject/composeApp/src/commonMain/sqldelight/com/cyssoftware/effyflow/db/app_config.sq" "App Pedidos/KotlinProject/composeApp/src/androidMain/kotlin/com/cyssoftware/effyflow/cotizacion/estados/AuthorizationClock.android.kt" "App Pedidos/KotlinProject/composeApp/src/commonMain/kotlin/com/cyssoftware/effyflow/cotizacion/estados/AuthorizationClock.kt" "App Pedidos/KotlinProject/composeApp/src/commonMain/kotlin/com/cyssoftware/effyflow/cotizacion/estados/CotizacionEstadosApi.kt" "App Pedidos/KotlinProject/composeApp/src/commonMain/kotlin/com/cyssoftware/effyflow/cotizacion/estados/CotizacionEstadosDtos.kt" "App Pedidos/KotlinProject/composeApp/src/commonMain/kotlin/com/cyssoftware/effyflow/cotizacion/estados/CotizacionEstadosRepository.kt" "App Pedidos/KotlinProject/composeApp/src/commonMain/kotlin/com/cyssoftware/effyflow/cotizacion/estados/CotizacionEstadosSync.kt" "App Pedidos/KotlinProject/composeApp/src/commonMain/kotlin/com/cyssoftware/effyflow/cotizacion/estados/CotizacionesSincronizadasScreen.kt" "App Pedidos/KotlinProject/composeApp/src/commonMain/kotlin/com/cyssoftware/effyflow/cotizacion/estados/DeviceIdentityRepository.kt" "App Pedidos/KotlinProject/composeApp/src/commonMain/sqldelight/com/cyssoftware/effyflow/db/cotizacion_estados.sq" "App Pedidos/KotlinProject/composeApp/src/jvmMain/kotlin/com/cyssoftware/effyflow/cotizacion/estados/AuthorizationClock.jvm.kt" "App Pedidos/KotlinProject/composeApp/src/jvmTest/kotlin/com/cyssoftware/effyflow/CotizacionEstadosApiTest.kt" "App Pedidos/KotlinProject/composeApp/src/jvmTest/kotlin/com/cyssoftware/effyflow/CotizacionEstadosOfflineLeaseTest.kt" "App Pedidos/KotlinProject/composeApp/src/jvmTest/kotlin/com/cyssoftware/effyflow/CotizacionEstadosRepositoryTest.kt" "App Pedidos/KotlinProject/composeApp/src/jvmTest/kotlin/com/cyssoftware/effyflow/CotizacionEstadosSyncTest.kt" "App Pedidos/KotlinProject/composeApp/src/jvmTest/kotlin/com/cyssoftware/effyflow/DeviceIdentityRepositoryTest.kt"
```

Mensaje: `feat(kotlin): integrar estados sincronizados de cotizaciones`

### Grupo B — Backend Sync

Incluye configuración, endpoints y servicios, ambas migraciones y sus pruebas.

Archivos tracked modificados:

- `Backend/effyFlow/pom.xml`
- `Backend/effyFlow/src/main/resources/application.properties`

Migraciones; deben viajar juntas y conservar el orden base → v2:

- `Backend/effyFlow/db/scripts/20261013_cotizaciones_sync_durable.sql`
- `Backend/effyFlow/db/scripts/20261013_cotizaciones_sync_durable_v2.sql`

Archivos nuevos de producción:

- `Backend/effyFlow/src/main/java/com/cyssoftware/effyFlow/config/CotizacionesMetricsConfig.java`
- `Backend/effyFlow/src/main/java/com/cyssoftware/effyFlow/config/CotizacionesReadWorkerConfig.java`
- `Backend/effyFlow/src/main/java/com/cyssoftware/effyFlow/config/ErpCotizacionesReadConfig.java`
- `Backend/effyFlow/src/main/java/com/cyssoftware/effyFlow/controller/cotizaciones/CotizacionEstadosSyncController.java`
- `Backend/effyFlow/src/main/java/com/cyssoftware/effyFlow/repository/CotizacionSyncRepository.java`
- `Backend/effyFlow/src/main/java/com/cyssoftware/effyFlow/service/cotizaciones/CotizacionReadAssertionService.java`
- `Backend/effyFlow/src/main/java/com/cyssoftware/effyFlow/service/cotizaciones/CotizacionReadIdentityService.java`
- `Backend/effyFlow/src/main/java/com/cyssoftware/effyFlow/service/cotizaciones/CotizacionStatesSyncService.java`
- `Backend/effyFlow/src/main/java/com/cyssoftware/effyFlow/service/cotizaciones/CotizacionStatesSyncWorker.java`
- `Backend/effyFlow/src/main/java/com/cyssoftware/effyFlow/service/cotizaciones/CotizacionSyncRepositoryModels.java`
- `Backend/effyFlow/src/main/java/com/cyssoftware/effyFlow/service/cotizaciones/CotizacionSyncRetentionWorker.java`
- `Backend/effyFlow/src/main/java/com/cyssoftware/effyFlow/service/cotizaciones/ErpCotizacionesReadClient.java`

Pruebas nuevas del backend:

- `Backend/effyFlow/src/test/java/com/cyssoftware/effyFlow/repository/CotizacionSyncPostgresIT.java`
- `Backend/effyFlow/src/test/java/com/cyssoftware/effyFlow/repository/CotizacionSyncRepositoryTest.java`
- `Backend/effyFlow/src/test/java/com/cyssoftware/effyFlow/service/cotizaciones/CotizacionReadAssertionServiceTest.java`
- `Backend/effyFlow/src/test/java/com/cyssoftware/effyFlow/service/cotizaciones/ErpCotizacionesReadClientTest.java`

Comando propuesto, no ejecutado:

```powershell
git add -- "Backend/effyFlow/pom.xml" "Backend/effyFlow/src/main/resources/application.properties" "Backend/effyFlow/db/scripts/20261013_cotizaciones_sync_durable.sql" "Backend/effyFlow/db/scripts/20261013_cotizaciones_sync_durable_v2.sql" "Backend/effyFlow/src/main/java/com/cyssoftware/effyFlow/config/CotizacionesMetricsConfig.java" "Backend/effyFlow/src/main/java/com/cyssoftware/effyFlow/config/CotizacionesReadWorkerConfig.java" "Backend/effyFlow/src/main/java/com/cyssoftware/effyFlow/config/ErpCotizacionesReadConfig.java" "Backend/effyFlow/src/main/java/com/cyssoftware/effyFlow/controller/cotizaciones/CotizacionEstadosSyncController.java" "Backend/effyFlow/src/main/java/com/cyssoftware/effyFlow/repository/CotizacionSyncRepository.java" "Backend/effyFlow/src/main/java/com/cyssoftware/effyFlow/service/cotizaciones/CotizacionReadAssertionService.java" "Backend/effyFlow/src/main/java/com/cyssoftware/effyFlow/service/cotizaciones/CotizacionReadIdentityService.java" "Backend/effyFlow/src/main/java/com/cyssoftware/effyFlow/service/cotizaciones/CotizacionStatesSyncService.java" "Backend/effyFlow/src/main/java/com/cyssoftware/effyFlow/service/cotizaciones/CotizacionStatesSyncWorker.java" "Backend/effyFlow/src/main/java/com/cyssoftware/effyFlow/service/cotizaciones/CotizacionSyncRepositoryModels.java" "Backend/effyFlow/src/main/java/com/cyssoftware/effyFlow/service/cotizaciones/CotizacionSyncRetentionWorker.java" "Backend/effyFlow/src/main/java/com/cyssoftware/effyFlow/service/cotizaciones/ErpCotizacionesReadClient.java" "Backend/effyFlow/src/test/java/com/cyssoftware/effyFlow/repository/CotizacionSyncPostgresIT.java" "Backend/effyFlow/src/test/java/com/cyssoftware/effyFlow/repository/CotizacionSyncRepositoryTest.java" "Backend/effyFlow/src/test/java/com/cyssoftware/effyFlow/service/cotizaciones/CotizacionReadAssertionServiceTest.java" "Backend/effyFlow/src/test/java/com/cyssoftware/effyFlow/service/cotizaciones/ErpCotizacionesReadClientTest.java"
```

Mensaje: `feat(sync): implementar sincronizacion durable de estados de cotizaciones`

### Grupo C — Herramientas UAT e integración HTTP

Incluye las pruebas Kotlin que hablan por HTTP con Sync real y las fuentes JavaScript del simulador/proxy.

- `App Pedidos/KotlinProject/composeApp/src/jvmTest/kotlin/com/cyssoftware/effyflow/CotizacionEstadosRealSyncIntegrationTest.kt`
- `App Pedidos/KotlinProject/composeApp/src/jvmTest/kotlin/com/cyssoftware/effyflow/CotizacionEstadosResilienceRealSyncIntegrationTest.kt`
- `App Pedidos/KotlinProject/composeApp/src/jvmTest/kotlin/com/cyssoftware/effyflow/CotizacionEstadosRemainingResilienceRealSyncIntegrationTest.kt`
- `uat-probe/COT-MOBILE-05C-3P/erp-contract-simulator.js`
- `uat-probe/COT-MOBILE-05C-3P/sync-ack-drop-proxy.js`
- `uat-probe/COT-MOBILE-05C-3P/run-3p3-kotlin-test.ps1`
- `uat-probe/COT-MOBILE-05C-3P/start-sync-proxy-3p3.ps1`
- `.gitignore` — regla acotada para clústeres PostgreSQL temporales del proyecto.

Comando propuesto para el subconjunto revisado, no ejecutado:

```powershell
git add -- ".gitignore" "App Pedidos/KotlinProject/composeApp/src/jvmTest/kotlin/com/cyssoftware/effyflow/CotizacionEstadosRealSyncIntegrationTest.kt" "App Pedidos/KotlinProject/composeApp/src/jvmTest/kotlin/com/cyssoftware/effyflow/CotizacionEstadosResilienceRealSyncIntegrationTest.kt" "App Pedidos/KotlinProject/composeApp/src/jvmTest/kotlin/com/cyssoftware/effyflow/CotizacionEstadosRemainingResilienceRealSyncIntegrationTest.kt" "uat-probe/COT-MOBILE-05C-3P/erp-contract-simulator.js" "uat-probe/COT-MOBILE-05C-3P/sync-ack-drop-proxy.js" "uat-probe/COT-MOBILE-05C-3P/run-3p3-kotlin-test.ps1" "uat-probe/COT-MOBILE-05C-3P/start-sync-proxy-3p3.ps1"
```

Mensaje: `test(uat): validar resiliencia de sincronizacion de cotizaciones`

Los lanzadores se incluyen sólo después de reemplazar la raíz absoluta por `$PSScriptRoot`, derivar las herramientas externas desde variables de entorno y conservar las barreras para SQLite/Sync/PostgreSQL UAT.

### Grupo D — Documentación revisada

Archivos sin rutas absolutas de usuario/proyecto detectadas en la inspección:

- `Backend/effyFlow/COT-MOBILE-05B-1-CONTRATO.md`
- `Backend/effyFlow/COT-MOBILE-05B-2-RESULTADO.md`
- `Backend/effyFlow/COT-MOBILE-05B-2C-RESULTADO.md`
- `Backend/effyFlow/COT-MOBILE-05B-2V-VALIDACION.md`
- `Backend/effyFlow/COT-MOBILE-05B-2T-RESULTADO.md`
- `Backend/effyFlow/COT-MOBILE-05B-DIAGNOSTICO.md`
- `App Pedidos/KotlinProject/COT-MOBILE-05C-2-RESULTADO.md`
- `COT-MOBILE-05C-1-DIAGNOSTICO.md`
- `COT-MOBILE-05C-3-RESULTADO.md`
- `COT-MOBILE-05C-3A-ENTORNO-UAT.md`
- `COT-MOBILE-05C-3A1-BASELINE-ERP.md`
- `COT-MOBILE-05C-3P-RESULTADO.md`
- `COT-MOBILE-05C-3P2-RESULTADO.md`
- `COT-MOBILE-05C-3P3-RESULTADO.md`
- `COT-MOBILE-05C-4-CIERRE-TECNICO.md`
- `COT-MOBILE-05C-5-PLAN-COMMITS.md`

Las ubicaciones de usuario/proyecto se normalizan en los informes antes de agregarlos; los valores y conclusiones históricas permanecen intactos. Comando propuesto, no ejecutado:

```powershell
git add -- "Backend/effyFlow/COT-MOBILE-05B-1-CONTRATO.md" "Backend/effyFlow/COT-MOBILE-05B-2-RESULTADO.md" "Backend/effyFlow/COT-MOBILE-05B-2C-RESULTADO.md" "Backend/effyFlow/COT-MOBILE-05B-2T-RESULTADO.md" "Backend/effyFlow/COT-MOBILE-05B-2V-VALIDACION.md" "Backend/effyFlow/COT-MOBILE-05B-DIAGNOSTICO.md" "App Pedidos/KotlinProject/COT-MOBILE-05C-2-RESULTADO.md" "COT-MOBILE-05C-1-DIAGNOSTICO.md" "COT-MOBILE-05C-3-RESULTADO.md" "COT-MOBILE-05C-3A-ENTORNO-UAT.md" "COT-MOBILE-05C-3A1-BASELINE-ERP.md" "COT-MOBILE-05C-3P-RESULTADO.md" "COT-MOBILE-05C-3P2-RESULTADO.md" "COT-MOBILE-05C-3P3-RESULTADO.md" "COT-MOBILE-05C-4-CIERRE-TECNICO.md" "COT-MOBILE-05C-5-PLAN-COMMITS.md"
```

Mensaje: `docs(cotizaciones): documentar implementacion y validacion del protocolo`

El commit D incluye los informes 05B/05C de este repositorio, no los informes del repositorio ERP independiente.

## 3. Revisión UAT y archivos excluidos

Se inspeccionaron los cuatro archivos actuales de `uat-probe/` y los tests de integración HTTP. No se encontraron bloques de claves privadas ni credenciales, tokens o contraseñas literales. Las coincidencias de búsqueda correspondían a nombres de campos, propiedades Spring y valores cargados desde variables de entorno o archivos UAT externos.

- `erp-contract-simulator.js` recibe usuario/contraseña técnica, bearer de respuesta, clave de control y archivo de claves públicas desde variables de entorno/ruta configurada. El archivo contiene fixtures sintéticos.
- `sync-ack-drop-proxy.js` recibe la clave de control desde entorno; no contiene un valor secreto.
- `run-3p3-kotlin-test.ps1` y `start-sync-proxy-3p3.ps1` leen archivos de credenciales UAT fuera del repo. No incluyen sus contenidos, pero fijan la raíz del checkout y rutas locales de UAT, JDK y PostgreSQL. No son portables entre máquinas.

Rutas locales detectadas en la revisión inicial y normalizadas para el commit D:

- `run-3p3-kotlin-test.ps1` y `start-sync-proxy-3p3.ps1`: raíz de checkout, JDK y cliente PostgreSQL ahora se resuelven con `$PSScriptRoot`/variables de entorno; los datos UAT permanecen bajo `%LOCALAPPDATA%`.
- `COT-MOBILE-05C-2-RESULTADO.md`, `COT-MOBILE-05C-3-RESULTADO.md`: se anonimizaron rutas locales de Android/JDK sin cambiar que la ruta configurada no existía.
- `COT-MOBILE-05B-2T-RESULTADO.md`, `COT-MOBILE-05B-DIAGNOSTICO.md`, `COT-MOBILE-05C-3A-ENTORNO-UAT.md`, `COT-MOBILE-05C-3A1-BASELINE-ERP.md`, `COT-MOBILE-05C-3P-RESULTADO.md` y `COT-MOBILE-05C-4-CIERRE-TECNICO.md`: se sustituyeron raíces locales por rutas relativas o descripciones de checkout independiente; los resultados permanecen iguales.

Otros excluidos:

- `Backend/effyFlow/.cotizaciones-pg17-2t-e128d7aa75874682988f8de222600b69/` — directorio físico PostgreSQL UAT (1,634 archivos untracked). Preservarlo; no agregarlo ni borrarlo.
- `cot-mobile-04f-effyflow-screen.png`, `cot-mobile-04i-cotizaciones-final.png`, `cot-mobile-04i-cotizaciones.png`, `cot-mobile-04i-current.png`, `cot-mobile-04i-home-full.png`, `cot-mobile-04i-home.png`, `cot-mobile-04i-module-final.png`, `cot-mobile-04i-module.png`, `cot-mobile-04i-pedido-editor.png`, `cot-mobile-04i-pedidos.png`, `cot-mobile-04i-visible.png`, `cot-mobile-04j-401.png`, `cot-mobile-04j-client-results.png`, `cot-mobile-04j-draft.png`, `cot-mobile-04j-line-added.png`, `cot-mobile-04j-pending-final.png`, `cot-mobile-04j-reopened.png` — capturas históricas ajenas a estos commits.
- `build/` y `target/` — artefactos generados; no forman parte de los candidatos enumerados. No aparecieron como archivos untracked del inventario.
- SQLite de ejecución, secretos, contraseñas, claves privadas, tokens y archivos locales de configuración UAT — no son candidatos del árbol Git; permanecer fuera.
- No agregar cambios del repositorio ERP ni sus documentos `docs/cotizaciones`; es un repositorio independiente y no fue modificado.

El informe 05C-5B se crea después de los cuatro commits para registrar sus hashes; permanece fuera del commit D para evitar una referencia circular a su propio hash.

## 4. Procedencia, dependencias y riesgos

- Los siete cambios tracked y los archivos nuevos de implementación ya estaban presentes sin commit. La procedencia por sub-tarea no queda registrada en Git; no se atribuyen como cambios nuevos de esta preparación.
- Los cambios `LoginRepository.kt` y `PedidoScreen.kt` son integración de sesión/navegación de Cotizaciones sincronizadas. No se incluyeron cambios de lógica comercial de capturas o pedidos.
- Los dos scripts SQL base/v2 forman una unidad de implementación, pero conservan el orden de ejecución que documenta el backend. Este plan sólo los agrupa; no ejecuta migraciones.
- A y B pueden prepararse por separado. C depende de A+B y está incompleto mientras se excluyan los lanzadores locales. D va después y queda incompleto hasta sanitizar los informes excluidos.
- El cliente HTTP Sync conserva valores por defecto cerrados para lectura/backpressure; el diff de propiedades usa placeholders de entorno, no credenciales literales.
- La suite PostgreSQL reportó nueve pruebas aprobadas y una omitida por fixture legacy ausente. No debe contabilizarse como suite completamente aprobada. La compilación Android y la UAT comercial contra ERP real no se deben inferir de los resultados JVM/Sync.

## 5. Evidencias existentes; pruebas no repetidas

No cambió código funcional durante esta preparación. Se conservan las evidencias documentadas en 05C-4: Kotlin determinista 111/111, Sync determinista 82/82 y PostgreSQL 9 aprobadas / 1 omitida. Las cinco pruebas offline con reloj inyectado están incluidas en el grupo A. No se repitieron pruebas costosas.

## 6. Estado Git al cierre de la preparación

La creación de este informe añade una ruta untracked. No se ejecutaron `git add`, `git commit`, `git push`, checkout, migraciones ni despliegue. El índice permanece vacío; el árbol de trabajo sigue conteniendo cambios previos pendientes, la base física PostgreSQL UAT y las capturas históricas excluidas.

Antes de aprobar los cuatro commits, se recomienda resolver las rutas locales indicadas, confirmar los informes que se quieren sanear y revisar cada índice con `git diff --cached --name-only` y `git diff --cached --check` antes de confirmar. Este plan no prepara ni confirma automáticamente ningún archivo.
