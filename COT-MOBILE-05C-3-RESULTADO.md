# COT-MOBILE-05C-3 — Resultado de validación de integración

Fecha: 2026-10-13
Alcance: inspección del entorno disponible y evaluación de seguridad para iniciar UAT end-to-end. No se realizaron llamadas HTTP de integración, escrituras, migraciones, reinicios ni pruebas contra servicios operativos.

## 1. Dictamen

**05C-3 BLOQUEADO.** No se pudo demostrar que exista un entorno exclusivo de UAT que incluya ERP 05A1B, Sync 05B y una base Kotlin desechable. En esta máquina hay un PostgreSQL escuchando en el puerto compartido 5432 y un proceso Tomcat8 en 8080; ninguno se pudo atribuir de forma segura a un UAT aislado. El PostgreSQL temporal 05B-2T, que sí fue aislado para las pruebas de backend, está detenido y no escucha en su puerto 55432.

Por la restricción de aislamiento, no se intentaron endpoints ni escenarios comerciales, y tampoco se abrió la SQLite existente. La suite JVM completa incluye pruebas optativas que pueden escribir por HTTP o usar servicios y datos locales; no se ejecutó en este entorno. El resultado no constituye validación end-to-end ni aprobación visual.

## 2. Inventario de versiones y disponibilidad

| Componente | Evidencia encontrada | Estado para UAT |
|---|---|---|
| Kotlin | Código KMP y resultado 05C-2 en `App Pedidos/KotlinProject`. El informe registra Gradle/JVM con JDK 21, 97 pruebas deterministas aprobadas y una compilación JVM. | Código local disponible; no se identificó una instancia UAT ni se ejecutó el flujo contra Sync. |
| Sync | Código Spring Boot en `Backend/effyFlow`. El POM declara `0.0.1-SNAPSHOT`, Spring Boot 3.5.16 y Java 25. | Esos datos describen el código fuente, no una instancia ejecutándose. No se identificó un proceso Sync local verificable. |
| ERP | No está el proyecto ERP ni el feed implementado 05A1B en este workspace. | Versión, endpoint, esquema y feed desplegado no verificables. El documento obligatorio `COT-MOBILE-05A1B-RESULTADO.md` no está presente. |
| PostgreSQL | Hay procesos PostgreSQL y un listener en `5432`, asociado al PID 7140; la consulta de procesos no permitió leer su ruta ni su directorio de datos. El puerto `55432` no tiene listener. | No se conectó a ninguna instancia. No es posible atribuir `5432` a una base UAT ni demostrar su aislamiento. |
| Sync HTTP local | El listener `8080` pertenece a `Tomcat8.exe` (PID 5144), no a una instancia Spring identificada. | No se consultó. No se puede tratar como Sync ni como UAT. |
| SQLite Kotlin | El código JVM usa `%LOCALAPPDATA%\EffyFlow\effy.db`; ese archivo existe en el perfil actual. | Se considera operativo/no desechable. No se abrió ni modificó. |
| PostgreSQL de 05B-2T | El resultado 05B-2T documenta PostgreSQL 17.5 aislado en `127.0.0.1:55432`, con bases `sync_cotizaciones_test` y `sync_cotizaciones_upgrade_test`. Informa que el clúster quedó detenido. | Evidencia válida de pruebas de backend realizadas entonces; no es un servicio actual ni contiene ERP/Kotlin. No se inició. |

La versión desplegada de ERP y Sync, sus hashes/builds, y la versión del feed no se pueden inferir de los fuentes ni de los resultados de pruebas anteriores. Se deben obtener de una instancia UAT identificada antes de validar rutas.

## 3. Aislamiento y configuración

La inspección fue de solo lectura. Se observó:

- PostgreSQL activo en `0.0.0.0:5432` y `:::5432`, sin poder identificar de manera fiable su propietario lógico o datos.
- `8080` ocupado por Tomcat8, sin Sync Spring identificado.
- Ningún listener en el puerto `55432` de la prueba aislada documentada en 05B-2T.
- La configuración fuente de Sync admite un archivo externo `.env.local`. Las propiedades contienen ajustes de BD, endpoint/credenciales ERP, identidad técnica de lectura, activación del lector, lease, colas/backpressure y claves de aserción. Los valores efectivos dependen del proceso y de su entorno; no se imprimieron ni se pudieron verificar.
- No se pudieron verificar la cuenta técnica de sólo lectura, el usuario/agente comercial, el permiso `cotizaciones.ver`, `kid`, issuer, audience, `SYNC_COTIZACIONES_READ_ENABLED` ni umbrales efectivos de backpressure.
- La configuración fuente tiene valores por defecto que apuntan a `jdbc:postgresql://localhost:5432/prueba` y deja deshabilitada la lectura si no se configura. Esto es sólo un valor de código: no se utilizó ni se afirma que sea la configuración efectiva de algún servicio.
- No se revelaron secretos, contraseñas, claves ni tokens.

