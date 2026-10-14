package com.cyssoftware.effyflow

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cyssoftware.effyflow.cotizacion.estados.*
import com.cyssoftware.effyflow.db.EffyDatabase
import com.cyssoftware.effyflow.db.LocalSchemaMigrations
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds

class CotizacionEstadosOfflineLeaseTest {
    private val start = Instant.parse("2026-10-13T12:00:00Z")
    private val sessionId = "11111111-2222-4333-8444-555555555555"
    private val deviceId = "c0a8012e-8a2d-4c9c-aaf4-9a8f58a1f9b2"
    private val documentUuid = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"

    private class FakeClock(var wall: Instant, var elapsed: Long = 10_000) : AuthorizationClock {
        override fun wallNow() = wall
        override fun elapsedRealtimeMillis() = elapsed
        fun advance(duration: Duration) {
            wall += duration
            elapsed += duration.inWholeMilliseconds
        }
    }

    private class TemporarySqlite {
        private val directory = Files.createTempDirectory("cot05c4-offline-")
        private val file = directory.resolve("states.sqlite")
        private var initialized = false

        fun open(clock: AuthorizationClock): Local {
            val driver = JdbcSqliteDriver("jdbc:sqlite:${file.toAbsolutePath()}")
            if (!initialized) {
                EffyDatabase.Schema.create(driver)
                LocalSchemaMigrations.apply(driver)
                initialized = true
            }
            val db = EffyDatabase(driver)
            return Local(driver, db, CotizacionEstadosRepository(db, clock))
        }

        fun delete() {
            Files.deleteIfExists(Path.of("$file-wal"))
            Files.deleteIfExists(Path.of("$file-shm"))
            Files.deleteIfExists(file)
            Files.deleteIfExists(directory)
        }
    }

    private data class Local(
        val driver: JdbcSqliteDriver,
        val db: EffyDatabase,
        val repository: CotizacionEstadosRepository
    ) : AutoCloseable {
        override fun close() = driver.close()
    }

    private fun activate(local: Local, clock: FakeClock, validUntil: Instant): EstadoSession {
        val session = local.repository.persistSession(
            SessionDto(sessionId, 1, "ACTIVA", SnapshotCursorDto(1, true), 0,
                OfflineAuthorizationDto(true, validUntil.toString())),
            userId = 42, agentId = 20, deviceId = deviceId
        )
        local.repository.applySnapshot(
            SnapshotPageDto(sessionId, 1, listOf(
                SnapshotItemDto(1, document = SnapshotDocumentDto(
                    321, documentUuid, "VIGENTE", 1, 20, clock.wallNow().toString()
                )),
                SnapshotItemDto(2, type = "SNAPSHOT_END")
            ), 1, 2, snapshotComplete = true, hasMore = false),
            userId = 42, agentId = 20, deviceId = deviceId
        )
        val pendingAck = checkNotNull(local.repository.find(sessionId, 1))
        local.repository.recordAck(pendingAck, AckDto(2, "ACTIVA"))
        return checkNotNull(local.repository.find(sessionId, 1))
    }

    @Test fun validLeaseAllowsReadAndExactValidUntilBlocksIt() {
        val clock = FakeClock(start)
        val temp = TemporarySqlite()
        val local = temp.open(clock)
        try {
            val expiry = start + 1.hours
            activate(local, clock, expiry)
            assertEquals(1, local.repository.authorizedDocuments(42, 20, deviceId).size)

            // Equality is expired; monotonic time is held constant so this checks the wall-clock boundary.
            clock.wall = expiry
            assertFailsWith<StateAccessDenied> {
                local.repository.authorizedDocuments(42, 20, deviceId)
            }
            Unit
        } finally {
            local.close()
            temp.delete()
        }
    }

    @Test fun leaseAcceptsExactly24HoursAndRejectsAnythingLonger() {
        val clock = FakeClock(start)
        val temp = TemporarySqlite()
        val local = temp.open(clock)
        try {
            val expiry = start + 24.hours
            activate(local, clock, expiry)
            assertEquals(expiry.toString(), local.repository.find(sessionId, 1)!!.validUntil)
            assertEquals(1, local.repository.authorizedDocuments(42, 20, deviceId).size)

            clock.wall = expiry
            assertFailsWith<StateAccessDenied> {
                local.repository.authorizedDocuments(42, 20, deviceId)
            }

            assertFailsWith<IllegalArgumentException> {
                local.repository.persistSession(
                    SessionDto("22222222-3333-4444-8555-666666666666", 1, "ACTIVA",
                        SnapshotCursorDto(1, true), 0,
                        OfflineAuthorizationDto(true, (clock.wall + 24.hours + 1.nanoseconds).toString())),
                    userId = 43, agentId = 21, deviceId = deviceId
                )
            }
        } finally {
            local.close()
            temp.delete()
        }
    }

