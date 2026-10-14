package com.cyssoftware.effyflow

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cyssoftware.effyflow.cotizacion.estados.*
import com.cyssoftware.effyflow.db.EffyDatabase
import com.cyssoftware.effyflow.db.LocalSchemaMigrations
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import java.io.IOException
import kotlin.test.*

class CotizacionEstadosSyncTest {
    private val session="11111111-2222-4333-8444-555555555555"
    private val device="c0a8012e-8a2d-4c9c-aaf4-9a8f58a1f9b2"
    private class Clock : AuthorizationClock {
        override fun wallNow()=Instant.parse("2026-10-13T12:00:00Z")
        override fun elapsedRealtimeMillis()=1_000L
    }
    private class Fixture {
        val driver=JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY); val db:EffyDatabase
        init { EffyDatabase.Schema.create(driver); LocalSchemaMigrations.apply(driver); db=EffyDatabase(driver) }
        fun client(engine:MockEngine)=HttpClient(engine){install(ContentNegotiation){json(Json{ignoreUnknownKeys=true})}}
    }
    private fun openJson()="""{"sessionId":"$session","generation":1,"state":"INICIALIZANDO","snapshot":{"nextDeliverySequence":1,"hasMore":true},"lastAckedSequence":0,"offlineAuthorization":{"valid":true,"validUntil":"2026-10-14T00:00:00Z"}}"""
    private fun stateJson(ack:Long,state:String)="""{"sessionId":"$session","generation":1,"state":"$state","snapshotComplete":${state=="ACTIVA"},"lastAckedSequence":$ack,"lastAvailableSequence":1,"ingestionSuspended":false,"capacityCode":"","offlineAuthorization":{"valid":true,"validUntil":"2026-10-14T00:00:00Z"},"reasonCode":""}"""

    @Test fun commitSurvivesLostAckAndNextRunReconcilesWithoutReplay()=runBlocking {
        val f=Fixture(); try {
            f.db.app_configQueries.upsertValue(DeviceIdentityRepository.KEY,device)
            var ackCalls=0; var remoteAck=0L
            val client=f.client(MockEngine { req ->
                val p=req.url.encodedPath
                when {
                    p.endsWith("/sesion") -> respond(openJson(),HttpStatusCode.Created,jsonHeaders())
                    p.endsWith("/snapshot") -> respond("""{"sessionId":"$session","generation":1,"items":[{"deliverySequence":1,"type":"SNAPSHOT_END"}],"fromSequence":1,"toSequence":1,"snapshotComplete":true,"hasMore":false}""",HttpStatusCode.OK,jsonHeaders())
                    p.endsWith("/ack") -> { ackCalls++; if(ackCalls==1) throw IOException("lost"); remoteAck=1; respond("""{"ackThroughSequence":1,"state":"ACTIVA"}""",HttpStatusCode.OK,jsonHeaders()) }
                    p.endsWith("/eventos") -> respond("""{"sessionId":"$session","generation":1,"events":[],"fromSequence":1,"toSequence":1,"hasMore":false}""",HttpStatusCode.OK,jsonHeaders())
                    else -> respond(stateJson(remoteAck,if(remoteAck==1L)"ACTIVA" else "INICIALIZANDO"),HttpStatusCode.OK,jsonHeaders())
                }
            })
            val repo=CotizacionEstadosRepository(f.db,Clock())
            val sync=CotizacionEstadosSync(repo,DeviceIdentityRepository(f.db),CotizacionEstadosApi(client,"https://sync.test"),42,20,{true},{})
            assertEquals("ERROR_TEMPORAL",sync.synchronize().state)
            assertEquals(1,repo.find(session,1)!!.committed); assertEquals(0,repo.find(session,1)!!.acked)
            val outcome=sync.synchronize()
            assertEquals("ACTIVA",outcome.state); assertEquals(1,repo.find(session,1)!!.acked)
            assertEquals(2,ackCalls)
            client.close()
        } finally { f.driver.close() }
    }

    @Test fun lateSnapshotAfterLogoutIsDiscardedBeforeCommit()=runBlocking {
        val f=Fixture(); try {
            f.db.app_configQueries.upsertValue(DeviceIdentityRepository.KEY,device)
            var active=true
            val client=f.client(MockEngine { req ->
                if(req.url.encodedPath.endsWith("/sesion")) respond(openJson(),HttpStatusCode.Created,jsonHeaders())
                else { active=false; respond("""{"sessionId":"$session","generation":1,"items":[{"deliverySequence":1,"type":"SNAPSHOT_END"}],"fromSequence":1,"toSequence":1,"snapshotComplete":true,"hasMore":false}""",HttpStatusCode.OK,jsonHeaders()) }
            })
            val repo=CotizacionEstadosRepository(f.db,Clock())
            val sync=CotizacionEstadosSync(repo,DeviceIdentityRepository(f.db),CotizacionEstadosApi(client,"https://sync.test"),42,20,{active},{})
            assertFailsWith<CancellationException>{sync.synchronize()}
            assertEquals(0,repo.find(session,1)!!.committed)
            client.close()
        } finally { f.driver.close() }
    }

    private fun jsonHeaders()=headersOf(HttpHeaders.ContentType,"application/json")
}
