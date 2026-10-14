package com.cyssoftware.effyflow

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cyssoftware.effyflow.cotizacion.estados.*
import com.cyssoftware.effyflow.db.EffyDatabase
import com.cyssoftware.effyflow.db.LocalSchemaMigrations
import kotlinx.datetime.Instant
import kotlin.test.*
import kotlin.time.Duration.Companion.hours

class CotizacionEstadosRepositoryTest {
    private val now = Instant.parse("2026-10-13T12:00:00Z")
    private val device = "c0a8012e-8a2d-4c9c-aaf4-9a8f58a1f9b2"
    private val sessionId = "11111111-2222-4333-8444-555555555555"

    private class FakeClock(var wall: Instant, var elapsed: Long = 10_000) : AuthorizationClock {
        override fun wallNow() = wall
        override fun elapsedRealtimeMillis() = elapsed
    }
    private class Fixture {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val db: EffyDatabase
        init { EffyDatabase.Schema.create(driver); LocalSchemaMigrations.apply(driver); db = EffyDatabase(driver) }
        fun close() = driver.close()
    }

    private fun open(repo: CotizacionEstadosRepository): EstadoSession = repo.persistSession(
        SessionDto(sessionId, 1, "INICIALIZANDO", SnapshotCursorDto(1, true), 0,
            OfflineAuthorizationDto(true, (now + 1.hours).toString())), 42, 20, device)

    @Test fun snapshotEmptyUsesTerminalAndAckBeforeReadable() {
        val f = Fixture(); try {
            val clock = FakeClock(now); val repo = CotizacionEstadosRepository(f.db, clock); open(repo)
            repo.applySnapshot(SnapshotPageDto(sessionId, 1,
                listOf(SnapshotItemDto(1, type="SNAPSHOT_END")), 1, 1, true, false), 42, 20, device)
            val committed = repo.find(sessionId, 1)!!
            assertEquals(1, committed.committed); assertEquals(0, committed.acked)
            assertFailsWith<StateAccessDenied> { repo.authorizedDocuments(42, 20, device) }
            repo.recordAck(committed, AckDto(1, "ACTIVA"))
            assertTrue(repo.authorizedDocuments(42, 20, device).isEmpty())
        } finally { f.close() }
    }

    @Test fun versionsTombstonesAndContradictionsAreTransactional() {
        val f = Fixture(); try {
            val clock = FakeClock(now); val repo = CotizacionEstadosRepository(f.db, clock); open(repo)
            val uuid = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
            repo.applySnapshot(SnapshotPageDto(sessionId,1,listOf(
                SnapshotItemDto(1, document=SnapshotDocumentDto(32,uuid,"VIGENTE",1,20,"2026-10-13T11:00:00Z")),
                SnapshotItemDto(2,type="SNAPSHOT_END")),1,2,true,false),42,20,device)
            repo.recordAck(repo.find(sessionId,1)!!, AckDto(2,"ACTIVA"))
            assertEquals(1, repo.authorizedDocuments(42,20,device).single().version)

            repo.applyEvents(EventPageDto(sessionId,1,listOf(
                CotizacionEventDto(3,10,uuid,32,null,2,"2026-10-13T11:30:00Z","REVOCADA",null,false)
            ),3,3,false),42,20,device)
            repo.recordAck(repo.find(sessionId,1)!!, AckDto(3,"ACTIVA"))
            assertTrue(repo.authorizedDocuments(42,20,device).isEmpty())

            repo.applyEvents(EventPageDto(sessionId,1,listOf(
                CotizacionEventDto(4,11,uuid,32,"ANTIGUA",1,"2026-10-13T11:00:00Z","ACTUALIZADA",20,true)
            ),4,4,false),42,20,device)
            assertTrue(repo.authorizedDocuments(42,20,device).isEmpty())

            assertFailsWith<StateIntegrityConflict> {
                repo.applyEvents(EventPageDto(sessionId,1,listOf(
                    CotizacionEventDto(5,12,uuid,32,"VIGENTE",2,"2026-10-13T11:30:00Z","ACTUALIZADA",20,true)
                ),5,5,false),42,20,device)
            }
            assertEquals(4, repo.find(sessionId,1)!!.committed)
            assertNull(f.db.cotizacion_estadosQueries.findStateReceipt(sessionId,1,5).executeAsOneOrNull())
        } finally { f.close() }
    }

