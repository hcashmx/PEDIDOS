package com.cyssoftware.effyflow.pedido.nuevoPedido

import kotlin.math.round

object PedidoCalculos {
    fun redondearImporte(value: Double): Double = round((value + 1e-9) * 100.0) / 100.0

    fun calcularImporteDescuento(precio: Double, descuentoPorcentaje: Double, cantidad: Double): Double {
        return redondearImporte(precio * cantidad * (descuentoPorcentaje / 100.0))
    }

    fun calcularSubtotal(precio: Double, descuentoPorcentaje: Double, cantidad: Double): Double {
        val bruto = redondearImporte(precio * cantidad)
        return redondearImporte(bruto - calcularImporteDescuento(precio, descuentoPorcentaje, cantidad))
    }

    fun calcularImpuesto(base: Double, tasaOCuota: Double): Double = redondearImporte(base * tasaOCuota)

    fun sumarImportes(values: Iterable<Double>): Double = redondearImporte(values.sum())

    fun calcularTotal(base: Double, impuestos: Iterable<Double>): Double {
        return redondearImporte(base + sumarImportes(impuestos))
    }

    fun calcularSiguienteFolio(folioSincronizado: Long?, maxFolioLocal: Long?): Long {
        return if (maxFolioLocal != null) {
            maxFolioLocal + 1
        } else {
            folioSincronizado ?: 1L
        }
    }
}
