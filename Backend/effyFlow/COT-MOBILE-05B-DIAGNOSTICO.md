# COT-MOBILE-05B — Diagnóstico de integración de estados de Cotizaciones

**Alcance:** auditoría técnica y propuesta. No se modificaron código, esquemas, configuración ni servicios; no se ejecutaron migraciones, pruebas ni despliegues. El único artefacto de esta fase es este diagnóstico.

## Dictamen

**Sync está parcialmente preparado, pero aún no está listo para implementar 05B.** Cuenta con autenticación JWT móvil, cliente HTTP al ERP, credenciales técnicas, configuración RSA/RS256 y una integración de Cotizaciones que ya revalida usuario/agente. Sin embargo, la aserción actual es exclusiva para `CREAR_COTIZACION`; no existe consumo del feed de estados, almacenamiento de proyección/cursor, reconciliador, ni permiso ERP `cotizaciones.ver` en la resolución de identidad existente. Tampoco existe una política de revocación de copias offline en Kotlin.

El contrato de ERP sí está documentado y desarrollado según `checkout ERP independiente\docs\cotizaciones\COT-MOBILE-05A1B-RESULTADO.md`. Esta propuesta conserva sus rutas, campos, aislamiento y reglas de cursor. Antes de programar se deben acordar el almacén persistente de Sync, el ciclo de vida de sesiones/dispositivos y la respuesta de revocación que consumirá Kotlin.

## 1. Arquitectura actual encontrada

- Backend monolítico Spring Boot 3.5.16, Java 25, Spring Web, Spring Security stateless, Spring Data JPA, JDBC y PostgreSQL. Hibernate tiene `ddl-auto=none`; las modificaciones de esquema existentes se entregan como scripts SQL versionados bajo `db/scripts`.
- La autenticación de la app es un JWT HS256 de Sync. El filtro vuelve a cargar el usuario por correo en cada petición; el token contiene correo y tipo, no un agente ni un dispositivo. `/api/sync/**` exige estar autenticado; los controladores comprueban además `SINCRONIZAR_DATOS` y autorización vigente donde corresponde.
- El cliente `ErpPedidoClient` usa `RestClient`, timeout de conexión/lectura configurables y redirecciones deshabilitadas. `ErpCapturaConfig` crea un cliente para Pedidos con `erp.*` y otro cualificado para Cotizaciones con `erp.cotizaciones.*`. El segundo actualmente permite fallback a `ERP_TOKEN`, `ERP_USERNAME` y `ERP_PASSWORD`.
- La cuenta técnica se autentica ante `POST /api/auth/login` con token configurado o username/password, y el token de sesión queda en memoria del cliente ERP. Ante 401, el cliente con credenciales vuelve a autenticar una vez. Los errores de login no reenvían cuerpo sensible. Los timeouts se convierten en 504. La ruta de captura Cotización ya adjunta `Authorization: Bearer ...` y `X-Cotizacion-Assertion`.
- `CotizacionIdentityAssertionService` carga una clave RSA PKCS#8 en Base64 desde configuración, exige RSA de al menos 2048 bits, y firma RS256 con `kid`, emisor, audiencia, `iat`, `exp` y `jti`. La configuración usa `app.cotizaciones.assertion.*`, vigencia actual por defecto de 60 segundos y una sola operación: `CREAR_COTIZACION`. Los valores por defecto de emisor/audiencia son `effyflow-sync` y `erp-cotizaciones`. La clave privada y credenciales están externalizadas por variables y `.env.local`; no se inspeccionaron valores secretos.
- La identidad de creación se resuelve desde el correo autenticado y consulta PostgreSQL ERP en línea: usuario activo, permiso efectivo `cotizaciones.crear` y exactamente un agente asociado. El agente enviado por el cliente sólo se acepta si coincide. El servicio de creación vuelve a validar la identidad antes de invocar ERP.
- Las revisiones comerciales de Cotizaciones se obtienen por HTTP desde ERP; Sync no persiste copias de Cotización ni realiza persistencia de esas respuestas.
- La sincronización de catálogos de Sync lee tablas de PostgreSQL directamente con `NamedParameterJdbcTemplate`, paginación `LIMIT/OFFSET`, y en algunos catálogos filtro incremental por `updated_at`. Los cambios de cursores SQL que existen para catálogos son timestamps de datos, no checkpoints del consumidor.
- Los estados de Pedido se exponen desde `GET /api/sync/pedidos/estados` consultando `pedidos_estados_historial`, con cursor numérico `afterId`, límite hasta 500 y orden por ID. Este flujo existente no tiene que modificarse ni reutilizarse para Cotizaciones.
- No se encontró en Sync tabla, entidad ni servicio para persistir cursores de integración ERP, snapshots de Cotización, estado de inicialización, eventos procesados o tombstones. El avance actual de Pedido corresponde al cliente/consumidor y no se puede asumir que sea un checkpoint server-side de Sync.
- El login devuelve `usuarioId`, `credentialVersion`, estado y `appAuthorized`; el JWT identifica al usuario por correo. Sync no mantiene registro ni binding entre un access token y `dispositivoId`. El campo opaco de dispositivo existe en la solicitud de captura y en la aserción de creación, pero no existe registro de dispositivos ni clave de estado por dispositivo.

