package com.cyssoftware.effyflow

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cyssoftware.effyflow.db.*
import com.cyssoftware.effyflow.login.data.*
import com.cyssoftware.effyflow.login.data.model.*
import com.cyssoftware.effyflow.pedido.nuevoPedido.PedidoRepository
import com.cyssoftware.effyflow.pedido.nuevoPedido.viewmodel.*
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.*

internal val authUser = AuthorizedUser(1, "seller", "UAT seller", roles="VENDEDOR", active=true, appAuthorized=true, credentialVersion=1)
internal fun authResponse(version: Long = 1) = LoginResponse(roles="VENDEDOR", usuarioId=1, username="seller", nombre="UAT seller",
    accessToken="expired-access", refreshToken="refresh", active=true, appAuthorized=true, credentialVersion=version)
internal fun mockAuthClient(engine: MockEngine) = HttpClient(engine) {
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys=true }) }
}
internal fun AuthLocalDataSource.seedEnrollment(password: String="fixture-password") {
    applyCatalog(listOf(authUser)); enroll("seller", password, authResponse())
}

class OfflineAuthenticationTest {
    private fun noNetworkRepository(local: AuthLocalDataSource): LoginRepository = LoginRepository(local,
        { error("Local login must never resolve server configuration") }, mockAuthClient(MockEngine { error("Unexpected HTTP") }), kotlinx.coroutines.Dispatchers.Unconfined)

    @Test fun firstExecutionRequiresProvisioningWithoutRemoteFallback() = runBlocking<Unit> {
        ComercialFixture().use { f ->
            val repo = noNetworkRepository(AuthLocalDataSource(f.db))
            assertEquals("Se requiere conexión inicial para sincronizar usuarios.", repo.login("seller", "fixture-password").error)
            assertFalse(repo.hasCatalog())
        }
    }

