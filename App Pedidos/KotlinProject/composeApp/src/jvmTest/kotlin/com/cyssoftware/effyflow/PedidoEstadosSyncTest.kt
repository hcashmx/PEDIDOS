package com.cyssoftware.effyflow

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cyssoftware.effyflow.db.EffyDatabase
import com.cyssoftware.effyflow.pedido.sincronizacion.viewModel.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.yield
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import com.cyssoftware.effyflow.pedido.pedidosDia.PedidosDiaRepository
import com.cyssoftware.effyflow.pedido.pedidosDia.viewmodel.*
import com.cyssoftware.effyflow.pedido.nuevoPedido.PedidoRepository
import java.nio.file.Files
import kotlin.test.*

class PedidoEstadosSyncTest {
    private class Fixture(url: String = JdbcSqliteDriver.IN_MEMORY, create: Boolean = true) : AutoCloseable {
        val driver = JdbcSqliteDriver(url)
        val db = EffyDatabase(driver)
        init { if (create) EffyDatabase.Schema.create(driver) }
        val sync = PedidoEstadosSync(db) { _, _ -> error("No network in unit tests") }
        fun add(id: Long = 7, uuid: String? = "local-uuid", remote: String? = "900") {
            driver.execute(null, """
                INSERT INTO pedidos(id_pedido, cliente_id, agente_ventas_id, usuario_creo_id,
                    serie_id, folio, fecha_creacion, plazo, estado, synced_at, uuid_local,
                    uuid_server, estado_id, observaciones, total_solicitado)
                VALUES (?,1,1,1,10,20,'2026-01-01',0,'SYNCHRONIZED','SYNCHRONIZED',?,?,1,'KEEP_CAPTURE',123.45)
                """, 3) {
                bindLong(0, id); bindString(1, uuid); bindString(2, remote)
            }
        }
        override fun close() { driver.getConnection().close(); driver.close() }
    }
    // These IDs/names are fixtures, not a hardcoded mapping of the live ERP catalog.
    private fun event(id: Long, state: Long = 2, uuid: String? = "local-uuid", remote: Long = 900) =
        PedidoEstadoEvento(id, remote, uuid, state, when(state) { 2L -> "AUTORIZADO"; 3L -> "RECHAZADO"; 4L -> "EN_SURTIDO"; else -> "RECIBIDO" }, "2026-10-01 12:00:00+00")
    private fun batch(vararg events: PedidoEstadoEvento): PedidoEstadosBatch {
        val last = events.lastOrNull()?.historialId ?: 0
        return PedidoEstadosBatch(last, last, false, events.toList())
    }
    @Test fun locatesByUuidAndPreservesCaptureAndSyncStatus() {
        Fixture().use { f ->
            f.add(remote = null)
            assertEquals(1, f.sync.aplicarLote(batch(event(10)), 0))
            val local = f.db.pedidosQueries.findById(7).executeAsOne()
            assertEquals(2, local.estado_id); assertEquals("900", local.uuid_server)
            assertEquals("AUTORIZADO", local.estado_erp)
            assertEquals(event(10).fechaCambio, f.db.app_configQueries.getValue("pedidos_estados_fecha_7").executeAsOne()); assertNull(local.updated_at); assertEquals("KEEP_CAPTURE", local.observaciones); assertEquals(123.45, local.total_solicitado)
            assertEquals(10, local.serie_id); assertEquals(20, local.folio)
            assertEquals("SYNCHRONIZED", local.synced_at); assertEquals("SYNCHRONIZED", local.estado)
            assertEquals(1, f.db.pedidosQueries.selectAll().executeAsList().size)
        }
    }
    @Test fun fallsBackToStoredRemoteIdWhenUuidIsNullOrUnknown() {
        Fixture().use { f ->
            f.add(uuid = null)
            f.sync.aplicarLote(batch(event(10, uuid = null)), 0)
            assertEquals(2, f.db.pedidosQueries.findById(7).executeAsOne().estado_id)
            f.sync.aplicarLote(batch(event(11, state = 4, uuid = "unknown-uuid")), 0)
            assertEquals(4, f.db.pedidosQueries.findById(7).executeAsOne().estado_id)
        }
    }
    @Test fun neverUsesLocalIdAsRemoteIdAndNeverCreatesUnknownOrders() {
        Fixture().use { f ->
            f.add(id = 25, remote = "900")
            assertEquals(0, f.sync.aplicarLote(batch(event(10, uuid = null, remote = 25)), 0))
            assertEquals(1, f.db.pedidosQueries.findById(25).executeAsOne().estado_id)
            assertEquals(1, f.db.pedidosQueries.selectAll().executeAsList().size)
            assertEquals(10, f.sync.lastId())
        }
    }
    @Test fun orderedMultipleChangesFinishAtLastCommercialStateDespiteEqualTimestamps() {
        Fixture().use { f ->
            f.add()
            f.sync.aplicarLote(batch(event(10, state = 2), event(11, state = 4)), 0)
            assertEquals(4, f.db.pedidosQueries.findById(7).executeAsOne().estado_id)
            assertEquals(11, f.sync.lastId())
        }
    }
    @Test fun authorizationThenRejectionIsReflected() {
        Fixture().use { f ->
            f.add()
            f.sync.aplicarLote(batch(event(10, state = 2)), 0)
            f.sync.aplicarLote(batch(event(11, state = 3)), 0)
            assertEquals(3, f.db.pedidosQueries.findById(7).executeAsOne().estado_id)
        }
    }
    @Test fun duplicateAndOlderEventsCannotRegressStateOrCreateRows() {
        Fixture().use { f ->
            f.add()
            val latest = batch(event(11, state = 4))
            assertEquals(1, f.sync.aplicarLote(latest, 0))
            assertEquals(0, f.sync.aplicarLote(latest, 0))
            assertEquals(0, f.sync.aplicarLote(batch(event(10, state = 2)), 0))
            assertEquals(4, f.db.pedidosQueries.findById(7).executeAsOne().estado_id)
            assertEquals(11, f.sync.lastId())
            assertEquals(1, f.db.pedidosQueries.selectAll().executeAsList().size)
        }
    }
    @Test fun failureMidBatchRollsBackStateAndBothCursorTypes() {
        Fixture().use { f ->
            f.add()
            assertFailsWith<IllegalStateException> {
                f.sync.aplicarLote(batch(event(10), event(11, remote = 999)), 0)
            }
            assertEquals(1, f.db.pedidosQueries.findById(7).executeAsOne().estado_id)
            assertEquals(0, f.sync.lastId())
            assertNull(f.db.app_configQueries.getValue("pedidos_estados_evento_7").executeAsOneOrNull())
            assertEquals(1, f.sync.aplicarLote(batch(event(10)), 0))
        }
    }
    @Test fun rejectsOutOfOrderBatchWithoutAdvancingCursor() {
        Fixture().use { f ->
            f.add()
            assertFailsWith<IllegalArgumentException> { f.sync.aplicarLote(batch(event(11), event(10)), 0) }
            assertEquals(0, f.sync.lastId())
            assertEquals(1, f.db.pedidosQueries.findById(7).executeAsOne().estado_id)
        }
    }
    @Test fun cursorAndPerOrderMarkerSurviveReopen() {
        val file = Files.createTempFile("effy-state-test-", ".sqlite")
        try {
            val url = "jdbc:sqlite:${file.toAbsolutePath()}"
            Fixture(url).use { f -> f.add(); f.sync.aplicarLote(batch(event(10)), 0) }
            Fixture(url, create = false).use { f ->
                assertEquals(10, f.sync.lastId())
                assertEquals(0, f.sync.aplicarLote(batch(event(10)), 0))
                assertEquals(2, f.db.pedidosQueries.findById(7).executeAsOne().estado_id)
            }
        } finally { Files.deleteIfExists(file) }
    }
    @Test fun pagingUsesFixedBoundAndRecoveryFindsCommitBelowGlobalCursor() = runBlocking {
        Fixture().use { f ->
            f.add()
            // A different order produced ID 20 before this order's ID 10 became visible.
            f.sync.aplicarLote(batch(event(20, uuid = null, remote = 999)), 0)
            val calls = mutableListOf<Pair<Long, Long?>>()
            val sync = PedidoEstadosSync(f.db) { after, until ->
                calls += Pair(after, until)
                when (calls.size) {
                    1 -> PedidoEstadosBatch(20, 20, false, emptyList())
                    2 -> PedidoEstadosBatch(10, 20, true, listOf(event(10)))
                    3 -> PedidoEstadosBatch(20, 20, false, listOf(event(20, uuid = null, remote = 999)))
                    else -> error("Unexpected request")
                }
            }
            sync.sincronizar()
            assertEquals(listOf(Pair(20L, null), Pair(0L, 20L), Pair(10L, 20L)), calls)
            assertEquals(2, f.db.pedidosQueries.findById(7).executeAsOne().estado_id)
            assertEquals(20, sync.lastId())
        }
    }
    @Test fun networkFailureKeepsCursorAndDoesNotApplyChanges() = runBlocking {
        Fixture().use { f ->
            f.add()
            val sync = PedidoEstadosSync(f.db) { _, _ -> error("Simulated network failure") }
            assertFailsWith<IllegalStateException> { sync.sincronizar() }
            assertEquals(0, sync.lastId())
            assertEquals(1, f.db.pedidosQueries.findById(7).executeAsOne().estado_id)
        }
    }

