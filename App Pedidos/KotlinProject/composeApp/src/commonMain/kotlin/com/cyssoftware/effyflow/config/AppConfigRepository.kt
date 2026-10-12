package com.cyssoftware.effyflow.config

import com.cyssoftware.effyflow.db.DatabaseProvider
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppConfigRepository {
    private val queries = DatabaseProvider.database.app_configQueries

    fun getBaseUrl(): String? =
        queries.getValue(BASE_URL_KEY).executeAsOneOrNull()

    fun requireBaseUrl(): String =
        getBaseUrl() ?: error("Configura la URL del servidor antes de continuar.")

    fun saveBaseUrl(rawUrl: String): UrlValidationResult {
        val normalized = normalizeBaseUrl(rawUrl)
        if (normalized == null) return UrlValidationResult.Invalid
        queries.upsertValue(BASE_URL_KEY, normalized)
        return UrlValidationResult.Valid(normalized)
    }

    fun normalizeBaseUrl(rawUrl: String): String? {
        val trimmed = rawUrl.trim().trimEnd('/')
        if (trimmed.isBlank()) return null
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return null
        val hostPart = trimmed.substringAfter("://", "")
        if (hostPart.isBlank() || hostPart.contains(" ")) return null
        return trimmed
    }

    suspend fun testConnection(rawUrl: String): ConnectionTestResult = withContext(Dispatchers.Default) {
        val normalized = normalizeBaseUrl(rawUrl) ?: return@withContext ConnectionTestResult.InvalidUrl
        val client = HttpClient {
            install(HttpTimeout) {
                requestTimeoutMillis = 2500
                connectTimeoutMillis = 2000
                socketTimeoutMillis = 2500
            }
        }
        try {
            val response: HttpResponse =
                client.get("$normalized/api/health")
            if (response.status == HttpStatusCode.OK) {
                ConnectionTestResult.Connected(normalized)
            } else {
                ConnectionTestResult.Unavailable
            }
        } catch (_: Exception) {
            ConnectionTestResult.Unavailable
        } finally {
            client.close()
        }
    }

    companion object {
        private const val BASE_URL_KEY = "base_url"
    }
}

sealed class UrlValidationResult {
    data object Invalid : UrlValidationResult()
    data class Valid(val normalizedUrl: String) : UrlValidationResult()
}

sealed class ConnectionTestResult {
    data object InvalidUrl : ConnectionTestResult()
    data object Unavailable : ConnectionTestResult()
    data class Connected(val normalizedUrl: String) : ConnectionTestResult()
}
