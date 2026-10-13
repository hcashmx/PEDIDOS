package com.cyssoftware.effyflow.login.data

import com.cyssoftware.effyflow.config.AppConfigRepository
import com.cyssoftware.effyflow.cotizacion.cotizacionesSyncClient
import com.cyssoftware.effyflow.login.data.model.*
import com.cyssoftware.effyflow.pedido.sincronizacion.viewModel.configureSyncBearer
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

internal val authorizationMutex = Mutex()
typealias SyncAuthClientFactory = (
    () -> Pair<String?, String?>,
    (String, String) -> Unit,
    () -> String,
    Long
) -> HttpClient

class LoginRepository(
    private val local: AuthLocalDataSource = AuthLocalDataSource(),
    private val baseUrl: () -> String = { AppConfigRepository().requireBaseUrl() },
    private val client: HttpClient = authHttpClient(),
    private val dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
    private val syncClientFactory: SyncAuthClientFactory = { getToken, saveTokens, refreshUrl, timeoutMillis ->
        cotizacionesSyncClient(getToken, saveTokens, timeoutMillis, refreshUrl)
    }
) {
    fun updateTokens(accessToken: String, refreshToken: String) = local.updateTokens(accessToken, refreshToken)
    fun getLastEmail() = local.getCachedEmail()
    fun getOfflineSession() = local.getSession()
    fun getSyncTokens() = local.getTokens()
    fun getSyncTokensForUser(userId: Long): Pair<String?, String?> {
        val session = local.getSession()
        return if (session?.usuarioId?.toLong() == userId) session.accessToken to session.refreshToken else null to null
    }

    /** Resume only a persisted local identity that Sync still accepts; offline access still requires its password. */
    suspend fun restoreRemoteSession(): Long? = withContext(dispatcher) {
        authMutex.withLock {
            val session = local.getSession() ?: return@withLock null
            val userId = session.usuarioId?.toLong() ?: return@withLock null
            val endpoint = try {
                requireSecureAuthUrl(baseUrl())
            } catch (_: Exception) {
                return@withLock null
            }
            val syncClient = syncClientFactory(
                { getSyncTokensForUser(userId) },
                { access, refresh -> updateTokensForUser(userId, access, refresh) },
                { "$endpoint/api/auth/refresh" },
                SESSION_RESTORE_TIMEOUT_MILLIS
            )
            try {
                val response = syncClient.get("$endpoint/api/sync/catalogos/usuarios?page=0&size=1")
                response.bodyAsText()
                if (response.status == HttpStatusCode.OK && local.getSession()?.usuarioId?.toLong() == userId) userId else null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } finally {
                syncClient.close()
            }
        }
    }

    fun updateTokensForUser(userId: Long, accessToken: String, refreshToken: String) = local.updateTokensForUser(userId, accessToken, refreshToken)
    fun logout() = local.clearSession()
    fun hasCatalog() = local.hasCatalog()
    fun users() = local.users()

    suspend fun login(username: String, password: String): LoginResponse = withContext(dispatcher) {
        authMutex.withLock {
            if (!local.hasCatalog()) return@withLock LoginResponse(error="Se requiere conexión inicial para sincronizar usuarios.")
            val user = local.findUser(username)
            if (user == null || user.active != 1L) return@withLock LoginResponse(error="Usuario no autorizado o deshabilitado.")
            if (user.verifier != null) return@withLock local.loginLocally(username, password)
            println("ONLINE_ENROLLMENT_REQUIRED")
            try {
                val response = onlineLogin(user.username, password)
                if (response.error != null) return@withLock response
                // Metadata authenticated in the same request as the password. It may be newer than the catalog.
                require(response.usuarioId?.toLong() == user.userId && response.username == user.username)
                require(response.active == true && response.appAuthorized == true && response.roles in AuthLocalDataSource.APP_ROLES)
                val updated = local.users().map { row ->
                    if (row.userId == user.userId) AuthorizedUser(row.userId, row.username, response.nombre ?: row.nombre,
                        row.email, response.roles!!, true, true, checkNotNull(response.credentialVersion))
                    else AuthorizedUser(row.userId, row.username, row.nombre, row.email, row.roles, row.active==1L, true, row.credentialVersion)
                }
                local.applyCatalog(updated)
                local.enroll(user.username, password, response)
                checkNotNull(local.getSession())
            } catch (_: Exception) {
                LoginResponse(error="Se requiere conexión y autenticación online para habilitar este usuario en el dispositivo.")
            }
        }
    }

    /** Explicit provisioning action. Uses online credentials only to authorize the catalog request. */
    suspend fun synchronizeUsers(username: String, password: String): String = withContext(dispatcher) {
        authMutex.withLock {
            val response = onlineLogin(username.trim(), password)
            require(response.error == null && response.appAuthorized == true && response.active == true) {
                response.error ?: "Usuario sin autorización para sincronizar usuarios."
            }
            val userId = checkNotNull(response.usuarioId).toLong()
            val access = checkNotNull(response.accessToken); val refresh = checkNotNull(response.refreshToken)
            val users = downloadAuthorizedUsers(client, requireSecureAuthUrl(baseUrl()), access)
            local.applyCatalog(users)
            if (users.any { it.id == userId && it.active }) local.saveRemoteTokens(userId, access, refresh)
            "Usuarios sincronizados. Cada usuario requiere una primera autenticación online en este dispositivo."
        }
    }

    /** Available from home after local login; expired JWT refresh is independent of local access. */
    suspend fun synchronizeUsersWithSession(): String = withContext(dispatcher) {
        authMutex.withLock {
            val userId = checkNotNull(local.getSession()?.usuarioId).toLong()
            val syncClient = HttpClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys=true }) }
                install(HttpTimeout) { requestTimeoutMillis=15_000 }
                configureSyncBearer({ getSyncTokensForUser(userId) }, { access, refresh -> updateTokensForUser(userId, access, refresh) }) {
                    "${requireSecureAuthUrl(baseUrl())}/api/auth/refresh"
                }
            }
            try {
                local.applyCatalog(downloadAuthorizedUsers(syncClient, requireSecureAuthUrl(baseUrl())))
                "Usuarios sincronizados."
            } finally { syncClient.close() }
        }
    }

    private suspend fun onlineLogin(username: String, password: String): LoginResponse {
        val response = client.post("${requireSecureAuthUrl(baseUrl())}/api/auth/login") {
            contentType(ContentType.Application.Json); setBody(mapOf("username" to username, "password" to password))
        }
        if (!response.status.isSuccess()) return LoginResponse(error=when(response.status.value) {
            401 -> "Usuario o contraseña incorrectos."
            403 -> "Usuario sin autorización."
            else -> "Autenticación online no disponible (${response.status.value})."
        })
        return response.body()
    }
    companion object {
        private val authMutex = authorizationMutex
        private const val SESSION_RESTORE_TIMEOUT_MILLIS = 4_000L
    }
}

