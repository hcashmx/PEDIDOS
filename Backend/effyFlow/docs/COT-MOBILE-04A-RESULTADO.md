# COT-MOBILE-04A — Resultado

## Dictamen

**SYNC PARCIALMENTE LISTO PARA INTEGRACIÓN KOTLIN.** El adaptador HTTP ya envía la captura al contrato ERP y traduce creación, recuperación idempotente y conflictos. La integración no queda declarada lista hasta ejecutar la suite final y validar contra `prueba`; además, las cuentas comerciales deben tener `cotizaciones.ver` para que ERP pueda devolver confirmaciones recuperadas.

## Auditoría de contratos

- Sync ya entregaba el paquete íntegro desde `GET /api/sync/cotizaciones/revisiones-comerciales/{id}` y emitía una aserción en `POST /api/sync/cotizaciones/aserciones-identidad`.
- El emisor existente carga el usuario activo, exige permiso efectivo `cotizaciones.crear` y un vínculo único a agente, carga la revisión y firma RS256 con `operation=CREAR_COTIZACION`, usuario, agente, dispositivo, UUID, revisión, huella, hash canónico, emisor, audiencia, expiración y `jti`.
- ERP `POST /api/cotizaciones/app` recibe `uuid`, `revisionComercialId` y `oferta`. La oferta exacta admite `clienteId`, `agenteVentasId`, `fecha`, `documentoSolicitado`, `observaciones`, `renglones`; cada renglón admite `productoId`, `presentacionId`, `cantidad`, `precioUnitario` y `descuentoPorcentaje`.
- ERP responde con `Detalle`: `cabecera` (`id`, `uuid`, `fecha`, `documentoSolicitado`, `subtotal`, `impuestos`, `total`, `estado`, `version`, entre otros) y `renglones` con importes autorizados y snapshots calculados. POST nuevo devuelve 201; recuperación devuelve 200.
- La revisión descargada incluye `id`, `versionEsquema`, `publicadaEn`, `referenciaComercial`, `huellaSha256` y `datosComerciales` completos. El envío verifica el hash provisto y mantiene el ID original; no sustituye la revisión por la actual.
- La cola Kotlin inspeccionada es específica de Pedido y congela el payload local antes del POST. No se modificó Kotlin; el contrato Cotización indicado abajo debe incorporarse en una fase Kotlin independiente.

## Endpoint Sync

`POST /api/sync/cotizaciones` requiere JWT de acceso Sync. El DTO `CotizacionCapturaRequest` recibe UUID, fecha comercial, ID y huella de revisión, dispositivo, agente opcional, cliente, documento, observaciones y renglones. `precioReferencia` se transforma a `precioUnitario`; `descuentoSolicitado` se transforma a `descuentoPorcentaje`.

Sync obtiene el vendedor del principal autenticado, vuelve a validar usuario/permiso/agente, comprueba el agente opcional y construye el body ERP una sola vez. El mismo `JsonNode` entregado al firmador se serializa como body del POST. La cuenta saliente usa exclusivamente `erp.cotizaciones.*`; no cambia `erp.*` de Pedidos. No envía composición de Kit ni valores fiscales, estado o folio.

El cliente reautentica una vez con las credenciales exclusivas de Cotizaciones ante 401 del token técnico. Si no se puede confirmar el POST, Sync retorna un error reintentable y conserva el UUID implícitamente en la solicitud; Kotlin debe guardar el body comercial original y reenviar el mismo UUID y contenido. Cada invocación de Sync crea una aserción nueva con `jti` nuevo. La idempotencia de ERP recupera el documento sin crear otro.

## Contrato esperado por Kotlin