## 2. Componentes reutilizables

1. **Cliente ERP HTTP:** reutilizar los timeouts, manejo de status, validación de URL, autenticación bearer y reautenticación técnica de `ErpPedidoClient`, pero encapsular la lectura de Cotizaciones en métodos GET dedicados. No extender el cliente compartido de Pedidos de forma que altere su comportamiento.
2. **Bean ERP de Cotizaciones:** reutilizar `erpCotizacionClient` y sus credenciales separadas. Para 05B, validar al iniciar/usar lectura que el username corresponda a la cuenta técnica indicada por ERP; evitar que el fallback genérico oculte una cuenta mal configurada.
3. **Firma:** reutilizar carga PKCS#8 y parámetros RSA, `kid`, `iss`, `aud` y vigencia de `CotizacionIdentityAssertionService`. Añadir una operación explícita de lectura (o un emisor separado) que genere una aserción nueva para cada petición y firme `operation=LEER_ESTADOS_COTIZACIONES` con `usuarioErpId`, `agenteErpId`, `dispositivoId`, UUID `jti`, timestamps, `sub` y el header `kid`. No incluir claims de creación ni hash/payload comercial. Mantener compatibilidad exacta con la política de ERP documentada.
4. **Identidad:** reutilizar como patrón la carga del usuario y la relación `users`–`agentes` en `SyncService`; crear resolución específica para lectura que exija usuario activo, rol vendedor/agente, permiso efectivo `cotizaciones.ver` y agente único. No aceptar `agenteId` de Kotlin para decidir acceso.
5. **Seguridad HTTP Sync:** reutilizar JWT, `SINCRONIZAR_DATOS` y la autorización de usuario activo que aplica el controlador Cotizaciones. La autorización efectiva `cotizaciones.ver` debe comprobarse además en cada inicio/continuación y antes de cada llamada a ERP.
6. **Persistencia y transacciones:** usar el `DataSource`/PostgreSQL de Sync con JDBC y transacciones. El esquema actual no contiene las tablas requeridas; se necesita propuesta y revisión de una migración futura. Mantener aislamiento de tablas y servicios separado del historial/cursor de Pedidos.

## 3. Brechas respecto a 05A1B

| Necesidad | Situación actual de Sync | Brecha |
|---|---|---|
| Llamar bootstrap/snapshot/estados | No hay métodos ni rutas de cliente para esos recursos | Implementar cliente GET con query exacta y header de aserción por petición |
| Aserción `LEER_ESTADOS_COTIZACIONES` | El emisor sólo crea `CREAR_COTIZACION` y exige `cotizaciones.crear` | Separar operación y resolver identidad con `cotizaciones.ver` |
| Cuenta técnica de transporte | Ya existe cliente dedicado, bearer y re-login | Confirmar configuración exclusiva y respuesta de errores técnicos sin filtrar secretos |
| Bootstrap y snapshot atómico por identidad | No existe estado local de inicialización | Agregar sesión de sincronización, paginador y recuperación durable |
| Cursor incremental por `eventId` visible | No existe cursor de este feed | Persistir literalmente `nextCursor`, sin calcular máximos ni incrementar |
| Deduplicación y orden por versión | No hay proyección ni ledger de eventos de Cotización | Agregar restricciones/operaciones transaccionales idempotentes |
| Revocación `visible=false` | No hay proyección ni tombstone | Retirar datos visibles y conservar marca de versión para evitar resurrección |
| Aislamiento usuario/agente/dispositivo | JWT sólo ata correo; `dispositivoId` no se registra | Definir clave de sesión y política de cambio de identidad/dispositivo |
| Revocación de permiso tras haber descargado datos | ERP devolverá 403 en la próxima llamada autorizada | Kotlin conserva copias offline hasta conectarse salvo política cliente/TTL aprobada |
| Contrato Kotlin | Endpoints actuales sólo cubren creación, revisión/aserción y Pedido | Definir API de inicialización, consulta, continuidad y revocación |

