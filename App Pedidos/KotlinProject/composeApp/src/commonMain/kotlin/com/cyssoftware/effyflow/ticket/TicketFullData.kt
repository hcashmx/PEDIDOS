package com.cyssoftware.effyflow.ticket

import com.cyssoftware.effyflow.db.GetDetalleConProductoPorPedidoId
import com.cyssoftware.effyflow.db.GetPedidoConClienteById
import com.cyssoftware.effyflow.db.Series_folios

data class TicketFullData(
    val pedido: GetPedidoConClienteById,
    val serie: Series_folios,
    val detalles: List<GetDetalleConProductoPorPedidoId>
)