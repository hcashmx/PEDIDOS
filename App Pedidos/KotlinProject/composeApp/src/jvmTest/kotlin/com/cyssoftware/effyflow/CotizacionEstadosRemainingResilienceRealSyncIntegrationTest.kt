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
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Directed resilience cases against real Sync HTTP, PostgreSQL UAT and file-backed SQLDelight SQLite. */
class CotizacionEstadosRemainingResilienceRealSyncIntegrationTest {
    @Serializable private data class LoginTokens(val accessToken: String, val refreshToken: String)
    private data class Actor(val email: String, val password: String, val userId: Long, val agentId: Long)
    private data class Local(val file: File, val driver: JdbcSqliteDriver, val db: EffyDatabase,
        val repo: CotizacionEstadosRepository, val devices: DeviceIdentityRepository, val device: String)
    private data class PgState(val leaseHeld: Boolean, val erpCursor: Long,
        val deliveryAcked: Long, val retryCount: Int)

    private val one by lazy { Actor(env("COT05C3P_USER_ONE"), env("COT05C3P_PASSWORD_ONE"), 73001, 74001) }
    private val two by lazy { Actor(env("COT05C3P_USER_TWO"), env("COT05C3P_PASSWORD_TWO"), 73002, 74002) }
    private val three by lazy { Actor(env("COT05C3P_USER_THREE"), env("COT05C3P_PASSWORD_THREE"), 73003, 74003) }

    @Test fun erp429502503504AndReadTimeoutPreserveStateThenRecover() = runBlocking {
        val base = env("COT05C3P_SYNC_URL")
        val local = newLocal(env("COT05C3P_SQLITE_PATH"))
        val (client, _) = login(base, one)
        try {
            val api = CotizacionEstadosApi(client, base)
            val sync = CotizacionEstadosSync(local.repo, local.devices, api, one.userId, one.agentId, { true })
            assertEquals("ACTIVA", sync.synchronize().state)
            val before = assertNotNull(local.repo.latest(one.userId, one.agentId, local.device))
            val pgBefore = pgState(before.sessionId)
            for (erpStatus in listOf(429, 502, 503, 504)) {
                simulatorControl(buildJsonObject {
                    put("failNext", true); put("operation", "events"); put("status", erpStatus)
                    put("count", 2); put("forDeviceId", local.device)
                })
                val raw = rawRevalidate(base, one, before.sessionId, local.device)
                assertEquals(503, raw.first)
                println("COT05C3P3_ERROR_BODY upstream=$erpStatus code=${raw.second.code} retryable=${raw.second.retryable}")
                val error = runCatching { api.revalidate(before.sessionId, local.device) }.exceptionOrNull()
                assertTrue(error is CotizacionEstadosHttpException,
                    "ERP HTTP $erpStatus should become a Sync HTTP error; got ${error?.javaClass?.simpleName}")
                error as CotizacionEstadosHttpException
                assertEquals(503, error.status, "Sync maps transient ERP HTTP $erpStatus to 503")
                assertTrue(error.retryable, "upstream=$erpStatus Sync code=${error.code} status=${error.status} retryable=${error.retryable}: ${error.safeMessage}")
                val afterFailure = assertNotNull(local.repo.find(before.sessionId, before.generation))
                assertEquals(before.committed, afterFailure.committed)
                assertEquals(before.acked, afterFailure.acked)
                assertEquals(before.validUntil, afterFailure.validUntil)
                val pg = pgState(before.sessionId)
                assertEquals(pgBefore.erpCursor, pg.erpCursor)
                assertEquals(pgBefore.deliveryAcked, pg.deliveryAcked)
                val recovered = api.revalidate(before.sessionId, local.device)
                assertEquals("ACTIVA", recovered.state)
                println("COT05C3P3_ERP_HTTP upstream=$erpStatus syncStatus=${error.status} retryable=${error.retryable} local=${afterFailure.committed}/${afterFailure.acked} cursor=${pg.erpCursor} recovered=${recovered.state}")
            }

            simulatorControl(buildJsonObject {
                put("delayNext", true); put("operation", "events"); put("delayMs", 18_000)
                put("count", 1); put("forDeviceId", local.device)
            })
            val started = System.currentTimeMillis()
            val timeout = runCatching { api.revalidate(before.sessionId, local.device) }.exceptionOrNull()
            val duration = System.currentTimeMillis() - started
            assertTrue(timeout is CotizacionEstadosHttpException)
            timeout as CotizacionEstadosHttpException
            assertEquals(503, timeout.status)
            assertTrue(timeout.retryable)
            assertTrue(duration in 12_000L..22_000L, "Unexpected ERP read timeout duration: ${duration}ms")
            val afterTimeout = local.repo.find(before.sessionId, before.generation)!!
            assertEquals(before.committed, afterTimeout.committed)
            assertEquals(before.acked, afterTimeout.acked)
            assertEquals(before.validUntil, afterTimeout.validUntil)
            awaitSimulatorIdle("events")
            val pgAfterTimeout = pgState(before.sessionId)
            assertFalse(pgAfterTimeout.leaseHeld)
            assertEquals(pgBefore.erpCursor, pgAfterTimeout.erpCursor)
            assertEquals(pgBefore.deliveryAcked, pgAfterTimeout.deliveryAcked)
            assertEquals("ACTIVA", api.revalidate(before.sessionId, local.device).state)
            println("COT05C3P3_ERP_TIMEOUT durationMs=$duration status=${timeout.status} checkpoint=${afterTimeout.committed}/${afterTimeout.acked} leaseReleased=true recovered=true")
        } finally { client.close(); local.driver.close() }
    }

