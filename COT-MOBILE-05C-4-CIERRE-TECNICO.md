# COT-MOBILE-05C-4 — Cierre técnico del protocolo Sync/Kotlin

Fecha: 13 de octubre de 2026, hora local. Las salidas Maven/Gradle quedaron fechadas el 13 de octubre local y el 14 de octubre UTC.

## 1. Dictamen

**`PROTOCOLO VALIDADO CON PENDIENTES`**

La lógica Kotlin de autorización offline pasó pruebas deterministas file-backed, las suites Kotlin y Sync seleccionadas pasaron, y la suite de repositorio contra PostgreSQL UAT aprobó nueve casos. No se repitieron los escenarios HTTP reales de 05C-3P3. El ERP comercial real no se validó y este dictamen no autoriza un despliegue.

## 2. Funcionalidad implementada

El árbol contiene la implementación Kotlin de 05C-2: cliente y DTO de estados Sync, repositorio SQLDelight aislado por sesión/generación, consumidor snapshot/incremental con ACK durable, versiones y tombstones, lease offline, identidad de dispositivo, adaptadores de reloj JVM/Android y pantalla **Cotizaciones sincronizadas**. La UI y el repositorio mantienen los estados sincronizados separados de la captura comercial.

El árbol también contiene el backend Sync de 05B: persistencia del ledger, delivery, sesiones/generaciones, ACK, identidad y autorización, cliente/worker de lectura ERP, backpressure y retención. Las migraciones SQL del backend están separadas en los scripts base y v2. `application.properties` usa variables de entorno sin valores secretos y conserva lectura de Cotizaciones deshabilitada por defecto.

En 05C-4 no cambié código funcional de Kotlin, Sync ni ERP. Añadí `CotizacionEstadosOfflineLeaseTest.kt` con SQLite temporal file-backed para cerrar la matriz offline. No se añadieron migraciones.

## 3. Resultados offline

Cinco pruebas deterministas con reloj inyectado pasaron. Cada una creó una SQLite nueva bajo el directorio temporal del sistema y la eliminó al cerrar; al final no quedaron directorios `cot05c4-offline-*`.

| Caso | Resultado |
|---|---|
| Lease vigente | El repositorio devolvió el documento autorizado antes del vencimiento. |
| Límite `validUntil` | En `now == validUntil` el repositorio negó la lectura. |
| Máximo de 24 horas | Aceptó un lease de exactamente 24 h, lo negó en su instante de expiración y rechazó uno que excedía el límite por 1 ns. |
| Retroceso material y reinicio | Tras cerrar y reabrir el mismo archivo SQLite con hora 10 min anterior y contador monotónico reiniciado, el repositorio negó la lectura. |
| Revalidación y falla temporal | Una respuesta HTTP 200 válida reactivó lectura después del vencimiento. Una revalidación HTTP 503 mantuvo `validUntil` sin cambio y la lectura se bloqueó al llegar a ese límite. |

No se esperaron 24 horas reales. Los archivos de prueba se crearon fuera del directorio de datos de EffyFlow y no se abrió la SQLite operativa.

## 4. Pruebas finales

| Suite | Resultado |
|---|---|
| Kotlin determinista aislada | **111 pruebas, 15 clases; 0 fallos, 0 errores, 0 omitidas.** Incluye las cinco nuevas pruebas offline y la regresión de clasificación HTTP 503. `LOCALAPPDATA` se redirigió a un directorio temporal aislado y se excluyeron clases que inicializan `DatabaseProvider`. |
| Sync determinista | **82 pruebas, 14 clases; 0 fallos, 0 errores, 0 omitidas**, con JDK 25. |
| `CotizacionSyncPostgresIT` | **9 aprobadas, 0 fallidas, 1 omitida.** Ejecutada después de verificar clúster y base UAT. Se omitió sólo `migratedLegacyGenerationCannotOfferAfterLedgerMigration`, porque requiere una fixture dedicada de migración que no se suministró. |
| Corrección HTTP 503 | Cubierta por `CotizacionEstadosApiTest` dentro de la suite Kotlin: los errores transitorios 503 de Sync siguen siendo reintentables y los errores de autenticación técnica mantienen su clasificación no reintentable. |

PostgreSQL se verificó antes de conectar: PostgreSQL 17.5, host `127.0.0.1`, puerto `55433`, base `cotizaciones_uat_05c3p2`, rol `cotizaciones_uat_app`, clúster con identificador `7696257406651362612` y directorio `%LOCALAPPDATA%\CotizacionesUAT\05C3A\postgres\data`. El listener era PID 4352. Los tests sólo insertaron fixtures aleatorias propias y sus limpiezas automáticas se ejecutaron; después verifiqué cero filas de sesión y generación en el rango de identidades sintéticas usado por esa clase. No se ejecutó DDL ni se conectó a `5432`, `prueba` o al VPS.