## 4. Modelo de persistencia recomendado

Separar una **sesión de sincronización** por identidad comercial y dispositivo, con una generación nueva si cambia el actor. Clave lógica mínima: `usuarioErpId + agenteErpId + dispositivoId`; incluir una `sessionId` UUID y estado `INICIALIZANDO/ACTIVA/REQUIERE_REINICIO/REVOCADA`. No usar correo como sustituto permanente del ID ERP. El dispositivo es una etiqueta declarada por el cliente, no una credencial fuerte; una decisión futura debe determinar cómo vincularlo con la sesión/token.

Tablas propuestas (nombres ilustrativos, no contrato cerrado):

- **sesion_sync_cotizaciones:** identidad comercial, `dispositivoId`, generación, `startCursor`, `maxCotizacionId`, `nextAfterId`, `eventCursor`, indicador de snapshot completo, estado, timestamps de auditoría y control de concurrencia. Restricción única de sesión activa para la tupla de identidad/dispositivo.
- **cotizacion_sync_estado:** proyección visible por `sessionId + cotizacionUuid`, `erpId`, estado, versión, `agenteId` visible, fecha/evento de última aplicación y marca `visible`. Nunca almacenar propietario nuevo a partir de una revocación; la revocación recibida tiene estado/agente nulos.
- **cotizacion_sync_evento_aplicado:** ledger por `sessionId + eventId`, con UUID, versión, estado/visibilidad y hash del contenido permitido. La clave única vuelve idempotente el replay. Puede combinarse con la proyección sólo si la regla de retención y auditoría queda definida.

Todas las escrituras de una página (ledger, proyección/tombstone y checkpoint) deben quedar en una transacción local. Se confirma el cursor sólo tras persistir durablemente la totalidad de eventos de la página. Si la aplicación falla, se revierte página y cursor; al reintentar, el mismo evento se vuelve a procesar de modo idempotente.

Para el snapshot inicial: crear sesión `INICIALIZANDO`; guardar el bootstrap recibido (`startCursor`, `maxCotizacionId`); aplicar cada página dentro de una transacción y actualizar `nextAfterId` a la respuesta `nextAfterId`; marcar `ACTIVA` únicamente al recibir `hasMore=false` en snapshot y haber persistido esa última página. Después consumir estados empezando exactamente en `startCursor`. El cursor incremental sólo se actualiza con `nextCursor` ERP al guardar la página.

Evitar que dos workers/device requests procesen simultáneamente una misma sesión mediante lock por `sessionId` o control optimista. Si se pierde/daña el estado local, crear una generación nueva y repetir bootstrap; no reiniciar usando `cursor=0` como reemplazo del snapshot.

## 5. Contrato propuesto para Kotlin

El API público es Sync → Kotlin; Kotlin no obtiene bearer técnico, aserción RS256, claves, username ni password del ERP. La forma concreta requiere acordar la política de `dispositivoId` y errores de sesión.

