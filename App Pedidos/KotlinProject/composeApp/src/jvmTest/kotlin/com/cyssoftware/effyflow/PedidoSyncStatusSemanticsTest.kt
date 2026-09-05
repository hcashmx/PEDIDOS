package com.cyssoftware.effyflow

import com.cyssoftware.effyflow.db.DatabaseProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class PedidoSyncStatusSemanticsTest {

    @Test
    fun syncOkAlineaEstadoYSyncedAt() {
        val db = DatabaseProvider.database
        val pedido = db.pedidosQueries.selectAll().executeAsList().singleOrNull()
        assertNotNull(pedido, "Expected exactly one local pedido for this controlled phase")

        db.pedidosQueries.updatePedidoSyncOk(
            idPedido = pedido.id_pedido,
            folio = pedido.folio,
            estado_id = pedido.estado_id ?: 0,
            uuidServer = pedido.uuid_server ?: "TEST_SERVER_ID"
        )

        val actualizado = db.pedidosQueries.findById(pedido.id_pedido).executeAsOne()
        assertEquals("SYNCHRONIZED", actualizado.estado)
        assertEquals("SYNCHRONIZED", actualizado.synced_at)
    }
}