fun authHttpClient() = HttpClient {
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys=true }) }
    install(HttpTimeout) { requestTimeoutMillis=15_000; connectTimeoutMillis=5_000 }
}

internal fun requireSecureAuthUrl(baseUrl: String): String {
    val url = Url(baseUrl)
    require(url.protocol.name == "https" || (url.protocol.name == "http" && url.host in setOf("localhost", "127.0.0.1", "::1", "[::1]"))) {
        "La autenticación online requiere HTTPS. HTTP sólo se permite en loopback para UAT local."
    }
    return baseUrl.trimEnd('/')
}

/** Fetch every page before replacing authorization; failures leave the last good snapshot intact. */
suspend fun downloadAuthorizedUsers(client: HttpClient, baseUrl: String, token: String? = null): List<AuthorizedUser> {
    val endpoint = requireSecureAuthUrl(baseUrl)
    val all = mutableListOf<AuthorizedUser>()
    var page = 0
    var total: Long? = null
    do {
        val response = client.get("$endpoint/api/sync/catalogos/usuarios") {
            parameter("page", page); parameter("size", 500)
            if (token != null) bearerAuth(token)
        }
        check(response.status.isSuccess()) { "No se pudo sincronizar usuarios (${response.status.value})." }
        val batch: AuthorizedUserBatch = response.body()
        require(batch.page == page && batch.totalPages <= 1000 && batch.totalPages >= 0)
        if (total == null) total = batch.totalElements else require(total == batch.totalElements)
        all.addAll(batch.data)
        page++
        if (page >= batch.totalPages) break
    } while (true)
    require(all.size.toLong() == total)
    return all
}
