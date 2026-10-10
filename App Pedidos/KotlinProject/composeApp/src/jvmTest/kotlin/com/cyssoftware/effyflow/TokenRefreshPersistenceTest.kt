package com.cyssoftware.effyflow

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cyssoftware.effyflow.db.EffyDatabase
import com.cyssoftware.effyflow.login.data.AuthLocalDataSource
import com.cyssoftware.effyflow.login.data.model.LoginResponse
import java.nio.file.Files
import kotlin.test.*
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import com.cyssoftware.effyflow.pedido.sincronizacion.viewModel.configureSyncBearer

class TokenRefreshPersistenceTest {
    @Test fun lateRefreshCannotAssignPreviousUsersTokensToNewLocalUser() {
        ComercialFixture().use { f ->
            val storage=AuthLocalDataSource(f.db)
            seedSession(storage,"first","first-password",LoginResponse(accessToken="first-access",refreshToken="first-refresh"))
            storage.applyCatalog(listOf(
                com.cyssoftware.effyflow.login.data.model.AuthorizedUser(42,"first","First",roles="AGENTE",active=true,appAuthorized=true,credentialVersion=1),
                com.cyssoftware.effyflow.login.data.model.AuthorizedUser(43,"second","Second",roles="AGENTE",active=true,appAuthorized=true,credentialVersion=1)))
            storage.enroll("second","second-password",LoginResponse(usuarioId=43,username="second",roles="AGENTE",
                active=true,appAuthorized=true,credentialVersion=1,accessToken="second-access",refreshToken="second-refresh"))
            assertFailsWith<IllegalStateException> { storage.updateTokensForUser(42,"late-first-access","late-first-refresh") }
            assertEquals("second-access",storage.getSession()?.accessToken)
            assertEquals("first-access",f.db.authQueries.getTokens(42).executeAsOne().accessToken)
        }
    }
    private fun seedSession(storage: AuthLocalDataSource, username: String, password: String, data: LoginResponse) {
        storage.applyCatalog(listOf(com.cyssoftware.effyflow.login.data.model.AuthorizedUser(
            42, username, data.nombre ?: "Test", roles=data.roles ?: "AGENTE", active=true, appAuthorized=true, credentialVersion=1)))
        storage.enroll(username, password, data.copy(usuarioId=42, username=username, active=true,
            appAuthorized=true, credentialVersion=1, roles=data.roles ?: "AGENTE"))
    }

    @Test
    fun unauthorizedRequestRefreshesPersistsAndRetriesWithNewBearer() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            EffyDatabase.Schema.create(driver)
            val storage = AuthLocalDataSource(EffyDatabase(driver))
            seedSession(storage, "test@example.invalid", "test", LoginResponse(accessToken = "old-access", refreshToken = "old-refresh"))
            var refreshCalls = 0
            var protectedCalls = 0
            val engine = MockEngine { request ->
                if (request.url.encodedPath == "/api/auth/refresh") {
                    refreshCalls++
                    assertTrue(request.body.toByteArray().decodeToString().contains("old-refresh"))
                    respond("{\"accessToken\":\"new-access\",\"refreshToken\":\"new-refresh\",\"mensaje\":\"ok\"}",
                        HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                } else {
                    protectedCalls++
                    when (request.headers[HttpHeaders.Authorization]) {
                        "Bearer new-access" -> respond("{\"status\":\"ok\"}", HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentType, "application/json"))
                        else -> respond("", HttpStatusCode.Unauthorized,
                            headersOf(HttpHeaders.WWWAuthenticate, "Bearer"))
                    }
                }
            }
            val client = HttpClient(engine) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                configureSyncBearer(
                    getToken = { val session = storage.getSession(); Pair(session?.accessToken, session?.refreshToken) },
                    saveTokens = storage::updateTokens,
                    refreshUrl = { "https://test.invalid/api/auth/refresh" },
                )
            }
            try {
                val response = client.get("https://test.invalid/api/sync/catalogos/pedidos")
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals(1, refreshCalls)
                assertTrue(protectedCalls >= 2)
                assertEquals("new-access", storage.getSession()?.accessToken)
                assertEquals("new-refresh", storage.getSession()?.refreshToken)
            } finally { client.close() }
        } finally { driver.getConnection().close(); driver.close() }
    }
    @Test
    fun renewedTokensSurviveReopenAndPreserveSessionMetadata() {
        val file = Files.createTempFile("effy-token-test-", ".sqlite")
        try {
            val url = "jdbc:sqlite:${file.toAbsolutePath()}"
            val driver = JdbcSqliteDriver(url)
            try {
                EffyDatabase.Schema.create(driver)
                val storage = AuthLocalDataSource(EffyDatabase(driver))
                seedSession(storage, "test@example.invalid", "test-only-password", LoginResponse(
                    accessToken = "old-access", refreshToken = "old-refresh", roles = "AGENTE",
                    usuarioId = 42, nombre = "Test"))
                val saveTokens: (String, String) -> Unit = storage::updateTokens
                saveTokens("new-access", "new-refresh")
                assertEquals("new-access", storage.getSession()?.accessToken)
                assertEquals("new-refresh", storage.getSession()?.refreshToken)
                // Repeating the same renewal does not create another session.
                saveTokens("new-access", "new-refresh")
            } finally { driver.getConnection().close(); driver.close() }
            val reopenedDriver = JdbcSqliteDriver(url)
            try {
                val storage = AuthLocalDataSource(EffyDatabase(reopenedDriver))
                val session = assertNotNull(storage.getSession())
                assertEquals("new-access", session.accessToken)
                assertEquals("new-refresh", session.refreshToken)
                assertEquals(42, session.usuarioId)
                assertEquals("AGENTE", session.roles)
                assertEquals("Test", session.nombre)
                assertNull(storage.loginLocally("test@example.invalid", "test-only-password").error)
            } finally { reopenedDriver.getConnection().close(); reopenedDriver.close() }
        } finally { Files.deleteIfExists(file) }
    }
    @Test
    fun blankTokensDoNotOverwriteExistingSession() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            EffyDatabase.Schema.create(driver)
            val storage = AuthLocalDataSource(EffyDatabase(driver))
            seedSession(storage, "test@example.invalid", "test", LoginResponse(accessToken = "old-access", refreshToken = "old-refresh"))
            assertFailsWith<IllegalArgumentException> { storage.updateTokens("", "new-refresh") }
            assertFailsWith<IllegalArgumentException> { storage.updateTokens("new-access", " ") }
            assertEquals("old-access", storage.getSession()?.accessToken)
            assertEquals("old-refresh", storage.getSession()?.refreshToken)
        } finally { driver.getConnection().close(); driver.close() }
    }
    @Test
    fun refreshWithoutSessionDoesNotCreateAnIncompleteLogin() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            EffyDatabase.Schema.create(driver)
            val storage = AuthLocalDataSource(EffyDatabase(driver))
            assertFailsWith<IllegalStateException> { storage.updateTokens("access", "refresh") }
            assertNull(storage.getSession())
        } finally { driver.getConnection().close(); driver.close() }
    }
}