El test Postgres probó el repositorio real contra PostgreSQL, no una ruta HTTP de Sync. Las pruebas HTTP con Sync real y el simulador ERP aprobadas en 05C-3P3 se conservan como evidencia anterior; no se repitieron aquí.

## 5. Defectos y correcciones

No apareció un defecto funcional nuevo en el protocolo offline. La cobertura confirmó que el repositorio no devuelve documentos al vencimiento ni después de un retroceso material, que el lease de servidor puede renovarse al revalidar y que el error temporal no lo prolonga localmente.

La clasificación de HTTP 503 corregida en 05C-3P3 quedó cubierta de nuevo y pasó. Durante el desarrollo del nuevo test hubo dos fallas de prueba, no de producto: JUnit exigió retorno `Unit` en un método y una fixture intentó insertar una segunda sesión con una clave de namespace ya ocupada. Se corrigieron la firma y el aislamiento de la fixture; la clase final pasó 5/5.

La primera barrera previa al test PostgreSQL abortó antes de conectar porque la línea de comando del proceso expresaba las rutas con barras `/` mientras la comparación esperaba `\\`. Normalicé la ruta y volví a comprobar ejecutable, directorio, host, puerto, identificador de clúster y conexión. La ejecución PostgreSQL sólo comenzó después de esa verificación.

## 6. Riesgos residuales y bloqueos de producción

- El feed y los permisos del ERP real siguen sin validación end-to-end; 05C-3P3 usó un simulador HTTP del contrato.
- No hay evidencia nueva de compilación Android en esta fase. El resultado 05C-2 ya documenta la ausencia de un SDK Android válido.
- SQLite no tiene cifrado en reposo verificado. El control del repositorio y el lease no cifran ni protegen el archivo ante lectura directa; se requiere una fase de cifrado y migración recuperable antes de producción.
- No se ejecutó la suite JVM completa. Las clases que usan `DatabaseProvider` o smoke tests con servicios/datos externos se excluyeron para no abrir destinos no identificados.
- La prueba Postgres que requiere una fixture de migración legacy quedó omitida; su resultado no debe contarse como aprobado.
- La prueba de retroceso cubre reloj local manipulado accidentalmente y la tolerancia implementada, no resistencia contra manipulación deliberada del sistema.

Por estos pendientes, no se declara validado el ERP comercial real ni se autoriza despliegue productivo.

## 7. Control de cambios Git

Sync y Kotlin están dentro del mismo repositorio Git: `.`, rama `main`. ERP tiene otro repositorio: `checkout ERP independiente`, rama `main`.

En el repositorio compartido, `git status` muestra siete archivos funcionales modificados y grupos de archivos nuevos de Kotlin, Sync, pruebas, contratos e informes. No hay archivos preparados por este trabajo. El repositorio ERP no tiene cambios rastreados modificados; sólo muestra ocho informes Markdown previos no rastreados bajo `docs/cotizaciones/`. No se modificó código ni migraciones ERP.

### Candidatos de commits separados

No se ejecutó `git add` ni `git commit`. Propongo revisar y luego separar los cambios así:

1. **Implementación Kotlin** — mensaje: `feat(kotlin): integrar estados sincronizados de Cotizaciones`.
   - Modificados: `App.kt`, `LocalSchemaMigrations.kt`, `LoginRepository.kt`, `PedidoScreen.kt`, `app_config.sq`.
   - Nuevos: los archivos `AuthorizationClock`, `CotizacionEstadosApi`, `CotizacionEstadosDtos`, `CotizacionEstadosRepository`, `CotizacionEstadosSync`, `CotizacionesSincronizadasScreen` y `DeviceIdentityRepository` en `src/commonMain/.../cotizacion/estados/`; adaptadores `AuthorizationClock.android.kt` y `AuthorizationClock.jvm.kt`; `cotizacion_estados.sq`.
   - Pruebas deterministas: `CotizacionEstadosApiTest.kt`, `CotizacionEstadosOfflineLeaseTest.kt`, `CotizacionEstadosRepositoryTest.kt`, `CotizacionEstadosSyncTest.kt` y `DeviceIdentityRepositoryTest.kt`.

2. **Backend Sync** — mensaje: `feat(sync): publicar estados durables de Cotizaciones`.
   - Modificados: `Backend/effyFlow/pom.xml` y `Backend/effyFlow/src/main/resources/application.properties`.
   - Nuevos: `Backend/effyFlow/db/scripts/20261013_cotizaciones_sync_durable.sql` y `_v2.sql`; configuraciones `CotizacionesMetricsConfig`, `CotizacionesReadWorkerConfig`, `ErpCotizacionesReadConfig`; `CotizacionEstadosSyncController`, `CotizacionSyncRepository`; servicios `CotizacionReadAssertionService`, `CotizacionReadIdentityService`, `CotizacionStatesSyncService`, `CotizacionStatesSyncWorker`, `CotizacionSyncRepositoryModels`, `CotizacionSyncRetentionWorker` y `ErpCotizacionesReadClient`.
   - Nuevas pruebas de este conjunto: `CotizacionSyncPostgresIT.java`, `CotizacionSyncRepositoryTest.java`, `CotizacionReadAssertionServiceTest.java` y `ErpCotizacionesReadClientTest.java`. No añadir pruebas rastreadas sin cambios sólo porque participaron en la suite.

