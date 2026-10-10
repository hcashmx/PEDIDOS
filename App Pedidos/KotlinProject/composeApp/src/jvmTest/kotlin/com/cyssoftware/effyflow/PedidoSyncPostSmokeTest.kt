package com.cyssoftware.effyflow

import com.cyssoftware.effyflow.db.DatabaseProvider
import com.cyssoftware.effyflow.login.data.AuthLocalDataSource
import com.cyssoftware.effyflow.login.data.LoginRepository
import com.cyssoftware.effyflow.pedido.sincronizacion.viewModel.SyncViewModel
import java.nio.file.Paths
import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class PedidoSyncPostSmokeTest {

    @Test
    fun sendPendingPedidoAndRetryIdempotently() = runBlocking {
        val db = DatabaseProvider.database
        val local = AuthLocalDataSource()
        val sessionBefore = db.authQueries.getSession().executeAsOneOrNull()
        assertNotNull(sessionBefore, "Expected local UserSession before syncing pedido")

        val login = LoginRepository().login(sessionBefore.email, (System.getenv("EFFY_TEST_PASSWORD") ?: error("Set EFFY_TEST_PASSWORD for opt-in UAT; passwords are never cached")))
        assertFalse(login.accessToken.isNullOrBlank(), "Login did not return access token")
        println("LOGIN_SUMMARY|ok=true|tokenGenerated=true")

        val initialPedido = db.pedidosQueries.getPedidosPendientesSync().executeAsList().single()
        val initialDetalles = db.detalle_pedidosQueries.getDetallesPorPedido(initialPedido.id_pedido).executeAsList()
        assertEquals(1, db.pedidosQueries.selectAll().executeAsList().size)
        assertEquals(1, initialDetalles.size)

        println(
            listOf(
                "INITIAL_SQLITE",
                "pedidoId=${initialPedido.id_pedido}",
                "uuidPresent=${!initialPedido.uuid_local.isNullOrBlank()}",
                "estado=${initialPedido.estado}",
                "syncedAt=${initialPedido.synced_at}",
                "serie=${initialPedido.serie_id}",
                "folio=${initialPedido.folio}",
                "total=${initialPedido.total_solicitado}"
            ).joinToString("|")
        )

        val firstMessage = runPedidoSync(local)
        println("FIRST_POST_RESULT|message=${firstMessage.replace('\n', ';')}")

        val afterFirst = db.pedidosQueries.findById(initialPedido.id_pedido).executeAsOne()
        println(
            listOf(
                "AFTER_FIRST_SQLITE",
                "pedidoId=${afterFirst.id_pedido}",
                "syncedAt=${afterFirst.synced_at}",
                "uuidServerPresent=${!afterFirst.uuid_server.isNullOrBlank()}",
                "uuidServer=${afterFirst.uuid_server}",
                "folio=${afterFirst.folio}",
                "estadoId=${afterFirst.estado_id}"
            ).joinToString("|")
        )

        resetPedidoAsPendingForRetry(afterFirst.id_pedido)

        val retryMessage = runPedidoSync(local)
        println("RETRY_POST_RESULT|message=${retryMessage.replace('\n', ';')}")

        val afterRetry = db.pedidosQueries.findById(initialPedido.id_pedido).executeAsOne()
        println(
            listOf(
                "AFTER_RETRY_SQLITE",
                "pedidoId=${afterRetry.id_pedido}",
                "syncedAt=${afterRetry.synced_at}",
                "uuidServerPresent=${!afterRetry.uuid_server.isNullOrBlank()}",
                "uuidServer=${afterRetry.uuid_server}",
                "folio=${afterRetry.folio}",
                "estadoId=${afterRetry.estado_id}"
            ).joinToString("|")
        )
    }

    private fun runPedidoSync(local: AuthLocalDataSource): String {
        val latch = CountDownLatch(1)
        val sync = SyncViewModel(
            getToken = {
                val session = local.getSession()
                Pair(session?.accessToken, session?.refreshToken)
            },
            saveTokens = { accessToken, refreshToken ->
                val session = local.getSession()
                if (session != null) {
                    val cached = DatabaseProvider.database.authQueries.getSession().executeAsOneOrNull()
                    local.updateTokens(accessToken, refreshToken)
                }
            },
            onSyncComplete = { latch.countDown() }
        )

        sync.syncPedidosPendientes()
        if (!latch.await(90, TimeUnit.SECONDS)) {
            error("Pedido sync did not finish in time")
        }
        return sync.message
    }

    private fun resetPedidoAsPendingForRetry(idPedido: Long) {
        val dbPath = Paths.get(System.getenv("APPDATA"), "EffyFlow", "effy.db").toString()
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
            connection.prepareStatement(
                """
                update pedidos
                set synced_at = 'PENDIENTE_SYNC',
                    uuid_server = null
                where id_pedido = ?
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, idPedido)
                assertEquals(1, statement.executeUpdate())
            }
        }
    }
}