    @Test fun unmatchedEventSurvivesRestartAndAppliesWithoutDownloadingAgain() {
        val file = Files.createTempFile("effy-state-inbox-", ".sqlite")
        try {
            val url = "jdbc:sqlite:$file"
            Fixture(url).use { f ->
                assertEquals(0, f.sync.aplicarLote(batch(event(10)), 0))
                assertEquals(1, f.db.pedidosQueries.pendingStates().executeAsList().size)
                assertEquals(10, f.sync.lastId())
            }
            Fixture(url, create = false).use { f ->
                f.add(remote = null)
                assertEquals(1, f.sync.aplicarPendientes())
                assertEquals("AUTORIZADO", f.db.pedidosQueries.findById(7).executeAsOne().estado_erp)
                assertTrue(f.db.pedidosQueries.pendingStates().executeAsList().isEmpty())
                assertEquals(1, f.db.pedidosQueries.selectAll().executeAsList().size)
            }
        } finally { Files.deleteIfExists(file) }
    }

    @Test fun reconnectRecoversOrderedEventsAndOfflinePreservesLastKnownState() = runBlocking<Unit> {
        Fixture().use { f ->
            f.add()
            f.sync.aplicarLote(batch(event(10)), 0)
            var offline = true
            val sync = PedidoEstadosSync(f.db) { after, until ->
                if (offline) error("Offline")
                if (after == 10L) PedidoEstadosBatch(12, 12, false,
                    listOf(event(11, state = 2), event(12, state = 4)))
                else PedidoEstadosBatch(10, until!!, false, listOf(event(10)))
            }
            assertFails { sync.sincronizar() }
            assertEquals(10, sync.lastId())
            assertEquals("AUTORIZADO", f.db.pedidosQueries.findById(7).executeAsOne().estado_erp)
            offline = false
            sync.sincronizar()
            assertEquals(12, sync.lastId())
            assertEquals("EN_SURTIDO", f.db.pedidosQueries.findById(7).executeAsOne().estado_erp)
            assertEquals(1, f.db.pedidosQueries.selectAll().executeAsList().size)
        }
    }

