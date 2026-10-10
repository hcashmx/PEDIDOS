package com.cyssoftware.effyflow.pedido.sincronizacion.viewModel

import com.cyssoftware.effyflow.db.EffyDatabase
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

@Serializable
data class PedidoEstadoEvento(
    val historialId: Long,
    val idPedido: Long,
    val uuidLocal: String? = null,
    val estadoId: Long,
    val estado: String,
    val fechaCambio: String,
)

@Serializable
data class PedidoEstadosBatch(
    val lastId: Long,
    val untilId: Long,
    val hasMore: Boolean,
    val data: List<PedidoEstadoEvento>,
)

suspend fun cargarPedidoEstados(client: HttpClient, endpoint: String, afterId: Long, untilId: Long?): PedidoEstadosBatch {
    val response = client.get(endpoint) {
        parameter("afterId", afterId)
        parameter("size", 500)
        if (untilId != null) parameter("untilId", untilId)
    }
    check(response.status == HttpStatusCode.OK) { "State sync HTTP ${response.status.value}" }
    return response.body()
}

class PedidoEstadosSync(
    private val db: EffyDatabase,
    private val fetch: suspend (Long, Long?) -> PedidoEstadosBatch,
) {
    companion object {
        const val CURSOR_KEY = "pedidos_estados_last_id"
        private const val EVENT_KEY_PREFIX = "pedidos_estados_evento_"
    }
    private val mutex = Mutex()
    fun lastId(): Long = db.app_configQueries.getValue(CURSOR_KEY).executeAsOneOrNull()?.toLong() ?: 0L

    suspend fun sincronizar() = mutex.withLock {
        aplicarPendientes()
        val previous = lastId()
        drain(previous, null)
        // Identity allocation is not commit ordering. Replay the old prefix to recover
        // late commits and orders that were previously unknown to this laptop.
        // Per-order event markers prevent this recovery pass from regressing a state.
        if (previous > 0) drain(0, previous)
    }

    private suspend fun drain(start: Long, fixedBound: Long?) {
        var after = start
        var bound = fixedBound
        do {
            val batch = fetch(after, bound)
            if (bound == null) bound = batch.untilId
            check(batch.untilId == bound) { "State sync bound changed" }
            aplicarLote(batch, after)
            after = batch.lastId
        } while (batch.hasMore)
    }

    fun aplicarLote(batch: PedidoEstadosBatch, afterId: Long): Int {
        require(afterId >= 0 && batch.untilId >= afterId && batch.data.size <= 500)
        var previous = afterId
        for (event in batch.data) {
            require(event.historialId > previous && event.historialId <= batch.untilId)
            require(event.idPedido > 0 && event.estadoId > 0 && event.estado.isNotBlank() && event.fechaCambio.isNotBlank())
            previous = event.historialId
        }
        require(batch.lastId == previous)
        require(!batch.hasMore || batch.data.isNotEmpty())
        var applied = 0
        db.transaction {
            for (event in batch.data) {
                db.pedidosQueries.savePendingState(event.historialId, event.idPedido, event.uuidLocal,
                    event.estadoId, event.estado, event.fechaCambio)
            }
            applied = applyPending()
            db.app_configQueries.upsertValue(CURSOR_KEY, maxOf(lastId(), batch.lastId).toString())
        }
        return applied
    }

    /** Unmatched events are durable before the download cursor is committed. */
    fun aplicarPendientes(): Int = db.transactionWithResult { applyPending() }

    private fun applyPending(): Int {
        var applied = 0
        for (event in db.pedidosQueries.pendingStates().executeAsList()) {
            val uuidMatch = event.uuid_local?.takeIf { it.isNotBlank() }?.let {
                db.pedidosQueries.findByUuidLocal(it).executeAsOneOrNull()
            }
            val local = uuidMatch ?: db.pedidosQueries.findByRemoteId(event.remote_id.toString()).executeAsOneOrNull()
            if (local == null) continue // Keep the event, never create an order from this feed.
            val remoteId = event.remote_id.toString()
            check(local.uuid_server.isNullOrBlank() || local.uuid_server == remoteId) { "Conflicting remote order identity" }
            val key = EVENT_KEY_PREFIX + local.id_pedido
            val lastEvent = db.app_configQueries.getValue(key).executeAsOneOrNull()?.toLong() ?: 0L
            if (event.historial_id < lastEvent || (event.historial_id == lastEvent && local.estado_erp != null)) {
                db.pedidosQueries.deletePendingState(event.historial_id)
                continue
            }
            db.pedidosQueries.applyRemoteState(
                estadoId = event.estado_id, estado = event.estado, remoteId = remoteId,
                localId = local.id_pedido)
            db.app_configQueries.upsertValue(key, event.historial_id.toString())
            db.app_configQueries.upsertValue("pedidos_estados_fecha_" + local.id_pedido, event.fecha_cambio)
            db.pedidosQueries.deletePendingState(event.historial_id)
            applied++
        }
        return applied
    }
}
