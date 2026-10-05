package com.cyssoftware.effyflow.login.data

import com.cyssoftware.effyflow.config.AppConfigRepository
import com.cyssoftware.effyflow.login.data.model.LoginResponse
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class LoginRepository() { // ⬅️ Inyectamos la DB aquí

    private val localDataSource = AuthLocalDataSource()
    private val configRepository = AppConfigRepository()
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        isLenient = true
    }

    private val client = HttpClient {
        install(ContentNegotiation) {
            json(json)
        }
        // Configura timeout para no esperar eternamente si no hay red
        install(HttpTimeout) {
            requestTimeoutMillis = 1500
            connectTimeoutMillis = 1500
        }
    }

    fun updateTokens(accessToken: String, refreshToken: String) {
        localDataSource.updateTokens(accessToken, refreshToken)
    }

    fun getLastEmail(): String? {
        return localDataSource.getCachedEmail()
    }

    fun getOfflineSession(): LoginResponse? {
        return localDataSource.getSession()
    }


    suspend fun login(email: String, password: String): LoginResponse {
        // 🚀 Movemos la ejecución al hilo IO para no congelar la UI en Desktop
        return withContext(Dispatchers.IO) {
            try {
                // 1. INTENTO ONLINE
                val httpResponse = client.post("${configRepository.requireBaseUrl()}/api/auth/login") {
                    contentType(ContentType.Application.Json)
                    setBody(mapOf("email" to email, "password" to password))
                }

                if (!httpResponse.status.isSuccess()) {
                    val errorText = httpResponse.bodyAsText()
                    val errorMessage = runCatching {
                        json.decodeFromString(LoginResponse.serializer(), errorText).error
                    }.getOrNull()
                    return@withContext LoginResponse(
                        error = errorMessage ?: "Login rechazado por el servidor (${httpResponse.status.value})"
                    )
                }

                val response: LoginResponse = httpResponse.body()

                // 2. SI ES EXITOSO, GUARDAR EN SQLITE (Con protección extra)
                if (!response.accessToken.isNullOrBlank()) {
                    try {
                        localDataSource.saveSession(email, password, response)
                    } catch (e: Exception) {
                        println("⚠️ Error al guardar sesión local: ${e.message}")
                        // No fallamos el login si la BD falla, porque el usuario ya entró online.
                    }
                }

                response

            } catch (e: Exception) {
                // 3. FALLO ONLINE -> INTENTO OFFLINE
                println("Fallo conexión: ${e.message}. Buscando en cache...")

                // Al estar dentro de withContext(IO), esto ya no bloqueará la UI
                if (localDataSource.validateCredentials(email, password)) {
                    val offlineSession = localDataSource.getSession()
                    if (offlineSession != null) {
                        return@withContext offlineSession
                    }
                }

                // 4. NO HAY DATOS O CREDENCIALES INCORRECTAS
                LoginResponse(error = "No se puede conectar y no hay sesión guardada. (${e.message})")
            }
        }
    }
}
