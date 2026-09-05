package com.cyssoftware.effyflow.pedido.pedidosDia


import com.cyssoftware.effyflow.db.DatabaseProvider
import com.cyssoftware.effyflow.db.GetDetalleConProductoPorPedidoId
import com.cyssoftware.effyflow.db.GetPedidosFiltrados // Tipo generado por la nueva query
import com.cyssoftware.effyflow.pedido.pedidosDia.viewmodel.PedidoFilters

// <-- Necesitas esta clase

class PedidosDiaRepository {

    private val db =DatabaseProvider.database
    private val pedidosQueries = db.pedidosQueries
    private val detallePedidosQueries = db.detalle_pedidosQueries

    /**
     * Obtiene la lista de pedidos aplicando los filtros dados.
     */
    fun getPedidosFiltrados(
        agenteId: Long,
        filters: PedidoFilters
    ): List<GetPedidosFiltrados> {
        // ESTA LLAMADA AHORA FUNCIONARÁ CORRECTAMENTE:
        return pedidosQueries.getPedidosFiltrados(
            agenteId = agenteId,
            fechaInicio = filters.fechaInicio, // String?
            fechaFin = filters.fechaFin, // String? (¡Ahora es nullable!)
            clienteNombre = filters.clienteNombre,
            estatus = filters.estatus,
            productoNombre = filters.productoNombre
        ).executeAsList()
    }

    /**
     * Obtiene todas las líneas de detalle (con nombres de producto) para un pedidoId.
     */
    fun getDetallePedido(pedidoId: Long): List<GetDetalleConProductoPorPedidoId> {
        // La consulta se llama desde el objeto 'detallePedidosQueries'
        return detallePedidosQueries.getDetalleConProductoPorPedidoId(pedidoId).executeAsList()
    }
}