| Endpoint propuesto | Propósito y respuesta de alto nivel |
|---|---|
| `POST /api/sync/cotizaciones/estados/bootstrap` | Iniciar o recuperar inicialización de la identidad autenticada y el dispositivo. Sync comprueba `cotizaciones.ver`, ejecuta bootstrap ERP y devuelve `sessionId`, `status=INICIALIZANDO`, `startCursor`, `maxCotizacionId` y continuación de snapshot. No recibe `agenteId`. |
| `GET /api/sync/cotizaciones/estados/snapshot?sessionId=...&limit=...` | Entregar una página durable del snapshot de Sync para esa sesión: `documents[]` con `erpId`, `cotizacionUuid`, `estado`, `version`, `agenteId`, `updatedAt`; `nextAfterId`, `hasMore`, `initialized`. `limit` validado en rango 1–100 en la frontera Kotlin/Sync; los parámetros ERP siguen siendo exactos al contrato 05A1B. |
| `GET /api/sync/cotizaciones/estados?sessionId=...&limit=...` | Entregar estados incrementales de la proyección local posteriores al cursor durable de sesión: `events[]` con campos del feed ERP (`eventId`, `cotizacionUuid`, `erpId`, `estado`, `version`, `changedAt`, `eventType`, `agenteId`, `visible`), `nextCursor`, `hasMore`. Para `visible=false`, los datos ERP son los documentados con `estado=null` y `agenteId=null`; no se añade propietario. |
| `GET /api/sync/cotizaciones/estados/sesion` | Consultar `sessionId`, identidad efectiva no sensible, fase, si requiere resync/revocación y cursores confirmados; no devuelve credenciales ni token. Puede fusionarse con respuestas anteriores si se acuerda. |

La identidad de agente se deriva en Sync desde la sesión autenticada y ERP vuelve a verificarla con la aserción. `sessionId` y `dispositivoId` sólo localizan estado; nunca amplían autorización. Un `403` de ERP debe traducirse a error tipado `SIN_AUTORIZACION`/`SYNC_COTIZACIONES_REVOCADA`, suspender nuevas lecturas y activar la acción cliente de purga/ocultamiento definida abajo. Un `401` técnico puede provocar un solo login técnico nuevo y una repetición; un `401` de aserción requiere emitir aserción nueva, registrar diagnóstico sanitizado y limitar la repetición a una vez. `5xx`, 408/429 y fallas de red son reintentables con backoff exponencial y jitter, respetando `Retry-After` si está presente; no se confirma avance hasta procesar la respuesta. No se reintenta indefinidamente una configuración inválida.

Los errores Sync deben mantener un código estable, `reintentable`, etapa (`BOOTSTRAP`, `SNAPSHOT`, `ESTADOS`, `PERSISTENCIA`) y un identificador de correlación. Nunca incluir JWT, aserción, contraseña, clave ni el body sensible de autenticación.

## 6. Estrategia de seguridad y recuperación

### Aserción y transporte

- Por cada GET ERP, Sync resuelve nuevamente la identidad desde el principal y el estado ERP local; comprueba activo, rol y `cotizaciones.ver`, además de `SINCRONIZAR_DATOS` para invocación Kotlin.
- Emite una aserción RS256 con operación exacta `LEER_ESTADOS_COTIZACIONES`, `sub=usuarioErpId`, `agenteErpId`, `dispositivoId`, `iss`, `aud`, `iat`, `exp`, UUID `jti`, y `kid` acordados. Respetar el máximo ERP de 300 segundos y la clave pública/kid registrados. El read assertion no lleva claims propios de creación. Enviar en `X-Cotizacion-Assertion` y mantener bearer técnico separado.
- No usar token de usuario móvil como bearer al ERP; no entregar token técnico ni aserción a Kotlin. No aceptar `agenteId` cliente ni cursor para seleccionar identidad. El cursor sólo afecta posición y ERP ya acota visibilidad por la identidad firmada.
- La cuenta técnica debe ser la configurada específicamente y coincidir exactamente con `COTIZACIONES_SYNC_ERP_TECHNICAL_USERNAME` en ERP. Revisar la cadena de fallback de `erp.cotizaciones.*`; si los entornos comparten intencionalmente credencial, documentar la decisión fuera del código.

### Paginación y cursores

- ERP: `limit` entre 1 y 100. Bootstrap devuelve `startCursor` y `maxCotizacionId` tomados del corte documentado. Snapshot usa `afterId`/`maxCotizacionId` hasta `hasMore=false`; después estados arranca en `startCursor`.
- Incremental: consumir `events` en orden y persistir exactamente el `nextCursor` devuelto. `nextCursor` es el último evento visible de esa página, no el máximo global. No sumar uno, saltar huecos, usar timestamps ni avanzar ante página vacía. Si la respuesta trae `events=[]`, mantener el cursor confirmado que indique ERP y terminar la vuelta cuando `hasMore=false`.
- Validar progreso: con eventos, `nextCursor` debe ser coherente con la página conforme al contrato; si `hasMore=true` y no hay evento/progreso, detener, no hacer loop cerrado, dejar cursor intacto y emitir alerta/reintento acotado. Mismatch de respuesta se trata como error de protocolo.
- Cada GET incremental puede llevar cursor ERP; una aserción nueva por petición. Repetir GET es seguro; el consumidor debe asumir páginas repetidas tras pérdida de respuesta.

