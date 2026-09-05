package com.cyssoftware.effyflow

import com.cyssoftware.effyflow.db.DatabaseProvider
import com.cyssoftware.effyflow.pedido.nuevoPedido.PedidoRepository
import com.cyssoftware.effyflow.pedido.nuevoPedido.viewmodel.NuevoPedidoViewModel
import com.cyssoftware.effyflow.pedido.nuevoPedido.viewmodel.PedidoStep
import com.cyssoftware.effyflow.pedido.nuevoPedido.viewmodel.TipoPedido
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class OfflineThreePedidosCaptureTest {

    @Test
    fun capturaTresPedidosOfflineConFoliosConsecutivos() = runBlocking {
        val db = DatabaseProvider.database
        val repo = PedidoRepository()
        val pedidosAntes = db.pedidosQueries.selectAll().executeAsList()
        val detallesAntes = db.detalle_pedidosQueries.selectAll().executeAsList()
        val pendientesAntes = db.pedidosQueries.getPedidosPendientesSync().executeAsList()
        val maxFolioAntes = db.folioSeries_pedidosQueries.getMaxFolioPorSerie(10L).executeAsOne().max_folio ?: -1L
        val siguienteEsperado = repo.getSiguienteFolio(10L)

        assertEquals(0, pendientesAntes.size, "La cola debe iniciar sin pendientes para esta prueba")
        assertEquals(maxFolioAntes + 1, siguienteEsperado)

        val pedidoBase = pedidosAntes.first { it.synced_at == "SYNCHRONIZED" }
        val detalleBase = db.detalle_pedidosQueries.getDetallesPorPedido(pedidoBase.id_pedido).executeAsList().single()
        val productoBase = db.productosQueries.findById(detalleBase.producto_id).executeAsOne()
        val searchTerm = productoBase.codigo ?: productoBase.nombre ?: productoBase.id_producto.toString()
        val producto = repo.searchProductosConDetalle(searchTerm).first { it.id_producto == productoBase.id_producto }
        val cliente = db.clientesQueries.findById(pedidoBase.cliente_id).executeAsOne()

        val creados = (0 until 3).map { index ->
            val vm = NuevoPedidoViewModel(repo, pedidoBase.usuario_creo_id, Dispatchers.Default)
            waitUntil { !vm.state.value.isLoading && vm.state.value.agenteLogueado != null }

            val serie = vm.state.value.seriesDisponibles.first { it.id_serie == 10L }
            vm.onSerieSeleccionada(serie)
            waitUntil { vm.state.value.folioAsignado == siguienteEsperado + index && vm.state.value.currentStep == PedidoStep.CLIENTE }

            vm.onClienteSeleccionado(cliente, TipoPedido.CONTADO, 0)
            waitUntil { vm.state.value.direccionSeleccionada != null }
            vm.finalizarSeleccionClienteYDireccion()

            vm.onAddProducto(producto, 1.0, false)
            waitUntil { vm.state.value.lineasPedido.size == 1 }
            vm.onRevisarPedido()
            vm.onConfirmarYSGuardar()
            waitUntil { vm.state.value.currentStep == PedidoStep.EXITO && vm.state.value.pedidoCreadoId != null }

            db.pedidosQueries.findById(vm.state.value.pedidoCreadoId!!).executeAsOne()
        }

        assertEquals(pedidosAntes.size + 3, db.pedidosQueries.selectAll().executeAsList().size)
        assertEquals(detallesAntes.size + 3, db.detalle_pedidosQueries.selectAll().executeAsList().size)
        assertEquals(3, db.pedidosQueries.getPedidosPendientesSync().executeAsList().size)
        assertEquals(listOf(siguienteEsperado, siguienteEsperado + 1, siguienteEsperado + 2), creados.map { it.folio })
        assertEquals(3, creados.mapNotNull { it.uuid_local }.distinct().size)

        creados.forEach {
            assertEquals("PENDIENTE_SYNC", it.estado)
            assertEquals("PENDIENTE_SYNC", it.synced_at)
            assertFalse(it.uuid_local.isNullOrBlank())
            assertEquals(null, it.uuid_server)
        }

        println(
            listOf(
                "OFFLINE_CAPTURE_SUMMARY",
                "beforePedidos=${pedidosAntes.size}",
                "afterPedidos=${db.pedidosQueries.selectAll().executeAsList().size}",
                "firstFolio=$siguienteEsperado",
                "folios=${creados.joinToString(",") { it.folio.toString() }}",
                "ids=${creados.joinToString(",") { it.id_pedido.toString() }}",
                "uuidDistinct=${creados.mapNotNull { it.uuid_local }.distinct().size}",
                "pending=${db.pedidosQueries.getPedidosPendientesSync().executeAsList().size}"
            ).joinToString("|")
        )
    }

    private fun waitUntil(timeoutMillis: Long = 20_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        error("Timed out waiting for condition")
    }
}