    @Test fun sqlitePersistenceFailureRollsBackInboxOrderAndCursorThenRetries() {
        Fixture().use { f ->
            f.add()
            f.driver.execute(null, "CREATE TRIGGER fail_state BEFORE UPDATE ON pedidos BEGIN SELECT RAISE(ABORT, 'disk failure fixture'); END", 0)
            assertFails { f.sync.aplicarLote(batch(event(10)), 0) }
            assertEquals(0, f.sync.lastId())
            assertNull(f.db.pedidosQueries.findById(7).executeAsOne().estado_erp)
            assertTrue(f.db.pedidosQueries.pendingStates().executeAsList().isEmpty())
            f.driver.execute(null, "DROP TRIGGER fail_state", 0)
            assertEquals(1, f.sync.aplicarLote(batch(event(10)), 0))
            assertEquals(10, f.sync.lastId())
        }
    }

    @Test fun replayBackfillsExactStateNameAtLegacyMarkerWithoutRegression() {
        Fixture().use { f ->
            f.add()
            f.db.app_configQueries.upsertValue("pedidos_estados_evento_7", "11")
            f.db.app_configQueries.upsertValue(PedidoEstadosSync.CURSOR_KEY, "11")
            f.sync.aplicarLote(batch(event(10), event(11, state = 4)), 0)
            assertEquals("EN_SURTIDO", f.db.pedidosQueries.findById(7).executeAsOne().estado_erp)
            assertEquals(11, f.sync.lastId())
        }
    }

