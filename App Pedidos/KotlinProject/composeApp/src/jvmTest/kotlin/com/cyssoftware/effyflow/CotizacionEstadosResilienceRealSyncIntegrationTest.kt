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
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real Sync HTTP + real PostgreSQL UAT + file-backed SQLDelight SQLite recovery scenarios. */
class CotizacionEstadosResilienceRealSyncIntegrationTest {
    @Serializable private data class LoginTokens(val accessToken: String, val refreshToken: String)
    private data class Actor(val email: String, val password: String, val userId: Long, val agentId: Long)
    private data class Local(val file: File, val driver: JdbcSqliteDriver, val db: EffyDatabase,
                             val repo: CotizacionEstadosRepository, val devices: DeviceIdentityRepository,
                             val device: String)

    private val one = Actor(
        env("COT05C3P_USER_ONE"), env("COT05C3P_PASSWORD_ONE"), 73001, 74001)
    private val two = Actor(
        env("COT05C3P_USER_TWO"), env("COT05C3P_PASSWORD_TWO"), 73002, 74002)
    private val three = Actor(
        env("COT05C3P_USER_THREE"), env("COT05C3P_PASSWORD_THREE"), 73003, 74003)

    @Test fun transactionFailureRollsBackRealSnapshotAndReceiptReplayIsIdempotent() = runBlocking {
        val base = env("COT05C3P_SYNC_URL")
        val local = newLocal(env("COT05C3P_SQLITE_PATH"))
        val (client, _) = login(base, one)
        try {
            val api = CotizacionEstadosApi(client, base)
            val session = open(api, local)
            val page = snapshotReady(api, local, session.sessionId)
            assertTrue(page.items.size >= 2)
            assertEquals(0L, local.repo.find(session.sessionId, session.generation)!!.committed)

            local.driver.execute(null,
                "CREATE TRIGGER fail_second_receipt BEFORE INSERT ON cot_estado_delivery_receipt " +
                    "WHEN NEW.delivery_sequence=2 BEGIN SELECT RAISE(ABORT,'injected UAT SQLite write failure'); END", 0)
            assertFailsWith<Exception> {
                local.repo.applySnapshot(page, one.userId, one.agentId, local.device)
            }
            assertEquals(0L, local.repo.find(session.sessionId, session.generation)!!.committed)
            assertEquals(0L, local.db.cotizacion_estadosQueries.countStateDocuments(session.sessionId,
                session.generation).executeAsOne())
            assertNull(local.db.cotizacion_estadosQueries.findStateReceipt(session.sessionId,
                session.generation, 1).executeAsOneOrNull())
            assertNull(local.db.cotizacion_estadosQueries.findStateReceipt(session.sessionId,
                session.generation, 2).executeAsOneOrNull())

            local.driver.execute(null, "DROP TRIGGER fail_second_receipt", 0)
            local.repo.applySnapshot(page, one.userId, one.agentId, local.device)
            local.repo.applySnapshot(page, one.userId, one.agentId, local.device)
            val committed = local.repo.find(session.sessionId, session.generation)!!
            assertEquals(page.toSequence, committed.committed)
            assertEquals(0L, committed.acked)
            assertEquals(page.items.count { it.document != null }.toLong(),
                local.db.cotizacion_estadosQueries.countStateDocuments(session.sessionId,
                    session.generation).executeAsOne())
            assertNotNull(local.db.cotizacion_estadosQueries.findStateReceipt(session.sessionId,
                session.generation, page.fromSequence).executeAsOneOrNull())
            val ack = api.acknowledge(session.sessionId, local.device, page.toSequence)
            local.repo.recordAck(committed, ack)
            println("COT05C3P2_SQLITE_ROLLBACK session=${session.sessionId} failedPageAtomic=true replayNoop=true committed=${page.toSequence} acked=${ack.ackThroughSequence}")
        } finally { client.close(); local.driver.close() }
    }

