# PED-COM-SYNC-03 — Delegar captura móvil al ERP

Desarrollo/UAT. Contrato auditado directamente en `C:\proyecto 2025\administrativo`, commit `b8a22e78ea00a5358889113cf06e872ee740f5e1`. Kotlin no se modificó. En ERP se corrigió una incompatibilidad real del preview con promociones: `PedidoCapturaService.previsualizar` ahora abre transacción de sólo lectura para evitar LazyInitializationException al acceder a productos de la promoción. Los cambios preexistentes en el Gradle Kotlin y el manual raíz permanecen fuera de esta tarea.

## Arquitectura

Antes: `POST /api/sync/pedidos` → PedidoService → transacción PostgreSQL, asignación de folio, escritura de cabecera/detalles, snapshot de dirección y cálculo de impuestos en Sync. El UUID existente se devolvía sin comparar la captura.

Ahora: Kotlin → mismo endpoint Sync autenticado → validación de forma/mapeo → cliente autenticado → `POST /api/pedidos/captura` → PedidoCapturaService ERP → respuesta Sync. ERP decide precios, volumen, promociones, descuentos, impuestos, snapshots, composición de Kits, folio e historial. Sync conserva lecturas de catálogos y PED-SYNC-R1.

Se elige B: create directo. El ERP evalúa referenciaApp antes de persistir/asignar folio y devuelve 409 con PedidoCapturaPreviewResp si difiere. Hacer preview antes de cada create duplicaría el resolver sin evitar cambios entre llamadas. El cliente ofrece preview opcional; no se agrega una llamada ni un endpoint móvil obligatorio. El test real usa preview únicamente para construir referencias de fixture y comprobar el motor ERP.

## Contrato Kotlin → Sync → ERP

Fuentes Kotlin auditadas: `PedidoConDetallesDTO.kt`, `SyncViewModel.kt` (sincronizar pedidos), `nuevoPedido/PedidoRepository.kt`, `RenglonFiscalSnapshot.kt` y `PedidoCalculos.kt`.

Entrada conserva `{pedido:{...}, detalles:[...]}` y nombres snake_case.

| Kotlin/Sync | API ERP | Tratamiento |
| --- | --- | --- |
| pedido.uuid_local | uuidLocal | UUID canónico obligatorio; se transmite el mismo valor en cada envío |
| cliente_id | clienteId | ID positivo |
| ubicacion_id | ubicacionId | Opcional; validación de pertenencia ERP |
| agente_ventas_id | agenteVentasId | ID positivo; ERP valida contexto |
| ruta_id | rutaId | Nuevo campo opcional Sync; Kotlin actual no lo envía |
| detalles[].almacen_id | almacenId | Requerido y único por captura; se rechazan almacenes mezclados |
| serie_id | serieId | ERP valida acceso del usuario autenticado y asigna folio |
| folio móvil | folioManual=null | No se usa el folio offline; ERP exige folio automático para UUID móvil |
| tipo_documento | documentoSolicitado | Exactamente FACTURA o REMISION; valores desconocidos reciben 400 antes del HTTP ERP |
| tipo_venta | tipoVenta | Nuevo campo requerido: CONTADO o CREDITO; independiente de documento |
| fecha_creacion | fechaCapturaComercial | OffsetDateTime ISO 8601 original, sin sustituir fecha ni zona |
| observaciones | observaciones | ERP limita longitud a 255 |
| detalles[].producto_id | renglones[].productoId | Incluye Kit como un producto comercial |
| presentacion_id | presentacionId | Opcional; ERP resuelve factor |
| cantidad_solicitada | cantidad | ERP valida precisión/rangos |
| descuento | descuentoPorcentaje y referenciaApp.renglones[].descuento | Porcentaje: Kotlin calcularSubtotal divide entre 100; ERP aplica límites |
| precio_unitario | referenciaApp.renglones[].precio | Sólo comparación |
| impuestos JSON: subtotal | referenciaApp.renglones[].subtotal | Base offline después del descuento, sin calcularla de nuevo |
| impuestos JSON: totalImpuestos | referenciaApp.renglones[].impuestos | Importe offline observado |
| total_linea | referenciaApp.renglones[].total | Importe offline observado |
| promoción | promocionCodigo=null | Kotlin actual no envía código; pendiente 04 |
| total_solicitado/autorizado/facturado | Sin campo global equivalente | El contrato ERP compara por renglón; no se inventa referencia global |
| usuario_creo_id | Sin mapeo | Auditoría ERP usa usuario del JWT; no se suplanta al usuario móvil |
| plazo | Sin mapeo | ERP decide plazo a partir de tipoVenta/contexto; no se deduce tipoVenta de plazo |
| cantidades autorizada/reservada/surtida/cancelada, estado_linea, IDs locales | Sin mapeo | No se proponen reservas ni estados comerciales |

