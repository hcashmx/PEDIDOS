package com.cyssoftware.effyflow

import com.cyssoftware.effyflow.cotizacion.estados.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.*

class CotizacionEstadosApiTest {
    private val session="11111111-2222-4333-8444-555555555555"
    private val device="c0a8012e-8a2d-4c9c-aaf4-9a8f58a1f9b2"
    private fun client(engine: MockEngine) =
        HttpClient(engine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys=true }) } }

    @Test fun routesUseRealBodiesAndRequiredDeviceHeader() = runBlocking {
        val seen=mutableSetOf<String>()
        val c=client(MockEngine { request ->
            val path=request.url.encodedPath; seen += path
            if (!path.endsWith("/sesion") && !path.endsWith("/reiniciar"))
                assertEquals(device,request.headers["X-Sync-Device-Id"])
            val body=when {
                path.endsWith("/sesion") -> """{"sessionId":"$session","generation":1,"state":"INICIALIZANDO","snapshot":{"nextDeliverySequence":1,"hasMore":true},"lastAckedSequence":0,"offlineAuthorization":{"valid":true,"validUntil":"2026-10-14T12:00:00Z"}}"""
                path.endsWith("/snapshot") -> """{"sessionId":"$session","generation":1,"items":[{"deliverySequence":1,"type":"SNAPSHOT_END"}],"fromSequence":1,"toSequence":1,"snapshotComplete":true,"hasMore":false}"""
                path.endsWith("/eventos") -> """{"sessionId":"$session","generation":1,"events":[],"fromSequence":1,"toSequence":1,"hasMore":false}"""
                path.endsWith("/ack") -> """{"ackThroughSequence":1,"state":"ACTIVA"}"""
                path.endsWith("/revalidar") -> """{"authorized":true,"validUntil":"2026-10-14T12:00:00Z","state":"ACTIVA","lastAvailableSequence":1}"""
                path.endsWith("/reiniciar") -> """{"sessionId":"aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee","generation":2,"state":"INICIALIZANDO","snapshot":{"nextDeliverySequence":1,"hasMore":true},"lastAckedSequence":0,"offlineAuthorization":{"valid":true,"validUntil":"2026-10-14T12:00:00Z"},"purgeRequired":true}"""
                else -> """{"sessionId":"$session","generation":1,"state":"ACTIVA","snapshotComplete":true,"lastAckedSequence":1,"lastAvailableSequence":1,"ingestionSuspended":false,"capacityCode":"","offlineAuthorization":{"valid":true,"validUntil":"2026-10-14T12:00:00Z"},"reasonCode":""}"""
            }
            respond(body,if(path.endsWith("/reiniciar")) HttpStatusCode.Created else HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType,"application/json"))
        })
        c.use {
            val api=CotizacionEstadosApi(it,"https://sync.test")
            api.open(device); api.state(session,device); api.snapshot(session,device,0); api.events(session,device,1)
            api.acknowledge(session,device,1); api.revalidate(session,device); api.reset(session,device,1)
        }
        assertEquals(7,seen.size)
    }

    @Test fun acceptsDetailedAndGenericErrorsAndTreats202AsPending() = runBlocking {
        var call=0
        val c=client(MockEngine {
            call++
            when(call) {
                1 -> respond("""{"code":"SESSION_STATE_CONFLICT","message":"wait","retryable":false}""",HttpStatusCode.Conflict,headersOf(HttpHeaders.ContentType,"application/json"))
                2 -> respond("""{"error":"Acceso denegado"}""",HttpStatusCode.Forbidden,headersOf(HttpHeaders.ContentType,"application/json"))
                else -> respond("",HttpStatusCode.Accepted)
            }
        })
        c.use {
            val api=CotizacionEstadosApi(it,"https://sync.test")
            val conflict=assertFailsWith<CotizacionEstadosHttpException>{api.state(session,device)}
            assertTrue(conflict.conflict); assertEquals("SESSION_STATE_CONFLICT",conflict.code)
            val denied=assertFailsWith<CotizacionEstadosHttpException>{api.state(session,device)}
            assertTrue(denied.authorizationRevoked); assertEquals("Acceso denegado",denied.safeMessage)
            val pending=assertFailsWith<CotizacionEstadosHttpException>{api.snapshot(session,device,0)}
            assertTrue(pending.snapshotPending)
        }
    }

    @Test fun transient503IsRetryableExceptTechnicalErpAuthenticationFailure() = runBlocking {
        var call=0
        val c=client(MockEngine {
            call++
            if (call==1) respond(
                """{"code":"ERP_UNAVAILABLE","message":"temporary","retryable":false}""",
                HttpStatusCode.ServiceUnavailable, headersOf(HttpHeaders.ContentType,"application/json"))
            else respond(
                """{"code":"ERP_TECHNICAL_AUTH_FAILED","message":"technical credentials rejected","retryable":false}""",
                HttpStatusCode.ServiceUnavailable, headersOf(HttpHeaders.ContentType,"application/json"))
        })
        c.use {
            val api=CotizacionEstadosApi(it,"https://sync.test")
            val transient=assertFailsWith<CotizacionEstadosHttpException>{api.state(session,device)}
            assertEquals(503,transient.status); assertTrue(transient.retryable)
            val auth=assertFailsWith<CotizacionEstadosHttpException>{api.state(session,device)}
            assertEquals(503,auth.status); assertFalse(auth.retryable)
        }
    }
}
