package com.cyssoftware.effyflow.pedido.sincronizacion.viewModel

import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.http.*
import kotlinx.serialization.json.*

class SyncAuthenticationException(message: String) : Exception(message)

internal fun HttpClientConfig<*>.configureSyncBearer(
    getToken: () -> Pair<String?, String?>,
    saveTokens: (String, String) -> Unit,
    refreshUrl: () -> String,
) {
    install(Auth) {
            bearer {
                // 1. Carga inicial de tokens
                loadTokens {
                    val (accessToken, refreshToken) = getToken()
                    if (accessToken != null && refreshToken != null) {
                        BearerTokens(accessToken, refreshToken)
                    } else {
                        null
                    }
                }
                // 2. Lógica para refrescar tokens ante un error 401
                refreshTokens {
                    val errorBody = runCatching { response.body<JsonObject>() }.getOrNull()
                    if (errorBody?.get("origenError")?.jsonPrimitive?.contentOrNull == "ERP") {
                        return@refreshTokens null
                    }
                    val oldRefreshToken = oldTokens?.refreshToken
                    if (oldRefreshToken == null) {
                        // No hay refresh token para usar, forzar re-login
                        throw SyncAuthenticationException("No refresh token available.")
                    }

                    val refreshUrl = refreshUrl()
                    val response: HttpResponse = client.post(refreshUrl) {
                        markAsRefreshTokenRequest()
                        contentType(ContentType.Application.Json)
                        // Usamos el refreshToken anterior para solicitar uno nuevo
                        setBody(mapOf("refreshToken" to oldRefreshToken))
                    }

                    if (response.status == HttpStatusCode.OK) {
                        val newTokens = response.body<RefreshTokenResponse>()
                        // 3. 💾 MUY IMPORTANTE: Guardar los nuevos tokens de forma persistente
                        saveTokens(newTokens.accessToken, newTokens.refreshToken)
                        // Retornar los nuevos tokens para que Ktor los use en el reintento
                        BearerTokens(newTokens.accessToken, newTokens.refreshToken)
                    } else {
                        // Si falla el refresh, forzar re-login
                        throw SyncAuthenticationException("Failed to refresh token: ${response.status}")
                    }
                }
            }
        }
}