### Versiones, duplicados y tombstones

- Deduplicar `eventId` dentro del `sessionId`/ámbito comercial. Un `eventId` repetido con el mismo contenido permitido es no-op. Si el mismo ID trae contenido distinto, detener la sesión y requerir resync/alarma de integridad.
- Por UUID, aplicar sólo una versión estrictamente superior a la guardada. Versión menor es evento fuera de orden/repetido y se ignora sin regresión; misma versión y mismos estado/visibilidad/datos permitidos es repetición; misma versión con estado o visibilidad contradictorios es conflicto de integridad y exige resync, sin sobrescribir.
- En `visible=false`, quitar/ocultar el documento y guardar tombstone con UUID y la versión recibida (y `eventId`) sin guardar ni devolver un nuevo agente o estado del propietario siguiente. Rechazar eventos antiguos cuya versión sea menor que el tombstone. Un snapshot nuevo sólo podrá reponer la cotización dentro de una nueva o reconciliada sesión tras bootstrap autorizado.
- `visible=true` posterior puede hacer visible el documento al agente autorizado destinatario, pero no revela la reasignación anterior. La política y versiones se aplican por sesión/identidad para no trasladar datos entre agentes.

### Permiso revocado y copias offline

ERP impide lecturas futuras cuando el usuario está inactivo, pierde `cotizaciones.ver` o cambia de relación comercial; Sync debe detenerse ante 401/403 apropiado y marcar la sesión `REVOCADA` o `REQUIERE_REINICIO`. Una sesión nueva para otro usuario/agente/dispositivo no debe consultar la proyección anterior.

La copia previamente descargada en un dispositivo offline no puede borrarse remotamente mientras no contacte Sync. En el siguiente contacto, Kotlin debe ocultar/revocar el área local ante respuesta `SYNC_COTIZACIONES_REVOCADA`, borrar las Cotizaciones y tombstones de esa sesión cuando la política aprobada lo ordene, y no presentar datos de una sesión anterior tras cambio de usuario. Se debe definir antes de implementar si se purga inmediatamente ante 403, qué pasa con datos pendientes de sincronizar, y si hay una vigencia máxima offline/cache. El backend por sí solo no puede hacer cumplir una purga en un dispositivo desconectado.

El token JWT actual no está ligado a `dispositivoId`; este último es autodeclarado. Para evitar reutilizar sesiones accidentalmente, Sync debe exigir que cada petición lleve un dispositivo identificador y vincularlo a la sesión del actor. Decidir si basta el identificador opaco o si Kotlin/Security entregará un binding más fuerte. Un mismo usuario con dos dispositivos debe tener sesiones independientes y checkpoints independientes.

## 7. Matriz de pruebas necesaria

