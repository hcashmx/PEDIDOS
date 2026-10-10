# PED-AUTH-OFFLINE-01 — Auditoría y contrato pendiente

Fecha: 2026-10-09. Entorno: desarrollo/UAT Windows. Base comercial: `adb117a`.

## Resultado y límite de esta fase

**El login offline solicitado no está implementado.** No existe un contrato de
provisión de credenciales locales seguras. Se detiene la implementación Kotlin
por la instrucción explícita de esta fase: si Sync necesita cambios para entregar
un verifier seguro, documentar y detener hasta definir el contrato. No se añadió
un endpoint de hashes ni se convirtió la caché de contraseñas en autenticación.
Se corrigió por separado la incompatibilidad de rol VENDEDOR en Sync.

## Causa del login remoto actual

- `LoginRepository.login` siempre hace POST `/api/auth/login` primero. Un HTTP
  no exitoso retorna el error del servidor inmediatamente. Sólo una excepción
  de conexión entra a `validateCredentials`; no es un login local primero.
- `AuthLocalDataSource.saveSession` escribe la contraseña recibida sin hash en
  `UserSession.password`; `validateCredentials` hace una comparación literal.
  Este almacenamiento existente no cumple el requisito de seguridad.
- `LoginViewModel` delega al repositorio. `LoginScreen` sólo llama a
  `onLoginSuccess` si existe `accessToken`, acoplando navegación y token remoto.
- `UserSession` guarda una sola identidad junto con password y tokens; no es
  una colección de usuarios autorizados con verificadores offline.
- `App` inicia en configuración de servidor o login; no restaura acceso a home
  automáticamente. Después del login consulta UserSession para obtener usuarioId.
- `clearSession` existe en el datasource, pero no hay una acción de logout
  conectada a esa función en la navegación actual.

## SQLite y sincronización inicial

`users.sq` contiene id, nombre, email, pass, roles, status, updated_at.
`SyncViewModel.saveUsuariosData` guarda `pass = ""`. Esa tabla es catálogo de
identidades, no autoridad para verificar contraseñas. No debe habilitarse un
login con contraseña vacía ni usar UserSession como sustituto de provisión.

GET `/api/sync/catalogos/usuarios` existe y requiere bearer autenticado. Su SELECT
devuelve id, nombre, email, role AS roles, status, updatedAt desde public.users.
**No devuelve password/verifier, no incluye active y no filtra usuarios con
autorización App Pedidos.** El filtro incremental no resuelve bajas ni permisos
offline por sí solo. La sincronización actual se abre después del login: falta
una provisión explícita accesible desde la pantalla inicial.

No se añadió una acción que aparente provisionar credenciales mediante ese
catálogo. La UI de primera ejecución sigue pendiente y deberá indicar:
«Se requiere conexión inicial para sincronizar usuarios», con acción explícita
de configurar/provisionar dispositivo cuando exista el contrato.

## Seguridad de credenciales

Sync usa Spring `BCryptPasswordEncoder`. ERP usa
`LaravelCompatiblePasswordEncoder`, también BCrypt, con soporte de prefijos
$2a$, $2b$ y $2y$. La existencia de un hash verificable en servidor no autoriza
su distribución a dispositivos. El catálogo actual correctamente no lo expone.

No se aprobó reutilizar ni exportar hashes ERP. Son material de autenticación
sensible y su copia multiplica exposición ante extracción de SQLite. Debe
definirse la estrategia de provisión antes de elegir algoritmo/librería Kotlin.
El servidor no puede generar un verifier independiente de la contraseña sólo
transformando el hash BCrypt existente; requiere una estrategia de enrolamiento
o una decisión explícita de distribución protegida. Ninguna se implementó aquí.

Pendiente obligatorio: migrar/eliminar `UserSession.password` heredado sin
convertirlo automáticamente en una autorización offline; también retirar el
fallback que lo compara. No se leyó ni imprimió ningún password/hash de SQLite,
ni se borraron datos del dispositivo UAT.

## Contrato mínimo a definir en Sync (propuesta, no endpoint implementado)

1. Autenticación y autorización explícitas para configurar el dispositivo;
   alcance de usuarios autorizados para App Pedidos y para ese dispositivo.
   Un vendedor no debe descargar hashes de todos los usuarios del ERP.
2. Estrategia de credencial offline aprobada: enrolamiento individual o provisión
   autorizada de verifier; algoritmo/formato/coste/salt/versionado y protección
   de transporte y almacenamiento. Prohibir texto plano y cifrado reversible.
3. Identidad estable usuarioId/email/nombre/roles; indicador inequívoco de
   autorización que considere active, status y permiso App Pedidos.
4. Revisión de credencial y autorización, snapshot consistente o cursor con
   tombstones/revocaciones; bajas y cambios de password deben actualizar la
   copia local incluso si el usuario ya no pertenece al conjunto activo.
5. Respuesta atómica y reglas de paginación/errores; una descarga parcial o
   fallida no debe borrar la última autorización válida ni habilitar usuarios.
6. Identidad/tokens para sincronización remota separados del login local;
   definir qué ocurre al renovar/reautenticar Sync y al cambiar usuario local.
   No confundir identidad técnica con propietario comercial de un Pedido.

