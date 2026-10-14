package com.cyssoftware.effyflow

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cyssoftware.effyflow.cotizacion.estados.*
import com.cyssoftware.effyflow.db.EffyDatabase
import com.cyssoftware.effyflow.db.LocalSchemaMigrations
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.*
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.io.File

/** Opt-in integration harness: exercises real Sync HTTP + file-backed SQLDelight SQLite. */
class CotizacionEstadosRealSyncIntegrationTest {
    @Serializable private data class LoginTokens(val accessToken: String, val refreshToken: String)
    private data class Actor(val email: String, val password: String, val userId: Long, val agentId: Long)
    private class TestClock(private val at: Instant) : AuthorizationClock {
        override fun wallNow() = at
        override fun elapsedRealtimeMillis() = 100_000L
    }

    @Test fun realSyncSnapshotIncrementalRefreshAndUserNamespaceIsolation() = runBlocking {
        val syncUrl = System.getenv("COT05C3P_SYNC_URL") ?: error("missing COT05C3P integration environment")
        val path = System.getenv("COT05C3P_SQLITE_PATH") ?: error("missing COT05C3P integration environment")
        val one = actor("ONE", 73001, 74001)
        val two = actor("TWO", 73002, 74002)
        val negative = actor("DENIED", 73003, 74003)
        val dbFile = File(path).absoluteFile
        val operational = File(System.getenv("LOCALAPPDATA") ?: "", "EffyFlow/effy.db").canonicalFile
        assertNotEquals(operational, dbFile.canonicalFile, "UAT must never use the operational SQLite path")
        check(!dbFile.exists()) { "Refusing to overwrite existing UAT SQLite: ${dbFile.absolutePath}" }
        dbFile.parentFile.mkdirs()
        val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath.replace('\\','/')}")
        try {
            EffyDatabase.Schema.create(driver)
            LocalSchemaMigrations.apply(driver)
            val db = EffyDatabase(driver)
            val repository = CotizacionEstadosRepository(db)
            val devices = DeviceIdentityRepository(db)
            val device = devices.getOrCreate()
            val tokenState = loginWithStaleAccess(syncUrl, one)
            val client = tokenState.first
            try {
                val apiOne = CotizacionEstadosApi(client, syncUrl)
                val sync = CotizacionEstadosSync(repository, devices, apiOne, one.userId, one.agentId, { true })
                val outcome = sync.synchronize()
                assertEquals("ACTIVA", outcome.state, "Sync outcome: ${outcome.message}; state=${outcome.state}")
                var session = assertNotNull(repository.latest(one.userId, one.agentId, device))
                assertEquals(session.committed, session.acked, "ACK must follow SQLite commit")
                assertTrue(session.snapshotEnd != null, "SNAPSHOT_END must be committed")
                assertTrue(session.committed >= 108, "Expected 103 snapshot rows, terminal marker, and four events")
                val oneDocs = repository.authorizedDocuments(one.userId, one.agentId, device)
                assertEquals(101, oneDocs.size, "Two reassigned/revoked documents should be tombstoned")
                assertFalse(oneDocs.any { it.uuid == "11111111-1111-4111-8111-111111111111" })
                assertFalse(oneDocs.any { it.uuid == "22222222-2222-4222-8222-222222222222" })
                assertTrue(tokenState.second(), "Stale access JWT should refresh using Sync's real endpoint")

                val expiredClock = TestClock(Instant.parse(session.validUntil!!) + 1.seconds)
                assertFailsWith<StateAccessDenied> {
                    CotizacionEstadosRepository(db, expiredClock).authorizedDocuments(one.userId, one.agentId, device)
                }
                val renewed = revalidateEventually(apiOne, session.sessionId, device)
                repository.updateLease(session, renewed)

                val reset = apiOne.reset(session.sessionId, device, session.generation)
                assertTrue(reset.purgeRequired)
                assertEquals(session.generation + 1, reset.generation)
                repository.purgeNamespace(session)
                assertFailsWith<StateAccessDenied> { repository.authorizedDocuments(one.userId, one.agentId, device) }
                assertEquals("ACTIVA", sync.synchronize().state)
                session = assertNotNull(repository.latest(one.userId, one.agentId, device))
                assertEquals(reset.generation, session.generation)
                assertEquals(session.committed, session.acked)
                assertEquals(101, repository.authorizedDocuments(one.userId, one.agentId, device).size)

                val secondTokens = loginWithStaleAccess(syncUrl, two)
                try {
                    val syncTwo = CotizacionEstadosSync(repository, devices,
                        CotizacionEstadosApi(secondTokens.first, syncUrl), two.userId, two.agentId, { true })
                    assertEquals("ACTIVA", syncTwo.synchronize().state)
                    val twoDocs = repository.authorizedDocuments(two.userId, two.agentId, device)
                    assertEquals(3, twoDocs.size, "Reassigned states should be visible in the new user's namespace")
                    assertTrue(twoDocs.any { it.uuid == "33333333-3333-4333-8333-333333333333" })
                    assertTrue(repository.authorizedDocuments(one.userId, one.agentId, device)
                        .none { d -> twoDocs.any { it.uuid == d.uuid } }, "The same SQLite file must isolate users")
                } finally { secondTokens.first.close() }

                val secondDbFile = File(dbFile.parentFile, dbFile.nameWithoutExtension + "-device2.sqlite")
                check(!secondDbFile.exists()) { "Refusing to overwrite second-device SQLite" }
                val secondDriver = JdbcSqliteDriver("jdbc:sqlite:${secondDbFile.absolutePath.replace('\\','/')}")
                try {
                    EffyDatabase.Schema.create(secondDriver)
                    LocalSchemaMigrations.apply(secondDriver)
                    val secondDb = EffyDatabase(secondDriver)
                    val secondRepo = CotizacionEstadosRepository(secondDb)
                    val secondDevices = DeviceIdentityRepository(secondDb)
                    val secondDevice = secondDevices.getOrCreate()
                    assertNotEquals(device, secondDevice)
                    val secondDeviceSync = CotizacionEstadosSync(secondRepo, secondDevices,
                        CotizacionEstadosApi(client, syncUrl), one.userId, one.agentId, { true })
                    assertEquals("ACTIVA", secondDeviceSync.synchronize().state)
                    val secondDeviceState = assertNotNull(secondRepo.latest(one.userId, one.agentId, secondDevice))
                    assertTrue(secondDeviceState.committed >= 108)
                    assertEquals(secondDeviceState.committed, secondDeviceState.acked)
                    println("COT05C3P_REAL_SYNC device2=$secondDevice session=${secondDeviceState.sessionId} generation=${secondDeviceState.generation} committed=${secondDeviceState.committed} acked=${secondDeviceState.acked}")
                } finally { secondDriver.close() }

                val deniedTokens = loginWithStaleAccess(syncUrl, negative)
                try {
                    val deniedApi = CotizacionEstadosApi(deniedTokens.first, syncUrl)
                    val error = runCatching { deniedApi.open(device) }.exceptionOrNull()
                    assertTrue(error is CotizacionEstadosHttpException && error.status == 403,
                        "User without cotizaciones.ver must be denied by real Sync")
                } finally { deniedTokens.first.close() }
                println("COT05C3P_REAL_SYNC user=${one.userId} agent=${one.agentId} device=$device session=${session.sessionId} generation=${session.generation} committed=${session.committed} acked=${session.acked} visible=101 refresh=true denial=403 leaseExpiry=true reset=true namespaceIsolation=true sqlite=${dbFile.absolutePath}")
            } finally { client.close() }
        } finally { driver.close() }
    }

    private suspend fun revalidateEventually(api: CotizacionEstadosApi, sessionId: String, deviceId: String): RevalidationDto {
        repeat(8) { attempt ->
            try { return api.revalidate(sessionId, deviceId) }
            catch (error: CotizacionEstadosHttpException) {
                if (!error.retryable || attempt == 7) throw error
                delay(250L * (attempt + 1))
            }
        }
        error("revalidation retry loop exhausted")
    }
    private suspend fun loginWithStaleAccess(base: String, actor: Actor): Pair<HttpClient, () -> Boolean> {
        val json = Json { ignoreUnknownKeys = true }
        val loginClient = HttpClient(CIO) { install(ContentNegotiation) { json(json) } }
        val tokens = try {
            loginClient.post("${base.trimEnd('/')}/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(mapOf("username" to actor.email, "password" to actor.password))
            }.body<LoginTokens>()
        } finally { loginClient.close() }
        var currentAccess = "expired-stale-access"
        var currentRefresh = tokens.refreshToken
        val http = cotizacionEstadosClient(
            getTokens = { currentAccess to currentRefresh },
            saveTokens = { access, refresh -> currentAccess = access; currentRefresh = refresh },
            refreshUrl = { "${base.trimEnd('/')}/api/auth/refresh" }
        )
        return http to { currentAccess != "expired-stale-access" }
    }

    private fun actor(suffix: String, user: Long, agent: Long): Actor {
        val email = System.getenv("COT05C3P_USER_$suffix") ?: error("missing COT05C3P integration environment")
        val password = System.getenv("COT05C3P_PASSWORD_$suffix") ?: error("missing COT05C3P integration environment")
        return Actor(email, password, user, agent)
    }
}