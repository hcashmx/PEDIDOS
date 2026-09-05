package com.cyssoftware.effyflow

import com.cyssoftware.effyflow.pedido.nuevoPedido.PedidoCalculos
import kotlin.test.Test
import kotlin.test.assertEquals

class PedidoCalculosTest {
    @Test
    fun descuentoSeCalculaComoPorcentajeNoComoImporteUnitario() {
        val precio = 152.31
        val descuentoPorcentaje = 12.0
        val cantidad = 1.0

        assertEquals(18.28, PedidoCalculos.calcularImporteDescuento(precio, descuentoPorcentaje, cantidad))
        assertEquals(134.03, PedidoCalculos.calcularSubtotal(precio, descuentoPorcentaje, cantidad))
    }
}