    @Test fun seedForSyncOutage() = runBlocking {
        val local = newLocal(env("COT05C3P_SQLITE_PATH"))
        val base = env("COT05C3P_SYNC_URL")
        val (client, _) = login(base, one)
        try {
            val sync = CotizacionEstadosSync(local.repo, local.devices, CotizacionEstadosApi(client, base),
                one.userId, one.agentId, { true })
            assertEquals("ACTIVA", sync.synchronize().state)
            val state = local.repo.latest(one.userId, one.agentId, local.device)!!
            println("COT05C3P3_SYNC_OUTAGE_SEED pid=${ProcessHandle.current().pid()} session=${state.sessionId} checkpoint=${state.committed}/${state.acked}")
        } finally { client.close(); local.driver.close() }
    }

    @Test fun syncDownKeepsAuthorizedData() = runBlocking {
        val local = openExisting(env("COT05C3P_SQLITE_PATH"))
        val before = local.repo.latest(one.userId, one.agentId, local.device)!!
        val stale = "uat-outage-no-token"
        val unavailableClient = cotizacionEstadosClient({ stale to stale }, { _, _ -> },
            { "${env("COT05C3P_SYNC_URL").trimEnd('/')}/api/auth/refresh" })
        try {
            val result = CotizacionEstadosSync(local.repo, local.devices,
                CotizacionEstadosApi(unavailableClient, env("COT05C3P_SYNC_URL")), one.userId, one.agentId, { true })
                .revalidate()
            assertEquals("ERROR_TEMPORAL", result.state)
            val after = local.repo.latest(one.userId, one.agentId, local.device)!!
            assertEquals(before.committed, after.committed)
            assertEquals(before.acked, after.acked)
            assertEquals(before.validUntil, after.validUntil)
            assertEquals(101, local.repo.authorizedDocuments(one.userId, one.agentId, local.device).size)
            println("COT05C3P3_SYNC_DOWN session=${before.sessionId} checkpoint=${after.committed}/${after.acked} leaseValid=true documents=101")
        } finally { unavailableClient.close(); local.driver.close() }
    }

    @Test fun recoverAfterSyncRestart() = runBlocking {
        val resumed = openExisting(env("COT05C3P_SQLITE_PATH"))
        val base = env("COT05C3P_SYNC_URL")
        val (client, _) = login(base, one)
        try {
            val consumer = CotizacionEstadosSync(resumed.repo, resumed.devices,
                CotizacionEstadosApi(client, base), one.userId, one.agentId, { true })
            assertEquals("ACTIVA", consumer.synchronize().state)
            val state = resumed.repo.latest(one.userId, one.agentId, resumed.device)!!
            assertEquals(state.committed, state.acked)
            assertEquals(101, resumed.repo.authorizedDocuments(one.userId, one.agentId, resumed.device).size)
            println("COT05C3P3_SYNC_RECOVER session=${state.sessionId} checkpoint=${state.committed}/${state.acked} documents=101")
        } finally { client.close(); resumed.driver.close() }
    }