Con estos hechos no hay evidencia suficiente para autorizar escrituras de UAT. No se usaron los puertos 5432, 8080 o 55432 ni se modificó configuración global.

## 4. Validación HTTP real

No ejecutada. No hay una instancia Sync identificada ni ERP 05A1B accesible en un entorno aislado verificable. Por tanto, no se validaron los códigos, DTO ni headers del despliegue, y no se puede afirmar que haya ocurrido alguno de estos pasos: JWT Sync, sesión con UUID persistente, resolución de agente/permisos, aserción RS256, lectura de ERP, bootstrap, snapshot, `SNAPSHOT_END`, ACK o entrega incremental.

Los tests unitarios del cliente y MockEngine registrados en 05C-2, y las pruebas de cliente/repositorio de 05B-2T, son evidencia focal de código. No sustituyen este recorrido real.

## 5. Casos comerciales

No se crearon ni cambiaron Cotizaciones. Quedan sin validar en UAT: creación, edición, emisión, cancelación, conversión a Pedido, reasignación, documento sin agente, pérdida de visibilidad del agente anterior y visibilidad del nuevo agente. No se modificó lógica comercial ni se accedió a usuarios o datos reales.

## 6. Persistencia, ACK y recuperación

No se probó el consumidor Kotlin contra páginas de un Sync real. Snapshot vacío/multipágina, `SNAPSHOT_END`, ACK perdido, caída antes/después del commit, reinicio de Sync, duplicados, versiones antiguas, tombstones, reset/generación, dos dispositivos y revocación durante una descarga siguen pendientes de integración.

El resultado 05C-2 informa pruebas locales deterministas con SQLite real temporal, incluyendo migración, persistencia, ACK y aislamiento, además de suites HTTP con MockEngine. El resultado 05B-2T informa nueve pruebas de integración sobre PostgreSQL aislado y una prueba independiente de migración v2. Ninguno ejercita conjuntamente ERP, Sync desplegado y Kotlin.

## 7. Autorización, revocación y modo offline

No se verificó con un usuario de UAT que Sync aplique el agente y `cotizaciones.ver`, ni que una revocación o cambio de usuario purgue/oculte el namespace correcto en una sesión real. Lease de 24 horas, renovación, vencimiento, pérdida temporal de red, retroceso de reloj, reconexión y purga selectiva no fueron validados end-to-end.

La conservación de capturas pendientes no se comprobó contra una base local operativa: esa base no se abrió. 05C-2 registra pruebas deterministas que preservan payload congelado/outbox; no prueba su coexistencia durante un recorrido real de logout, cambio de usuario y revocación.

## 8. Suite JVM completa

No se ejecutó de nuevo `:composeApp:jvmTest`. La suite contiene pruebas optativas que usan HTTP y configuración/servicios locales, incluidas pruebas de sincronización de pedidos y flujos de captura. La inspección de los runners muestra posibles llamadas a servicios y provisión de usuarios; correrlas sin aislar endpoint, base SQLite y cuentas incumpliría el requisito de UAT seguro.

El resultado 05C-2 registra una ejecución inicial de 117 pruebas: 105 aprobadas y 12 fallidas. Dos fallos nuevos fueron corregidos; en la ejecución focal posterior se aprobaron 97 pruebas deterministas. El informe deja 10 fallos de smoke/UAT existentes pendientes de repetición con servicios configurados. Los nombres registrados son `FirstSyncSmokeTest`, `LocalPedidoCaptureSmokeTest`, `OfflinePendingPedidosRealSyncTest`, `OfflineThreePedidosCaptureTest`, `PedidoSyncPostSmokeTest`, `PedidoSyncStatusSemanticsTest`, `PendingQueueFailureRecoveryTest` y `SegundoPedidoFullFlowTest` (algunos agrupan varios casos). Esa evidencia no permite asignar de forma concluyente la causa individual de cada fallo; deben clasificarse al repetirlos en UAT aislado con logs y configuración enmascarada. No se alteraron pruebas para obtener un resultado verde.

## 9. Android

