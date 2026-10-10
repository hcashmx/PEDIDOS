# PED-AUTH-OFFLINE-02 — Provisión segura y login local offline

Desarrollo/UAT Windows, 2026-10-09/10. Base comercial adb117a; corrección VENDEDOR
20ef97d. SQLite/SQLDelight y PED-COM-SYNC-04 se conservan.

## Diseño implementado

1. Configurar URL Sync. Sin catálogo, login muestra «Se requiere conexión inicial
   para sincronizar usuarios»; la acción Sincronizar usuarios usa credenciales
   online autorizadas introducidas en el formulario para descargar el catálogo.
2. Descargar únicamente identidades/autorización/versiones. Este paso no enrola
   usuarios ni habilita credenciales offline de otras personas.
3. Cada usuario no enrolado hace su primer login online en ese dispositivo.
   Kotlin verifica la respuesta autenticada y genera un verifier local independiente.
4. Con verifier vigente, ingresar siempre compara localmente, incluso con servidor
   disponible. Contraseña incorrecta no hace fallback remoto. La URL ni siquiera se
   resuelve para decidir un login local. No hay expiración adicional de autorización.
5. Sincronización remota y renovación de tokens ocurren sólo al solicitar sync.

## Catálogo y autorización

GET `/api/sync/catalogos/usuarios`: id, username, nombre, email, roles, active,
appAuthorized, credentialVersion, status, updatedAt. Sin password/hash/verifier.
Snapshot completo paginado; no se usa updatedAfter para usuarios. Todas las páginas
deben descargarse antes de sustituir la autorización local en una transacción.
Una descarga parcial, inválida o fallida conserva la última autorización válida.

Sync filtra role legacy AGENTE/VENDEDOR/ADMIN y además el rol real de id_rol,
con nombres AGENTE, VENDEDOR, ADMIN o super_admin. Este segundo control es necesario:
ERP también guarda role=VENDEDOR en perfiles Doctor/panel_user. Esos perfiles no
provisionan ni aparecen como autorizados. Roles personalizados distintos requieren
una definición funcional explícita; no se autoriza por el valor legacy solamente.

Los inactivos del conjunto autorizado se incluyen con active=false para comunicar
bajas. Ausencia del snapshot (eliminación/pérdida de rol) también revoca acceso.
Username es canónico y se valida de forma sensible a mayúsculas; el login Sync
también mantiene la compatibilidad de email. En la app se usa el username mostrado.

## credentialVersion

No existía un equivalente independiente fiable. ERP incorpora
public.users.credential_version bigint, inicial 1, actualizado por trigger si
password cambia. Cambios de perfil/active no lo incrementan; un reset que genera
otro hash sí. Un UPDATE no puede sobrescribir arbitrariamente la versión.

Sync entrega sólo ese contador, leído junto a la credencial al autenticar. No se
usa updated_at como versión y no se exporta BCrypt. Migración ERP debe aplicarse
antes de arrancar el nuevo Sync. En UAT local quedó aplicada.

Descargas y enrolamiento comparten un mutex para impedir que una respuesta
concurrente restaure autorización antigua. Versiones que retroceden se rechazan
sin sobrescribir el último catálogo.

## Enrolamiento y algoritmo/verifier

POST Sync `/api/auth/login` con username/password sólo cuando falta verifier.
La respuesta debe confirmar userId/username, active, appAuthorized, rol y versión.
La contraseña permanece en memoria únicamente para enviar esa autenticación y
generar la KDF; nunca se persiste ni se registra.

PBKDF2-HMAC-SHA256 mediante SecretKeyFactory JVM, 600.000 iteraciones, salt aleatorio
SecureRandom de 32 bytes y salida de 256 bits. Comparación MessageDigest.isEqual de
bytes de longitud fija. Salt/verifier se representan en hex en SQLite; el formato
almacena iteraciones y enrolledAt. No hay criptografía propia ni hash rápido directo.

