# Base de datos local Kotlin

## SQLite y ubicación

La app de escritorio crea:

```text
%APPDATA%\EffyFlow\effy.db
```

`composeApp\src\jvmMain\kotlin\com\cyssoftware\effyflow\db\DatabaseDriver.kt` crea el directorio y abre SQLite mediante `JdbcSqliteDriver`.

## Creación del esquema

SQLDelight genera `EffyDatabase.Schema` desde los archivos `.sq` de `composeApp\src\commonMain\sqldelight`. El ajuste actual en `DatabaseDriver.kt` calcula `shouldCreateSchema = !dbFile.exists()` antes de abrir la conexión y ejecuta `EffyDatabase.Schema.create(driver)` solo cuando el archivo todavía no existía. Esto corrige el caso de primera ejecución: abrir la conexión crea físicamente el archivo, por lo que comprobar su existencia después de abrirlo impediría crear las tablas.

No hay un sistema de migraciones SQLDelight documentado en este workspace; cambios futuros de esquema requieren una estrategia formal.

## Tablas principales

| Tabla | Uso |
|---|---|
| `UserSession` | Una sesión local: email, password, access token, refresh token, roles y usuario. |
| Catálogos | `users`, `agentes`, `clientes`, `clientes_ubicaciones`, `productos`, `precios_producto`, `stock_productos`, `impuestos`, `productos_impuestos`, series y otros. |
| `pedidos` | Encabezado local, folio propuesto/confirmado, UUID y estado de sincronización. |
| `detalle_pedidos` | Productos, cantidades, precio, descuento, impuestos y total de línea. |
| `folioSeries_pedidos` | Folios ya reservados localmente, con `UNIQUE (serie_id, folio)`. |

## UserSession

`auth.sq` fuerza una sola fila con `id = 1` y usa `INSERT OR REPLACE`. Actualmente guarda password, access token y refresh token en texto dentro de SQLite; es un riesgo conocido, no una recomendación.

## Catálogos

Los archivos `.sq` definen tablas y operaciones generadas por SQLDelight. La mayoría de las funciones de guardado de `SyncViewModel` llaman sentencias `INSERT OR REPLACE`, normalmente dentro de una transacción por página.

## Pedidos y detalle

`PedidoRepository.guardarPedidoLocal` inserta encabezado y detalles en una transacción SQLite:

- genera `uuid_local`;
- deja `uuid_server` nulo;
- guarda `estado = PENDIENTE_SYNC`;
- guarda `synced_at = PENDIENTE_SYNC`;
- asigna cantidades autorizadas, reservadas, surtidas y canceladas en cero;
- registra cada línea como `PENDIENTE`.

En SQLite, `detalle_pedidos.impuestos` es texto y actualmente guarda una representación compacta local. Al llegar al backend, el desglose se recalcula contra el ERP y se persiste como JSON PostgreSQL para pedidos nuevos.

## Folios locales

`PedidoRepository.getSiguienteFolio` combina:

1. el folio sincronizado en `series_folios.folio`;
2. el máximo reservado en `folioSeries_pedidos`.

`PedidoCalculos.calcularSiguienteFolio` usa el folio sincronizado para la primera captura y después `maxFolioLocal + 1`.

## Semántica de estado

- `estado` representa el estado visible/local del pedido.
- `synced_at`, pese a su nombre histórico, funciona actualmente como indicador textual del estado de sincronización, no como fecha.
- `PENDIENTE_SYNC`: el pedido existe en SQLite y espera envío.
- `SYNCHRONIZED`: el backend respondió correctamente y Kotlin guardó el identificador y folio de servidor.

`pedidos.sq:updatePedidoSyncOk` alinea ambos campos a `SYNCHRONIZED`, actualiza `uuid_server`, folio y `estado_id`.
