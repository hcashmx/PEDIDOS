package com.cyssoftware.effyflow

import com.cyssoftware.effyflow.db.DatabaseProvider
import com.cyssoftware.effyflow.login.data.AuthLocalDataSource
import com.cyssoftware.effyflow.login.data.LoginRepository
import com.cyssoftware.effyflow.pedido.nuevoPedido.PedidoRepository
import com.cyssoftware.effyflow.pedido.nuevoPedido.viewmodel.NuevoPedidoViewModel
import com.cyssoftware.effyflow.pedido.nuevoPedido.viewmodel.PedidoStep
import com.cyssoftware.effyflow.pedido.nuevoPedido.viewmodel.TipoPedido
import com.cyssoftware.effyflow.pedido.sincronizacion.viewModel.SyncViewModel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class SegundoPedidoFullFlowTest {

    @Test
    fun capturaYEnviaExactamenteElSegundoPedido() = runBlocking {
        val db = DatabaseProvider.database
        val repo = PedidoRepository()
        val local = AuthLocalDataSource()

        val pedidosIniciales = db.pedidosQueries.selectAll().executeAsList()
        val detallesIniciales = db.detalle_pedidosQueries.selectAll().executeAsList()
        assertEquals(1, pedidosIniciales.size, "Debe existir solo el primer pedido local antes de iniciar")
        assertEquals(1, detallesIniciales.size, "Debe existir solo un detalle local antes de iniciar")

        val primerPedido = pedidosIniciales.single()
        val primerDetalle = detallesIniciales.single()
        assertEquals("SYNCHRONIZED", primerPedido.estado)
        assertEquals("SYNCHRONIZED", primerPedido.synced_at)
        assertEquals(0L, primerPedido.folio)
        assertEquals(0L, db.folioSeries_pedidosQueries.getMaxFolioPorSerie(10L).executeAsOne().max_folio)

        val sessionBefore = db.authQueries.getSession().executeAsOneOrNull()
        assertNotNull(sessionBefore, "Debe existir UserSession local")
        val login = LoginRepository().login(sessionBefore.email, sessionBefore.password)
        assertFalse(login.accessToken.isNullOrBlank(), "Login no generó token")

        runCatalogSync(local)
        val folioSincronizado = db.series_foliosQueries.getSerieByIdFromPedido(10L).executeAsOne().folio
        assertEquals(1L, folioSincronizado, "SQLite debe recibir series_folios.folio = 1")

        val stockAntes = repo.getStock(primerDetalle.producto_id)
        val siguienteAntes = repo.getSiguienteFolio(10L)
        assertEquals(1L, siguienteAntes)

        val vm = NuevoPedidoViewModel(repo, primerPedido.usuario_creo_id, Dispatchers.Default)
        waitUntil { !vm.state.value.isLoading && vm.state.value.agenteLogueado != null }

        val serie = vm.state.value.seriesDisponibles.first { it.id_serie == 10L }
        vm.onSerieSeleccionada(serie)
        waitUntil { vm.state.value.folioAsignado == 1L && vm.state.value.currentStep == PedidoStep.CLIENTE }

        val cliente = db.clientesQueries.findById(primerPedido.cliente_id).executeAsOne()
        vm.onClienteSeleccionado(cliente, TipoPedido.CONTADO, 0)
        waitUntil { vm.state.value.direccionSeleccionada != null }
        vm.finalizarSeleccionClienteYDireccion()

        val baseProduct = db.productosQueries.findById(primerDetalle.producto_id).executeAsOne()
        val searchTerm = baseProduct.codigo ?: baseProduct.nombre ?: baseProduct.id_producto.toString()
        val product = repo.searchProductosConDetalle(searchTerm).first { it.id_producto == primerDetalle.producto_id }

        vm.onAddProducto(product, 1.0, false)
        waitUntil { vm.state.value.lineasPedido.size == 1 }

        val linea = vm.state.value.lineasPedido.single()
        val iva = linea.impuestos.sumOf { it.monto }
        assertEquals(560.0, linea.precio)
        assertEquals(1.0, linea.cantidad)
        assertEquals(0.0, linea.descuento)
        assertEquals(560.0, linea.subtotal)
        assertEquals(89.6, iva)
        assertEquals(649.6, linea.total)

        vm.onRevisarPedido()
        vm.onConfirmarYSGuardar()
        waitUntil { vm.state.value.currentStep == PedidoStep.EXITO && vm.state.value.pedidoCreadoId != null }

        val segundoPedidoId = vm.state.value.pedidoCreadoId!!
        val segundoPedidoAntesPost = db.pedidosQueries.findById(segundoPedidoId).executeAsOne()
        val segundoDetalleAntesPost = db.detalle_pedidosQueries.getDetallesPorPedido(segundoPedidoId).executeAsList().single()
        assertNotEquals(primerPedido.uuid_local, segundoPedidoAntesPost.uuid_local)
        assertEquals("PENDIENTE_SYNC", segundoPedidoAntesPost.estado)
        assertEquals("PENDIENTE_SYNC", segundoPedidoAntesPost.synced_at)
        assertEquals(1L, segundoPedidoAntesPost.folio)
        assertEquals(2, db.pedidosQueries.selectAll().executeAsList().size)
        assertEquals(2, db.detalle_pedidosQueries.selectAll().executeAsList().size)
        assertEquals(1L, db.folioSeries_pedidosQueries.getMaxFolioPorSerie(10L).executeAsOne().max_folio)

        println(
            listOf(
                "SECOND_LOCAL_BEFORE_POST",
                "pedidoId=$segundoPedidoId",
                "uuidPresent=${!segundoPedidoAntesPost.uuid_local.isNullOrBlank()}",
                "uuidDifferent=${primerPedido.uuid_local != segundoPedidoAntesPost.uuid_local}",
                "estado=${segundoPedidoAntesPost.estado}",
                "syncedAt=${segundoPedidoAntesPost.synced_at}",
                "folio=${segundoPedidoAntesPost.folio}",
                "detalleId=${segundoDetalleAntesPost.id_detalle_pedido}",
                "subtotal=${linea.subtotal}",
                "tax=$iva",
                "total=${linea.total}",
                "stockBefore=$stockAntes"
            ).joinToString("|")
        )

        val syncMessage = runPedidoSync(local)
        println("SECOND_POST_RESULT|message=${syncMessage.replace('\n', ';')}")

        val segundoPedidoDespuesPost = db.pedidosQueries.findById(segundoPedidoId).executeAsOne()
        assertEquals("SYNCHRONIZED", segundoPedidoDespuesPost.estado)
        assertEquals("SYNCHRONIZED", segundoPedidoDespuesPost.synced_at)
        assertEquals(1L, segundoPedidoDespuesPost.folio)
        assertFalse(segundoPedidoDespuesPost.uuid_server.isNullOrBlank())
        assertEquals(0, db.pedidosQueries.getPedidosPendientesSync().executeAsList().size)
        assertEquals(stockAntes, repo.getStock(primerDetalle.producto_id), "El stock local no debe cambiar")

        println(
            listOf(
                "SECOND_SQLITE_AFTER_POST",
                "pedidoId=$segundoPedidoId",
                "estado=${segundoPedidoDespuesPost.estado}",
                "syncedAt=${segundoPedidoDespuesPost.synced_at}",
                "uuidServer=${segundoPedidoDespuesPost.uuid_server}",
                "folio=${segundoPedidoDespuesPost.folio}",
                "pending=${db.pedidosQueries.getPedidosPendientesSync().executeAsList().size}",
                "stockAfter=${repo.getStock(primerDetalle.producto_id)}"
            ).joinToString("|")
        )
    }

    private fun runCatalogSync(local: AuthLocalDataSource) {
        val latch = CountDownLatch(1)
        val sync = newSyncViewModel(local, latch)
        sync.syncAllCatalogs()
        if (!latch.await(180, TimeUnit.SECONDS)) {
            error("Catalog sync did not finish in time")
        }
        assertTrue(sync.message.contains("correctamente"), sync.message)
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

    private fun waitUntil(timeoutMillis: Long = 20_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        error("Timed out waiting for condition")
    }
}