    @Test fun materialWallClockRollbackAfterApplicationRestartBlocksReads() {
        val temp = TemporarySqlite()
        val originalClock = FakeClock(start, elapsed = 50_000)
        val firstRun = temp.open(originalClock)
        activate(firstRun, originalClock, start + 12.hours)
        firstRun.close()

        val alteredClockAfterRestart = FakeClock(start - 10.minutes, elapsed = 100)
        val restarted = temp.open(alteredClockAfterRestart)
        try {
            assertFailsWith<StateAccessDenied> {
                restarted.repository.authorizedDocuments(42, 20, deviceId)
            }
        } finally {
            restarted.close()
            temp.delete()
        }
    }

    @Test fun validOnlineRevalidationRenewsExpiredLeaseAndRestoresRepositoryRead() = runBlocking {
        val clock = FakeClock(start)
        val temp = TemporarySqlite()
        val local = temp.open(clock)
        var client: HttpClient? = null
        try {
            val originalExpiry = start + 1.hours
            activate(local, clock, originalExpiry)
            local.db.app_configQueries.upsertValue(DeviceIdentityRepository.KEY, deviceId)
            clock.wall = originalExpiry
            assertFailsWith<StateAccessDenied> {
                local.repository.authorizedDocuments(42, 20, deviceId)
            }

            val renewedUntil = originalExpiry + 2.hours
            client = HttpClient(MockEngine { request ->
                assertEquals("POST", request.method.value)
                assertTrue(request.url.encodedPath.endsWith("/$sessionId/revalidar"))
                assertEquals(deviceId, request.headers["X-Sync-Device-Id"])
                respond(
                    """{"authorized":true,"validUntil":"$renewedUntil","state":"ACTIVA","lastAvailableSequence":2}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json")
                )
            }) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
            val sync = CotizacionEstadosSync(
                local.repository,
                DeviceIdentityRepository(local.db),
                CotizacionEstadosApi(client, "https://sync.test"),
                userId = 42,
                agentId = 20,
                identityStillActive = { true }
            )

            assertEquals("ACTIVA", sync.revalidate().state)
            assertEquals(renewedUntil.toString(), local.repository.latest(42, 20, deviceId)!!.validUntil)
            assertEquals(1, local.repository.authorizedDocuments(42, 20, deviceId).size)
        } finally {
            client?.close()
            local.close()
            temp.delete()
        }
    }

    @Test fun temporaryHttpFailureDoesNotExtendLeaseAndRepositoryBlocksAtExpiry() = runBlocking {
        val clock = FakeClock(start)
        val temp = TemporarySqlite()
        val local = temp.open(clock)
        var client: HttpClient? = null
        try {
            val expiry = start + 1.hours
            activate(local, clock, expiry)
            local.db.app_configQueries.upsertValue(DeviceIdentityRepository.KEY, deviceId)
            clock.advance(5.minutes)
            client = HttpClient(MockEngine {
                respond(
                    """{"code":"ERP_UNAVAILABLE","message":"Temporal","retryable":true}""",
                    HttpStatusCode.ServiceUnavailable,
                    headersOf(HttpHeaders.ContentType, "application/json")
                )
            }) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
            val sync = CotizacionEstadosSync(
                local.repository,
                DeviceIdentityRepository(local.db),
                CotizacionEstadosApi(client, "https://sync.test"),
                userId = 42,
                agentId = 20,
                identityStillActive = { true }
            )

            assertEquals("ERROR_TEMPORAL", sync.revalidate().state)
            assertEquals(expiry.toString(), local.repository.latest(42, 20, deviceId)!!.validUntil)
            assertEquals(1, local.repository.authorizedDocuments(42, 20, deviceId).size)

            clock.advance(55.minutes)
            assertEquals(expiry, clock.wall)
            assertFailsWith<StateAccessDenied> {
                local.repository.authorizedDocuments(42, 20, deviceId)
            }
            Unit
        } finally {
            client?.close()
            local.close()
            temp.delete()
        }
    }
}