    /** This test deliberately exits after receiving a page but before SQLite commit. Resume is a separate JVM test run. */
    @Test fun processBoundaryBeforeCommitLeavesCheckpointUnchanged() = runBlocking {
        val base = env("COT05C3P_SYNC_URL")
        val local = newLocal(env("COT05C3P_SQLITE_PATH"))
        val (client, _) = login(base, one)
        try {
            val api = CotizacionEstadosApi(client, base)
            val session = open(api, local)
            val page = snapshotReady(api, local, session.sessionId)
            assertTrue(page.items.isNotEmpty())
            assertEquals(0L, local.repo.find(session.sessionId, session.generation)!!.committed)
            assertEquals(0L, local.repo.find(session.sessionId, session.generation)!!.acked)
            println("COT05C3P2_BEFORE_COMMIT process=${ProcessHandle.current().pid()} session=${session.sessionId} received=${page.fromSequence}-${page.toSequence} committed=0 acked=0")
        } finally { client.close(); local.driver.close() }
    }

    /** Run in a later Gradle/JVM invocation using the same SQLite path as processBoundaryBeforeCommitLeavesCheckpointUnchanged. */
    @Test fun newProcessReplaysPageLostBeforeCommitAndCompletes() = runBlocking {
        val base = env("COT05C3P_SYNC_URL")
        val local = openExisting(env("COT05C3P_SQLITE_PATH"))
        val before = assertNotNull(local.repo.latest(one.userId, one.agentId, local.device))
        assertEquals(0L, before.committed)
        assertEquals(0L, before.acked)
        val (client, _) = login(base, one)
        try {
            val sync = CotizacionEstadosSync(local.repo, local.devices,
                CotizacionEstadosApi(client, base), one.userId, one.agentId, { true })
            val result = sync.synchronize()
            assertEquals("ACTIVA", result.state, result.message)
            val after = assertNotNull(local.repo.latest(one.userId, one.agentId, local.device))
            assertEquals(114L, after.committed)
            assertEquals(after.committed, after.acked)
            assertEquals(101, local.repo.authorizedDocuments(one.userId, one.agentId, local.device).size)
            println("COT05C3P2_BEFORE_COMMIT_RECOVER process=${ProcessHandle.current().pid()} session=${after.sessionId} generation=${after.generation} committed=${after.committed} acked=${after.acked}")
        } finally { client.close(); local.driver.close() }
    }

    /** This process commits SQLite and exits without ACK; the next Gradle/JVM run recovers it. */
    @Test fun processBoundaryAfterCommitBeforeAckPersistsPage() = runBlocking {
        val base = env("COT05C3P_SYNC_URL")
        val local = newLocal(env("COT05C3P_SQLITE_PATH"))
        val (client, _) = login(base, one)
        try {
            val api = CotizacionEstadosApi(client, base)
            val session = open(api, local)
            val page = snapshotReady(api, local, session.sessionId)
            local.repo.applySnapshot(page, one.userId, one.agentId, local.device)
            val durable = local.repo.find(session.sessionId, session.generation)!!
            assertEquals(page.toSequence, durable.committed)
            assertEquals(0L, durable.acked)
            println("COT05C3P2_AFTER_COMMIT process=${ProcessHandle.current().pid()} session=${session.sessionId} committed=${durable.committed} acked=0 receipts=${page.items.size}")
        } finally { client.close(); local.driver.close() }
    }

    /** Run in a later Gradle/JVM invocation using the same SQLite path as processBoundaryAfterCommitBeforeAckPersistsPage. */
    @Test fun newProcessReconcilesCommittedUnackedPageAndCompletes() = runBlocking {
        val base = env("COT05C3P_SYNC_URL")
        val local = openExisting(env("COT05C3P_SQLITE_PATH"))
        val before = assertNotNull(local.repo.latest(one.userId, one.agentId, local.device))
        assertTrue(before.committed > before.acked)
        val committedBeforeRestart = before.committed
        val (client, _) = login(base, one)
        try {
            val sync = CotizacionEstadosSync(local.repo, local.devices,
                CotizacionEstadosApi(client, base), one.userId, one.agentId, { true })
            val result = sync.synchronize()
            assertEquals("ACTIVA", result.state, result.message)
            val after = assertNotNull(local.repo.latest(one.userId, one.agentId, local.device))
            assertTrue(after.committed >= committedBeforeRestart)
            assertEquals(114L, after.committed)
            assertEquals(after.committed, after.acked)
            assertEquals(101, local.repo.authorizedDocuments(one.userId, one.agentId, local.device).size)
            println("COT05C3P2_AFTER_COMMIT_RECOVER process=${ProcessHandle.current().pid()} session=${after.sessionId} generation=${after.generation} priorCommitted=$committedBeforeRestart committed=${after.committed} acked=${after.acked}")
        } finally { client.close(); local.driver.close() }
    }

