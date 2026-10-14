package com.cyssoftware.effyflow.cotizacion.estados

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.serialization.json.Json

class CotizacionEstadosApi(private val client: HttpClient, baseUrl: String) {
    private val root = "${baseUrl.trimEnd('/')}/api/sync/cotizaciones/estados"
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun open(deviceId: String): SessionDto = decode(client.post("$root/sesion") {
        contentType(ContentType.Application.Json); setBody(DeviceRequest(deviceId))
    }, setOf(200, 201))

    suspend fun state(sessionId: String, deviceId: String): SessionStateDto = decode(
        client.get("$root/sesion/$sessionId") { device(deviceId) }, setOf(200))

    suspend fun snapshot(sessionId: String, deviceId: String, after: Long, limit: Int = 100): SnapshotPageDto = decode(
        client.get("$root/sesion/$sessionId/snapshot") {
            device(deviceId); parameter("afterSequence", after); parameter("limit", limit)
        }, setOf(200))

    suspend fun events(sessionId: String, deviceId: String, after: Long, limit: Int = 100): EventPageDto = decode(
        client.get("$root/sesion/$sessionId/eventos") {
            device(deviceId); parameter("afterSequence", after); parameter("limit", limit)
        }, setOf(200))

    suspend fun acknowledge(sessionId: String, deviceId: String, through: Long): AckDto = decode(
        client.post("$root/sesion/$sessionId/ack") {
            device(deviceId); contentType(ContentType.Application.Json); setBody(AckRequest(through))
        }, setOf(200))

    suspend fun revalidate(sessionId: String, deviceId: String): RevalidationDto = decode(
        client.post("$root/sesion/$sessionId/revalidar") { device(deviceId) }, setOf(200))

    suspend fun reset(sessionId: String, deviceId: String, generation: Long): SessionDto = decode(
        client.post("$root/sesion/$sessionId/reiniciar") {
            contentType(ContentType.Application.Json); setBody(ResetRequest(deviceId, generation))
        }, setOf(201))

    private fun HttpRequestBuilder.device(value: String) { header("X-Sync-Device-Id", value) }

    private suspend inline fun <reified T> decode(response: HttpResponse, accepted: Set<Int>): T {
        if (response.status.value in accepted) return response.body()
        val text = response.bodyAsText()
        val error = runCatching { json.decodeFromString<SyncErrorBody>(text) }.getOrNull()
        val status = response.status.value
        val retryableByContract = status == 429 || status == 504 ||
            (status == 503 && error?.code != "ERP_TECHNICAL_AUTH_FAILED") ||
            (status == 502 && error == null)
        throw CotizacionEstadosHttpException(
            status = status,
            code = error?.code ?: "HTTP_$status",
            safeMessage = (error?.message ?: error?.error ?: "Sync respondió HTTP ${response.status.value}.").take(500),
            retryable = error?.retryable == true || retryableByContract,
            sessionState = error?.sessionState,
            retryAfterSeconds = response.headers[HttpHeaders.RetryAfter]?.toLongOrNull()
        )
    }
}

class CotizacionEstadosHttpException(
    val status: Int, val code: String, val safeMessage: String,
    val retryable: Boolean, val sessionState: String?, val retryAfterSeconds: Long?
) : Exception(safeMessage) {
    val snapshotPending: Boolean get() = status == 202
    val authorizationRevoked: Boolean get() = status == 403
    val unusableSession: Boolean get() = status == 404
    val conflict: Boolean get() = status == 409
    val resetRequired: Boolean get() = status == 410
}