    @Test fun seedForErpOutage() = runBlocking {
        val local = newLocal(env("COT05C3P_SQLITE_PATH"))
        val base = env("COT05C3P_SYNC_URL")
        val (client, _) = login(base, one)
        try {
            val sync = CotizacionEstadosSync(local.repo, local.devices, CotizacionEstadosApi(client, base),
                one.userId, one.agentId, { true })
            assertEquals("ACTIVA", sync.synchronize().state)
            val state = local.repo.latest(one.userId, one.agentId, local.device)!!
            println("COT05C3P3_ERP_OUTAGE_SEED session=${state.sessionId} checkpoint=${state.committed}/${state.acked}")
        } finally { client.close(); local.driver.close() }
    }

    @Test fun erpDownKeepsLease() = runBlocking {
        val base = env("COT05C3P_SYNC_URL")
        val local = openExisting(env("COT05C3P_SQLITE_PATH"))
        val (client, _) = login(base, one)
        try {
            val api = CotizacionEstadosApi(client, base)
            val before = local.repo.latest(one.userId, one.agentId, local.device)!!
            val pgBefore = pgState(before.sessionId)
            val result = CotizacionEstadosSync(local.repo, local.devices, api,
                one.userId, one.agentId, { true }).revalidate()
            assertEquals("ERROR_TEMPORAL", result.state)
            val after = local.repo.latest(one.userId, one.agentId, local.device)!!
            assertEquals(before.committed, after.committed)
            assertEquals(before.acked, after.acked)
            assertEquals(before.validUntil, after.validUntil)
            assertEquals(101, local.repo.authorizedDocuments(one.userId, one.agentId, local.device).size)
            val pgAfter = pgState(before.sessionId)
            assertEquals(pgBefore.erpCursor, pgAfter.erpCursor)
            assertEquals(pgBefore.deliveryAcked, pgAfter.deliveryAcked)
            assertTrue(pgAfter.retryCount > 0, "Temporary ERP failure must schedule worker backoff")
            println("COT05C3P3_ERP_DOWN session=${before.sessionId} checkpoint=${after.committed}/${after.acked} cursor=${pgAfter.erpCursor} leaseValid=true")
        } finally { client.close(); local.driver.close() }
    }

    @Test fun recoverAfterErpRestart() = runBlocking {
        val resumed = openExisting(env("COT05C3P_SQLITE_PATH"))
        val base = env("COT05C3P_SYNC_URL")
        val (retryClient, _) = login(base, one)
        try {
            val consumer = CotizacionEstadosSync(resumed.repo, resumed.devices,
                CotizacionEstadosApi(retryClient, base), one.userId, one.agentId, { true })
            assertEquals("ACTIVA", consumer.synchronize().state)
            val after = resumed.repo.latest(one.userId, one.agentId, resumed.device)!!
            assertEquals(after.committed, after.acked)
            assertEquals(101, resumed.repo.authorizedDocuments(one.userId, one.agentId, resumed.device).size)
            println("COT05C3P3_ERP_RECOVER session=${after.sessionId} checkpoint=${after.committed}/${after.acked} documents=101")
        } finally { retryClient.close(); resumed.driver.close() }
    }

    @Test fun erpWorkerAutomaticallyClearsBackoffAfterRecovery() = runBlocking {
        val local = openExisting(env("COT05C3P_SQLITE_PATH"))
        try {
            val before = local.repo.latest(one.userId, one.agentId, local.device)!!
            val remoteBefore = pgState(before.sessionId)
            val started = System.currentTimeMillis()
            var recovered: PgState? = null
            var attempts = 0
            while (recovered == null && attempts < 180) {
                attempts++
                val current = pgState(before.sessionId)
                if (!current.leaseHeld && current.retryCount == 0) {
                    recovered = current
                } else {
                    delay(500)
                }
            }
            val after = assertNotNull(recovered,
                "Sync worker did not clear its recorded ERP retry backoff after simulator recovery")
            assertEquals(remoteBefore.erpCursor, after.erpCursor,
                "No Kotlin request should be needed to retry authorization when there are no new ERP events")
            assertEquals(remoteBefore.deliveryAcked, after.deliveryAcked)
            assertEquals(before.committed, local.repo.latest(one.userId, one.agentId, local.device)!!.committed)
            assertEquals(101, local.repo.authorizedDocuments(one.userId, one.agentId, local.device).size)
            println("COT05C3P3_ERP_AUTO_RECOVERY session=${before.sessionId} waitMs=${System.currentTimeMillis()-started} retryCount=${after.retryCount} cursor=${after.erpCursor} ack=${after.deliveryAcked} noKotlinRequest=true")
        } finally { local.driver.close() }
    }