    @Test fun ackResponseLostAfterRemoteCommitReconcilesWithoutCursorAdvance() = runBlocking {
        val base = env("COT05C3P_SYNC_URL")
        val proxy = env("COT05C3P_SYNC_PROXY_URL")
        val local = newLocal(env("COT05C3P_SQLITE_PATH"))
        val (viaProxy, _) = login(proxy, one)
        try {
            val firstSync = CotizacionEstadosSync(local.repo, local.devices,
                CotizacionEstadosApi(viaProxy, proxy), one.userId, one.agentId, { true })
            val interrupted = firstSync.synchronize()
            assertEquals("ERROR_TEMPORAL", interrupted.state)
            val localAfterLoss = assertNotNull(local.repo.latest(one.userId, one.agentId, local.device))
            assertTrue(localAfterLoss.committed > localAfterLoss.acked,
                "The SQLite ACK checkpoint must not advance when the response is dropped")

            val (direct, _) = login(base, one)
            try {
                val directApi = CotizacionEstadosApi(direct, base)
                val remote = directApi.state(localAfterLoss.sessionId, local.device)
                assertEquals(localAfterLoss.committed, remote.lastAckedSequence,
                    "The upstream ACK must have committed before the proxy dropped its response")
                val resume = CotizacionEstadosSync(local.repo, local.devices, directApi,
                    one.userId, one.agentId, { true })
                val recovered = resume.synchronize()
                assertEquals("ACTIVA", recovered.state, recovered.message)
                val after = assertNotNull(local.repo.latest(one.userId, one.agentId, local.device))
                assertEquals(114L, after.committed)
                assertEquals(114L, after.acked)
                assertEquals(101, local.repo.authorizedDocuments(one.userId, one.agentId, local.device).size)
                println("COT05C3P2_ACK_LOSS session=${after.sessionId} localBefore=${localAfterLoss.committed}/${localAfterLoss.acked} remoteBefore=${remote.lastAckedSequence} recovered=${after.committed}/${after.acked}")
            } finally { direct.close() }
        } finally { viaProxy.close(); local.driver.close() }
    }

    @Test fun realDeliveryOrdersVersionsAndPreservesTombstoneUntilHigherReappearance() = runBlocking {
        val base = env("COT05C3P_SYNC_URL")
        val local = newLocal(env("COT05C3P_SQLITE_PATH"))
        val (client, _) = login(base, one)
        try {
            val api = CotizacionEstadosApi(client, base)
            val opened = open(api, local)
            var state = assertNotNull(local.repo.latest(one.userId, one.agentId, local.device))
            while (state.bootstrapState != "ACTIVA") {
                val page = snapshotReady(api, local, opened.sessionId, state.acked)
                local.repo.applySnapshot(page, one.userId, one.agentId, local.device)
                val ack = api.acknowledge(opened.sessionId, local.device, page.toSequence)
                local.repo.recordAck(local.repo.find(opened.sessionId, opened.generation)!!, ack)
                state = local.repo.find(opened.sessionId, opened.generation)!!
            }
            val uuid = "00000000-0000-4000-8000-000000000400"
            val target = 400L
            val expectedIds = listOf(101L,102L,103L,105L,106L,107L,108L,109L,110L,111L)
            for (expected in expectedIds) {
                val page = eventsReady(api, local, opened.sessionId, state.committed)
                assertEquals(1, page.events.size)
                val event = page.events.single()
                assertEquals(expected, event.eventId)
                local.repo.applyEvents(page, one.userId, one.agentId, local.device)
                val ack = api.acknowledge(opened.sessionId, local.device, page.toSequence)
                local.repo.recordAck(local.repo.find(opened.sessionId, opened.generation)!!, ack)
                state = local.repo.find(opened.sessionId, opened.generation)!!
                if (event.erpId == target) {
                    val doc = local.db.cotizacion_estadosQueries.findStateDocument(opened.sessionId,
                        opened.generation, uuid).executeAsOneOrNull()
                    val tombstone = local.db.cotizacion_estadosQueries.findStateTombstone(opened.sessionId,
                        opened.generation, uuid).executeAsOneOrNull()
                    when (event.eventId) {
                        106L, 107L, 108L -> assertEquals(2L, doc?.version,
                            "Newer v2 applies; older v1 and equal compatible v2 are semantic no-ops")
                        109L -> { assertNull(doc); assertEquals(3L, tombstone?.version) }
                        110L -> { assertNull(doc); assertEquals(3L, tombstone?.version,
                            "Older visible event must not resurrect a tombstoned row") }
                        111L -> { assertEquals(4L, doc?.version); assertNull(tombstone,
                            "Authorized version 4 may reappear after tombstone version 3") }
                    }
                }
            }
            assertEquals(114L, state.committed)
            assertEquals(state.committed, state.acked)
            println("COT05C3P2_VERSION session=${state.sessionId} events=${expectedIds.joinToString(",")} finalVersion=4 tombstoneRetainedUntilHigherVersion=true committed=${state.committed} acked=${state.acked}")
        } finally { client.close(); local.driver.close() }
    }