    @Test fun provisionThenEnrollOnlineOnceAndUseLocalLoginThereafter() = runBlocking<Unit> {
        ComercialFixture().use { f ->
            val local=AuthLocalDataSource(f.db); var calls=0
            val engine=MockEngine { request ->
                calls++
                val body=if (request.url.encodedPath.endsWith("login")) Json.encodeToString(authResponse())
                    else Json.encodeToString(AuthorizedUserBatch(0,1,1,listOf(authUser)))
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType,"application/json"))
            }
            val client=mockAuthClient(engine); val repo=LoginRepository(local,{"http://localhost:8082"},client)
            try {
                repo.synchronizeUsers("seller","fixture-password")
                assertEquals(2,calls); assertTrue(repo.hasCatalog()); assertNull(local.findUser("seller")?.verifier)
                assertNull(local.getSession())
                assertNull(repo.login("seller","fixture-password").error)
                val enrolled=assertNotNull(local.findUser("seller"))
                assertNotNull(enrolled.salt); assertNotNull(enrolled.verifier); assertNotNull(enrolled.enrolledAt)
                assertEquals(600_000L,enrolled.iterations); assertEquals(3,calls)
                repo.logout()
                assertNull(repo.login("seller","fixture-password").error)
                assertNotNull(repo.login("seller","wrong").error)
                assertEquals(3,calls,"Local success and failure must generate no HTTP")
                assertNull(f.db.usersQueries.findById(1).executeAsOne().pass)
            } finally { client.close() }
        }
    }

    @Test fun offlineLoginIgnoresExpiredOrAbsentRemoteTokenAndServerConfiguration() = runBlocking<Unit> {
        ComercialFixture().use { f ->
            val local=AuthLocalDataSource(f.db); local.seedEnrollment(); local.clearSession()
            val repo=noNetworkRepository(local)
            assertNull(repo.login("seller","fixture-password").error)
            f.db.authQueries.deleteTokens(1)
            repo.logout()
            val session=repo.login("seller","fixture-password")
            assertNull(session.error); assertNull(session.accessToken); assertEquals(1,session.usuarioId)
            assertNotNull(repo.login("seller","incorrect-password").error)
        }
    }

    @Test fun verifierSurvivesFileCloseReopenAndLogout() = runBlocking<Unit> {
        val file=Files.createTempFile("effy-offline-auth-", ".sqlite")
        try {
            val url="jdbc:sqlite:${file.toAbsolutePath()}"
            val before=ComercialFixture(url).use { f ->
                val local=AuthLocalDataSource(f.db); local.seedEnrollment(); local.clearSession()
                local.findUser("seller")!!.verifier
            }
            repeat(2) {
                ComercialFixture(url,false).use { f ->
                    LocalSchemaMigrations.apply(f.driver)
                    val local=AuthLocalDataSource(f.db); val repo=noNetworkRepository(local)
                    assertEquals(before,local.findUser("seller")!!.verifier)
                    assertNull(repo.login("seller","fixture-password").error)
                    repo.logout(); assertNull(local.getSession()); assertTrue(local.hasCatalog())
                    assertEquals(before,local.findUser("seller")!!.verifier)
                }
            }
            assertFalse(Files.readAllBytes(file).toString(Charsets.ISO_8859_1).contains("fixture-password"))
        } finally { Files.deleteIfExists(file) }
    }

    @Test fun credentialVersionChangeRevokesSessionAndRequiresOnlineReEnrollment() = runBlocking<Unit> {
        ComercialFixture().use { f ->
            val local=AuthLocalDataSource(f.db); local.seedEnrollment()
            local.applyCatalog(listOf(authUser.copy(credentialVersion=2)))
            assertNull(local.findUser("seller")!!.verifier); assertNull(local.getSession())
            assertNull(f.db.authQueries.getTokens(1).executeAsOneOrNull())
            var calls=0
            val engine=MockEngine { request ->
                calls++
                if (request.body.toByteArray().decodeToString().contains("new-password"))
                    respond(Json.encodeToString(authResponse(2)),HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"))
                else respond("{}",HttpStatusCode.Unauthorized,headersOf(HttpHeaders.ContentType,"application/json"))
            }
            val client=mockAuthClient(engine); val repo=LoginRepository(local,{"http://localhost:8082"},client)
            try {
                assertNotNull(repo.login("seller","fixture-password").error)
                assertNull(repo.login("seller","new-password").error)
                assertEquals(2,calls); repo.logout()
                assertNull(repo.login("seller","new-password").error)
                assertNotNull(repo.login("seller","fixture-password").error)
                assertEquals(2,calls); assertEquals(2L,local.findUser("seller")!!.credentialVersion)
            } finally { client.close() }
        }
    }

    @Test fun disabledAndRemovedUsersLoseOpenSessionAndCannotLogin() = runBlocking<Unit> {
        ComercialFixture().use { f ->
            val local=AuthLocalDataSource(f.db); local.seedEnrollment()
            local.applyCatalog(listOf(authUser.copy(active=false)))
            assertNull(local.getSession()); assertNull(local.findUser("seller")!!.verifier)
            assertNotNull(noNetworkRepository(local).login("seller","fixture-password").error)
            local.seedEnrollment(); local.applyCatalog(emptyList())
            assertNull(local.getSession()); assertEquals(0L,local.findUser("seller")!!.active)
            assertNotNull(noNetworkRepository(local).login("seller","fixture-password").error)
        }
    }

    @Test fun invalidSnapshotCannotReplaceLastKnownAuthorization() {
        ComercialFixture().use { f ->
            val local=AuthLocalDataSource(f.db); local.seedEnrollment()
            val before=local.findUser("seller")
            assertFailsWith<IllegalArgumentException> { local.applyCatalog(listOf(authUser.copy(roles="COBRANZA"))) }
            assertFailsWith<IllegalArgumentException> { local.applyCatalog(listOf(authUser,authUser)) }
            assertEquals(before,local.findUser("seller")); assertNotNull(local.getSession())
        }
    }

    @Test fun staleVersionCannotRestoreOldAuthorization() {
        ComercialFixture().use { f ->
            val local=AuthLocalDataSource(f.db)
            local.applyCatalog(listOf(authUser.copy(credentialVersion=2)))
            assertFailsWith<IllegalArgumentException> { local.applyCatalog(listOf(authUser)) }
            assertEquals(2L,local.findUser("seller")!!.credentialVersion)
            assertNull(local.findUser("seller")!!.verifier)
        }
    }

    @Test fun provisioningRefusesRemoteCleartextTransport() {
        assertFailsWith<IllegalArgumentException> { requireSecureAuthUrl("http://sync.example.invalid") }
        assertEquals("https://sync.example.invalid",requireSecureAuthUrl("https://sync.example.invalid/"))
        assertEquals("http://localhost:8082",requireSecureAuthUrl("http://localhost:8082"))
    }

    @Test fun partialCatalogDownloadDoesNotRevokeExistingUsers() = runBlocking<Unit> {
        ComercialFixture().use { f ->
            val local=AuthLocalDataSource(f.db); local.seedEnrollment()
            val before=local.findUser("seller")
            val client=mockAuthClient(MockEngine { request ->
                if (request.url.encodedPath.endsWith("login")) respond(Json.encodeToString(authResponse()),HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"))
                else if (request.url.parameters["page"]=="0") respond(Json.encodeToString(AuthorizedUserBatch(0,2,2,listOf(authUser))),HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"))
                else respond("{}",HttpStatusCode.ServiceUnavailable,headersOf(HttpHeaders.ContentType,"application/json"))
            })
            try {
                assertFails { LoginRepository(local,{"http://localhost:8082"},client).synchronizeUsers("seller","fixture-password") }
                assertEquals(before,local.findUser("seller")); assertNotNull(local.getSession())
            } finally { client.close() }
        }
    }

    @Test fun localVerifierUsesIndependentRandomSaltAndSupportsUnicode() {
        val first=PasswordVerifier.create("contraseña-🔒")
        val second=PasswordVerifier.create("contraseña-🔒")
        assertNotEquals(first.salt,second.salt); assertNotEquals(first.verifier,second.verifier)
        assertTrue(PasswordVerifier.matches("contraseña-🔒",first.salt,first.verifier,first.iterations))
        assertFalse(PasswordVerifier.matches("wrong",first.salt,first.verifier,first.iterations))
    }

    @Test fun legacyMigrationScrubsPlaintextAndPreservesCommercialQueue() {
        val file=Files.createTempFile("effy-legacy-auth-", ".sqlite")
        try {
            ComercialFixture("jdbc:sqlite:${file.toAbsolutePath()}").use { f ->
                val order=f.add(); val before=f.db.pedidosQueries.findById(order).executeAsOne()
                f.driver.execute(null,"CREATE TABLE UserSession(id INTEGER, password TEXT)",0)
                f.driver.execute(null,"INSERT INTO UserSession VALUES(1,'legacy-password-to-remove')",0)
                f.driver.execute(null,"INSERT INTO users(id,pass) VALUES(1,'legacy-password-to-remove')",0)
                LocalSchemaMigrations.apply(f.driver); LocalSchemaMigrations.apply(f.driver)
                assertNull(f.db.usersQueries.findById(1).executeAsOne().pass)
                val count=f.driver.executeQuery(null,"SELECT count(*) FROM sqlite_master WHERE name='UserSession'",
                    { c -> c.next(); app.cash.sqldelight.db.QueryResult.Value(c.getLong(0)) },0).value
                assertEquals(0L,count); assertEquals(before,f.db.pedidosQueries.findById(order).executeAsOne())
                assertNull(AuthLocalDataSource(f.db).getSession())
            }
            assertFalse(Files.readAllBytes(file).toString(Charsets.ISO_8859_1).contains("legacy-password-to-remove"))
        } finally { Files.deleteIfExists(file) }
    }

    @Test fun localSellerCanCapturePedidoAndLeaveStableOfflineQueue() = runBlocking<Unit> {
        ComercialFixture().use { f ->
            val local=AuthLocalDataSource(f.db); local.seedEnrollment(); local.clearSession()
            assertNull(noNetworkRepository(local).login("seller","fixture-password").error)
            f.driver.execute(null,"INSERT INTO agentes(id_agente,id_usuario) VALUES(1,1)",0)
            f.driver.execute(null,"INSERT INTO clientes(id_cliente,nombre) VALUES(1,'fixture')",0)
            f.driver.execute(null,"INSERT INTO series_folios(id_serie) VALUES(10)",0)
            val id=PedidoRepository(f.db).guardarPedidoLocal(f.db.agentesQueries.findById(1).executeAsOne(),
                f.db.usersQueries.findById(1).executeAsOne(),f.db.clientesQueries.findById(1).executeAsOne(),
                f.db.series_foliosQueries.selectAll().executeAsList().single(),20,emptyList(),0,"",TipoDocumento.REMISION,null,TipoPedido.CONTADO)
            val order=f.db.pedidosQueries.findById(assertNotNull(id)).executeAsOne()
            assertEquals("PENDIENTE_SYNC",order.synced_at); assertNotNull(order.uuid_local)
            assertNotNull(java.time.OffsetDateTime.parse(order.fecha_creacion))
            local.clearSession()
            assertEquals(order,f.db.pedidosQueries.findById(id).executeAsOne())
        }
    }
}
