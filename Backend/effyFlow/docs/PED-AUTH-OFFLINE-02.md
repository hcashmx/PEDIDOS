# PED-AUTH-OFFLINE-02 — Provisión sin credenciales ERP

Entorno desarrollo/UAT. Aplicar primero la migración ERP
`V20261009_02__usuarios_version_credencial_offline.sql` en la base compartida.
Sync no administra esa migración ni modifica contraseñas del ERP.

## Catálogo

GET `/api/sync/catalogos/usuarios?page=0&size=500`, bearer access de Sync.
Campos: id, username, nombre, email (compatibilidad del catálogo), roles, active,
appAuthorized, credentialVersion, status y updatedAt. Nunca password, hash,
verifier local ni remember_token.

Es un **snapshot completo paginado**: usuarios ignora updatedAfter. El consumidor
descarga todas las páginas y aplica una transacción local sólo si la descarga es
completa. Incluye usuarios inactivos del conjunto autorizado para comunicar bajas;
un usuario eliminado o que pierde el rol desaparece del snapshot e invalida su
autorización local anterior.

Autorización: role legacy en AGENTE/VENDEDOR/ADMIN **y** rol real asignado mediante
users.id_rol → roles.id cuyo nombre sea AGENTE, VENDEDOR, ADMIN o super_admin.
No basta role=VENDEDOR: ERP usa ese valor también para Doctor/panel_user. No se
otorgan permisos ERP ni se incorporan perfiles desconocidos automáticamente.

El solicitante del catálogo necesita SINCRONIZAR_DATOS y pertenecer al conjunto
autorizado con active=true y status distinto de INACTIVO. Un perfil ajeno recibe
403, aunque su columna legacy diga VENDEDOR. Sin bearer válido: 401.

## Enrolamiento

POST `/api/auth/login` acepta username/password; conserva email/password para
compatibilidad. Busca username exacto y luego email. La contraseña se verifica
contra BCrypt en servidor usando el flujo normal existente.

La respuesta agrega username, active, appAuthorized y credentialVersion a la
identidad y tokens existentes. La versión se lee junto al hash en la misma entidad
Usuario. Sólo el cliente que autenticó correctamente conoce la contraseña y puede
crear su verifier independiente. Sync nunca recibe salt/verifier locales.

Los tokens son autenticación remota separada; no habilitan por sí solos login
offline ni se reutiliza el hash ERP como credencial local. VENDEDOR mantiene los
permisos AGENTE del commit 20ef97d, sin permisos administrativos.

## Versión

public.users.credential_version es bigint, NOT NULL, inicial 1. El trigger ERP
incrementa ante cualquier reemplazo de password y evita sobrescribir el contador
desde un UPDATE de perfil. active/status comunican autorización por separado.
updatedAt no se usa como versión de credencial.

## Validación

49 pruebas focalizadas Sync y Maven package aprobados; incluyen username,
metadatos sin secretos, filtro por rol real, rechazo de perfil ajeno, VENDEDOR,
refresh, contratos comerciales y feed R1. Script de lectura local:
`scripts/check-ped-auth-vendedor-local.py`.

La UAT Windows y evidencia de SQLite/ERP se documentan en
`App Pedidos/KotlinProject/docs/PED-AUTH-OFFLINE-02.md`. No push.