| Caso | Verificación principal |
|---|---|
| Bootstrap vacío | Fija corte/cursor; snapshot vacío finaliza; inicialización queda activa y el incremental parte de `startCursor`. |
| Bootstrap con documentos | Sólo documentos visibles para identidad y snapshot del ERP se conserva completo. |
| Snapshot de varias páginas | `afterId` y `maxCotizacionId` exactos; recuperación tras caída no duplica ni omite; marca activa sólo al final durable. |
| Evento posterior al bootstrap | Evento con `eventId > startCursor` aparece tras completar snapshot. |
| Reasignación entre agentes | Agente anterior recibe revocación nula y quita copia; agente nuevo recibe estado completo; no filtrar propietario nuevo. |
| Documento sin agente y posterior asignación | Creador ve antes conforme a ERP; luego la revocación/asignación no filtra datos ajenos. |
| Versiones duplicadas y fuera de orden | Deduplicación por `eventId`; sólo aumenta versión; repetición no-op y contradicción misma versión se detiene. |
| Mismo evento recuperado después de reinicio | Ledger y proyección hacen no-op; cursor ya confirmado sigue durable. |
| Página incremental vacía | Cursor no se incrementa ni salta al máximo global; continúa en la posición recibida. |
| Rollback/interrupción durante persistencia | Ni proyección ni checkpoint parcial quedan confirmados; replay repara. |
| Cursor que no puede avanzar | No hay loop caliente ni avance artificial; se registra error de protocolo y se conserva cursor. |
| Firma inválida o caducada | 401 clasificado; aserción nueva en un retry limitado; nunca usar aserción de creación. |
| Usuario inactivo o sin `cotizaciones.ver` | 403, ninguna lectura/persistencia nueva, sesión suspendida y respuesta tipada a Kotlin. |
| Cambio de agente/usuario/dispositivo | No reutiliza sesión, cursor ni proyección; resync aislado. |
| Aislamiento entre agentes y dispositivos | Prueba cruzada: feed, sesión, snapshot, tombstones y respuestas no contienen datos de otra identidad/sesión. |
| Error de cuenta técnica | 401 técnico provoca sólo re-login limitado; secreto/cuerpo de login nunca se entrega a Kotlin/logs. |
| Reintentos de red/ERP | Backoff, misma posición al fallar, sin perder ni duplicar aplicación durable. |
| Regresión de Pedidos | Contratos, cursor y endpoint `GET /api/sync/pedidos/estados` siguen idénticos; no se comparten tablas/checkpoints ni se cambia su comportamiento. |
| Payload de captura de Cotización | Contrato comercial aprobado permanece intacto; la API de estados no agrega campos a la captura. |

## 8. Archivos que probablemente requerirán cambios

Los siguientes son candidatos para una fase de implementación; ninguno se modificó en esta auditoría.

- `src/main/java/com/cyssoftware/effyFlow/service/pedido/ErpPedidoClient.java`: cliente GET Cotizaciones para bootstrap, snapshot y estados, query params exactos y header de aserción por solicitud. Procurar aislar la extensión para no alterar Pedidos.
- `src/main/java/com/cyssoftware/effyFlow/config/ErpCapturaConfig.java` y `src/main/resources/application.properties`: configuración de clave/`kid`/issuer/audience y credenciales técnicas de lectura, sólo si el contrato de despliegue requiere separación; mantener secretos fuera del repositorio.
- `src/main/java/com/cyssoftware/effyFlow/service/cotizaciones/CotizacionIdentityAssertionService.java` o nuevo servicio de lectura: aserción de operación `LEER_ESTADOS_COTIZACIONES` y resolución específica de `cotizaciones.ver`.
- `src/main/java/com/cyssoftware/effyFlow/service/pedido/SyncService.java` o nuevo `IdentidadCotizacionesReadService`: consulta segura del actor y permiso efectivo; evitar modificar funciones de Pedido/catálogos más allá de añadir una ruta de identidad aislada.
- Nuevos servicios/modelos/repositories bajo `service/cotizaciones`, `repository` o `dto`: cliente ERP feed, sesión, bootstrap/snapshot, proyección/ledger, conciliación versionada, clasificación de error y DTOs de Kotlin.
- `src/main/java/com/cyssoftware/effyFlow/controller/cotizaciones/CotizacionSyncController.java` o un controlador dedicado de estados: API autenticada para Kotlin con protección, validación de sesión/dispositivo y errores tipados.
- Nuevo script SQL bajo `db/scripts` para sesiones, proyección/tombstones, eventos aplicados e índices/constraints. Se deberá aprobar y ejecutar en otra fase; Hibernate está configurado con `ddl-auto=none`.
- Nuevas pruebas en `src/test/java/.../service/cotizaciones`, tests HTTP del cliente ERP/controlador, persistencia transaccional y regresiones de contrato existentes.
- Documentación de API Sync → Kotlin y variables por entorno una vez resueltas las decisiones operativas.

No deberían cambiarse `PedidoEstadosService`, `PedidoEstadosController`, historial/cursor de Pedidos ni el payload de captura comercial de Cotizaciones.

## 9. Riesgos y decisiones pendientes

