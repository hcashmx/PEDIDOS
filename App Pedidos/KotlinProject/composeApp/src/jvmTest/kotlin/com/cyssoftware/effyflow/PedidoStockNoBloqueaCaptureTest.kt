package com.cyssoftware.effyflow

import com.cyssoftware.effyflow.db.DatabaseProvider
import com.cyssoftware.effyflow.db.SearchProductosConDetalleStock
import com.cyssoftware.effyflow.pedido.nuevoPedido.PedidoRepository
import com.cyssoftware.effyflow.pedido.nuevoPedido.viewmodel.NuevoPedidoViewModel
import com.cyssoftware.effyflow.pedido.nuevoPedido.viewmodel.PedidoStep
import com.cyssoftware.effyflow.pedido.nuevoPedido.viewmodel.TipoPedido
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class PedidoStockNoBloqueaCaptureTest {

    @Test
    fun aceptaCantidadesSinUsarExistenciaComoBloqueo() = runBlocking {
        val repo = PedidoRepository()
        val productoConStock = productoConStock(repo)
        val productoSinStock = productoSinStock(repo)

        assertAgregaLinea(repo, productoConStock, 1.0)
        assertAgregaLinea(repo, productoConStock, 18.0)
        assertAgregaLinea(repo, productoConStock, 20.0)
        assertAgregaLinea(repo, productoSinStock, 1.0)
    }

    @Test
    fun rechazaCantidadesNoPositivas() = runBlocking {
        val repo = PedidoRepository()
        val producto = productoConStock(repo)

        assertRechazaCantidad(repo, producto, 0.0)
        assertRechazaCantidad(repo, producto, -1.0)
    }

    private suspend fun assertAgregaLinea(
        repo: PedidoRepository,
        producto: SearchProductosConDetalleStock,
        cantidad: Double
    ) {
        val vm = prepararPedido(repo)

        vm.onAddProducto(producto, cantidad, false)
        waitUntil { vm.state.value.lineasPedido.size == 1 || vm.state.value.productoError != null }

        assertEquals(1, vm.state.value.lineasPedido.size)
        assertEquals(cantidad, vm.state.value.lineasPedido.single().cantidad)
    }

    private suspend fun assertRechazaCantidad(
        repo: PedidoRepository,
        producto: SearchProductosConDetalleStock,
        cantidad: Double
    ) {
        val vm = prepararPedido(repo)

        vm.onAddProducto(producto, cantidad, false)

        assertEquals(0, vm.state.value.lineasPedido.size)
        assertEquals("La cantidad debe ser mayor a 0.", vm.state.value.productoError)
    }

    private suspend fun prepararPedido(repo: PedidoRepository): NuevoPedidoViewModel {
        val db = DatabaseProvider.database
        val pedidoBase = db.pedidosQueries.selectAll().executeAsList().first()
        val vm = NuevoPedidoViewModel(repo, pedidoBase.usuario_creo_id, Dispatchers.Default)

        waitUntil { !vm.state.value.isLoading && vm.state.value.agenteLogueado != null }
        val serie = vm.state.value.seriesDisponibles.first { it.id_serie == 10L }
        vm.onSerieSeleccionada(serie)
        waitUntil { vm.state.value.currentStep == PedidoStep.CLIENTE }

        val cliente = db.clientesQueries.findById(pedidoBase.cliente_id).executeAsOne()
        vm.onClienteSeleccionado(cliente, TipoPedido.CONTADO, 0)
        waitUntil { vm.state.value.direccionSeleccionada != null }
        vm.finalizarSeleccionClienteYDireccion()
        waitUntil { vm.state.value.currentStep == PedidoStep.PRODUCTOS }

        return vm
    }

    private fun productoConStock(repo: PedidoRepository): SearchProductosConDetalleStock {
        val db = DatabaseProvider.database
        val detalle = db.detalle_pedidosQueries.selectAll().executeAsList().first()
        val producto = db.productosQueries.findById(detalle.producto_id).executeAsOne()
        val term = producto.codigo ?: producto.nombre ?: producto.id_producto.toString()
        return repo.searchProductosConDetalle(term).first { it.id_producto == producto.id_producto }
    }

    private fun productoSinStock(repo: PedidoRepository): SearchProductosConDetalleStock {
        val db = DatabaseProvider.database
        val id = db.stock_productosQueries.transactionWithResult {
            val rows = db.productosQueries.selectAll().executeAsList()
            rows.first { producto ->
                producto.activo == 1L &&
                    db.stock_productosQueries.findByProductoId(producto.id_producto).executeAsOneOrNull()?.SUM == null
            }.id_producto
        }
        val producto = db.productosQueries.findById(id).executeAsOne()
        val term = producto.codigo ?: producto.nombre ?: producto.id_producto.toString()
        val encontrado = repo.searchProductosConDetalle(term).first { it.id_producto == id }

        assertTrue((encontrado.existenciaTotal ?: 0.0) <= 0.0)
        return encontrado
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
