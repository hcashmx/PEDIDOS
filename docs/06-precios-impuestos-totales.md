# Precios, impuestos y totales

## Convención fiscal

`tasa_o_cuota = 0.16` significa 16 %. La fórmula vigente es:

```text
impuesto = base * tasa
```

No se divide nuevamente entre 100.

Ejemplo:

```text
base = 560.00
tasa = 0.16
impuesto = 89.60
total = 649.60
```

## Kotlin

`PedidoCalculos` centraliza:

- `redondearImporte`: dos decimales;
- subtotal: `(precio - descuento) * cantidad`;
- impuesto: `base * tasa`;
- suma de impuestos;
- total: `base + impuestos`.

Kotlin usa `Double` y redondeo a dos decimales en cada operación monetaria relevante. El precio se selecciona en `PedidoRepository.getPrecioParaCliente(productoId, cliente.numero_precio_id)`. El descuento de línea se resta del precio antes de multiplicar por cantidad.

`NuevoPedidoViewModel` consulta impuestos por producto, calcula cada importe y suma subtotal bruto, descuento, impuestos y total neto.

## Backend

DTO, entidades y `PedidoService` usan `BigDecimal`. Para cada detalle:

```text
base = (precio_unitario - descuento) * cantidad
importe tasa = base * tasa_o_cuota
```

La base y los importes se redondean con `RoundingMode.HALF_UP` a dos decimales. Los impuestos se vuelven a consultar en PostgreSQL para no confiar únicamente en el texto fiscal enviado por el cliente.

## Desglose JSON

Los nuevos pedidos persisten en `detalle_pedidos.impuestos` un JSON con datos como identificador, nombre, tipo de aplicación, tipo de factor, tasa, base, importe, código y signo. `PedidoService.calcularImpuestosJson` realiza la serialización.

El valor local SQLite puede contener una representación compacta; el JSON PostgreSQL es el desglose fiscal definitivo generado por backend.

## Pruebas

- `PedidoCalculosTest` verifica 0.16, decimales, total y redondeo.
- `PedidoIdempotenciaFolioTest.precisionMonetariaUsaBigDecimal` verifica 560.00 + IVA.
- `PedidoIdempotenciaFolioTest.guardaDesgloseImpuestosJsonEnDetalle` verifica persistencia del JSON y su importe.
