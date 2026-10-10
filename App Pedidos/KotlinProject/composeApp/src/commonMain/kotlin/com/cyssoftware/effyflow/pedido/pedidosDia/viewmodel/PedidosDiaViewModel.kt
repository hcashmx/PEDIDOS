package com.cyssoftware.effyflow.pedido.pedidosDia.viewmodel

// commonMain/kotlin/.../pedidosDia/PedidosDiaViewModel.kt


import com.cyssoftware.effyflow.db.GetDetalleConProductoPorPedidoId
import com.cyssoftware.effyflow.db.GetPedidosFiltrados // Tipo actualizado
import com.cyssoftware.effyflow.pedido.nuevoPedido.PedidoRepository// <-- Clase de Filtros
import com.cyssoftware.effyflow.pedido.pedidosDia.PedidosDiaRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

// La clase PedidoFilters debe estar en un módulo común o aquí:
data class PedidoFilters(
    val fechaInicio: String? = null,
    val fechaFin: String? = null,
    val clienteNombre: String? = null,
    val estatus: String? = null,
    val productoNombre: String? = null
) {
    fun isDefault(): Boolean = fechaInicio != null && fechaFin != null && clienteNombre == null && estatus == null && productoNombre == null
}

data class PedidosDiaState(
    val isLoading: Boolean = false,
    val pedidos: List<GetPedidosFiltrados> = emptyList(), // <-- Tipo actualizado
    val pedidoSeleccionado: GetPedidosFiltrados? = null, // <-- Tipo actualizado
    val detalleSeleccionado: List<GetDetalleConProductoPorPedidoId> = emptyList(),
    val currentFilters: PedidoFilters = PedidoFilters(), // <-- Estado de filtros
    val error: String? = null
)

class PedidosDiaViewModel(
    private val pedidosDiaRepository: PedidosDiaRepository,
    private val agenteRepository: PedidoRepository,
    private val usuarioId: Long,
    private val dispatcher: CoroutineDispatcher
) {
    private val viewModelScope = CoroutineScope(dispatcher)

    private val _state = MutableStateFlow(PedidosDiaState())
    val state: StateFlow<PedidosDiaState> = _state.asStateFlow()

    private var agenteId: Long? = null

    init {
        loadAgenteYPedidos()
    }

    private fun loadAgenteYPedidos() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                // 1. Obtener el Agente
                val agente = agenteRepository.getAgentePorUsuario(usuarioId)
                if (agente == null) {
                    _state.update { it.copy(isLoading = false, error = "Agente no encontrado") }
                    return@launch
                }
                agenteId = agente.id_agente

                // 2. Aplicar filtros por defecto (Día actual)
                val hoy = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date.toString()
                val defaultFilters = PedidoFilters(fechaInicio = hoy, fechaFin = hoy)

                _state.update { it.copy(currentFilters = defaultFilters) }
                applyFilters(defaultFilters)

            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = "Error al cargar datos iniciales: ${e.message}") }
            }
        }
    }

    /**
     * Aplica un nuevo conjunto de filtros y recarga la lista de pedidos.
     */
    fun applyFilters(newFilters: PedidoFilters) {
        viewModelScope.launch {
            val currentAgenteId = agenteId ?: return@launch

            _state.update { it.copy(isLoading = true, currentFilters = newFilters) }

            try {
                val pedidos = pedidosDiaRepository.getPedidosFiltrados(
                    agenteId = currentAgenteId,
                    filters = newFilters
                )

                _state.update { it.copy(isLoading = false, pedidos = pedidos, error = null) }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = "Error al aplicar filtros: ${e.message}") }
            }
        }
    }

    /**
     * Se llama cuando el usuario hace clic en un pedido de la lista.
     */
    fun seleccionarPedido(pedido: GetPedidosFiltrados) { // <-- Tipo actualizado
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                val detalle = pedidosDiaRepository.getDetallePedido(pedido.id_pedido)
                _state.update {
                    it.copy(
                        isLoading = false,
                        pedidoSeleccionado = pedido,
                        detalleSeleccionado = detalle
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = "Error al cargar detalle: ${e.message}") }
            }
        }
    }

    /**
     * Se llama cuando el usuario presiona "Volver" en la pantalla de detalle.
     */
    fun reintentarRevision() {
        val pedido = _state.value.pedidoSeleccionado ?: return
        com.cyssoftware.effyflow.db.DatabaseProvider.database.pedidosQueries.retryRevision(pedido.id_pedido)
        cerrarDetalle()
        applyFilters(_state.value.currentFilters)
    }

    fun cerrarDetalle() {
        _state.update {
            it.copy(
                pedidoSeleccionado = null,
                detalleSeleccionado = emptyList()
            )
        }
    }
}