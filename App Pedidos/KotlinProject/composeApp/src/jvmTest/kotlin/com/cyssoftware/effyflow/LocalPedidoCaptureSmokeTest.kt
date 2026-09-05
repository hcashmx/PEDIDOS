package com.cyssoftware.effyflow

import com.cyssoftware.effyflow.db.DatabaseProvider
import com.cyssoftware.effyflow.pedido.nuevoPedido.PedidoRepository
import com.cyssoftware.effyflow.pedido.nuevoPedido.viewmodel.NuevoPedidoViewModel
import com.cyssoftware.effyflow.pedido.nuevoPedido.viewmodel.PedidoStep
import com.cyssoftware.effyflow.pedido.nuevoPedido.viewmodel.TipoPedido
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class LocalPedidoCaptureSmokeTest {

    @Test
    fun captureOneLocalPedidoWithoutSyncing() = runBlocking {
        val db = DatabaseProvider.database
        val repo = PedidoRepository()
        val pedidosAntes = db.pedidosQueries.selectAll().executeAsList()
        val detallesAntes = db.detalle_pedidosQueries.selectAll().executeAsList()
        val foliosAntes = db.folioSeries_pedidosQueries.selectAll().executeAsList()

        assertTrue(pedidosAntes.size <= 1, "Expected at most one local test pedido before cleanup")
        assertTrue(detallesAntes.size <= 1, "Expected at most one local test detalle before cleanup")
        assertTrue(foliosAntes.size <= 1, "Expected at most one local test folio before cleanup")

        val existingPedido = pedidosAntes.singleOrNull()
        val existingDetalle = detallesAntes.singleOrNull()
        val userId = existingPedido?.usuario_creo_id ?: envLong("EFFY_TEST_USER_ID")
        val clientId = existingPedido?.cliente_id ?: envLong("EFFY_TEST_CLIENT_ID")
        val productId = existingDetalle?.producto_id ?: envLong("EFFY_TEST_PRODUCT_ID")
        val seriesId = existingPedido?.serie_id ?: envLong("EFFY_TEST_SERIE_ID")

        existingDetalle?.let { db.detalle_pedidosQueries.deleteDetallePedidos(it.id_detalle_pedido) }
        existingPedido?.let { db.pedidosQueries.deletePedidos(it.id_pedido) }
        foliosAntes.forEach { db.folioSeries_pedidosQueries.deleteBySerieFolio(it.serie_id, it.folio) }

        assertEquals(0, db.pedidosQueries.selectAll().executeAsList().size, "Expected empty pedidos before test")
        assertEquals(0, db.detalle_pedidosQueries.selectAll().executeAsList().size, "Expected empty detalle_pedidos before test")
        assertEquals(0, db.folioSeries_pedidosQueries.selectAll().executeAsList().size, "Expected empty folioSeries_pedidos before test")

        val initialStock = repo.getStock(productId)
        val vm = NuevoPedidoViewModel(repo, userId, Dispatchers.Default)
        waitUntil { !vm.state.value.isLoading && vm.state.value.agenteLogueado != null }

        val serie = vm.state.value.seriesDisponibles.first { it.id_serie == seriesId }
        val erpFolio = serie.folio
        val localNextBefore = repo.getSiguienteFolio(serie.id_serie)
        vm.onSerieSeleccionada(serie)
        waitUntil { vm.state.value.folioAsignado != null && vm.state.value.currentStep == PedidoStep.CLIENTE }

        val client = db.clientesQueries.findById(clientId).executeAsOne()
        vm.onClienteSeleccionado(client, TipoPedido.CONTADO, 0)
        waitUntil { vm.state.value.direccionSeleccionada != null }
        vm.finalizarSeleccionClienteYDireccion()

        val baseProduct = db.productosQueries.findById(productId).executeAsOne()
        val searchTerm = baseProduct.codigo ?: baseProduct.nombre ?: productId.toString()
        val product = repo.searchProductosConDetalle(searchTerm).first { it.id_producto == productId }
        val selectedPrice = repo.getPrecioParaCliente(product.id_producto, client.numero_precio_id)?.precio
        val taxes = repo.getImpuestosParaProducto(product.id_producto)

        vm.onAddProducto(product, 1.0, false)
        waitUntil { vm.state.value.lineasPedido.size == 1 }
        vm.onRevisarPedido()
        vm.onConfirmarYSGuardar()
        waitUntil { vm.state.value.currentStep == PedidoStep.EXITO && vm.state.value.pedidoCreadoId != null }

        val pedidoId = vm.state.value.pedidoCreadoId!!
        val pedido = db.pedidosQueries.findById(pedidoId).executeAsOne()
        val detalles = db.detalle_pedidosQueries.getDetallesPorPedido(pedidoId).executeAsList()
        val folios = db.folioSeries_pedidosQueries.selectAll().executeAsList()
        val stockAfter = repo.getStock(productId)

        assertEquals(1, db.pedidosQueries.selectAll().executeAsList().size)
        assertEquals(1, detalles.size)
        assertEquals(1, folios.size)
        assertEquals(initialStock, stockAfter, "Local stock changed after saving a pedido")

        val line = vm.state.value.lineasPedido.single()
        val totalTax = line.impuestos.sumOf { it.monto }
        println(
            listOf(
                "LOCAL_PEDIDO_SUMMARY",
                "pedidoId=$pedidoId",
                "uuidPresent=${!pedido.uuid_local.isNullOrBlank()}",
                "estado=${pedido.estado}",
                "syncedAt=${pedido.synced_at}",
                "serie=${pedido.serie_id}",
                "erpFolio=$erpFolio",
                "localNextBefore=$localNextBefore",
                "folio=${pedido.folio}",
                "folioRows=${folios.size}",
                "nextAfter=${repo.getSiguienteFolio(serie.id_serie)}",
                "clientId=${pedido.cliente_id}",
                "agentId=${pedido.agente_ventas_id}",
                "userId=${pedido.usuario_creo_id}",
                "productId=${detalles.single().producto_id}",
                "quantity=${line.cantidad}",
                "selectedPrice=$selectedPrice",
                "linePrice=${line.precio}",
                "discount=${line.descuento}",
                "subtotal=${line.subtotal}",
                "taxTotal=$totalTax",
                "total=${line.total}",
                "pedidoTotal=${pedido.total_solicitado}",
                "stockBefore=$initialStock",
                "stockAfter=$stockAfter",
                "taxes=${taxes.size}"
            ).joinToString("|")
        )
    }

    private fun envLong(name: String): Long =
        System.getenv(name)?.toLongOrNull() ?: error("$name is required")

    private fun waitUntil(timeoutMillis: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        error("Timed out waiting for condition")
    }
}
