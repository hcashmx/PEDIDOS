package com.cyssoftware.effyflow.cotizacion.estados

import kotlinx.serialization.Serializable

@Serializable data class DeviceRequest(val dispositivoId: String)
@Serializable data class AckRequest(val ackThroughSequence: Long)
@Serializable data class ResetRequest(val dispositivoId: String, val expectedGeneration: Long)
@Serializable data class OfflineAuthorizationDto(val valid: Boolean = false, val validUntil: String = "")
@Serializable data class SnapshotCursorDto(val nextDeliverySequence: Long, val hasMore: Boolean)
@Serializable data class SessionDto(
    val sessionId: String, val generation: Long, val state: String,
    val snapshot: SnapshotCursorDto? = null, val lastAckedSequence: Long = 0,
    val offlineAuthorization: OfflineAuthorizationDto = OfflineAuthorizationDto(),
    val oldSessionState: String? = null, val purgeRequired: Boolean = false
)
@Serializable data class SnapshotDocumentDto(
    val erpId: Long, val cotizacionUuid: String, val estado: String,
    val version: Long, val agenteId: Long? = null, val updatedAt: String? = null
)
@Serializable data class SnapshotItemDto(
    val deliverySequence: Long, val type: String? = null, val document: SnapshotDocumentDto? = null
)
@Serializable data class SnapshotPageDto(
    val sessionId: String, val generation: Long, val items: List<SnapshotItemDto>,
    val fromSequence: Long, val toSequence: Long, val snapshotComplete: Boolean, val hasMore: Boolean
)
@Serializable data class CotizacionEventDto(
    val deliverySequence: Long, val eventId: Long, val cotizacionUuid: String, val erpId: Long,
    val estado: String? = null, val version: Long, val changedAt: String? = null,
    val eventType: String, val agenteId: Long? = null, val visible: Boolean
)
@Serializable data class EventPageDto(
    val sessionId: String, val generation: Long, val events: List<CotizacionEventDto>,
    val fromSequence: Long, val toSequence: Long, val hasMore: Boolean
)
@Serializable data class AckDto(val ackThroughSequence: Long, val state: String)
@Serializable data class SessionStateDto(
    val sessionId: String, val generation: Long, val state: String,
    val snapshotComplete: Boolean = false, val lastAckedSequence: Long = 0,
    val lastAvailableSequence: Long = 0, val pendingQueueEntries: Long = 0,
    val pendingQueueEstimatedBytes: Long = 0, val ingestionSuspended: Boolean = false,
    val capacityCode: String = "", val offlineAuthorization: OfflineAuthorizationDto = OfflineAuthorizationDto(),
    val reasonCode: String = ""
)
@Serializable data class RevalidationDto(
    val authorized: Boolean, val validUntil: String, val state: String, val lastAvailableSequence: Long
)
@Serializable data class SyncErrorBody(
    val code: String? = null, val message: String? = null, val retryable: Boolean? = null,
    val phase: String? = null, val sessionState: String? = null,
    val correlationId: String? = null, val error: String? = null
)
