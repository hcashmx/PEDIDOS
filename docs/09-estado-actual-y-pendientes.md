# Estado actual y pendientes

## COMPLETADO

- Arquitectura ERP -> backend -> Kotlin -> SQLite implementada.
- Endpoint paginado de catálogos con Bearer y `updatedAfter`.
- Fuente de stock corregida a PostgreSQL `existencias` y transformación a SQLite `stock_productos`.
- Captura local transaccional con `uuid_local`, detalle y `PENDIENTE_SYNC`.
- Cálculo IVA corregido a `base * tasa`; pruebas unitarias cubren 0.16.
- Primer folio local basado en el folio sincronizado y siguientes folios basados en reservas locales.
- Autoridad final de folio en backend, bloqueo pesimista y actualización transaccional.
- Idempotencia por `uuid_local` y unicidad `(serie_id, folio)`.
- Persistencia de impuestos JSON para pedidos nuevos.
- Semántica local alineada a `estado=SYNCHRONIZED` y `synced_at=SYNCHRONIZED`, con reporte de prueba exitoso.
- Existencia tratada como snapshot informativo: no bloquea captura Kotlin ni recepción backend.
- Suite backend de idempotencia/folios/stock: 12 pruebas exitosas.
- La captura local no descuenta stock; existe prueba que compara stock SQLite antes y después.
- Flujo de una laptop `captura offline -> reconexión -> sincronización` validado.
- Cola de pedidos pendientes ordenada por `id_pedido ASC`.
- Múltiples pedidos pueden capturarse completamente offline y permanecen `PENDIENTE_SYNC`.
- La reconexión envía pedidos pendientes secuencialmente.
- HTTP 4xx/5xx y excepciones de red conservan el pedido pendiente.
- Un fallo de un pedido no corrompe la cola ni marca pedidos no confirmados.
- Reintentos posteriores recuperan pendientes; pérdida de respuesta se resuelve por `uuid_local`.
- Pedidos `SYNCHRONIZED` no se reenvían.
- Sincronización incremental posterior no modifica pedidos locales.
- Errores de red reflejados en `message`.

## EN VALIDACIÓN

- Primera y segunda sincronización completas: el test existe y hubo resultado registrado, pero no se conserva su reporte.
- Volúmenes grandes y paginación: se registraron aproximadamente 71,898 productos y 71,896 precios/productos-impuestos, sin log persistente.
- Primer POST real, persistencia PostgreSQL y reintento idempotente: el smoke test existe y el resultado fue registrado, pero no se conserva XML/log.
- Segundo pedido con folio consecutivo fue validado end-to-end con Kotlin offline -> POST en ambiente local.
- Flujo de autorización/surtido: hay estructura de tablas y pantallas React, pero pertenece al siguiente desarrollo del ERP; no es responsabilidad terminada de esta app.

## PENDIENTE

- Probar dos o más laptops reservando folios localmente y enviando concurrentemente.
- Definir política de autorización en matriz usando cartera/mora/crédito del cliente.
- Definir política de surtido, entrega parcial y backorder. Actualmente ni captura ni recepción descuentan `existencias`.
- Definir dónde configurar la aceptación de entregas parciales por cliente y posible override por pedido.
- Completar administración React: usuarios, productos, configuración y rutas.
- Implementar o alinear en backend los endpoints administrativos, autorización y surtido consumidos por React.
- Formalizar migraciones de PostgreSQL y SQLDelight; los scripts actuales son manuales.
- Externalizar datasource, JWT, puertos y endpoint Kotlin.
- Definir configuración definitiva de servidor/host para instalación real.
- Empaquetado/instalación Windows.
- Seguridad de credenciales y tokens.
- Pruebas contra infraestructura real.
- Implementar, si se necesita el flujo ERP -> Kotlin para detalles históricos, la persistencia local del catálogo GET `detalle_pedidos`. Su ausencia no bloquea la captura SQLite ni el envío Kotlin -> ERP.
- Definir propagación de eliminaciones/inactivaciones en sincronización incremental.

## ESTADO LOCAL TRAS VALIDACION OFFLINE

- PostgreSQL `pedidos = 5`.
- PostgreSQL `detalle_pedidos = 5`.
- Serie 10 `siguiente_folio = 5`.
- SQLite `pedidos = 5`.
- SQLite `detalle_pedidos = 5`.
- SQLite pendientes `0`.
- Estos conteos son evidencia local de pruebas, no requisitos ni datos iniciales del sistema.

## RIESGOS CONOCIDOS

- Credenciales y secreto JWT en código/configuración versionada.
- Password y tokens guardados sin cifrado en SQLite; tokens React en `localStorage`.
- Diferencias entre el `application.properties` versionado y el entorno de validación: `prueba` fue la base usada y 8082 un puerto temporal, no una configuración definitiva.
- `synced_at` contiene un estado textual y no una fecha, lo que puede inducir errores futuros.
- `Double` en cálculos Kotlin frente a `BigDecimal` backend.
- Logs Kotlin pueden imprimir payloads completos y registros de catálogo con datos empresariales.
- Errores por registro durante catálogos se registran y la sincronización continúa, por lo que un catálogo podría quedar parcialmente actualizado.
- La app elimina todo `kits_detalle` antes de descargar; una interrupción puede dejar el catálogo local incompleto.
- `stock_productos` SQLite es snapshot de `existencias`; puede estar desactualizado por ventas de mostrador, otros vendedores, compras, surtidos o ajustes.
- No existe todavía una política implementada de autorización automática por cartera/mora.
- No existe todavía una política implementada de surtido parcial/backorder conectada al backend PEDIDOS.