    @Test fun interruptedPageAndLateUserAgentResponsesDoNotCommitOrAck() = runBlocking {
        val base = env("COT05C3P_SYNC_URL")
        val proxy = env("COT05C3P_SYNC_PROXY_URL")
        val local = newLocal(env("COT05C3P_SQLITE_PATH"))
        val (direct, _) = login(base, one)
        try {
            val api = CotizacionEstadosApi(direct, base)
            val consumer = CotizacionEstadosSync(local.repo, local.devices, api, one.userId, one.agentId, { true })
            assertEquals("ACTIVA", consumer.synchronize().state)
            val before = local.repo.latest(one.userId, one.agentId, local.device)!!
            simulatorControl(buildJsonObject {
                put("appendEvents", buildJsonArray {
                    add(buildJsonObject {
                        put("eventId", 112); put("cotizacionUuid", "00000000-0000-4000-8000-000000000400")
                        put("erpId", 400); put("estado", "CANCELADA"); put("version", 5)
                        put("changedAt", "2026-10-13T18:13:00Z"); put("eventType", "REASIGNADA")
                        put("agenteVentasId", 74002); put("agenteAnteriorId", 74001)
                        put("usuarioAnteriorId", one.userId); put("usuarioCreoId", one.userId)
                    })
                })
            })
            api.revalidate(before.sessionId, local.device)
            assertEquals(112L, pgState(before.sessionId).erpCursor)
            val remote = api.state(before.sessionId, local.device)
            assertEquals(before.committed + 1, remote.lastAvailableSequence)
            val uuid = "00000000-0000-4000-8000-000000000400"
            assertEquals(4L, local.db.cotizacion_estadosQueries.findStateDocument(
                before.sessionId, before.generation, uuid).executeAsOneOrNull()?.version)

            proxyControl(buildJsonObject {
                put("cutNext", true); put("operation", "EVENTOS"); put("count", 1)
                put("forDeviceId", local.device)
            })
            val (viaProxy, _) = login(proxy, one)
            try {
                val outcome = CotizacionEstadosSync(local.repo, local.devices,
                    CotizacionEstadosApi(viaProxy, proxy), one.userId, one.agentId, { true }).synchronize()
                assertEquals("ERROR_TEMPORAL", outcome.state)
            } finally { viaProxy.close() }
            val afterCut = local.repo.find(before.sessionId, before.generation)!!
            assertEquals(before.committed, afterCut.committed)
            assertEquals(before.acked, afterCut.acked)
            assertNull(local.db.cotizacion_estadosQueries.findStateReceipt(
                before.sessionId, before.generation, before.committed + 1).executeAsOneOrNull())
            assertEquals(4L, local.db.cotizacion_estadosQueries.findStateDocument(
                before.sessionId, before.generation, uuid).executeAsOneOrNull()?.version)
            assertNull(local.db.cotizacion_estadosQueries.findStateTombstone(
                before.sessionId, before.generation, uuid).executeAsOneOrNull())
            assertEquals(before.acked, api.state(before.sessionId, local.device).lastAckedSequence)
            assertEquals("ACTIVA", consumer.synchronize().state)
            val recovered = local.repo.latest(one.userId, one.agentId, local.device)!!
            assertEquals(before.committed + 1, recovered.committed)
            assertEquals(recovered.committed, recovered.acked)
            assertNull(local.db.cotizacion_estadosQueries.findStateDocument(
                before.sessionId, before.generation, uuid).executeAsOneOrNull())
            assertEquals(5L, local.db.cotizacion_estadosQueries.findStateTombstone(
                before.sessionId, before.generation, uuid).executeAsOneOrNull()?.version)

            // Move the document back to the first agent, then make that event response arrive after logout.
            simulatorControl(buildJsonObject {
                put("appendEvents", buildJsonArray {
                    add(buildJsonObject {
                        put("eventId", 113); put("cotizacionUuid", uuid); put("erpId", 400)
                        put("estado", "VIGENTE"); put("version", 6)
                        put("changedAt", "2026-10-13T18:14:00Z"); put("eventType", "REASIGNACION_REVERTIDA")
                        put("agenteVentasId", 74001); put("agenteAnteriorId", 74002)
                        put("usuarioAnteriorId", one.userId); put("usuarioCreoId", one.userId)
                    })
                })
            })
            api.revalidate(before.sessionId, local.device)
            assertEquals(113L, pgState(before.sessionId).erpCursor)
            val identityActive = AtomicBoolean(true)
            proxyControl(buildJsonObject {
                put("delayNext", true); put("operation", "EVENTOS"); put("delayMs", 1800)
                put("count", 1); put("forDeviceId", local.device)
            })
            val (delayedClient, _) = login(proxy, one)
            try {
                val delayedConsumer = CotizacionEstadosSync(local.repo, local.devices,
                    CotizacionEstadosApi(delayedClient, proxy), one.userId, one.agentId,
                    { identityActive.get() })
                supervisorScope {
                    val pending = async(Dispatchers.IO) { delayedConsumer.synchronize() }
                    awaitProxyDelay("EVENTOS")
                    identityActive.set(false)
                    assertFailsWith<CancellationException> { pending.await() }
                }
            } finally { delayedClient.close() }
            val afterLate = local.repo.find(before.sessionId, before.generation)!!
            assertEquals(recovered.committed, afterLate.committed)
            assertEquals(recovered.acked, afterLate.acked)
            assertEquals(recovered.acked, api.state(before.sessionId, local.device).lastAckedSequence)
            assertEquals(5L, local.db.cotizacion_estadosQueries.findStateTombstone(
                before.sessionId, before.generation, uuid).executeAsOneOrNull()?.version)
            assertNull(local.repo.latest(two.userId, two.agentId, local.device))
            val (secondClient, _) = login(base, two)
            try {
                val other = CotizacionEstadosSync(local.repo, local.devices,
                    CotizacionEstadosApi(secondClient, base), two.userId, two.agentId, { true })
                assertEquals("ACTIVA", other.synchronize().state)
                val secondState = local.repo.latest(two.userId, two.agentId, local.device)!!
                assertTrue(secondState.sessionId != before.sessionId)
                assertFalse(local.repo.authorizedDocuments(two.userId, two.agentId, local.device)
                    .any { it.uuid == uuid })
                println("COT05C3P3_LATE_USER_RESPONSE old=${before.committed}/${before.acked} afterCut=${afterCut.committed}/${afterCut.acked} afterLate=${afterLate.committed}/${afterLate.acked} secondUserSession=${secondState.sessionId} noCrossNamespace=true tombstone=5")
            } finally { secondClient.close() }
        } finally { direct.close(); local.driver.close() }
    }