```json
{
  "uuid": "UUID original",
  "fechaComercial": "2026-10-11",
  "revisionComercialId": "UUID de revisión offline",
  "revisionHuellaSha256": "64 caracteres hex",
  "dispositivoId": "desktop-...",
  "agenteId": 9,
  "clienteId": 7,
  "documentoSolicitado": "FACTURA",
  "observaciones": "texto opcional",
  "renglones": [{
    "productoId": 4,
    "presentacionId": 2,
    "cantidad": 2.5,
    "precioReferencia": 99.90,
    "descuentoSolicitado": 3
  }]
}
```

`agenteId` puede omitirse; si se incluye debe coincidir. Kotlin nunca debe enviar autoridad fiscal, total, folio, estado, usuario, snapshot fiscal ni composición de Kit. Kits viajan como producto comercial normal.

Al confirmar, Sync devuelve HTTP 201 (`resultadoIdempotencia=CREADA`) o HTTP 200 (`RECUPERADA`) con `uuidLocal`, `idCotizacion`, `estado`, revisión, versión, fecha, documento, subtotal/impuestos/total ERP y campos comerciales autorizados de cada renglón. El adaptador omite `impuestosSnapshot` y `kitSnapshot`; no transforma ni calcula esos datos. El estado comercial es `BORRADOR`; el estado local de sincronización corresponde a Kotlin.

Errores conservan el status ERP y ofrecen `resultado`, `reintentable`, `origenError`, `error` y, para 400, sólo errores de campo. 409 es permanente y no reintentable. 403 se conserva como denegación. 5xx, 408 y timeout son ambiguos y reintentables con el mismo UUID. Un 401 del JWT de Sync ocurre antes de llamar al adaptador; los 401 ERP clasifican credencial/assertion cuando el mensaje de ERP aporta esa distinción.

## Permisos y recuperación ERP

El endpoint APP exige permiso `cotizaciones.crear` al vendedor y a la cuenta técnica. La política ERP permite al vendedor acceder a la Cotización de su agente. La ruta ordinaria `GET /api/cotizaciones/{id}` exige además `cotizaciones.ver`. La recuperación del mismo `jti` ejecuta esa consulta, por lo que el replay exacto de una aserción con vendedor sin `ver` puede recibir 403. Los reintentos hechos a través del endpoint Sync emiten `jti` nuevo; la idempotencia por UUID del servicio de dominio devuelve el borrador existente sin esa consulta. Aun así, `cotizaciones.ver` es el permiso funcional para consultar/recuperar confirmaciones propias y debe habilitarse por la administración ERP según su matriz comercial. Sync no concede permisos ni oculta 403.

No se agregó consulta SQL ni endpoint alterno de reconciliación: ERP ya reconcilia el UUID mediante el POST idempotente. La autorización de cliente/agente se vuelve a comprobar dentro de ERP. No hay folio asignado en esta creación.

## Pruebas y límites

Pasaron 44 pruebas focalizadas: seguridad MVC del endpoint, contrato y headers del cliente, relogin técnico, preservación de estados ERP, HTTP 201/200, control de agente/revisión, mapeo de importes ERP y clasificación de conflictos/timeouts. `mvn clean package` pasó en `target-cot-mobile-04a` aislado (93 tests, 0 fallas, 2 skips opt-in); se usó esa salida porque el JAR normal estaba bloqueado por el proceso local del backend. `git diff --check` pasó.

No se ejecutó la prueba opt-in real: esta revisión local no proporciona credenciales visibles para la cuenta técnica, clave privada RS256 ni sesión comercial ERP. Tampoco se modificaron fixtures ni datos de PostgreSQL `prueba`. La prueba opt-in deberá enviar UUID identificable, observar 201, reenviar igual payload con nuevo `jti`, observar 200 y confirmar un solo documento, sin extraer secretos a logs.

El paquete ejecutó la suite de Sync y Pedido, incluidas las pruebas de contrato existentes; no se modificó el flujo Pedido ni `PED-SYNC-R1`. La prueba opt-in de Pedidos pendiente de COT-MOBILE-03F sigue siendo una regresión independiente. No se tocaron ERP, Kotlin, CFDI Kit, despliegue ni remoto Git.