Se eligió PBKDF2 porque está disponible en la JVM Windows empaquetada sin incorporar
una dependencia Argon2id ni DLL nativa adicional. Parámetro contrastado con
[OWASP Password Storage](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html).
Los buffers char temporales se limpian; los String de UI/HTTP viven en memoria
administrada y no pueden borrarse de forma garantizada. Se vacía el formulario al
entrar. Nunca se registra password, verifier, salt ni tokens.

Autenticación/provisión requiere HTTPS; HTTP se permite sólo para localhost,
127.0.0.1 y ::1 en UAT. No se cambia el transporte comercial existente.

## SQLite y sesión local

- OfflineUser: userId, username, nombre/email, roles, active, credentialVersion,
  salt, verifier, iterations, enrolledAt.
- LocalSession: identidad autenticada localmente y authenticatedAt.
- SyncCredentials: accessToken/refreshToken por userId, independientes de la sesión.
- users continúa como catálogo de identidad para captura de Pedidos, con pass=NULL.
- app_config conserva señal de catálogo provisionado y último username, sin password.

La sesión local se valida mediante usuario activo y verifier vigente; el join con
tokens es opcional. Un JWT vencido o inexistente no impide acceder. Al abrir la app
se exige nuevamente contraseña local; no se restaura automáticamente home sólo
porque exista una fila de sesión o un token guardado.

Logout elimina únicamente LocalSession. Conserva catálogo, verifier y tokens
remotos por usuario para permitir nuevo login offline y posterior sync. Cambiar
usuario local recupera sólo los tokens de esa identidad. Los callbacks de refresh
quedan ligados al userId original: una respuesta tardía no puede asignar tokens
al siguiente usuario; hay una prueba explícita de este caso. «Desvincular dispositivo»
queda como opción futura, sin implementar ni borrar datos automáticamente.

## Eliminación del password legacy

Migración más simple y segura: descartar la caché heredada y exigir enrolamiento
online. No se transforma el texto legacy en autorización nueva.

LocalSchemaMigrations crea las tablas nuevas, habilita secure_delete, limpia
users.pass y elimina UserSession completo (incluidos password y tokens viejos).
Si había UserSession, hace checkpoint WAL y VACUUM. Es repetible y falla ante un
error real de migración; no habilita acceso usando datos descartados.

Prueba explícita sobre SQLite de archivo verifica ausencia de tabla/password,
ausencia de los bytes del password sintético y conservación íntegra de un Pedido
en cola. Esto no elimina backups externos que el usuario haya creado anteriormente.

## Usuario inactivo y cambio de contraseña

active=false o pérdida de rol: revoca verifier/tokens y elimina sesión local.
La app detecta una sesión revocada y vuelve a login, con revisión cada segundo.
Si el usuario ya no puede autenticarse en Sync, otro usuario autorizado puede
actualizar el catálogo desde la acción de provisión; una descarga rechazada no
se interpreta como una baja recibida.

Versión N→N+1: elimina verifier y sesión. El siguiente login requiere conexión y
la nueva contraseña. Sólo después de autenticarla se genera otro salt/verifier.
La contraseña anterior deja de entrar localmente. No se actualiza un verifier
sin conocer la nueva contraseña ni se reutiliza el hash del servidor.

Mientras no haya una sincronización válida nueva, offline conserva la última
credencial/autorización conocida, incluso si hubo cambios o baja en servidor.

## Observabilidad

Eventos sin secretos: LOCAL_LOGIN_SUCCESS, LOCAL_LOGIN_FAILURE,
ONLINE_ENROLLMENT_REQUIRED, CREDENTIAL_VERSION_CHANGED, USER_DISABLED.
No hay logging de cuerpos de autenticación. Las pruebas de HTTP local verifican
que tanto acierto como fallo de contraseña enrolada generan cero requests.

## Regresión Pedidos