    @Test fun contradictoryEqualVersionFromWireBlocksLocalPageAndDoesNotAck() = runBlocking {
        val base = env("COT05C3P_SYNC_URL")
        val proxy = env("COT05C3P_SYNC_PROXY_URL")
        val local = newLocal(env("COT05C3P_SQLITE_PATH"))
        val (viaProxy, _) = login(proxy, one)
        try {
            val consumer = CotizacionEstadosSync(local.repo, local.devices,
                CotizacionEstadosApi(viaProxy, proxy), one.userId, one.agentId, { true })
            val outcome = consumer.synchronize()
            assertEquals("REQUIERE_REINICIO", outcome.state, outcome.message)
            val blocked = assertNotNull(local.repo.latest(one.userId, one.agentId, local.device))
            assertEquals("REQUIERE_REINICIO", blocked.bootstrapState)
            assertEquals("CONFLICTO_INTEGRIDAD", blocked.errorCode)
            assertEquals(104L, blocked.committed)
            assertEquals(104L, blocked.acked)
            assertEquals(103L, local.db.cotizacion_estadosQueries.countStateDocuments(
                blocked.sessionId, blocked.generation).executeAsOne())
            assertNull(local.db.cotizacion_estadosQueries.findStateReceipt(
                blocked.sessionId, blocked.generation, 105).executeAsOneOrNull())

            val (direct, _) = login(base, one)
            try {
                val remote = CotizacionEstadosApi(direct, base).state(blocked.sessionId, local.device)
                assertEquals(104L, remote.lastAckedSequence,
                    "The client must not ACK the contradictory event page")
            } finally { direct.close() }
            println("COT05C3P2_EQUAL_VERSION_CONFLICT session=${blocked.sessionId} committed=${blocked.committed} acked=${blocked.acked} remoteAck=104 documents=103")
        } finally { viaProxy.close(); local.driver.close() }
    }

