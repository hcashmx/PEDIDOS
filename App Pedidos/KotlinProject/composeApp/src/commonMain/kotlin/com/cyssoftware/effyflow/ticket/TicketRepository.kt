package com.cyssoftware.effyflow.ticket
import com.cyssoftware.effyflow.db.DatabaseProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TicketRepository {

    // Obtenemos la instancia de la base de datos generada por SQLDelight
    private val queries = DatabaseProvider.database

    /**
     * Busca toda la información necesaria para un ticket dado un ID de pedido.
     */
    suspend fun getFullTicketData(pedidoId: Long): Result<TicketFullData> = withContext(Dispatchers.Default) {
        try {
            // 1. Obtener el pedido y cliente
            val pedidoConCliente = queries.pedidosQueries.getPedidoConClienteById(pedidoId).executeAsOneOrNull()
                ?: throw Exception("No se encontró el pedido con ID $pedidoId")

            // 2. Obtener la serie (opcional, podría fallar si no existe)
            val serie = queries.series_foliosQueries.getSerieByIdFromPedido(pedidoConCliente.serie_id).executeAsOneOrNull()
            // 3. Obtener los detalles y productos
            val detalles = queries.detalle_pedidosQueries.getDetalleConProductoPorPedidoId(pedidoId).executeAsList()

            // 4. Combinar todo
            val fullData = TicketFullData(
                pedido = pedidoConCliente,
                serie = serie!!,
                detalles = detalles
            )
            Result.success(fullData)

        } catch (e: Exception) {
            println("Error al obtener datos del ticket: ${e.message}")
            Result.failure(e)
        }
    }
}