package com.cyssoftware.effyflow

import com.cyssoftware.effyflow.pedido.nuevoPedido.PedidoCreditoRules
import com.cyssoftware.effyflow.pedido.nuevoPedido.viewmodel.TipoPedido
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PedidoCreditoRulesTest {

    @Test
    fun clientePlazo30EsCredito30PorDefecto() {
        val result = PedidoCreditoRules.resolverCondicion(30, false, TipoPedido.NINGUNO)

        assertEquals(TipoPedido.CREDITO, result.tipo)
        assertEquals(30L, result.plazo)
        assertTrue(result.puedeCredito)
    }

    @Test
    fun clienteCredito30PuedeCambiarAContado() {
        val result = PedidoCreditoRules.resolverCondicion(30, false, TipoPedido.CONTADO)

        assertEquals(TipoPedido.CONTADO, result.tipo)
        assertEquals(0L, result.plazo)
        assertTrue(result.puedeCredito)
    }

    @Test
    fun alVolverACreditoUsaPlazoAutorizado() {
        val result = PedidoCreditoRules.resolverCondicion(30, false, TipoPedido.CREDITO)

        assertEquals(TipoPedido.CREDITO, result.tipo)
        assertEquals(30L, result.plazo)
    }

    @Test
    fun clientePlazo0EsSoloContado() {
        val result = PedidoCreditoRules.resolverCondicion(0, false, TipoPedido.NINGUNO)

        assertEquals(TipoPedido.CONTADO, result.tipo)
        assertEquals(0L, result.plazo)
        assertFalse(result.puedeCredito)
    }

    @Test
    fun noSePuedeFabricarCreditoParaClienteSinCredito() {
        val result = PedidoCreditoRules.resolverCondicion(0, false, TipoPedido.CREDITO)

        assertEquals(TipoPedido.CONTADO, result.tipo)
        assertEquals(0L, result.plazo)
        assertFalse(result.puedeCredito)
    }
}