    @Test fun lowQueueSuspendsOnlyLargeSessionAndResumesAfterSnapshotAck() = runBlocking {
        val base = env("COT05C3P_SYNC_URL")
        val local = newLocal(env("COT05C3P_SQLITE_PATH"))
        val (client, _) = login(base, one)
        try {
            val api = CotizacionEstadosApi(client, base)
            val opened = open(api, local)
            val sessionId = opened.sessionId
            var remote = awaitState(api, sessionId, local.device) { it.snapshotComplete && it.ingestionSuspended }
            assertTrue(remote.pendingQueueEntries >= 100)
            val pgDuring = pgSessionStatus(sessionId)
            assertFalse(pgDuring.leaseHeld, "The worker lease must be released after the saturated check")
            assertTrue(pgDuring.queueSaturated)
            val cursorBefore = pgDuring.erpCursor
            assertEquals(111L, cursorBefore)

            control(buildJsonObject {
                put("appendEvents", buildJsonArray {
                    add(buildJsonObject {
                        put("eventId", 112); put("forUserId", one.userId)
                        put("cotizacionUuid", "00000000-0000-4000-8000-000000000400")
                        put("erpId", 400); put("estado", "CANCELADA"); put("version", 5)
                        put("changedAt", "2026-10-13T18:15:00Z"); put("eventType", "CANCELADA")
                        put("agenteVentasId", one.agentId); put("agenteAnteriorId", JsonNull)
                        put("usuarioAnteriorId", JsonNull); put("usuarioCreoId", one.userId)
                    })
                })
            })
            delay(11_000)
            val stillSuspended = pgSessionStatus(sessionId)
            assertTrue(stillSuspended.queueSaturated)
            assertEquals(cursorBefore, stillSuspended.erpCursor,
                "ERP cursor must stay fixed while a saturated session is suspended")
            assertEquals(0L, stillSuspended.deliveryAcked)

            val consumer = CotizacionEstadosSync(local.repo, local.devices, api,
                one.userId, one.agentId, { true })
            val result = consumer.synchronize()
            assertEquals("ACTIVA", result.state, result.message)
            remote = awaitState(api, sessionId, local.device) { !it.ingestionSuspended && it.lastAvailableSequence >= 115 }
            val pgAfter = pgSessionStatus(sessionId)
            assertFalse(pgAfter.queueSaturated)
            assertEquals(112L, pgAfter.erpCursor)
            assertEquals(115L, pgAfter.deliveryAcked)
            println("COT05C3P2_BACKPRESSURE session=$sessionId beforeEntries=${remote.pendingQueueEntries} cursorPaused=$cursorBefore cursorResumed=${pgAfter.erpCursor} ack=${pgAfter.deliveryAcked} resumed=true")
        } finally { client.close(); local.driver.close() }

        val otherPath = File(env("COT05C3P_SQLITE_PATH")).resolveSibling("${File(env("COT05C3P_SQLITE_PATH")).nameWithoutExtension}-other.sqlite").path
        val other = newLocal(otherPath)
        val (otherClient, _) = login(base, two)
        try {
            val otherApi = CotizacionEstadosApi(otherClient, base)
            val otherSync = CotizacionEstadosSync(other.repo, other.devices, otherApi,
                two.userId, two.agentId, { true })
            assertEquals("ACTIVA", otherSync.synchronize().state)
            val otherState = assertNotNull(other.repo.latest(two.userId, two.agentId, other.device))
            assertFalse(otherState.ingestionSuspended, "A small independent session should remain serviceable")
            assertTrue(otherState.committed > 0)
            println("COT05C3P2_BACKPRESSURE_OTHER session=${otherState.sessionId} user=${two.userId} suspended=false committed=${otherState.committed}")
        } finally { otherClient.close(); other.driver.close() }
    }

    @Test fun confirmedERPRevocationPurgesReceivedStateButPreservesCaptureSentinel() = runBlocking {
        val base = env("COT05C3P_SYNC_URL")
        val local = newLocal(env("COT05C3P_SQLITE_PATH"))
        val (client, _) = login(base, one)
        try {
            val api = CotizacionEstadosApi(client, base)
            val consumer = CotizacionEstadosSync(local.repo, local.devices, api,
                one.userId, one.agentId, { true })
            assertEquals("ACTIVA", consumer.synchronize().state)
            val session = assertNotNull(local.repo.latest(one.userId, one.agentId, local.device))
            local.db.app_configQueries.upsertValue("capture_pending_sentinel", "preserve-uat-outbox")
            assertEquals(101, local.repo.authorizedDocuments(one.userId, one.agentId, local.device).size)
            control(buildJsonObject { put("revokeUserId", one.userId) })
            val result = consumer.revalidate()
            assertEquals("REVOCADA", result.state)
            assertEquals("REVOCADA", local.repo.find(session.sessionId, session.generation)!!.remoteState)
            assertEquals(0L, local.db.cotizacion_estadosQueries.countStateDocuments(
                session.sessionId, session.generation).executeAsOne())
            assertFailsWith<StateAccessDenied> {
                local.repo.authorizedDocuments(one.userId, one.agentId, local.device)
            }
            assertEquals("preserve-uat-outbox",
                local.db.app_configQueries.getValue("capture_pending_sentinel").executeAsOne())
            println("COT05C3P2_REVOCATION session=${session.sessionId} state=REVOCADA documentsPurged=true captureSentinelPreserved=true")
        } finally {
            runCatching { control(buildJsonObject { put("restoreUserId", one.userId) }) }
            client.close(); local.driver.close()
        }
    }

