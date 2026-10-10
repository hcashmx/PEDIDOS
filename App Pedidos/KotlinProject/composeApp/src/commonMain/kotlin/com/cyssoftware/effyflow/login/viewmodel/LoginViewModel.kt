package com.cyssoftware.effyflow.login.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cyssoftware.effyflow.login.data.LoginRepository
import com.cyssoftware.effyflow.login.data.model.LoginResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class LoginUiState(
    val isLoading: Boolean = false,
    val message: String? = null,
    val success: Boolean = false,
    val user: LoginResponse? = null,
    val catalogReady: Boolean = false,
    val authorizedUsers: List<String> = emptyList(),
    val messageIsError: Boolean = false
)

class LoginViewModel(
    private val repository: LoginRepository = LoginRepository()
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState

    private val _savedEmail = MutableStateFlow<String>("")
    val savedEmail: StateFlow<String> = _savedEmail
    init {
        loadLastEmail()
        loadCatalogState()
    }

    private fun loadCatalogState(message: String? = null, messageIsError: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            val ready = repository.hasCatalog()
            _uiState.value = LoginUiState(catalogReady=ready,
                messageIsError=messageIsError || !ready,
                message=message ?: if (!ready) "Se requiere conexión inicial para sincronizar usuarios." else null,
                authorizedUsers=repository.users().filter { it.active==1L }.map {
                    "${it.username}: ${if (it.verifier == null) "requiere enrolamiento online" else "habilitado offline"}"
                })
        }
    }

    fun synchronizeUsers(username: String, password: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isLoading=true, success=false)
            try { loadCatalogState(repository.synchronizeUsers(username, password)) }
            catch (_: Exception) { loadCatalogState("No se pudieron sincronizar usuarios. Verifica conexión, servidor y credenciales.", true) }
        }
    }

    private fun loadLastEmail() {
        // ✅ CORRECCIÓN: Añadir Dispatchers.IO
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val email = repository.getLastEmail()
                if (!email.isNullOrBlank()) {
                    _savedEmail.value = email
                }
            } catch (e: Exception) {
                println("Error cargando email cache: ${e.message}")
            }
        }
    }

    fun login(email: String, password: String) {
        // ⚠️ CAMBIO IMPORTANTE: Lanzar en Dispatchers.IO para no congelar Desktop
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isLoading = true, success=false, message=null)

            val result = repository.login(email, password)

            _uiState.value = if (result.error != null) {
                _uiState.value.copy(isLoading=false, message = result.error, success = false, messageIsError=true)
            } else {
                _uiState.value.copy(isLoading=false, messageIsError=false,
                    message = result.mensaje,
                    success = true,
                    user = result
                )
            }
        }
    }


}
