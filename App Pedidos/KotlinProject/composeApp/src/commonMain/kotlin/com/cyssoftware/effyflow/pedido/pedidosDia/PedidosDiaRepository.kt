package com.cyssoftware.effyflow.pedido.pedidosDia


import com.cyssoftware.effyflow.db.DatabaseProvider
import com.cyssoftware.effyflow.db.GetDetalleConProductoPorPedidoId
import com.cyssoftware.effyflow.db.GetPedidosFiltrados // Tipo generado por la nueva query
import com.cyssoftware.effyflow.pedido.pedidosDia.viewmodel.PedidoFilters
import com.cyssoftware.effyflow.db.EffyDatabase
import app.cash.sqldelight.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map

// <-- Necesitas esta clase

class PedidosDiaRepository(private val db: EffyDatabase = DatabaseProvider.database) {

    private val pedidosQueries = db.pedidosQueries
    private val detallePedidosQueries = db.detalle_pedidosQueries

    fun observePedidosFiltrados(agenteId: Long, filters: PedidoFilters) = callbackFlow {
        val query = pedidosQueries.getPedidosFiltrados(agenteId, filters.fechaInicio, filters.fechaFin,
            filters.clienteNombre, filters.estatus, filters.productoNombre)
        val listener = object : Query.Listener {
            override fun queryResultsChanged() { trySend(Unit) }
        }
        query.addListener(listener)
        trySend(Unit)
        awaitClose { query.removeListener(listener) }
    }.conflate().map { getPedidosFiltrados(agenteId, filters) }

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