    @Test fun effectivePermissionRevocationAndAgentSwitchPurgeOnlyReceivedState() = runBlocking {
        val base = env("COT05C3P_SYNC_URL")
        setReadPermission(three.userId, true)
        try {
            val revokeLocal = newLocal(env("COT05C3P_SQLITE_PATH"))
            val (client, _) = login(base, three)
            try {
                val consumer = CotizacionEstadosSync(revokeLocal.repo, revokeLocal.devices,
                    CotizacionEstadosApi(client, base), three.userId, three.agentId, { true })
                assertEquals("ACTIVA", consumer.synchronize().state)
                val session = revokeLocal.repo.latest(three.userId, three.agentId, revokeLocal.device)!!
                assertEquals(1, revokeLocal.repo.authorizedDocuments(three.userId, three.agentId, revokeLocal.device).size)
                revokeLocal.db.app_configQueries.upsertValue("capture_pending_sentinel", "preserve-after-permission-revoke")
                setReadPermission(three.userId, false)
                val result = consumer.revalidate()
                assertEquals("REVOCADA", result.state)
                assertEquals("REVOCADA", revokeLocal.repo.find(session.sessionId, session.generation)!!.remoteState)
                assertEquals(0L, revokeLocal.db.cotizacion_estadosQueries.countStateDocuments(
                    session.sessionId, session.generation).executeAsOne())
                assertFailsWith<StateAccessDenied> {
                    revokeLocal.repo.authorizedDocuments(three.userId, three.agentId, revokeLocal.device)
                }
                assertEquals("preserve-after-permission-revoke",
                    revokeLocal.db.app_configQueries.getValue("capture_pending_sentinel").executeAsOne())
                println("COT05C3P3_PERMISSION_REVOKED session=${session.sessionId} removedEffectivePermission=true status=403 purged=true capturePreserved=true")
            } finally { client.close(); revokeLocal.driver.close() }

            setReadPermission(three.userId, true)
            val agentPath = File(env("COT05C3P_SQLITE_PATH")).resolveSibling("agent-change-${java.util.UUID.randomUUID()}.sqlite").path
            val agentLocal = newLocal(agentPath)
            val (direct, _) = login(base, three)
            try {
                val identityActive = AtomicBoolean(true)
                val directApi = CotizacionEstadosApi(direct, base)
                val consumer = CotizacionEstadosSync(agentLocal.repo, agentLocal.devices,
                    directApi, three.userId, three.agentId, { identityActive.get() })
                assertEquals("ACTIVA", consumer.synchronize().state)
                val before = agentLocal.repo.latest(three.userId, three.agentId, agentLocal.device)!!
                assertEquals(1, agentLocal.repo.authorizedDocuments(three.userId, three.agentId, agentLocal.device).size)
                agentLocal.db.app_configQueries.upsertValue("capture_pending_sentinel", "preserve-after-agent-switch")
                simulatorControl(buildJsonObject {
                    put("appendEvents", buildJsonArray {
                        add(buildJsonObject {
                            put("eventId", 114); put("cotizacionUuid", "44444444-4444-4444-8444-444444444444")
                            put("erpId", 504); put("estado", "VIGENTE"); put("version", 2)
                            put("changedAt", "2026-10-13T18:15:00Z"); put("eventType", "ACTUALIZADA")
                            put("agenteVentasId", 74003); put("agenteAnteriorId", JsonNull)
                            put("usuarioAnteriorId", JsonNull); put("usuarioCreoId", three.userId)
                        })
                    })
                })
                directApi.revalidate(before.sessionId, agentLocal.device)
                assertEquals(114L, pgState(before.sessionId).erpCursor)
                proxyControl(buildJsonObject {
                    put("delayNext", true); put("operation", "EVENTOS"); put("delayMs", 1800)
                    put("count", 1); put("forDeviceId", agentLocal.device)
                })
                val proxy = env("COT05C3P_SYNC_PROXY_URL")
                val (viaProxy, _) = login(proxy, three)
                try {
                    val delayed = CotizacionEstadosSync(agentLocal.repo, agentLocal.devices,
                        CotizacionEstadosApi(viaProxy, proxy), three.userId, three.agentId,
                        { identityActive.get() })
                    supervisorScope {
                        val pending = async(Dispatchers.IO) { delayed.synchronize() }
                        awaitProxyDelay("EVENTOS")
                        setAgent(three.userId, 74004)
                        identityActive.set(false)
                        assertFailsWith<CancellationException> { pending.await() }
                    }
                    val afterLate = agentLocal.repo.find(before.sessionId, before.generation)!!
                    assertEquals(before.committed, afterLate.committed)
                    assertEquals(before.acked, afterLate.acked)
                    assertEquals(1, agentLocal.repo.authorizedDocuments(three.userId, three.agentId, agentLocal.device).size)
                    identityActive.set(true)
                    val unusable = delayed.revalidate()
                    assertEquals("REVOCADA", unusable.state)
                    assertEquals(0L, agentLocal.db.cotizacion_estadosQueries.countStateDocuments(
                        before.sessionId, before.generation).executeAsOne())
                    assertEquals("preserve-after-agent-switch",
                        agentLocal.db.app_configQueries.getValue("capture_pending_sentinel").executeAsOne())
                    println("COT05C3P3_AGENT_SWITCH session=${before.sessionId} from=${three.agentId} to=74004 latePageDiscarded=true result=${unusable.state} purged=true capturePreserved=true")
                } finally { viaProxy.close() }
            } finally { direct.close(); agentLocal.driver.close() }
        } finally {
            runCatching { setAgent(three.userId, three.agentId.toInt()) }
            runCatching { setReadPermission(three.userId, false) }
        }
    }

