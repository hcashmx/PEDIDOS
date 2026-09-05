# Registro de decisiones técnicas

## ADR-001: ERP actual

- **Problema:** El workspace contiene un dump histórico y una base evolucionada.
- **Decisión:** `localhost:5432/prueba` fue la base ERP utilizada para las validaciones actuales; `BD\respaldo.sql` es solo referencia histórica.
- **Motivo:** Las validaciones recientes y scripts posteriores se realizaron contra `prueba`, aunque el `application.properties` versionado del backend PEDIDOS todavía apunte a otra configuración.
- **Consecuencias:** Las diferencias del dump no deben interpretarse como defectos de la base de validación vigente, y la configuración versionada no debe tomarse como descripción automática del entorno validado.

## ADR-002: Adaptación al ERP

- **Problema:** El contrato Kotlin y el esquema ERP usan nombres y formas diferentes.
- **Decisión:** El backend PEDIDOS se adapta al ERP, no el ERP al cliente Kotlin.
- **Motivo:** Preservar el modelo empresarial y desacoplar clientes.
- **Consecuencias:** `SyncService` mantiene consultas/proyecciones explícitas y DTO compatibles.

## ADR-003: Fuente de stock

- **Problema:** Existen varios nombres/tablas relacionados con stock.
- **Decisión:** `existencias` es la fuente real; PostgreSQL `stock_productos` no participa en la integración actual.
- **Motivo:** `existencias` contiene almacén, producto y cantidad disponible vigentes.
- **Consecuencias:** Backend transforma `existencias -> JSON stock_productos -> SQLite stock_productos`.

## ADR-004: Dirección de ubicaciones

- **Problema:** El dump histórico usa `direcccion`.
- **Decisión:** La cadena de compatibilidad actual es `ERP clientes_ubicaciones.direccion -> backend JSON direcccion -> Kotlin`.
- **Motivo:** El esquema actual fue corregido.
- **Consecuencias:** `direccion` es la columna PostgreSQL correcta. `direcccion` permanece únicamente como clave JSON temporal para no romper el contrato Kotlin y no debe propagarse como nombre de columna ERP.

## ADR-005: Timestamps de sincronización

- **Problema:** La sincronización incremental necesita cursores confiables.
- **Decisión:** PostgreSQL mantiene `created_at` y `updated_at` mediante triggers. En el proyecto externo **ERP Administrativo actual**, sus entidades JPA los mapean como lectura con `insertable=false, updatable=false`.
- **Motivo:** Cubrir escrituras JPA, importaciones y SQL directo sin que la aplicación administrativa sobrescriba timestamps administrados por PostgreSQL.
- **Consecuencias:** Kotlin usa `MAX(updated_at)` y `updatedAfter`. La configuración de las entidades del ERP Administrativo pertenece a un proyecto externo y no puede verificarse desde `C:\BDS\PEDIDOS`. Las entidades de `Backend\effyFlow` son del backend PEDIDOS y deben documentarse por separado, sin tratarlas como las entidades del ERP Administrativo.

## ADR-006: Backend como adaptador

- **Problema:** Kotlin requiere JSON estable aunque cambie la implementación ERP.
- **Decisión:** El backend proyecta columnas ERP a nombres DTO Kotlin.
- **Motivo:** Evitar acoplar SQLDelight al esquema físico.
- **Consecuencias:** Cambios de esquema se absorben principalmente en `SyncService` y DTO.

## ADR-007: Idempotencia

- **Problema:** Un pedido offline puede reenviarse por timeout o pérdida de respuesta.
- **Decisión:** `pedidos.uuid_local` identifica el intento lógico y tiene protección única parcial.
- **Motivo:** Un reintento debe devolver el pedido existente.
- **Consecuencias:** No se duplica el pedido ni se vuelve a consumir folio.

## ADR-008: Autoridad de folios

- **Problema:** Varias laptops pueden proponer el mismo folio offline.
- **Decisión:** Kotlin propone; el servidor decide el folio definitivo bajo bloqueo pesimista.
- **Motivo:** Solo PostgreSQL puede serializar concurrencia global.
- **Consecuencias:** La respuesta puede cambiar el folio local; `(serie_id, folio)` permanece único y `siguiente_folio` avanza transaccionalmente.

## ADR-009: Estado de sincronización local

- **Problema:** La app necesita distinguir pedidos pendientes y confirmados.
- **Decisión:** `estado` y `synced_at` se alinean a `PENDIENTE_SYNC` o `SYNCHRONIZED`.
- **Motivo:** Mantener la consulta de pendientes y la UI consistentes.
- **Consecuencias:** `synced_at` no es actualmente una fecha, pese a su nombre.

## ADR-010: Convención monetaria y fiscal

- **Problema:** Interpretar 0.16 como porcentaje entero producía IVA incorrecto.
- **Decisión:** 0.16 significa 16 % y se calcula `base * tasa`; backend usa `BigDecimal`.
- **Motivo:** Coincidir con el ERP y evitar pérdida de precisión.
- **Consecuencias:** Kotlin redondea a dos decimales y backend recalcula/persiste JSON fiscal.

## ADR-011: Existencia no bloquea pedidos

- **Problema:** El flujo inicial trataba la existencia como condición para aceptar un pedido.
- **Decisión:** La existencia sincronizada es solo informativa. Kotlin y backend deben aceptar pedidos de productos activos con cantidad positiva y precio válido aunque la existencia sea cero o insuficiente.
- **Motivo:** En la operación comercial real, el vendedor captura pedidos generalmente a crédito; matriz autoriza después con base en cartera/mora y almacén decide surtido parcial/completo. Un faltante puede generar compra a proveedor.
- **Consecuencias:** No se implementa reserva local ni descuento de `existencias` al capturar o recibir pedidos. La disponibilidad se resuelve en autorización/surtido. Debe definirse después dónde configurar entrega parcial por cliente y posible override por pedido.

## ADR-012: Cola offline de una laptop

- **Problema:** Una laptop debe capturar pedidos sin conexión y recuperarse al volver a tener backend, sin duplicar pedidos ni perder pendientes.
- **Decisión:** La cola local de pedidos pendientes se procesa ordenada por `id_pedido ASC`. Los pedidos capturados sin conexión permanecen como `PENDIENTE_SYNC`; al reconectar se envían secuencialmente. Los pedidos `SYNCHRONIZED` no se reenvían.
- **Motivo:** El flujo offline debe conservar pedidos confirmados, pendientes y fallidos de forma simple y recuperable para una laptop.
- **Reglas confirmadas:** HTTP 4xx/5xx conserva el pedido pendiente; una excepción de red conserva el pedido pendiente; un fallo no corrompe la cola ni marca como sincronizado un pedido no confirmado; reintentos posteriores procesan pendientes; una pérdida de respuesta se recupera por `uuid_local`; sincronización incremental posterior de catálogos no modifica pedidos locales; errores de red se reflejan en `message`.
- **Evidencia local:** PostgreSQL `pedidos = 5`, PostgreSQL `detalle_pedidos = 5`, serie 10 `siguiente_folio = 5`, SQLite `pedidos = 5`, SQLite `detalle_pedidos = 5` y pendientes `0`.
- **Consecuencias:** El flujo de una laptop `captura offline -> reconexión -> sincronización` queda validado. Siguen pendientes configuración definitiva de servidor/host, empaquetado/instalación, seguridad de credenciales/tokens, múltiples laptops y pruebas contra infraestructura real.

Los conteos de evidencia son datos de validación local, no requisitos ni datos iniciales del sistema.
