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
    val user: LoginResponse? = null
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
            _uiState.value = LoginUiState(isLoading = true)

            val result = repository.login(email, password)

            _uiState.value = if (result.error != null) {
                LoginUiState(message = result.error, success = false)
            } else {
                LoginUiState(
                    message = result.mensaje,
                    success = true,
                    user = result
                )
            }
        }
    }


}