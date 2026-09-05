package com.cyssoftware.effyflow

import com.cyssoftware.effyflow.db.DatabaseProvider
import com.cyssoftware.effyflow.login.data.AuthLocalDataSource
import com.cyssoftware.effyflow.login.data.LoginRepository
import com.cyssoftware.effyflow.pedido.sincronizacion.viewModel.SyncViewModel
import java.nio.file.Paths
import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class OfflinePendingPedidosRealSyncTest {

    @Test
    fun sincronizaPendientesYRecuperaRespuestaPerdida() = runBlocking {
        val db = DatabaseProvider.database
        val local = AuthLocalDataSource()
        val sessionBefore = db.authQueries.getSession().executeAsOneOrNull()
        assertNotNull(sessionBefore, "Debe existir UserSession local")

        val login = LoginRepository().login(sessionBefore.email, sessionBefore.password)
        assertFalse(login.accessToken.isNullOrBlank(), "Login no generó access token")

        val pendientesIniciales = db.pedidosQueries.getPedidosPendientesSync().executeAsList()
        assertEquals(3, pendientesIniciales.size, "Deben existir exactamente tres pendientes offline")
        val idsPendientes = pendientesIniciales.map { it.id_pedido }.toSet()

        val mensaje = runPedidoSync(local)
        println("OFFLINE_QUEUE_SYNC_RESULT|message=${mensaje.replace('\n', ';')}")

        val despuesSync = idsPendientes.map { db.pedidosQueries.findById(it).executeAsOne() }
        assertEquals(0, db.pedidosQueries.getPedidosPendientesSync().executeAsList().size)
        despuesSync.forEach {
            assertEquals("SYNCHRONIZED", it.estado)
            assertEquals("SYNCHRONIZED", it.synced_at)
            assertFalse(it.uuid_server.isNullOrBlank())
        }

        val simulado = despuesSync.first()
        val uuidServerAntes = simulado.uuid_server
        resetPedidoComoPendiente(simulado.id_pedido)

        val retryMessage = runPedidoSync(local)
        val despuesRetry = db.pedidosQueries.findById(simulado.id_pedido).executeAsOne()

        assertEquals("SYNCHRONIZED", despuesRetry.estado)
        assertEquals("SYNCHRONIZED", despuesRetry.synced_at)
        assertEquals(uuidServerAntes, despuesRetry.uuid_server)
        assertEquals(0, db.pedidosQueries.getPedidosPendientesSync().executeAsList().size)

        println(
            listOf(
                "LOST_RESPONSE_RECOVERY",
                "pedidoId=${simulado.id_pedido}",
                "folio=${simulado.folio}",
                "uuidServer=$uuidServerAntes",
                "retryMessage=${retryMessage.replace('\n', ';')}",
                "pending=${db.pedidosQueries.getPedidosPendientesSync().executeAsList().size}"
            ).joinToString("|")
        )
    }

    @Test
    fun syncIncrementalCatalogosNoAlteraPedidosLocales() {
        val db = DatabaseProvider.database
        val local = AuthLocalDataSource()
        val before = db.pedidosQueries.selectAll().executeAsList()
            .map { PedidoSnapshot(it.id_pedido, it.uuid_local, it.uuid_server, it.estado, it.synced_at, it.folio) }

        val latch = CountDownLatch(1)
        val sync = newSyncViewModel(local, latch)
        sync.syncAllCatalogs()
        if (!latch.await(180, TimeUnit.SECONDS)) {
            error("Catalog sync did not finish in time")
        }

        val after = db.pedidosQueries.selectAll().executeAsList()
            .map { PedidoSnapshot(it.id_pedido, it.uuid_local, it.uuid_server, it.estado, it.synced_at, it.folio) }

        assertEquals(before, after)
        assertTrue(sync.message.contains("correctamente"), sync.message)
        println("CATALOG_SYNC_STABILITY|pedidos=${after.size}|pending=${db.pedidosQueries.getPedidosPendientesSync().executeAsList().size}|message=${sync.message}")
    }

    private fun runPedidoSync(local: AuthLocalDataSource): String {
        val latch = CountDownLatch(1)
        val sync = newSyncViewModel(local, latch)
        sync.syncPedidosPendientes()
        if (!latch.await(90, TimeUnit.SECONDS)) {
            error("Pedido sync did not finish in time")
        }
        return sync.message
    }

    private fun newSyncViewModel(local: AuthLocalDataSource, latch: CountDownLatch): SyncViewModel =
        SyncViewModel(
            getToken = {
                val session = local.getSession()
                Pair(session?.accessToken, session?.refreshToken)
            },
            saveTokens = { accessToken, refreshToken ->
                val session = local.getSession()
                val cached = DatabaseProvider.database.authQueries.getSession().executeAsOneOrNull()
                if (session != null && cached != null) {
                    local.saveSession(
                        email = cached.email,
                        pass = cached.password,
                        data = session.copy(accessToken = accessToken, refreshToken = refreshToken)
                    )
                }
            },
            onSyncComplete = { latch.countDown() }
        )

    private fun resetPedidoComoPendiente(idPedido: Long) {
        val dbPath = Paths.get(System.getenv("APPDATA"), "EffyFlow", "effy.db").toString()
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
            connection.prepareStatement(
                """
                update pedidos
                set estado = 'PENDIENTE_SYNC',
                    synced_at = 'PENDIENTE_SYNC'
                where id_pedido = ?
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, idPedido)
                assertEquals(1, statement.executeUpdate())
            }
        }
    }

    private data class PedidoSnapshot(
        val id: Long,
        val uuidLocal: String?,
        val uuidServer: String?,
        val estado: String?,
        val syncedAt: String?,
        val folio: Long,
    )
}