`operacionKitId=null`: la identidad móvil es uuidLocal; no se introduce otra operación UUID. `referenciaApp.renglones` conserva el orden y tamaño de renglones. Importes ausentes permanecen null (el ERP compara los presentes). No se calcula ningún importe ausente. Un snapshot legacy como `IVA:89.60`, o JSON que no sea objeto/números esperados, recibe 400 y requiere migración/recaptura móvil.

DTO ERP auditados: PedidoCapturaCrearReq, PedidoCapturaPreviewReq, PedidoCapturaRenglonCrearReq, PedidoCapturaReferenciaApp, PedidoCapturaPreviewResp, PedidoCapturaGuardadoResp y DocumentoSolicitado. Create incluye los 15 campos reales; preview excluye uuidLocal y operacionKitId. No se mezclan Cotizaciones.

## Fecha comercial y pendientes obligatorios PED-COM-SYNC-04

Kotlin actual guarda `Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).toString()`: tiene hora, pero pierde offset/zona. Su DTO tampoco transporta tipoVenta. Por eso **el payload móvil actual recibe 400 hasta PED-COM-SYNC-04**. No es seguro reconstruir su offset con la zona del Sync ni su tipoVenta con el plazo.

PED-COM-SYNC-04 debe:

1. Persistir y enviar `tipo_venta` como CONTADO/CREDITO, independiente de FACTURA/REMISION.
2. Persistir fecha original con desplazamiento UTC y enviarla en `fecha_creacion` (p. ej. `2026-10-08T09:10:11.123-06:00`). Los pedidos antiguos necesitan una política explícita de recuperación de zona/recaptura; Sync no la inventa.
3. Mantener uuid_local estable y payload comercial estable durante retries; no generar UUID nuevo para un timeout.
4. Enviar un almacén por Pedido; decidir qué hacer con pedidos legacy sin almacén o de varios almacenes.
5. Consumir resultado, origenError, reintentable y detalleErp; evitar renovar la sesión Sync cuando un 401 sea de origen ERP.
6. En 409 diferencias, mostrar `detalleErp.diferencias`/valores ERP, conservar pedido pendiente y definir confirmación explícita. No quitar automáticamente referenciaApp ni afirmar creación.
7. Consumir total definitivo, serie, folio, versión y fechaRecepcion; conservar aliases uuidServer/estadoId mientras se migra.
8. Transportar ruta y código de promoción si se requieren; este contrato móvil aún no los proporciona. Conservar el snapshot offline numérico para la referencia.

ERP compara vigencia con fecha local del offset recibido, rechaza más de cinco minutos futuros y normaliza persistencia a microsegundos. No hay límite de antigüedad aprobado en el contrato 02.

## Autenticación y configuración local

Credenciales Sync → ERP separadas de JWT móvil → Sync. Se usa mecanismo real `POST /api/auth/login` con `{usernameOrEmail,password}`; LoginResponse entrega `token`. El usuario ERP debe tener `pedidos.capturar` (constante ErpPermissions.Pedidos.CAPTURAR), serie y almacén habilitados. Sync no cambia permisos ni usuarios.

| Variable | Uso |
| --- | --- |
| ERP_BASE_URL | Origen/base ERP; no hay URL runtime hardcodeada |
| ERP_TOKEN | JWT suministrado externamente, tiene prioridad sobre login |
| ERP_USERNAME / ERP_PASSWORD | Login real ERP cuando no se proporciona token |
| ERP_CONNECT_TIMEOUT_MS | 3000 por defecto |
| ERP_READ_TIMEOUT_MS | 15000 por defecto |

JWT de login en memoria, adquisición sincronizada. Ante 401 de captura se invalida y se hace **un** relogin y reenvío con el mismo payload/UUID. Si vuelve a fallar, se propaga 401. El endpoint `/api/auth/renew` existe, pero el adaptador usa relogin al expirar; no inventa refresh tokens. Token externo: 401 se propaga sin relogin y debe rotarse externamente. 403 no dispara login/retry automático. Un timeout no dispara una segunda captura automática.