    @Test fun concurrentRevalidationMeasuresWorkerLease503AndRecovers() = runBlocking {
        val base = env("COT05C3P_SYNC_URL")
        val local = newLocal(env("COT05C3P_SQLITE_PATH"))
        val (client, _) = login(base, one)
        val monitor = HttpClient(CIO) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        try {
            val api = CotizacionEstadosApi(client, base)
            val consumer = CotizacionEstadosSync(local.repo, local.devices, api, one.userId, one.agentId, { true })
            assertEquals("ACTIVA", consumer.synchronize().state)
            val session = assertNotNull(local.repo.latest(one.userId, one.agentId, local.device))
            control(buildJsonObject {
                put("delayNext", true)
                put("operation", "events")
                put("delayMs", 5000)
                put("count", 1)
                put("forDeviceId", local.device)
            })

            data class Attempt(val startedMs: Long, val endedMs: Long, val status: Int)
            suspend fun attempt() : Attempt {
                val started = System.currentTimeMillis()
                val status = try {
                    api.revalidate(session.sessionId, local.device)
                    200
                } catch (error: CotizacionEstadosHttpException) {
                    error.status
                }
                return Attempt(started, System.currentTimeMillis(), status)
            }

            val first = async(Dispatchers.IO) { attempt() }
            var delayedReads = 0
            for (poll in 0 until 60) {
                val status = monitor.get("${env("COT05C3P_SIMULATOR_URL")}/__control/status") {
                    header("x-uat-control-key", env("COT05C3P_CONTROL_KEY"))
                }
                assertEquals(HttpStatusCode.OK, status.status)
                val state = status.body<Map<String, Map<String, Int>>>()
                delayedReads = state["activeDelayedReads"]?.get("events") ?: 0
                if (delayedReads > 0) break
                delay(100)
            }
            assertEquals(1, delayedReads, "Expected a delayed ERP request for this device before contending")
            var leaseDuring = pgSessionStatus(session.sessionId)
            assertTrue(leaseDuring.leaseHeld, "Expected an active PostgreSQL worker lease during delayed ERP read")
            assertTrue(leaseDuring.leaseSeconds in 1L..60L)
            val contender = async(Dispatchers.IO) { attempt() }
            val second = contender.await()
            val firstResult = first.await()
            assertEquals(503, second.status, "A concurrent revalidation must report transient worker contention")
            assertTrue(firstResult.status == 200 || firstResult.status == 503)

            val recovered = attempt()
            assertEquals(200, recovered.status, "Revalidation should succeed after the worker releases its lease")
            val after = pgSessionStatus(session.sessionId)
            assertFalse(after.leaseHeld)
            assertEquals("ACTIVA", api.state(session.sessionId, local.device).state)
            println("COT05C3P2_LEASE_503 session=${session.sessionId} leaseHeldDuring=true leaseSeconds=${leaseDuring.leaseSeconds} first=${firstResult.status}:${firstResult.endedMs-firstResult.startedMs}ms contender=${second.status}:${second.endedMs-second.startedMs}ms recovered=${recovered.status}:${recovered.endedMs-recovered.startedMs}ms leaseReleased=true cursor=${after.erpCursor} ack=${after.deliveryAcked}")
        } finally { monitor.close(); client.close(); local.driver.close() }
    }

    private suspend fun newLocal(path: String): Local {
        val file = safeSqlite(path, mustExist = false)
        check(!file.exists()) { "Refusing to overwrite UAT SQLite: ${file.absolutePath}" }
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
        val device = devices.getOrCreate()
        return Local(file, driver, db, CotizacionEstadosRepository(db), devices, device)
    }

    private suspend fun open(api: CotizacionEstadosApi, local: Local): SessionDto {
        val dto = api.open(local.device)
        local.repo.persistSession(dto, one.userId, one.agentId, local.device)
        return dto
    }