1. **Almacén de estado:** aprobar proyección/checkpoints en PostgreSQL de Sync y política de retención; hoy no hay tablas ni checkpoints server-side.
2. **Identidad/dispositivo:** definir cardinalidad usuario–agente, si un usuario puede tener varios agentes, cómo detectar cambio de asociación y cómo vincular `dispositivoId` a sesión/JWT.
3. **Revocación offline:** decidir purga y comportamiento de Cotizaciones pendientes cuando Kotlin reciba 403, y vigencia permitida sin conexión. La revocación inmediata de una copia totalmente offline no es técnicamente posible.
4. **Permisos:** confirmar que `cotizaciones.ver` es el permiso ERP que habilita el feed y que los usuarios móviles que lo consuman lo tendrán efectivamente; Sync hoy sólo comprueba `cotizaciones.crear` para integración de captura.
5. **Aserción/llaves:** confirmar que el `kid`, emisor, audiencia y par de claves desplegados coinciden con configuración de ERP; confirmar si se comparte clave de integración APP existente o se emite un par específico para lectura. La documentación ERP pide misma integración APP, pero su despliegue real debe verificarse.
6. **Cuenta técnica:** confirmar que Sync usa exactamente el username técnico ERP configurado y resolver fallback actual entre credenciales genéricas y Cotizaciones.
7. **Concurrencia:** decidir si Sync corre como una instancia o varias; si hay varias, usar bloqueo/transacción distribuida a través de PostgreSQL y leases para evitar dos consumidores de una sesión.
8. **Errores/contrato Kotlin:** acordar nombres/status de sesión, cómo iniciar explícitamente una generación nueva y cómo expresar revocación, cursor atascado, integridad y resync requerido.
9. **Visibilidad durante snapshot:** ERP describe visibilidad en cada página y cubre cambios concurrentes vía feed posterior a `startCursor`; Sync debe retener todos los eventos desde el corte hasta completar snapshot. Confirmar que no haya truncamiento/retención del outbox durante una inicialización prolongada.
10. **Operación/observabilidad:** acordar retención de ledger/tombstones, alerta de cursor inmóvil, métricas y límites de backoff; logs sólo con IDs de correlación/evento y nunca tokens.

## 10. Plan de implementación por etapas

1. **Cerrar decisiones de seguridad y contrato:** confirmar `cotizaciones.ver`, username técnico exacto, `kid`/llave/issuer/audience, binding de dispositivo, política de revocación offline y contrato Kotlin.
2. **Diseñar persistencia:** DDL revisable para sesión, cursores, snapshot, proyección, ledger y tombstones; claves únicas, transacciones y estrategia multi-instancia. No tocar Pedidos.
3. **Implementar identidad y aserción read-only:** validar usuario/permiso/agente en cada invocación y firmar operación exacta de lectura con configuración segura. Verificar unitaria y contractualmente encabezado/claims sin exponer secreto.
4. **Implementar cliente feed:** bootstrap, snapshot y estados con query params definidos en 05A1B, cuenta técnica, retry acotado para auth, timeouts y backoff para fallas transitorias.
5. **Implementar máquina de estados durable:** bootstrap → todas las páginas snapshot → incremental desde `startCursor`; aplicación transaccional, checkpoint posterior al commit, dedupe por `eventId`, comparación de versión y tombstone.
6. **Publicar API Sync → Kotlin:** endpoints de inicio, páginas de snapshot, eventos incrementales, estado de sesión y errores/revocación; sin secretos ERP ni cambios al payload de captura.
7. **Validar con pruebas y ERP de prueba:** ejecutar la matriz de la sección 7 con identidades aisladas y cortes/reinicios. Comprobar que las respuestas siguen exactamente el contrato aprobado.
8. **Regresión y habilitación operativa:** ejecutar regresiones existentes de Sync/Pedidos, revisar diferencias/esquema y documentar configuración; despliegue queda fuera de esta fase y requerirá autorización propia.

**Conclusión:** Sync tiene bases reutilizables para HTTP, autenticación técnica, RS256 y resolución de identidad, pero requiere nuevas capacidades de lectura, persistencia, reconciliación y revocación. El dictamen es **PARCIALMENTE PREPARADO; no iniciar implementación hasta resolver decisiones 1–6 de la sección 9 y aprobar el modelo de sesión/retención**. El contrato ERP requerido está identificado en `checkout ERP independiente\docs\cotizaciones\COT-MOBILE-05A1B-RESULTADO.md`; no se necesita inventar ni rediseñar sus campos.
