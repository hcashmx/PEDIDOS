package com.cyssoftware.effyflow.pedido.nuevoPedido

import com.cyssoftware.effyflow.pedido.nuevoPedido.viewmodel.TipoPedido

object PedidoCreditoRules {
    fun resolverCondicion(
        plazoAutorizado: Long?,
        creditoBloqueado: Boolean,
        solicitado: TipoPedido
    ): CondicionPedido {
        val plazo = plazoAutorizado ?: 0L
        val puedeCredito = plazo > 0L && !creditoBloqueado
        if (!puedeCredito) return CondicionPedido(TipoPedido.CONTADO, 0L, false)
        if (solicitado == TipoPedido.CONTADO) return CondicionPedido(TipoPedido.CONTADO, 0L, true)
        return CondicionPedido(TipoPedido.CREDITO, plazo, true)
    }
}

data class CondicionPedido(
    val tipo: TipoPedido,
    val plazo: Long,
    val puedeCredito: Boolean,
)