    private suspend fun snapshotReady(api: CotizacionEstadosApi, local: Local, session: String,
                                      after: Long = 0): SnapshotPageDto {
        repeat(24) { attempt ->
            try { return api.snapshot(session, local.device, after) }
            catch (error: CotizacionEstadosHttpException) {
                if (!error.snapshotPending || attempt == 23) throw error
                delay(150L * (attempt + 1))
            }
        }
        error("snapshot polling exhausted")
    }

    private suspend fun eventsReady(api: CotizacionEstadosApi, local: Local, session: String,
                                    after: Long): EventPageDto {
        repeat(20) { attempt ->
            try { return api.events(session, local.device, after, limit = 1) }
            catch (error: CotizacionEstadosHttpException) {
                if (!error.retryable || attempt == 19) throw error
                delay(200L * (attempt + 1))
            }
        }
        error("events polling exhausted")
    }

    private suspend fun awaitState(api: CotizacionEstadosApi, session: String, device: String,
                                   predicate: (SessionStateDto) -> Boolean): SessionStateDto {
        repeat(60) { attempt ->
            val state = api.state(session, device)
            if (predicate(state)) return state
            delay(200L * (attempt.coerceAtMost(4) + 1))
        }
        error("Sync state did not reach expected UAT condition")
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

    private suspend fun control(body: JsonObject) {
        val base = env("COT05C3P_SIMULATOR_URL")
        val key = env("COT05C3P_CONTROL_KEY")
        val client = HttpClient(CIO) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        try {
            val response = client.post("${base.trimEnd('/')}/__control") {
                header("x-uat-control-key", key)
                contentType(ContentType.Application.Json)
                setBody(Json.encodeToString(JsonObject.serializer(), body))
            }
            assertEquals(HttpStatusCode.OK, response.status)
        } finally { client.close() }
    }

    private data class PgSessionStatus(val leaseHeld: Boolean, val leaseSeconds: Long,
                                      val erpCursor: Long, val deliveryAcked: Long, val queueSaturated: Boolean)

    private fun pgSessionStatus(sessionId: String): PgSessionStatus {
        val psql = env("COT05C3P_PSQL")
        val passwordFile = env("COT05C3P_DB_PASSWORD_FILE")
        val dbName = env("COT05C3P_DB_NAME")
        val connection = "host=127.0.0.1 port=55433 dbname=$dbName user=cotizaciones_uat_app sslmode=disable"
        val sql = "SELECT (lease_owner IS NOT NULL)::text || '|' || " +
            "GREATEST(0,ceil(extract(epoch from (lease_until-clock_timestamp()))))::bigint::text || '|' || " +
            "COALESCE(erp_cursor,-1)::text || '|' || delivery_acked_sequence::text || '|' || queue_saturated::text " +
            "FROM public.sync_cotizacion_sesion WHERE session_id='$sessionId'"
        val builder = ProcessBuilder(psql, "-X", "-w", connection, "-A", "-t", "-v", "ON_ERROR_STOP=1", "-c", sql)
            .redirectErrorStream(true)
        builder.environment()["PGPASSWORD"] = File(passwordFile).readText().trim()
        val process = builder.start()
        val output = process.inputStream.bufferedReader().readText().trim()
        check(process.waitFor(15, TimeUnit.SECONDS)) { "psql UAT diagnostic timed out" }
        check(process.exitValue() == 0) { "psql UAT diagnostic failed" }
        val fields = output.split('|')
        require(fields.size == 5) { "Unexpected session diagnostic shape" }
        return PgSessionStatus(fields[0].toBoolean(), fields[1].toLong(), fields[2].toLong(),
            fields[3].toLong(), fields[4].toBoolean())
    }

    private fun safeSqlite(path: String, mustExist: Boolean): File {
        val file = File(path).absoluteFile
        val operational = File(env("LOCALAPPDATA"), "EffyFlow/effy.db").canonicalFile
        assertTrue(file.canonicalFile != operational, "Refusing operational SQLite path")
        if (mustExist) assertTrue(file.isFile, "Expected the preceding JVM run's SQLite file")
        return file
    }

    private fun env(name: String) = System.getenv(name) ?: error("missing integration environment variable: $name")
}