El contrato debe quedar acordado y probado en Sync antes de implementar su
consumidor Kotlin. No reutilizar el catálogo general como API de hashes.

## Arquitectura objetivo (pendiente)

Inicialización online explícita → usuarios autorizados/verificadores persistidos
en SQLDelight → login siempre local → sesión de identidad local → sincronización
independiente con sus tokens remotos. Un password incorrecto no consulta Sync
como fallback. JWT vencido o red ausente no bloquean el login local.

Logout termina la sesión local y limpia lo correspondiente a tokens/identidad
según el contrato, conservando usuarios provisionados y cola de Pedidos. Una
sesión guardada y un token guardado no equivalen a validar una credencial local.
No se inventan expiraciones adicionales ni aceptación automática.

## Usuarios inactivos y cambio de contraseña (política requerida)

Después de una sincronización completa válida, una baja impide nuevos logins;
un cambio de contraseña reemplaza el verifier y rechaza la contraseña anterior.
Sin sincronización nueva, conserva la última autorización/credencial conocida,
incluido un usuario dado de baja en servidor. Esta política está definida por el
requerimiento, pero todavía no está implementada en Kotlin.

## Rol VENDEDOR

El rechazo original es POST `/api/auth/login`, HTTP 400, después de validar la
contraseña: `CustomUserDetails` llama `Rol.valueOf("VENDEDOR")` y el enum Sync
sólo conocía AGENTE, COBRANZA y ADMIN. No era una identidad incorrecta enviada
por Kotlin ni un 403 del catálogo. El mismo problema afectaba refresh y lectura
de usuario por el filtro JWT.

Sync ahora reconoce VENDEDOR con exactamente los permisos de AGENTE:
VER_MODULO_PEDIDOS, CREAR_PEDIDO, SINCRONIZAR_DATOS. No se cambia el rol en ERP
ni se otorgan permisos ADMIN. `/api/sync/**` exige autenticación; el
`@PreAuthorize` del catálogo está comentado, por lo que actualmente no exige
SINCRONIZAR_DATOS por método. La corrección de enum no implementa una política
de provisión ni restringe el catálogo general.

## Validación Windows y regresión

- Sync: 43 pruebas focalizadas (LoginServiceSecurityTest 6, SyncSecurityTest 12,
  JwtTokenProviderTest 5, PedidoEstadosServiceTest 4,
  PedidoControllerContractTest 8, PedidoErpAdapterTest 8), sin fallos.
- Build Sync: Maven package con JDK 25, exitoso.
- UAT real local mediante `Backend/effyFlow/scripts/check-ped-auth-vendedor-local.py`:
  usa credenciales existentes de `.env.local` sólo en memoria; login VENDEDOR
  200, refresh 200, catálogo usuarios 200 sin verifier, feed PED-SYNC-R1 200,
  password deliberadamente incorrecto 401. No inserta ni modifica datos ERP.
- Kotlin: 39 pruebas focalizadas comerciales/estados/tokens/renglones/crédito/
  cálculos, exitosas; build jvmJar con JDK 21 exitoso.
- No se modificó código Kotlin comercial, cola, uuidLocal, fecha comercial,
  diferencias ni cursor id_historial. No se volvió a crear un Pedido ERP en
  esta fase; regresión comercial por pruebas y feed real de lectura.

Los escenarios Windows de cerrar app, desconectar servidor, login local y
reinicio **no se certifican**: el contrato ausente impide implementar el nuevo
flujo. No se reinició el PC ni se simuló éxito con datos locales manuales.

### Matriz A–N solicitada

| Caso | Resultado en esta fase |
| --- | --- |
| A Primera ejecución sin usuarios | Auditada; mensaje/acción nuevos pendientes |
| B Sincronización inicial | Catálogo real 200; provisión de verifier bloqueada |
| C Usuario persistido | Catálogo SQLite existente; credencial segura pendiente |
| D Login offline correcto | Pendiente; fallback heredado inseguro no certificable |
| E Password incorrecto | Rechazo remoto real 401; rechazo sólo local pendiente |
| F Servidor caído no bloquea login | Pendiente |
| G Reinicio conserva usuarios | Nuevo flujo pendiente de prueba durable |
| H Usuario inactivo después de sync | Login/refresh Sync rechazan; offline pendiente |
| I Cambio de password después de sync | Política documentada; implementación pendiente |
| J JWT vencido no bloquea login local | Separación pendiente |
| K Sin password en texto plano | No cumple diseño heredado; migración pendiente |
| L Logout local | clearSession existe sin acción UI; pendiente |
| M Comercial después de login | Regresión automatizada; sesión nueva pendiente |
| N PED-SYNC-R1 | Regresión automatizada y feed real 200 |

## Git y próximos pasos

Se preservan los cambios ajenos en composeApp/build.gradle.kts y en
docs/12-manual-ejecucion-local.md. Commits separados para corrección Sync y
documentación Kotlin; sin push. No se usa un commit feat de login offline porque
esa funcionalidad no está implementada.

Antes de continuar login offline o iniciar Cotizaciones móviles: definir el
contrato seguro de provisión, resolver la migración de credenciales heredadas,
implementar sesiones separadas y validar A–N/Windows con servidor desconectado.