No se registran credenciales, JWT ni cuerpos de login. Login fallido no se devuelve con cuerpo de sesión. URL con credenciales embebidas se rechaza; redirects HTTP no se siguen. No activar trazas HTTP de cuerpos/headers en el ambiente.

`src/main/resources/application-erp-local.properties.example` contiene ejemplo localhost:8081 y placeholders vacíos. Configurar variables antes de arrancar Sync o completar `.env.local` (formato properties `ERP_USERNAME=...`, sin comillas de shell). Spring importa opcionalmente ese archivo desde el directorio de ejecución; las variables de entorno tienen prioridad. El archivo está excluido de Git por reglas existentes y la plantilla local creada no contiene secretos. Las propiedades BD existentes siguen aplicando; Sync y ERP deben leer la misma BD para el catálogo de estados/feed. Aplicar en ERP su migración `V20261009_01__pedidos_fecha_comercial_uuid_movil.sql` mediante el procedimiento ERP, nunca desde Sync.

## Idempotencia, diferencias y errores

Sync no consulta pedidos para decidir si insertar/reintentar, ni tiene una segunda deduplicación comercial. ERP usa lock PostgreSQL/UUID único; retry equivalente devuelve idPedido/folio existente; distinta captura devuelve 409. El contrato ERP no devuelve indicador idempotent: Sync tampoco lo inventa. La cola móvil conserva la responsabilidad de retry técnico. Timeout puede ocurrir después de commit ERP: reenviar mismo UUID y captura recupera el pedido, sin consumir otro folio.

| HTTP | Respuesta Sync |
| --- | --- |
| 2xx create válido | HTTP 200; resultado ACEPTADO y respuesta ERP ampliada con aliases móviles |
| 400 | ERROR_NO_REINTENTABLE; forma inválida local o validación ERP |
| 401 | ERROR_NO_REINTENTABLE, origenError ERP; requiere corregir/renovar autenticación ERP |
| 403 | ERROR_NO_REINTENTABLE, origenError ERP; requiere permisos ERP |
| 409 con diferencias[] no vacío | DIFERENCIAS_COMERCIALES; detalleErp conserva cuerpo diferencial sin recalcular |
| otros 409 | CONFLICTO; incluye UUID incompatible o cambios de captura/Kit |
| 422 | ERROR_NO_REINTENTABLE si ERP lo emite; no se detectó como respuesta específica en controller captura |
| 408/429/5xx | ERROR_REINTENTABLE; se conserva HTTP ERP |
| timeout/error conexión | 504, ERROR_REINTENTABLE; sin confirmar creación |
| configuración ausente o lectura técnica BD fallida | 503; retry tras corregir dependencia/configuración |
| cuerpo ERP inválido/vacío o catálogo de estado inconsistente | 502; repetir mismo UUID después de corregir dependencia |

Errores tienen `resultado`, `reintentable`, `origenError`, `error`, `detalleErp`. El body ERP no JSON queda null; no se transmite HTML interno. Sólo aceptación incluye identidad de pedido. Cuerpo diferencial original está bajo detalleErp, no se construyen nuevas diferencias. ERP usa también errores globales 400/403/409/5xx; Sync conserva código sin convertirlos a 500.

Respuesta aceptada conserva `idPedido`, `origen`, `estado`, `serieId`, `serie`, `folio`, `version`, `fechaCaptura`, `total` de ERP; agrega `uuidLocal`, `uuidServer=idPedido`, `fechaRecepcion=fechaCaptura`, `resultado=ACEPTADO`, mensaje y estadoId leído de pedido_estados por nombre. No se supone que RECIBIDO tenga ID 1. Si falla esa lectura después de crear, el retry mantiene la misma identidad ERP. Total es BigDecimal; HTTP usa fecha ISO con offset explícito.

## PED-SYNC-R1 y Kits

PedidoEstadosService, PedidoEstadosController y PedidoEstadosBatch permanecen intactos: `pedidos_estados_historial`, cursor id_historial, uuid_local, idPedido, estado y fecha. ERP genera historial inicial RECIBIDO y guarda uuidLocal/origen APP. El adaptador sólo lee el catálogo para compatibilidad de la respuesta móvil. Sin cambios en Cotizaciones.