No se encontró un SDK Android válido para este usuario. Según 05C-2, `local.properties` apunta a `<ruta local omitida>`, ruta que no existe en este equipo; tampoco se encontró `%LOCALAPPDATA%\Android\Sdk` ni `C:\Android\Sdk`. La compilación Android no se inició. No se cambió `local.properties` ni se configuró una ruta global. Android sigue pendiente.

## 10. Inspección visual

No realizada. No se inició la aplicación conectada a la SQLite existente ni se fabricó una identidad/sesión para mostrar documentos. No hay evidencia visual real de acceso a **Cotizaciones sincronizadas**, carga, lista/detalle, actualización manual, errores, lease vencido, revocación, reinicio requerido o saturación. La pantalla implementada que describe 05C-2 requiere inspección en una base de prueba y una sesión UAT aisladas.

## 11. Protección local

05C-2 informa que `effy.db` no tiene cifrado en reposo verificado y que SQLCipher permanece desactivado/comentado. No se activó cifrado ni se tocó el archivo existente. El bloqueo de acceso en el repositorio no equivale a cifrado del archivo SQLite. La protección local sigue siendo condición bloqueante para liberar a producción documentos comerciales accesibles en el archivo sin protección equivalente.

Antes de producción se necesita una fase específica para escoger almacenamiento cifrado compatible en JVM y Android, diseñar la migración recuperable, respaldar y verificar capturas/outbox pendientes, probar restauración/rollback y documentar la administración de claves. No se debe ejecutar esa migración sobre datos operativos como parte de esta UAT.

## 12. Defectos y correcciones

No se reprodujo un defecto de integración porque el recorrido real no pudo comenzar. No se realizaron correcciones en ERP, Sync ni Kotlin durante esta validación. Las correcciones locales previamente reflejadas en 05C-2 no se vuelven a atribuir a pruebas de esta fase.

## 13. Riesgos operativos

- Ejecutar llamadas o pruebas contra el PostgreSQL de 5432 podría afectar la base compartida `prueba` u otra instancia no identificada.
- El servicio en 8080 no es Sync verificado; usarlo podría enviar datos a una aplicación ajena al flujo.
- La SQLite JVM ya existe y puede contener datos locales; no se utilizó como fixture.
- Las variables y archivos de configuración efectivos del proceso no están disponibles para verificar autorización, firmas, endpoints o backpressure.
- El ERP y el resultado 05A1B están fuera del workspace; el contrato de feed real no puede contrastarse aquí.
- La ausencia de cifrado local impide tratar la validación de acceso funcional como seguridad en reposo.

## 14. Requisitos para reanudar UAT

1. Entregar o provisionar ERP de UAT con el feed 05A1B identificado por versión/build, y Sync 05B desplegado sobre PostgreSQL desechable propio. Confirmar por evidencia sus hosts, puertos, builds y bases antes de escribir.
2. Proveer una base SQLite temporal exclusiva de Kotlin y directorio de datos separado; verificar que no se resuelva a `%LOCALAPPDATA%\EffyFlow\effy.db`.
3. Configurar cuenta técnica de sólo lectura y un usuario/agente comercial de prueba con `cotizaciones.ver`; verificar de forma enmascarada RSA `kid`/issuer/audience, flag de lectura y umbrales de backpressure.
4. Confirmar que todas las identidades, Cotizaciones y Pedidos usados son fixtures de UAT, no datos ni cuentas reales; definir limpieza limitada al entorno desechable.
5. Ejecutar primero el recorrido HTTP completo y los cambios comerciales con los IDs de fixture; luego recuperación, aislamiento, offline, revocaciones y suite completa. Capturar status/DTO/header y secuencias sin tokens ni datos sensibles.
6. Proveer SDK Android compatible y, por separado, base de prueba para inspección visual JVM/Android.
7. Mantener pendiente la liberación productiva hasta resolver protección en reposo y demostrar recuperación sin pérdida de capturas.

Hasta satisfacer los puntos de aislamiento y versiones no se deben ejecutar migraciones, reinicios ni pruebas de escritura.

## 15. Archivos y cambios

Este entregable es el único archivo creado en esta fase. No se modificaron código, configuración, pruebas, bases SQLite/PostgreSQL, servicios ni datos comerciales. No se hizo commit, push ni despliegue. Los cambios preexistentes del workspace permanecen intactos.

## 16. Dictamen de integración

**05C-3 BLOQUEADO.** La evidencia disponible confirma pruebas unitarias/locales previas, pero no acredita un entorno aislado ni el recorrido ERP → Sync → Kotlin. No se declara validación end-to-end ni validación parcial de integración real.
