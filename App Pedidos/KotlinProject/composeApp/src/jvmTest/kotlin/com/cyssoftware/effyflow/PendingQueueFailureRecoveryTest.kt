package com.cyssoftware.effyflow

import com.cyssoftware.effyflow.db.DatabaseProvider
import com.cyssoftware.effyflow.login.data.AuthLocalDataSource
import com.cyssoftware.effyflow.pedido.nuevoPedido.PedidoRepository
import com.cyssoftware.effyflow.pedido.nuevoPedido.viewmodel.NuevoPedidoViewModel
import com.cyssoftware.effyflow.pedido.nuevoPedido.viewmodel.PedidoStep
import com.cyssoftware.effyflow.pedido.nuevoPedido.viewmodel.TipoPedido
import com.cyssoftware.effyflow.pedido.sincronizacion.viewModel.SyncViewModel
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class PendingQueueFailureRecoveryTest {

    @Test
    fun falloIntermedioConservaPendienteYReintentoSinDuplicarConfirmados() = runBlocking {
        val db = DatabaseProvider.database
        val repo = PedidoRepository()
        val createdIds = mutableListOf<Long>()
        val createdFolios = mutableListOf<Long>()

        assertEquals(0, db.pedidosQueries.getPedidosPendientesSync().executeAsList().size)

        try {
            repeat(3) {
                val pedido = crearPedidoTemporal(repo)
                createdIds += pedido.id_pedido
                createdFolios += pedido.folio
            }

            val firstServer = FakePedidoServer { requestNumber, folio ->
                if (requestNumber == 2) {
                    FakePedidoResponse(500, """{"error":"fallo simulado"}""")
                } else {
                    FakePedidoResponse(200, """{"uuidServer":"fake-$requestNumber","folio":$folio,"estadoId":1}""")
                }
            }
            firstServer.use {
                val message = runPedidoSync("http://localhost:${it.port}/api/sync/pedidos")
                println("QUEUE_FAILURE_FIRST_SYNC|requests=${it.requests.get()}|message=${message.replace('\n', ';')}")
            }

            val afterFirst = createdIds.map { db.pedidosQueries.findById(it).executeAsOne() }
            assertEquals("SYNCHRONIZED", afterFirst[0].synced_at)
            assertEquals("PENDIENTE_SYNC", afterFirst[1].synced_at)
            assertEquals("SYNCHRONIZED", afterFirst[2].synced_at)
            assertEquals(1, db.pedidosQueries.getPedidosPendientesSync().executeAsList().size)

            val retryServer = FakePedidoServer { requestNumber, folio ->
                FakePedidoResponse(200, """{"uuidServer":"retry-$requestNumber","folio":$folio,"estadoId":1}""")
            }
            retryServer.use {
                val message = runPedidoSync("http://localhost:${it.port}/api/sync/pedidos")
                println("QUEUE_FAILURE_RETRY_SYNC|requests=${it.requests.get()}|message=${message.replace('\n', ';')}")
                assertEquals(1, it.requests.get(), "Solo debe reintentarse el pedido pendiente")
            }

            val afterRetry = createdIds.map { db.pedidosQueries.findById(it).executeAsOne() }
            assertTrue(afterRetry.all { it.synced_at == "SYNCHRONIZED" && it.estado == "SYNCHRONIZED" })
            assertEquals(0, db.pedidosQueries.getPedidosPendientesSync().executeAsList().size)
        } finally {
            createdIds.forEach { id ->
                db.detalle_pedidosQueries.getDetallesPorPedido(id).executeAsList()
                    .forEach { db.detalle_pedidosQueries.deleteDetallePedidos(it.id_detalle_pedido) }
                db.pedidosQueries.deletePedidos(id)
            }
            createdFolios.forEach { folio ->
                db.folioSeries_pedidosQueries.deleteBySerieFolio(10L, folio)
            }
        }
    }

    @Test
    fun errorDeRedConservaPedidoPendiente() = runBlocking {
        val db = DatabaseProvider.database
        val repo = PedidoRepository()
        val pedido = crearPedidoTemporal(repo)

        try {
            val closedPort = ServerSocket(0).use { it.localPort }
            val message = runPedidoSync("http://localhost:$closedPort/api/sync/pedidos")
            val after = db.pedidosQueries.findById(pedido.id_pedido).executeAsOne()

            assertEquals("PENDIENTE_SYNC", after.synced_at)
            assertTrue(message.contains("Pedido ${pedido.folio}"), message)
            println("QUEUE_NETWORK_FAILURE|pedidoId=${pedido.id_pedido}|folio=${pedido.folio}|synced=${after.synced_at}|message=${message.replace('\n', ';')}")
        } finally {
            db.detalle_pedidosQueries.getDetallesPorPedido(pedido.id_pedido).executeAsList()
                .forEach { db.detalle_pedidosQueries.deleteDetallePedidos(it.id_detalle_pedido) }
            db.pedidosQueries.deletePedidos(pedido.id_pedido)
            db.folioSeries_pedidosQueries.deleteBySerieFolio(10L, pedido.folio)
        }
    }

    private suspend fun crearPedidoTemporal(repo: PedidoRepository) =
        with(DatabaseProvider.database) {
            val pedidoBase = pedidosQueries.selectAll().executeAsList().first { it.synced_at == "SYNCHRONIZED" }
            val detalleBase = detalle_pedidosQueries.getDetallesPorPedido(pedidoBase.id_pedido).executeAsList().single()
            val productoBase = productosQueries.findById(detalleBase.producto_id).executeAsOne()
            val searchTerm = productoBase.codigo ?: productoBase.nombre ?: productoBase.id_producto.toString()
            val producto = repo.searchProductosConDetalle(searchTerm).first { it.id_producto == productoBase.id_producto }
            val cliente = clientesQueries.findById(pedidoBase.cliente_id).executeAsOne()

            val vm = NuevoPedidoViewModel(repo, pedidoBase.usuario_creo_id, Dispatchers.Default)
            waitUntil { !vm.state.value.isLoading && vm.state.value.agenteLogueado != null }
            val serie = vm.state.value.seriesDisponibles.first { it.id_serie == 10L }
            vm.onSerieSeleccionada(serie)
            waitUntil { vm.state.value.currentStep == PedidoStep.CLIENTE }
            vm.onClienteSeleccionado(cliente, TipoPedido.CONTADO, 0)
            waitUntil { vm.state.value.direccionSeleccionada != null }
            vm.finalizarSeleccionClienteYDireccion()
            vm.onAddProducto(producto, 1.0, false)
            waitUntil { vm.state.value.lineasPedido.size == 1 }
            vm.onRevisarPedido()
            vm.onConfirmarYSGuardar()
            waitUntil { vm.state.value.currentStep == PedidoStep.EXITO && vm.state.value.pedidoCreadoId != null }
            pedidosQueries.findById(vm.state.value.pedidoCreadoId!!).executeAsOne()
        }

    private fun runPedidoSync(basePedidosUrl: String): String {
        val latch = CountDownLatch(1)
        val local = AuthLocalDataSource()
        val sync = SyncViewModel(
            basePedidosUrl = basePedidosUrl,
            getToken = {
                val session = local.getSession()
                Pair(session?.accessToken ?: "test-token", session?.refreshToken ?: "test-refresh")
            },
            onSyncComplete = { latch.countDown() }
        )
        sync.syncPedidosPendientes()
        if (!latch.await(60, TimeUnit.SECONDS)) {
            error("Pedido sync did not finish in time")
        }
        return sync.message
    }

    private class FakePedidoServer(
        private val responder: (Int, Long) -> FakePedidoResponse
    ) : AutoCloseable {
        val requests = AtomicInteger(0)
        val server: HttpServer = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        val port: Int

        init {
            server.createContext("/api/sync/pedidos") { exchange ->
                val requestNumber = requests.incrementAndGet()
                val body = exchange.requestBody.bufferedReader().use { it.readText() }
                val folio = """"folio"\s*:\s*(\d+)""".toRegex().find(body)?.groupValues?.get(1)?.toLong() ?: 0L
                val response = responder(requestNumber, folio)
                val bytes = response.body.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(response.status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
            port = server.address.port
        }

        override fun close() {
            server.stop(0)
        }
    }

    private data class FakePedidoResponse(val status: Int, val body: String)

    private fun waitUntil(timeoutMillis: Long = 20_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        error("Timed out waiting for condition")
    }
}