    @Test fun replaySamePageIsSafeAndDifferentPayloadIsRejected() {
        val f=Fixture(); try {
            val repo=CotizacionEstadosRepository(f.db,FakeClock(now)); open(repo)
            val page=SnapshotPageDto(sessionId,1,listOf(SnapshotItemDto(1,type="SNAPSHOT_END")),1,1,true,false)
            repo.applySnapshot(page,42,20,device); repo.applySnapshot(page,42,20,device)
            assertEquals(1,repo.find(sessionId,1)!!.committed)
            assertFailsWith<StateIntegrityConflict> { repo.applySnapshot(page.copy(items=listOf(
                SnapshotItemDto(1,document=SnapshotDocumentDto(1,"aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee","X",1))
            )),42,20,device) }
        } finally { f.close() }
    }

    @Test fun leaseExpiryRollbackAndUserIsolationBlockRepositoryReads() {
        val f=Fixture(); try {
            val clock=FakeClock(now); val repo=CotizacionEstadosRepository(f.db,clock); open(repo)
            repo.applySnapshot(SnapshotPageDto(sessionId,1,listOf(SnapshotItemDto(1,type="SNAPSHOT_END")),1,1,true,false),42,20,device)
            repo.recordAck(repo.find(sessionId,1)!!,AckDto(1,"ACTIVA"))
            assertFailsWith<StateAccessDenied> { repo.authorizedDocuments(43,20,device) }
            clock.wall=now+2.hours; clock.elapsed+=7_200_000
            assertFailsWith<StateAccessDenied> { repo.authorizedDocuments(42,20,device) }
            clock.wall=Instant.parse("2026-10-13T11:50:00Z")
            assertFailsWith<StateAccessDenied> { repo.authorizedDocuments(42,20,device) }
        } finally { f.close() }
    }

    @Test fun revocationAndLogoutPurgeOnlyReceivedNamespace() {
        val f=Fixture(); try {
            val repo=CotizacionEstadosRepository(f.db,FakeClock(now)); val s=open(repo)
            f.db.app_configQueries.upsertValue("capture_sentinel","preserve")
            repo.revokeAndPurge(s,"ACCESS_DENIED","revoked")
            assertEquals("REVOCADA",repo.find(sessionId,1)!!.remoteState)
            assertEquals("preserve",f.db.app_configQueries.getValue("capture_sentinel").executeAsOne())
            repo.purgeUser(42)
            assertNull(repo.find(sessionId,1))
            assertEquals("preserve",f.db.app_configQueries.getValue("capture_sentinel").executeAsOne())
        } finally { f.close() }
    }

    @Test fun multipageSnapshotAndNewGenerationRemainIsolated() {
        val f=Fixture(); try {
            val repo=CotizacionEstadosRepository(f.db,FakeClock(now)); open(repo)
            val uuid="aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
            repo.applySnapshot(SnapshotPageDto(sessionId,1,listOf(
                SnapshotItemDto(1,document=SnapshotDocumentDto(32,uuid,"VIGENTE",1,20,"2026-10-13T11:00:00Z"))
            ),1,1,false,true),42,20,device)
            repo.recordAck(repo.find(sessionId,1)!!,AckDto(1,"INICIALIZANDO"))
            repo.applySnapshot(SnapshotPageDto(sessionId,1,listOf(SnapshotItemDto(2,type="SNAPSHOT_END")),2,2,true,false),42,20,device)
            repo.recordAck(repo.find(sessionId,1)!!,AckDto(2,"ACTIVA"))
            assertEquals(uuid,repo.authorizedDocuments(42,20,device).single().uuid)
            assertNull(repo.latest(42,21,device))

            val next="99999999-8888-4777-8666-555555555555"
            repo.persistSession(SessionDto(next,2,"INICIALIZANDO",SnapshotCursorDto(1,true),0,
                OfflineAuthorizationDto(true,(now+1.hours).toString())),42,20,device)
            assertEquals(2,repo.latest(42,20,device)!!.generation)
            assertFailsWith<StateAccessDenied>{repo.authorizedDocuments(42,20,device)}
            assertEquals(1L,f.db.cotizacion_estadosQueries.countStateDocuments(sessionId,1).executeAsOne())
            assertEquals(0L,f.db.cotizacion_estadosQueries.countStateDocuments(next,2).executeAsOne())
        } finally { f.close() }
    }
}