El catálogo de identidad se mantiene para NuevoPedido; usuario local conserva su
id. No se modifica PedidoRepository, cola comercial, UUID, fecha con offset,
payload estable, reglas de diferencias, expansión de Kits ni feed PED-SYNC-R1.
SyncViewModel usa la nueva descarga completa para usuarios; el resto de catálogos
y contratos comerciales se conservan.

Las pruebas históricas de UAT ya no leen passwords SQLite para refrescar tokens:
usan updateTokens o EFFY_TEST_PASSWORD explícita para una ejecución opt-in.
No se ejecutan smoke tests que dependen del SQLite personal del usuario.

## Pruebas

53 pruebas Kotlin focalizadas: 13 OfflineAuthenticationTest y 40 comerciales,
estados, tokens, renglones, crédito y cálculos. Cubren catálogo/enrolamiento,
KDF aleatoria/Unicode, cero tráfico en login local, contraseña incorrecta,
JWT ausente/vencido, reapertura SQLite, revocaciones, cambio de versión,
reenrolamiento, logout, legado, transporte y captura offline.

49 pruebas Sync focalizadas y package, más CredentialVersionPostgresTest ERP
aislado, aprobados. Build Windows createDistributable aprobado; no Android SDK
disponible y no se certifica Android en esta fase.

La UAT real usa `scripts/run-ped-auth-offline-02-uat.py --interactive` y
OfflineAuthenticationLocalUatTest. Usuario temporal creado/reset/deshabilitado
sólo mediante API ERP; credenciales permanecen en memoria. El ejecutable Windows
usa LOCALAPPDATA temporal para aislar SQLite, sin alterar el dispositivo personal.
El controlador opera únicamente su propia ventana, captura evidencia y deshabilita
su cuenta al terminar. No se insertan documentos ERP manualmente.

## UAT Windows real

Ejecutado con el distributable Windows y SQLite aislado: provisión online,
enrolamiento, cierre, Sync detenido (sin listener 8082), reapertura y login local
correcto. Logout y contraseña incorrecta rechazados offline. Dos verificaciones
con reapertura del archivo confirman cero solicitudes HTTP y verifier durable.

Al recuperar Sync, el Pedido capturado offline fue aceptado como id ERP 114,
folio 61, FACTURA/CONTADO, total 176.679600, estado RECIBIDO. Conservó UUID
6dd7a518-734d-42c7-9e77-27352878dbd9 y fecha comercial original con offset
2026-10-10T00:02:02.573709600-06:00. PostgreSQL confirmó estos datos; el feed
PED-SYNC-R1 devolvió historial 113 y el cursor local quedó en 113.

Otra cuenta temporal, creada únicamente mediante API ERP, confirmó cambio real
de password: credentialVersion 1→2, revocación, rechazo de contraseña anterior,
reenrolamiento con la nueva y posterior login local. Su baja real invalidó sesión
y acceso. Las cuentas temporales quedaron inactivas; los Pedidos UAT se conservan.

Evidencia fuera del repositorio:
`C:/Users/hcash/AppData/Local/Temp/PED-AUTH-OFFLINE-02-k00gsk7u/`,
con evidence.json, credential-evidence.json y capturas catalog_safe.png,
enrolled.png, offline_home.png e incorrect_password_offline.png.
El escenario de versión/baja se reproduce con
`scripts/check-ped-auth-version-local.py`.


## Pendientes y límites

Tokens remotos siguen persistidos en SQLite por usuario, con el mecanismo existente;
almacenamiento protegido por el SO/DPAPI y desvinculación explícita quedan fuera de
esta fase. El verifier limita ataques a contraseñas ante extracción; no sustituye
la protección del dispositivo ni firma la base contra alteraciones locales.

Android y políticas adicionales (expiración offline, rate limit local, otros roles)
requieren una fase específica; no se inventan aquí. Cotizaciones no se mezclan con
Pedidos. Antes de incorporarlas, conservar esta separación de identidad local,
tokens y autorización remota. Validación humana de ergonomía queda disponible;
no se declara PASS_MANUAL del usuario a partir de automatización.