    private suspend fun newLocal(path: String): Local {
        val file = safeSqlite(path, mustExist = false)
        check(!file.exists()) { "Refusing to overwrite temporary UAT SQLite: ${file.absolutePath}" }
        file.parentFile.mkdirs()
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath.replace('\\','/')}")
        EffyDatabase.Schema.create(driver)
        LocalSchemaMigrations.apply(driver)
        val db = EffyDatabase(driver)
        val devices = DeviceIdentityRepository(db)
        return Local(file, driver, db, CotizacionEstadosRepository(db), devices, devices.getOrCreate())
    }

    private suspend fun openExisting(path: String): Local {
        val file = safeSqlite(path, mustExist = true)
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath.replace('\\','/')}")
        val db = EffyDatabase(driver)
        val devices = DeviceIdentityRepository(db)
        return Local(file, driver, db, CotizacionEstadosRepository(db), devices, devices.getOrCreate())
    }

    private fun safeSqlite(path: String, mustExist: Boolean): File {
        val file = File(path).absoluteFile
        val operational = File(env("LOCALAPPDATA"), "EffyFlow/effy.db").canonicalFile
        assertTrue(file.canonicalFile != operational, "Refusing to open the operational SQLite database")
        val uatRoot = File(env("COT05C3P_SQLITE_ROOT")).canonicalFile.toPath()
        assertTrue(file.canonicalFile.toPath().startsWith(uatRoot), "SQLite test path must remain under the UAT root")
        if (mustExist) assertTrue(file.isFile, "Expected prior isolated suite SQLite file")
        return file
    }

    private suspend fun login(base: String, actor: Actor): Pair<HttpClient, () -> Boolean> {
        val json = Json { ignoreUnknownKeys = true }
        val loginClient = HttpClient(CIO) { install(ContentNegotiation) { json(json) } }
        val tokens = try {
            loginClient.post("${base.trimEnd('/')}/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(mapOf("username" to actor.email, "password" to actor.password))
            }.body<LoginTokens>()
        } finally { loginClient.close() }
        var access = "expired-stale-access"
        var refresh = tokens.refreshToken
        val client = cotizacionEstadosClient(
            getTokens = { access to refresh },
            saveTokens = { nextAccess, nextRefresh -> access = nextAccess; refresh = nextRefresh },
            refreshUrl = { "${base.trimEnd('/')}/api/auth/refresh" }
        )
        return client to { access != "expired-stale-access" }
    }

    private suspend fun rawRevalidate(base: String, actor: Actor, sessionId: String,
                                      deviceId: String): Pair<Int, SyncErrorBody> {
        val client = HttpClient(CIO) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        try {
            val tokens = client.post("${base.trimEnd('/')}/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(mapOf("username" to actor.email, "password" to actor.password))
            }.body<LoginTokens>()
            val response = client.post("${base.trimEnd('/')}/api/sync/cotizaciones/estados/sesion/$sessionId/revalidar") {
                header("Authorization", "Bearer ${tokens.accessToken}")
                header("X-Sync-Device-Id", deviceId)
            }
            val error = response.body<SyncErrorBody>()
            return response.status.value to error
        } finally { client.close() }
    }

    private suspend fun simulatorControl(body: JsonObject) = controlPost(
        "${env("COT05C3P_SIMULATOR_URL").trimEnd('/')}/__control", env("COT05C3P_CONTROL_KEY"), body)

    private suspend fun proxyControl(body: JsonObject) = controlPost(
        "${env("COT05C3P_SYNC_PROXY_URL").trimEnd('/')}/__proxy_control", env("COT05C3P_CONTROL_KEY"), body)

    private suspend fun controlPost(url: String, key: String, body: JsonObject) {
        val client = HttpClient(CIO) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        try {
            val response = client.post(url) {
                header("x-uat-control-key", key)
                contentType(ContentType.Application.Json)
                setBody(Json.encodeToString(JsonObject.serializer(), body))
            }
            assertEquals(HttpStatusCode.OK, response.status)
        } finally { client.close() }
    }

    private suspend fun simulatorDelayedReads(): Map<String, Int> {
        val client = HttpClient(CIO) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        try {
            val response = client.get("${env("COT05C3P_SIMULATOR_URL")}/__control/status") {
                header("x-uat-control-key", env("COT05C3P_CONTROL_KEY"))
            }
            assertEquals(HttpStatusCode.OK, response.status)
            return response.body<Map<String, Map<String, Int>>>()["activeDelayedReads"].orEmpty()
        } finally { client.close() }
    }

    private suspend fun awaitSimulatorIdle(operation: String) {
        repeat(120) {
            if ((simulatorDelayedReads()[operation] ?: 0) == 0) return
            delay(100)
        }
        error("ERP simulator still has delayed $operation reads")
    }

    private suspend fun awaitProxyDelay(operation: String) {
        val client = HttpClient(CIO) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        try {
            repeat(100) {
                val response = client.get("${env("COT05C3P_SYNC_PROXY_URL")}/__proxy_control/status") {
                    header("x-uat-control-key", env("COT05C3P_CONTROL_KEY"))
                }
                assertEquals(HttpStatusCode.OK, response.status)
                val active = response.body<Map<String, Map<String, Int>>>()["activeDelayedResponses"].orEmpty()
                if ((active[operation] ?: 0) > 0) return
                delay(50)
            }
        } finally { client.close() }
        error("Proxy did not begin delaying $operation response")
    }

    private fun pgState(sessionId: String): PgState {
        val connection = "host=127.0.0.1 port=55433 dbname=${env("COT05C3P_DB_NAME")} user=cotizaciones_uat_app sslmode=disable"
        val sql = "SELECT (lease_owner IS NOT NULL)::text || '|' || COALESCE(erp_cursor,-1)::text || '|' || " +
            "delivery_acked_sequence::text || '|' || retry_count::text FROM public.sync_cotizacion_sesion WHERE session_id='$sessionId'"
        val builder = ProcessBuilder(env("COT05C3P_PSQL"), "-X", "-w", connection,
            "-A", "-t", "-v", "ON_ERROR_STOP=1", "-c", sql).redirectErrorStream(true)
        builder.environment()["PGPASSWORD"] = File(env("COT05C3P_DB_PASSWORD_FILE")).readText().trim()
        val process = builder.start()
        val output = process.inputStream.bufferedReader().readText().trim()
        check(process.waitFor(15, TimeUnit.SECONDS)) { "PostgreSQL UAT diagnostic timed out" }
        check(process.exitValue() == 0) { "PostgreSQL UAT diagnostic failed" }
        val values = output.split('|')
        require(values.size == 4) { "Unexpected PostgreSQL session diagnostic" }
        return PgState(values[0].toBoolean(), values[1].toLong(), values[2].toLong(), values[3].toInt())
    }

    private fun setReadPermission(userId: Long, enabled: Boolean) {
        val sql = if (enabled) "INSERT INTO public.model_has_permissions(permission_id,model_type,model_id) " +
            "VALUES (72001,'App\\Models\\User',$userId) ON CONFLICT DO NOTHING"
        else "DELETE FROM public.model_has_permissions WHERE permission_id=72001 " +
            "AND model_type='App\\Models\\User' AND model_id=$userId"
        psql(sql)
    }

    private fun setAgent(userId: Long, agentId: Int) = psql(
        "UPDATE public.agentes SET id_agente=$agentId,updated_at=CURRENT_TIMESTAMP WHERE id_usuario=$userId")

    private fun psql(sql: String) {
        val connection = "host=127.0.0.1 port=55433 dbname=${env("COT05C3P_DB_NAME")} user=cotizaciones_uat_app sslmode=disable"
        val builder = ProcessBuilder(env("COT05C3P_PSQL"), "-X", "-w", connection,
            "-v", "ON_ERROR_STOP=1", "-c", sql).redirectErrorStream(true)
        builder.environment()["PGPASSWORD"] = File(env("COT05C3P_DB_PASSWORD_FILE")).readText().trim()
        val process = builder.start()
        val output = process.inputStream.bufferedReader().readText().trim()
        check(process.waitFor(15, TimeUnit.SECONDS)) { "PostgreSQL UAT fixture operation timed out" }
        check(process.exitValue() == 0) { "PostgreSQL UAT fixture operation failed: $output" }
    }

    private fun env(name: String) = System.getenv(name) ?: error("missing integration environment variable: $name")
}