3. **Pruebas de integración y harness UAT** — mensaje: `test(uat): probar resiliencia del protocolo Sync y Kotlin`.
   - Kotlin: `CotizacionEstadosRealSyncIntegrationTest.kt`, `CotizacionEstadosResilienceRealSyncIntegrationTest.kt` y `CotizacionEstadosRemainingResilienceRealSyncIntegrationTest.kt`.
   - Harness: `uat-probe/COT-MOBILE-05C-3P/erp-contract-simulator.js`, `sync-ack-drop-proxy.js`, `start-sync-proxy-3p3.ps1` y `run-3p3-kotlin-test.ps1`.
   - Mantenerlo separado del código de producción y revisar los controles locales antes de decidir si se conserva en Git.

4. **Informes de la fase** — mensaje: `docs(cotizaciones): cerrar validación técnica 05C-4`.
   - Este informe y los resultados 05C-1/05C-2/05C-3/05C-3A/05C-3A1/05C-3P/05C-3P2/05C-3P3, más los documentos de contrato/resultados 05B. Revisar su procedencia antes de agrupar; varios ya eran untracked antes de 05C-4.

Los ocho informes de ERP bajo `checkout ERP independiente\docs\cotizaciones\` pertenecen a su repositorio independiente: `COT-MOBILE-02-RESULTADO.md`, `COT-MOBILE-02B-RESULTADO.md`, `COT-MOBILE-03B-ENTORNO-RESULTADO.md`, `COT-MOBILE-03B-RESULTADO.md`, `COT-MOBILE-03B-VALIDACION-RESULTADO.md`, `COT-MOBILE-03E-RESULTADO.md`, `COT-MOBILE-03F-RESULTADO.md` y `COT-MOBILE-05A1-RESULTADO.md`. No deben mezclarse con los commits Sync/Kotlin.

## 8. Archivos UAT y temporales que deben excluirse

- `Backend/effyFlow/.cotizaciones-pg17-2t-e128d7aa75874682988f8de222600b69/`: clúster PostgreSQL 17 local, con datos físicos y archivos de configuración/log; ocupa aproximadamente 75 MB. Excluirlo completo y conservarlo; no se borró.
- SQLite de ejecución, contraseñas, llaves privadas, tokens y archivos de configuración UAT bajo `%LOCALAPPDATA%\CotizacionesUAT\...`: permanecen fuera del repositorio y no deben copiarse a Git.
- Directorios de salida `build/` y `target/`: artefactos generados; no formar parte de commits.
- Capturas históricas `cot-mobile-04*.png`: artefactos previos de UI ajenos al cierre 05C-4; no incluir con la funcionalidad Sync/Kotlin.
- `uat-probe/` contiene sólo los cuatro scripts fuente listados arriba. Son herramientas, no datos ni secretos; si se versionan, hacerlo en el commit UAT separado.

No se encontraron archivos de credenciales dentro de `uat-probe/`. No se imprimieron secretos. El clúster PostgreSQL del repositorio no se inició ni se modificó en esta fase.

## 9. Condiciones para UAT contra ERP real

Antes de declarar cierre end-to-end o preparar producción:

1. Identificar y aislar un ERP con feed 05A1B verificado, una cuenta técnica de sólo lectura y usuarios/agentes sintéticos con permisos efectivos.
2. Configurar Sync con datasource UAT, URL ERP comprobada y claves RS256 exclusivas de UAT; mantener lectura deshabilitada hasta verificar límites de cola y autorización.
3. Ejecutar contra HTTP real del ERP bootstrap, snapshot, incremental, cambios de agente, revocación, reset, timeout y reintentos; registrar códigos, DTO y secuencias sin secretos ni datos comerciales innecesarios.
4. Ejecutar Kotlin con SQLite temporal, incluyendo ACK/replay, expiración del lease, cambio de usuario y purga selectiva.
5. Compilar y revisar Android con un SDK válido; completar la fase de cifrado/protección SQLite y su migración antes de producción.
6. Mantener evidencia de aislamiento, fixtures, scripts y resultados por separado de cualquier configuración operativa.

## Dictamen final

**`PROTOCOLO VALIDADO CON PENDIENTES`**

Kotlin y Sync pasan la cobertura determinista de cierre y la integración PostgreSQL del repositorio. El protocolo queda validado con pendientes para UAT contra el ERP real, Android y protección local de SQLite. No se hizo commit, push ni despliegue.
