package com.cyssoftware.effyflow.cotizacion.estados

import com.cyssoftware.effyflow.cotizacion.sha256Hex
import com.cyssoftware.effyflow.db.DatabaseProvider
import com.cyssoftware.effyflow.db.EffyDatabase
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.milliseconds

data class EstadoSession(
    val sessionId: String, val userId: Long, val agentId: Long, val deviceId: String,
    val generation: Long, val remoteState: String, val bootstrapState: String,
    val committed: Long, val acked: Long, val snapshotEnd: Long?, val validUntil: String?,
    val ingestionSuspended: Boolean, val capacityCode: String?, val lastSyncAt: String?,
    val errorCode: String?, val errorMessage: String?
)

data class CotizacionSincronizada(
    val uuid: String, val erpId: Long, val estado: String, val version: Long,
    val agenteId: Long?, val updatedAt: String?
)

class StateAccessDenied(message: String) : IllegalStateException(message)
class StateIntegrityConflict(message: String) : IllegalStateException(message)

class CotizacionEstadosRepository(
    private val db: EffyDatabase = DatabaseProvider.database,
    private val clock: AuthorizationClock = PlatformAuthorizationClock()
) {
    private val q get() = db.cotizacion_estadosQueries

    fun latest(userId: Long, agentId: Long, deviceId: String): EstadoSession? =
        q.findLatestStateSession(userId, agentId, deviceId).executeAsOneOrNull()?.let(::session)

    fun find(sessionId: String, generation: Long): EstadoSession? =
        q.findStateSession(sessionId, generation).executeAsOneOrNull()?.let(::session)

    fun persistSession(dto: SessionDto, userId: Long, agentId: Long, deviceId: String): EstadoSession {
        require(userId > 0 && agentId > 0 && canonicalUuid(dto.sessionId) && canonicalUuid(deviceId))
        require(dto.generation > 0 && dto.lastAckedSequence >= 0)
        val now = clock.wallNow().toString()
        val existing = q.findStateSessionById(dto.sessionId).executeAsOneOrNull()
        if (existing != null) require(existing.user_id == userId && existing.agent_id == agentId &&
            existing.device_id == deviceId && existing.generation == dto.generation) { "La sesión pertenece a otro namespace." }
        q.insertStateSession(
            dto.sessionId, userId, agentId, deviceId, dto.generation, dto.state,
            if (dto.state == "ACTIVA") "ACTIVA" else "INICIALIZANDO",
            existing?.last_committed_sequence ?: dto.lastAckedSequence,
            existing?.last_acked_sequence ?: dto.lastAckedSequence,
            existing?.snapshot_end_sequence, validUntil(dto.offlineAuthorization), now,
            clock.elapsedRealtimeMillis(), maxWall(existing?.max_observed_wall_utc, now),
            existing?.revocation_reason_code, existing?.ingestion_suspended ?: 0,
            existing?.capacity_code, existing?.last_sync_at_utc, null, null,
            existing?.created_at_utc ?: now, now
        )
        q.updateStateRemoteStatus(dto.state, if (dto.state == "ACTIVA") "ACTIVA" else "INICIALIZANDO",
            validUntil(dto.offlineAuthorization), now, clock.elapsedRealtimeMillis(),
            maxWall(existing?.max_observed_wall_utc, now), existing?.revocation_reason_code,
            existing?.ingestion_suspended ?: 0, existing?.capacity_code, existing?.last_sync_at_utc,
            now, dto.sessionId, dto.generation)
        return checkNotNull(find(dto.sessionId, dto.generation))
    }

    fun applySnapshot(page: SnapshotPageDto, userId: Long, agentId: Long, deviceId: String) {
        val s = requireNamespace(page.sessionId, page.generation, userId, agentId, deviceId)
        val deliveries = page.items.map { item ->
            require(item.deliverySequence > 0)
            when {
                item.type == "SNAPSHOT_END" && item.document == null -> Delivery.SnapshotEnd(item.deliverySequence)
                item.type == null && item.document != null -> Delivery.Visible(item.deliverySequence, item.document)
                else -> throw StateIntegrityConflict("Elemento snapshot inválido.")
            }
        }
        validateEnvelope(s.committed, page.fromSequence, page.toSequence, deliveries.map { it.sequence }, page.hasMore)
        applyPage(s, deliveries, page.snapshotComplete)
    }

    fun applyEvents(page: EventPageDto, userId: Long, agentId: Long, deviceId: String) {
        val s = requireNamespace(page.sessionId, page.generation, userId, agentId, deviceId)
        require(s.remoteState == "ACTIVA" || s.bootstrapState == "ACTIVA") { "La sesión aún no está activa." }
        val deliveries = page.events.map { event ->
            require(event.deliverySequence > 0 && event.eventId > 0 && event.erpId > 0 && event.version >= 0)
            require(canonicalUuid(event.cotizacionUuid))
            if (event.visible) require(!event.estado.isNullOrBlank())
            else require(event.estado == null && event.agenteId == null)
            Delivery.Event(event)
        }
        validateEnvelope(s.committed, page.fromSequence, page.toSequence, deliveries.map { it.sequence }, page.hasMore)
        applyPage(s, deliveries, false)
    }

    private fun applyPage(s: EstadoSession, deliveries: List<Delivery>, snapshotComplete: Boolean) {
        val now = clock.wallNow().toString()
        db.transaction {
            val current = checkNotNull(find(s.sessionId, s.generation))
            require(current.committed == s.committed) { "El checkpoint cambió durante la página." }
            val repeated = deliveries.isNotEmpty() && deliveries.all { d ->
                q.findStateReceipt(s.sessionId, s.generation, d.sequence).executeAsOneOrNull()?.let {
                    if (it.payload_fingerprint != receiptFingerprint(d))
                        throw StateIntegrityConflict("La secuencia ${d.sequence} reapareció con otro contenido.")
                    true
                } ?: false
            }
            if (repeated) return@transaction
            require(deliveries.none { q.findStateReceipt(s.sessionId, s.generation, it.sequence).executeAsOneOrNull() != null }) {
                "La página mezcla secuencias nuevas y repetidas."
            }
            deliveries.forEach { delivery ->
                applyDelivery(s, delivery)
                q.insertStateReceipt(s.sessionId, s.generation, delivery.sequence, delivery.kind,
                    receiptFingerprint(delivery), now)
            }
            val terminal = deliveries.filterIsInstance<Delivery.SnapshotEnd>().singleOrNull()?.sequence
            require(!snapshotComplete || terminal != null) { "snapshotComplete sin SNAPSHOT_END." }
            val last = deliveries.lastOrNull()?.sequence ?: s.committed
            q.commitStatePage(last, terminal ?: s.snapshotEnd,
                if (terminal != null) "SNAPSHOT_APLICADO_PENDIENTE_ACK" else s.bootstrapState,
                now, s.sessionId, s.generation)
        }
    }

    private fun applyDelivery(s: EstadoSession, d: Delivery) {
        when (d) {
            is Delivery.SnapshotEnd -> Unit
            is Delivery.Visible -> applyVisible(s, d.sequence, d.document.erpId, d.document.cotizacionUuid,
                d.document.estado, d.document.version, d.document.agenteId, d.document.updatedAt)
            is Delivery.Event -> if (d.event.visible) applyVisible(s, d.sequence, d.event.erpId,
                d.event.cotizacionUuid, checkNotNull(d.event.estado), d.event.version,
                d.event.agenteId, d.event.changedAt) else applyTombstone(s, d.event)
        }
    }

    private fun applyVisible(s: EstadoSession, seq: Long, erpId: Long, uuid: String, estado: String,
                             version: Long, agentId: Long?, updatedAt: String?) {
        require(erpId > 0 && version >= 0 && estado.isNotBlank() && canonicalUuid(uuid))
        val fingerprint = projectionFingerprint(uuid, erpId, version, true, estado, agentId, updatedAt)
        val doc = q.findStateDocument(s.sessionId, s.generation, uuid).executeAsOneOrNull()
        val tomb = q.findStateTombstone(s.sessionId, s.generation, uuid).executeAsOneOrNull()
        val knownVersion = maxOf(doc?.version ?: -1, tomb?.version ?: -1)
        if (version < knownVersion) return
        if (version == knownVersion) {
            val known = if (tomb?.version == version) tomb.payload_fingerprint else doc?.payload_fingerprint
            if (known != fingerprint) throw StateIntegrityConflict("Versión $version contradictoria para $uuid.")
            return
        }
        q.deleteStateTombstone(s.sessionId, s.generation, uuid)
        q.insertStateDocument(s.sessionId, s.generation, uuid, erpId, version, estado, agentId,
            updatedAt, seq, fingerprint)
    }

    private fun applyTombstone(s: EstadoSession, event: CotizacionEventDto) {
        val fingerprint = projectionFingerprint(event.cotizacionUuid, event.erpId, event.version,
            false, null, null, event.changedAt)
        val doc = q.findStateDocument(s.sessionId, s.generation, event.cotizacionUuid).executeAsOneOrNull()
        val tomb = q.findStateTombstone(s.sessionId, s.generation, event.cotizacionUuid).executeAsOneOrNull()
        val knownVersion = maxOf(doc?.version ?: -1, tomb?.version ?: -1)
        if (event.version < knownVersion) return
        if (event.version == knownVersion) {
            val known = if (tomb?.version == event.version) tomb.payload_fingerprint else doc?.payload_fingerprint
            if (known != fingerprint) throw StateIntegrityConflict("Tombstone contradictorio para ${event.cotizacionUuid}.")
            return
        }
        q.deleteStateDocument(s.sessionId, s.generation, event.cotizacionUuid)
        q.insertStateTombstone(s.sessionId, s.generation, event.cotizacionUuid, event.erpId,
            event.version, event.eventId, event.changedAt, event.deliverySequence, fingerprint)
    }

    fun recordAck(s: EstadoSession, ack: AckDto) {
        require(ack.ackThroughSequence in s.acked..s.committed)
        val now = clock.wallNow().toString()
        val bootstrap = if (ack.state == "ACTIVA") "ACTIVA" else s.bootstrapState
        q.ackStatePage(ack.ackThroughSequence, ack.state, bootstrap, now, now, s.sessionId, s.generation)
    }

    fun reconcileState(s: EstadoSession, state: SessionStateDto) {
        require(state.sessionId == s.sessionId && state.generation == s.generation)
        require(state.lastAckedSequence <= s.committed) { "Sync confirmó una secuencia que no existe localmente." }
        val now = clock.wallNow().toString()
        val lease = validUntil(state.offlineAuthorization)
        val existingMax = q.findStateSession(s.sessionId, s.generation).executeAsOne().max_observed_wall_utc
        q.updateStateRemoteStatus(state.state, if (state.state == "ACTIVA") "ACTIVA" else state.state,
            lease, now, clock.elapsedRealtimeMillis(), maxWall(existingMax, now), state.reasonCode.ifBlank { null },
            if (state.ingestionSuspended) 1 else 0, state.capacityCode.ifBlank { null }, now, now,
            s.sessionId, s.generation)
        if (state.lastAckedSequence > s.acked) {
            q.ackStatePage(state.lastAckedSequence, state.state,
                if (state.state == "ACTIVA") "ACTIVA" else s.bootstrapState, now, now, s.sessionId, s.generation)
        }
    }

    fun updateLease(s: EstadoSession, response: RevalidationDto) {
        require(response.authorized)
        val valid = requireValidLease(response.validUntil)
        val now = clock.wallNow().toString()
        val existingMax = q.findStateSession(s.sessionId, s.generation).executeAsOne().max_observed_wall_utc
        q.updateStateRemoteStatus(response.state, if (response.state == "ACTIVA") "ACTIVA" else response.state,
            valid, now, clock.elapsedRealtimeMillis(), maxWall(existingMax, now), null, 0, null, now, now,
            s.sessionId, s.generation)
    }

    fun markError(s: EstadoSession, code: String, message: String) =
        q.markStateError(code, message.take(500), clock.wallNow().toString(), s.sessionId, s.generation)

    fun revokeAndPurge(s: EstadoSession, code: String, message: String) {
        db.transaction {
            q.markStateBlocked("REVOCADA", "REVOCADA", code, code, message.take(500),
                clock.wallNow().toString(), s.sessionId, s.generation)
            // Cascading deletion would remove the marker, so remove commercial projections explicitly.
            q.deleteAllStateDocuments(s.sessionId, s.generation)
            q.deleteAllStateTombstones(s.sessionId, s.generation)
            q.deleteAllStateReceipts(s.sessionId, s.generation)
        }
    }

    fun markResetRequired(s: EstadoSession, code: String, message: String) = q.markStateBlocked(
        "REQUIERE_REINICIO", "REQUIERE_REINICIO", code, code, message.take(500),
        clock.wallNow().toString(), s.sessionId, s.generation)

    fun purgeNamespace(s: EstadoSession) = db.transaction {
        q.deleteAllStateDocuments(s.sessionId, s.generation)
        q.deleteAllStateTombstones(s.sessionId, s.generation)
        q.deleteAllStateReceipts(s.sessionId, s.generation)
        q.purgeStateNamespace(s.sessionId, s.generation)
    }
    fun purgeUser(userId: Long) = db.transaction {
        q.listStateSessionsForUser(userId).executeAsList().forEach { row ->
            q.deleteAllStateDocuments(row.session_id, row.generation)
            q.deleteAllStateTombstones(row.session_id, row.generation)
            q.deleteAllStateReceipts(row.session_id, row.generation)
            q.purgeStateNamespace(row.session_id, row.generation)
        }
    }

    fun authorizedDocuments(userId: Long, agentId: Long, deviceId: String): List<CotizacionSincronizada> {
        val s = latest(userId, agentId, deviceId) ?: throw StateAccessDenied("Cotizaciones sincronizadas aún no inicializadas.")
        requireReadable(s, userId, agentId, deviceId)
        return q.listStateDocuments(s.sessionId, s.generation).executeAsList().map {
            CotizacionSincronizada(it.cotizacion_uuid, it.erp_id, it.estado, it.version, it.agent_id, it.updated_at_utc)
        }
    }

    fun requireReadable(s: EstadoSession, userId: Long, agentId: Long, deviceId: String) {
        if (s.userId != userId || s.agentId != agentId || s.deviceId != deviceId)
            throw StateAccessDenied("La identidad activa no corresponde a esta sesión.")
        if (s.remoteState != "ACTIVA" || s.bootstrapState != "ACTIVA")
            throw StateAccessDenied("La sesión de Cotizaciones no está activa.")
        val expiry = s.validUntil?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ?: throw StateAccessDenied("La autorización offline no está disponible.")
        val now = clock.wallNow()
        if (now >= expiry) throw StateAccessDenied("La autorización offline venció.")
        val row = q.findStateSession(s.sessionId, s.generation).executeAsOne()
        val maxWall = row.max_observed_wall_utc?.let { runCatching { Instant.parse(it) }.getOrNull() }
        if (maxWall != null && now.plus(ROLLBACK_TOLERANCE) < maxWall)
            throw StateAccessDenied("El reloj local retrocedió; revalida en línea.")
        val observedWall = row.last_server_observed_at_utc?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val observedElapsed = row.last_elapsed_observed_ms
        val elapsed = clock.elapsedRealtimeMillis()
        if (observedWall != null && observedElapsed != null && elapsed >= observedElapsed) {
            val monotonicNow = observedWall + (elapsed - observedElapsed).milliseconds
            if (monotonicNow >= expiry) throw StateAccessDenied("La autorización offline venció.")
        }
    }

    private fun requireNamespace(sessionId: String, generation: Long, userId: Long, agentId: Long, deviceId: String): EstadoSession {
        val s = find(sessionId, generation) ?: throw StateIntegrityConflict("Namespace desconocido.")
        require(s.userId == userId && s.agentId == agentId && s.deviceId == deviceId) { "Namespace de otra identidad." }
        require(s.bootstrapState !in setOf("REVOCADA", "REQUIERE_REINICIO", "CONFLICTO_INTEGRIDAD"))
        return s
    }

    private fun validateEnvelope(committed: Long, from: Long, to: Long, sequences: List<Long>, hasMore: Boolean) {
        require(from >= 0 && to >= from)
        if (sequences.isEmpty()) {
            require(from == committed && to == committed && !hasMore) { "Página vacía avanzó el checkpoint." }
            return
        }
        sequences.zipWithNext().forEach { (a, b) -> require(b == a + 1) { "deliverySequence no contiguo." } }
        require(from == sequences.first() && to == sequences.last())
        require(sequences.first() == committed + 1 || sequences.last() <= committed) { "La página salta el checkpoint." }
    }

    private sealed class Delivery(open val sequence: Long, val kind: String) {
        data class SnapshotEnd(override val sequence: Long) : Delivery(sequence, "SNAPSHOT_END")
        data class Visible(override val sequence: Long, val document: SnapshotDocumentDto) : Delivery(sequence, "SNAPSHOT")
        data class Event(val event: CotizacionEventDto) : Delivery(event.deliverySequence, "EVENT") { override val sequence = event.deliverySequence }
    }

    private fun receiptFingerprint(d: Delivery): String = when (d) {
        is Delivery.SnapshotEnd -> hash("SNAPSHOT_END\n${d.sequence}")
        is Delivery.Visible -> hash("SNAPSHOT\n${d.sequence}\n" + projectionCanonical(d.document.cotizacionUuid,
            d.document.erpId, d.document.version, true, d.document.estado, d.document.agenteId, d.document.updatedAt))
        is Delivery.Event -> hash("EVENT\n${d.sequence}\n${d.event.eventId}\n${d.event.eventType}\n" +
            projectionCanonical(d.event.cotizacionUuid, d.event.erpId, d.event.version, d.event.visible,
                d.event.estado, d.event.agenteId, d.event.changedAt))
    }
    private fun projectionFingerprint(uuid: String, erpId: Long, version: Long, visible: Boolean,
                                      state: String?, agent: Long?, changed: String?) =
        hash(projectionCanonical(uuid, erpId, version, visible, state, agent, changed))
    private fun projectionCanonical(uuid: String, erpId: Long, version: Long, visible: Boolean,
                                    state: String?, agent: Long?, changed: String?) =
        listOf(uuid.lowercase(), erpId, version, visible, state ?: "<null>", agent ?: "<null>", changed ?: "<null>").joinToString("\n")
    private fun hash(value: String) = sha256Hex(value.encodeToByteArray())

    private fun validUntil(authorization: OfflineAuthorizationDto): String? =
        if (authorization.valid && authorization.validUntil.isNotBlank()) requireValidLease(authorization.validUntil) else null
    private fun requireValidLease(value: String): String {
        val expiry = Instant.parse(value)
        val now = clock.wallNow()
        require(expiry > now && expiry <= now.plus(MAX_LEASE)) { "Lease fuera del máximo de 24 horas." }
        return expiry.toString()
    }
    private fun maxWall(old: String?, now: String): String = listOfNotNull(old, now).maxOrNull()!!
    private fun canonicalUuid(value: String) = UUID_REGEX.matches(value)

    @Suppress("UNCHECKED_CAST")
    private fun session(row: com.cyssoftware.effyflow.db.Cot_estado_sync_session) = EstadoSession(
        row.session_id,row.user_id,row.agent_id,row.device_id,row.generation,row.remote_state,row.bootstrap_state,
        row.last_committed_sequence,row.last_acked_sequence,row.snapshot_end_sequence,row.offline_valid_until_utc,
        row.ingestion_suspended == 1L,row.capacity_code,row.last_sync_at_utc,row.last_error_code,row.last_error_message)

    companion object {
        private val UUID_REGEX = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
        private val MAX_LEASE = kotlin.time.Duration.parse("24h")
        private val ROLLBACK_TOLERANCE = kotlin.time.Duration.parse("5m")
    }
}