    @Test fun lateInitialAcceptanceDoesNotOverwriteNewerErpState() {
        Fixture().use { f ->
            f.add(remote = null)
            f.sync.aplicarLote(batch(event(10)), 0)
            f.db.pedidosQueries.acceptComercial("900", 1, 20, 10, "P", "123.45", "2026-10-01", null, "{}", 7)
            val order = f.db.pedidosQueries.findById(7).executeAsOne()
            assertEquals("AUTORIZADO", order.estado_erp)
            assertEquals(2, order.estado_id)
            assertEquals("SYNCHRONIZED", order.synced_at)
        }
    }

    @Test fun persistsAllExactErpNamesWithoutHardcodedIdMapping() {
        Fixture().use { f ->
            f.add()
            listOf("RECIBIDO", "AUTORIZADO", "EN_SURTIDO", "SURTIDO_PARCIAL", "SURTIDO_COMPLETO",
                "EN_FACTURACION", "FACTURACION_PARCIAL", "FACTURADO_TOTAL", "CANCELADO", "RECHAZADO")
                .forEachIndexed { i, name ->
                    val e = event((i + 1).toLong()).copy(estadoId = (i + 100).toLong(), estado = name)
                    f.sync.aplicarLote(batch(e), i.toLong())
                    assertEquals(name, f.db.pedidosQueries.findById(7).executeAsOne().estado_erp)
                    assertEquals("SYNCHRONIZED", f.db.pedidosQueries.findById(7).executeAsOne().synced_at)
                }
        }
    }

    @Test fun viewModelObservesListAndOpenDetailWithoutReloadOrRestart() = runBlocking<Unit> {
        Fixture().use { f ->
            f.add()
            f.driver.execute(null, "INSERT INTO clientes(id_cliente,nombre) VALUES(1,'UAT cliente')", 0)
            f.db.agentesQueries.insertAgentes(1, "UAT agente", 1, 1, null)
            val vm = PedidosDiaViewModel(PedidosDiaRepository(f.db), PedidoRepository(f.db), 1, Dispatchers.Unconfined)
            try {
                yield()
                vm.applyFilters(PedidoFilters())
                val initial = withTimeout(5000) { vm.state.first { it.pedidos.size == 1 } }
                vm.seleccionarPedido(initial.pedidos.single())
                assertNotNull(vm.state.value.pedidoSeleccionado)
                f.sync.aplicarLote(batch(event(10)), 0)
                val updated = withTimeout(5000) { vm.state.first { it.pedidoSeleccionado?.estado_erp == "AUTORIZADO" } }
                assertEquals("AUTORIZADO", updated.pedidos.single().estado_erp)
                f.sync.aplicarLote(batch(event(11, state = 4)), 10)
                withTimeout(5000) { vm.state.first { it.pedidoSeleccionado?.estado_erp == "EN_SURTIDO" } }
            } finally { vm.close(); yield() }
        }
    }
}
