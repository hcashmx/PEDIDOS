package com.cyssoftware.effyflow.ticket

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.onSuccess

data class TicketUiState(
    val isLoading: Boolean = true,
    val ticketData: TicketFullData? = null,
    val error: String? = null,
    val ticketText: String = ""
)

class TicketViewModel(
    private val pedidoId: Long,
    private val ticketRepository: TicketRepository,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Main)
) {

    private val _uiState = MutableStateFlow(TicketUiState())
    val uiState: StateFlow<TicketUiState> = _uiState.asStateFlow()

    init {
        loadTicketData()
    }

    fun loadTicketData() {
        _uiState.update { it.copy(isLoading = true, error = null) }

        coroutineScope.launch {
            val result = ticketRepository.getFullTicketData(pedidoId)

            result.onSuccess { data ->
                val ticketText = TicketGenerator.generateTicketText(data)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        ticketData = data,
                        ticketText = ticketText
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "Error al cargar el ticket: ${error.message}"
                    )
                }
            }
        }
    }
}