Kit se envía como productoId/presentacionId/cantidad/descuento normales; Sync no expande, reserva ni arma snapshots. Resolver ERP incorpora composición en el snapshot fiscal `kit`; en RECIBIDO cantidadReservada permanece cero según crearDetalle ERP. La integración optativa comprueba snapshot y reservas desde lecturas de detalle_pedidos.

## Escrituras retiradas y repositorios

En producción de código Sync quedan **0 escrituras directas de negocio** a pedidos, detalle_pedidos, series_folios o pedidos_estados_historial. Se retiraron save/saveAndFlush de pedido/detalle, actualización de siguienteFolio, transacción comercial Sync, snapshot de dirección y cálculo de impuestos local. La búsqueda de update/insert/save en src/main/java no encuentra llamadas de escritura.

Repositorios PedidoRepository/DetallePedidoRepository quedan declarados sin consumidores de creación; SeriesFoliosRepository sigue sirviendo lecturas de catálogos. No se eliminan entidades/repositorios en esta fase. Sus métodos heredados JPA no se usan como ruta comercial. El SQL histórico db/scripts/20260820_pedidos_idempotencia_folios.sql es un artefacto antiguo, no ejecutado por el nuevo flujo ni por la prueba real.

Se reemplazan pruebas de la autoridad anterior (PedidoIdempotenciaFolioTest, PedidoRenglonContractUnitTest, PedidoUbicacionCompatibilidadUnitTest y el caso antiguo SyncContractsTest); ya no deben insertar fixtures de negocio directamente desde Sync ni exigir sus cálculos/snapshots. La autoridad se verifica en adaptador/HTTP y la integración ERP optativa.

## Validación y límites

Pruebas focalizadas: PedidoErpAdapterTest, ErpPedidoClientTest, PedidoControllerContractTest, PedidoEstadosServiceTest, SyncContractsTest y SyncSecurityTest: **44 aprobadas**. PedidoErpLocalIntegrationTest: **1 prueba integral aprobada, sin omisión**, que recorre ocho escenarios comerciales y simula pérdida de respuesta después de commit. ERP: **18 pruebas focalizadas aprobadas** (PedidoCapturaServiceTest y PedidoCapturaControllerTest). Builds Maven y git diff --check aprobados. Clean recompiló con javac 25 después de detectar bytecode IDE stale.

| Requisito | Evidencia actual |
| --- | --- |
| A aceptación | HTTP Sync → ERP real → PostgreSQL; importes y folio ERP preservados |
| B/C/D documento y venta | Cuatro combinaciones unitarias y FACTURA/REMISION, CONTADO/CREDITO en integración real |
| E/F UUID retry/conflicto | Cada captura aceptada se repite: mismo ID/folio, count UUID=1; otra observación con mismo UUID devuelve 409 |
| G fecha | JSON conserva offset/hora; persistencia ERP conserva instante a microsegundos; fecha sin offset rechazada |
| H/I volumen/promoción | Precios esperados de catálogo comparados con preview, respuesta Sync y detalle PostgreSQL |
| J diferencias | Referencia incompatible recibe 409 DIFERENCIAS_COMERCIALES; count UUID=0 en PostgreSQL |
| K timeout/folio | Proxy retiene respuesta después de commit real; 504 reintentable; retry devuelve mismo ID/folio, count UUID=1 y siguiente_folio no avanza |
| L Kit | Snapshot fiscal kit persistido por ERP; un producto comercial y reserva cero en RECIBIDO |
| M/N historial/feed | Para cada aceptación el HTTP feed Sync devuelve RECIBIDO con mismo uuidLocal e idPedido |
| O/P/Q 401/403/timeout | Tests HTTP y endpoint conservan estados, relogin acotado y retry técnico |

**Integración automatizada local aprobada; UAT manual Kotlin permanece pendiente de PED-COM-SYNC-04.** ERP arrancó en localhost:8081 con su configuración habitual existente: `C:\administrativo-config\application-pac-sandbox.properties`, perfil `pac-sandbox`, conforme a `scripts/start-backend-sandbox.ps1`. Se usó spring.config.additional-location sobre ese archivo sin modificarlo. Login real usuario/contraseña desde `.env.local` del Sync entregó JWT. No se agregó CFDI_ENCRYPTION_SECRET al Sync, no se copiaron secretos ERP y no se realizaron operaciones CFDI/PAC.

PostgreSQL real: localhost:5432/prueba. JDBC sólo hizo lecturas. La corrida aprobada creó pedidos ERP 91–98, folios P01/38–45; quedaron como evidencia durable UAT. Los siete escenarios aceptados incluyen retry/conflicto, diferencias sin persistencia y feed con UUID; el octavo verifica recuperación tras timeout. Precio/total definitivos observados:

| Escenario | Producto/presentación | Precio ERP | Total ERP | Pedido |
| --- | --- | --- | --- | --- |
| Normal, cantidad 1 | 4/base | 152.310000 | 176.679600 | 91 |
| Volumen, cantidad 5 | 4/base | 145.000000 | 841.000000 | 92 |
| Promoción 10%, cantidad 1 | 47/base | 1080.000000 | 1252.800000 | 93 |
| Volumen sin promoción, cantidad 5 | 47/base | 1180.000000 | 6844.000000 | 94 |
| Presentación factor 100 | 1/2 | 130.000000 | 150.800000 | 95 |
| Descuento manual permitido 5% | 4/base | 152.310000 | 167.845620 | 96 |
| Kit fuera de vigencia promoción | 47/base | 1200.000000 | 1392.000000 | 97 |
| Timeout después de commit y recuperación | 4/base | 152.310000 | 176.679600 | 98 |

Descuento manual 1% sobre volumen de producto 4: **409 comercial, sin pedido creado**. Los precios se resolvieron en ERP; Sync no aplica estas fórmulas. La promoción temporal 724 y la regla de volumen temporal 779 se crearon/activaron/desactivaron mediante API ERP; al terminar ambas quedaron inactivas. Los fixtures 723/778 del primer intento también quedaron inactivos. No se editaron reglas comerciales preexistentes ni se borraron Pedidos UAT. La integración reprodujo el fallo lazy del preview con una promoción activa y pasó después de la corrección mínima readOnly ERP.

Para repetir la integración, arrancar ERP con su configuración local habitual mediante `scripts/start-backend-sandbox.ps1`; no copiar ni duplicar secretos/configuración CFDI al Sync. Habilitar `PED_COM_SYNC_03_ENABLED=true` en el entorno y proporcionar ERP_BASE_URL/autenticación y `ERP_TEST_DB_URL`, `ERP_TEST_DB_USERNAME`, `ERP_TEST_DB_PASSWORD` apuntando a la BD UAT compartida. Configurar `ERP_TEST_SCENARIOS_FILE` como ruta de JSON UTF-8 sin BOM externo con claves `normal`, `volumen`, `promocion`, `volumen_excluye_promocion`, `presentacion_factor`, `descuento_permitido`, `descuento_rechazado`, `kit`. Cada valor contiene `request` (payload móvil con IDs/cantidades/fecha válidos), y `expected.precioUnitario`/`expected.promocionCodigo` según catálogo conocido, salvo descuento_rechazado. Las condiciones de catálogo deben estar preparadas por ERP mediante sus APIs; no sembrar negocio por JDBC Sync. Incluir FACTURA/REMISION y CONTADO/CREDITO entre fixtures. Los fixtures temporales de esta corrida están desactivados; deben habilitarse explícitamente para repetirla y desactivarse al finalizar.

El test real hace Kotlin-like HTTP a controller Sync mediante MockMvc → HTTP ERP autenticado → PostgreSQL → HTTP feed Sync, verifica total frente a preview/BD, UUID único, mismo folio al retry, conflicto con otra captura, diferencias sin pedido y snapshot de Kit. Un proxy HTTP local retiene la respuesta una vez que el ERP confirma commit y permite verificar recuperación después de timeout sin consumo extra de folio. No cubre red/autenticación móvil Sync (cubierta separadamente por SyncSecurityTest). Crea pedidos UAT durables por ERP; no los borra ni ejecuta INSERT/UPDATE de negocio.

```powershell
mvn clean "-Dtest=PedidoErpAdapterTest,ErpPedidoClientTest,PedidoControllerContractTest,PedidoEstadosServiceTest,SyncContractsTest,SyncSecurityTest,PedidoErpLocalIntegrationTest" test
mvn -DskipTests package
git diff --check
# Con ambiente UAT configurado:
mvn -Dtest=PedidoErpLocalIntegrationTest test
```

Commits locales: Sync `584e027` (`feat(pedidos): delegar captura móvil al ERP`), más seguimiento de validación integral; ERP `6fc5390` (`fix(pedidos): conservar sesion en preview comercial`). Sin push. El commit ERP cambia únicamente una anotación readOnly; no altera DTO, reglas comerciales ni configuración CFDI.
