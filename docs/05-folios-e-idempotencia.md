# Folios e idempotencia

## Diseño actual

Kotlin propone un folio para permitir captura offline, pero el backend conserva la autoridad final.

### Kotlin

- Primera captura de una serie: usa `series_folios.folio`, sincronizado desde `series_folios.siguiente_folio` del ERP.
- Capturas posteriores: usa `MAX(folio)` de `folioSeries_pedidos` más uno.
- `folioSeries_pedidos` tiene `UNIQUE (serie_id, folio)`.
- Cada pedido genera un `uuid_local` antes de intentar enviarse.

### Backend

`PedidoService.guardarPedidoDesdeApp` ejecuta el guardado con `TransactionTemplate`:

1. normaliza `uuid_local`;
2. si ya existe, devuelve el pedido existente;
3. bloquea la fila de serie con `SeriesFoliosRepository.findByIdForUpdate`;
4. resuelve el folio definitivo;
5. inserta encabezado y detalles;
6. actualiza `siguiente_folio`;
7. confirma todo en la misma transacción.

El repositorio usa `@Lock(LockModeType.PESSIMISTIC_WRITE)`. Para series consecutivas, un folio propuesto atrasado u ocupado se sustituye por el siguiente disponible. Después del guardado, `siguiente_folio` solo avanza si `folioUsado + 1` supera el valor actual.

## Protecciones PostgreSQL

`Backend\effyFlow\db\scripts\20260820_pedidos_idempotencia_folios.sql`:

- agrega `pedidos.uuid_local`;
- prepara secuencias para los identificadores de pedidos y detalles cuando no eran identity;
- crea un índice único parcial para `uuid_local IS NOT NULL`;
- crea un índice único sobre `(serie_id, folio)`;
- ejecuta todo dentro de `BEGIN/COMMIT`.

El script no contiene secretos y no se ejecuta desde la aplicación.

## Reintento idempotente

Un reintento con el mismo `uuid_local`:

- encuentra el pedido existente;
- devuelve el mismo `uuidServer` y folio;
- responde `idempotent = true`;
- no inserta otro pedido;
- no vuelve a avanzar `siguiente_folio`.

La búsqueda previa cubre el caso normal. El manejo de `DataIntegrityViolationException` vuelve a consultar por UUID para cubrir carreras concurrentes protegidas por el índice único.

La validación de cola offline cubrió también la pérdida de respuesta: si PostgreSQL guardó el pedido pero Kotlin lo conserva como pendiente, el reintento posterior con el mismo `uuid_local` recupera el pedido existente, permite marcarlo como `SYNCHRONIZED`, no duplica encabezado/detalle y no vuelve a avanzar `siguiente_folio`.

## Transacción y fallos

El avance del folio ocurre después de persistir los detalles y dentro de la misma transacción. Si falla una validación o persistencia, no debe quedar pedido parcial ni consumirse el siguiente folio. `PedidoIdempotenciaFolioTest.falloDeGuardadoNoConsumeSiguienteFolio` verifica este comportamiento.

## Evidencia

La suite backend conserva un reporte de doce pruebas exitosas, incluyendo mismo UUID, UUID distintos con folios consecutivos, restricción única de UUID, precisión monetaria, JSON fiscal, stock no bloqueante y rollback del folio ante fallo.

La validación offline posterior amplió la evidencia:

- folios locales y servidor consecutivos hasta el folio 4;
- `series_folios.siguiente_folio = 5`;
- PostgreSQL `pedidos = 5` y `detalle_pedidos = 5`;
- SQLite `pedidos = 5`, `detalle_pedidos = 5` y pendientes `0`;
- pedidos ya `SYNCHRONIZED` no se reenviaron;
- reintentos por fallo de red o respuesta perdida no generaron duplicados.

Estos conteos corresponden solo a la base local de validación, no a requisitos ni datos iniciales del sistema.
