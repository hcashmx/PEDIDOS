package com.cyssoftware.effyFlow.repository;

import com.cyssoftware.effyFlow.service.cotizaciones.CotizacionSyncRepositoryModels.DeliveryRow;
import com.cyssoftware.effyFlow.service.cotizaciones.CotizacionSyncRepositoryModels.DeliveryPage;
import com.cyssoftware.effyFlow.service.cotizaciones.CotizacionSyncRepositoryModels.ErpEvent;
import com.cyssoftware.effyFlow.service.cotizaciones.CotizacionSyncRepositoryModels.SessionRow;
import com.cyssoftware.effyFlow.service.cotizaciones.CotizacionSyncRepositoryModels.SnapshotDocument;
import com.cyssoftware.effyFlow.service.cotizaciones.CotizacionSyncRepositoryModels.QueueUsage;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** PostgreSQL persistence for one isolated Cotizaciones session. */
@Repository
@RequiredArgsConstructor
public class CotizacionSyncRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public SessionRow findCurrent(long userId, int agentId, UUID deviceId) {
        List<SessionRow> rows = jdbc.query("""
                SELECT * FROM public.sync_cotizacion_sesion
                WHERE usuario_erp_id=:userId AND agente_erp_id=:agentId AND device_id=:deviceId
                  AND state IN ('BOOTSTRAP_PENDIENTE','INICIALIZANDO','ACTIVA')
                ORDER BY generation DESC LIMIT 1
                """, Map.of("userId", userId, "agentId", agentId, "deviceId", deviceId),
                CotizacionSyncRepository::sessionRow);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Transactional
    public SessionAcquisition getOrCreateCurrent(long userId, int agentId, UUID deviceId) {
        lockIdentity(userId, agentId, deviceId);
        SessionRow current = findCurrent(userId, agentId, deviceId);
        return current == null ? new SessionAcquisition(insertSession(userId, agentId, deviceId), true)
                : new SessionAcquisition(current, false);
    }

    @Transactional
    public SessionRow createSession(long userId, int agentId, UUID deviceId) {
        lockIdentity(userId, agentId, deviceId);
        return insertSession(userId, agentId, deviceId);
    }

    private void lockIdentity(long userId, int agentId, UUID deviceId) {
        String lockKey = userId + ":" + agentId + ":" + deviceId;
        jdbc.getJdbcTemplate().queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                (rs, n) -> rs.getObject(1), lockKey);
    }

    private SessionRow insertSession(long userId, int agentId, UUID deviceId) {
        Long generation = jdbc.queryForObject("""
                INSERT INTO public.sync_cotizacion_generation(usuario_erp_id,agente_erp_id,device_id,last_generation)
                VALUES (:u,:a,:d,1)
                ON CONFLICT (usuario_erp_id,agente_erp_id,device_id) DO UPDATE SET
                  last_generation=public.sync_cotizacion_generation.last_generation+1,updated_at=CURRENT_TIMESTAMP
                RETURNING last_generation
                """, Map.of("u", userId, "a", agentId, "d", deviceId), Long.class);
        UUID sessionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO public.sync_cotizacion_sesion
                  (session_id,usuario_erp_id,agente_erp_id,device_id,generation,state)
                VALUES (:id,:u,:a,:d,:g,'BOOTSTRAP_PENDIENTE')
                """, new MapSqlParameterSource().addValue("id", sessionId).addValue("u", userId)
                .addValue("a", agentId).addValue("d", deviceId).addValue("g", generation));
        return findById(sessionId);
    }

    public SessionRow findById(UUID sessionId) {
        List<SessionRow> rows = jdbc.query("SELECT * FROM public.sync_cotizacion_sesion WHERE session_id=:id",
                Map.of("id", sessionId), CotizacionSyncRepository::sessionRow);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Sesión no encontrada.");
        return rows.getFirst();
    }

    public SessionRow lockSession(UUID sessionId) {
        List<SessionRow> rows = jdbc.query("SELECT * FROM public.sync_cotizacion_sesion WHERE session_id=:id FOR UPDATE",
                Map.of("id", sessionId), CotizacionSyncRepository::sessionRow);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Sesión no encontrada.");
        return rows.getFirst();
    }

    @Transactional
    public void touchClientActivity(UUID id) {
        jdbc.update("""
                UPDATE public.sync_cotizacion_sesion SET last_client_activity_at=CURRENT_TIMESTAMP
                WHERE session_id=:id AND state IN ('BOOTSTRAP_PENDIENTE','INICIALIZANDO','ACTIVA')
                """, Map.of("id", id));
    }

    @Transactional
    public boolean claimBootstrap(UUID id) {
        SessionRow row = lockSession(id);
        if (!"BOOTSTRAP_PENDIENTE".equals(row.state()) || row.bootstrapStarted()) return false;
        return jdbc.update("""
                UPDATE public.sync_cotizacion_sesion SET bootstrap_started=true,updated_at=CURRENT_TIMESTAMP,
                  row_version=row_version+1 WHERE session_id=:id AND state='BOOTSTRAP_PENDIENTE'
                """, Map.of("id", id)) == 1;
    }

    @Transactional
    public void saveBootstrap(UUID id, UUID owner, long startCursor, long maxCotizacionId) {
        SessionRow row = lockSession(id);
        requireLease(id, owner);
        if (!row.bootstrapStarted() || !"BOOTSTRAP_PENDIENTE".equals(row.state()))
            throw restart("El bootstrap no está disponible para esta generación.");
        jdbc.update("""
                UPDATE public.sync_cotizacion_sesion SET state='INICIALIZANDO',start_cursor=:cursor,
                  erp_cursor=:cursor,max_cotizacion_id=:maxId,updated_at=CURRENT_TIMESTAMP,
                  row_version=row_version+1 WHERE session_id=:id
                """, Map.of("id", id, "cursor", startCursor, "maxId", maxCotizacionId));
    }

    @Transactional
    public void saveSnapshotPage(UUID id, UUID owner, long expectedAfterId, long nextAfterId,
            boolean hasMore, List<SnapshotDocument> documents) {
        SessionRow row = lockSession(id);
        requireLease(id, owner);
        requireState(row, "INICIALIZANDO");
        if (row.snapshotSourceComplete() || row.snapshotAfterId() != expectedAfterId
                || nextAfterId < expectedAfterId || (hasMore && nextAfterId == expectedAfterId))
            throw restart("El checkpoint de snapshot no avanzó conforme al contrato.");
        Long nextOrdinal = jdbc.queryForObject("""
                SELECT COALESCE(MAX(snapshot_ordinal),0)+1 FROM public.sync_cotizacion_snapshot WHERE session_id=:id
                """, Map.of("id", id), Long.class);
        for (SnapshotDocument d : documents) {
            jdbc.update("""
                    INSERT INTO public.sync_cotizacion_snapshot
                      (session_id,snapshot_ordinal,cotizacion_uuid,erp_id,estado,version,agente_id,updated_at)
                    VALUES (:s,:o,:uuid,:erp,:estado,:version,:agent,:updated)
                    """, new MapSqlParameterSource().addValue("s", id).addValue("o", nextOrdinal++)
                    .addValue("uuid", d.cotizacionUuid()).addValue("erp", d.erpId()).addValue("estado", d.estado())
                    .addValue("version", d.version()).addValue("agent", d.agenteId()).addValue("updated", timestamp(d.updatedAt())));
        }
        jdbc.update("""
                UPDATE public.sync_cotizacion_sesion SET snapshot_after_id=:next,
                  snapshot_source_complete=:complete,updated_at=CURRENT_TIMESTAMP,row_version=row_version+1
                WHERE session_id=:id
                """, Map.of("id", id, "next", nextAfterId, "complete", !hasMore));
    }

    @Transactional
    public void materializeSnapshot(UUID id, UUID owner) {
        SessionRow session = lockSession(id);
        requireLease(id, owner);
        requireState(session, "INICIALIZANDO");
        if (!session.snapshotSourceComplete() || session.snapshotComplete()) return;
        List<SnapshotDocument> docs = jdbc.query("""
                SELECT cotizacion_uuid,erp_id,estado,version,agente_id,updated_at
                FROM public.sync_cotizacion_snapshot WHERE session_id=:id ORDER BY snapshot_ordinal
                """, Map.of("id", id), (rs, n) -> snapshotRow(rs));
        for (SnapshotDocument doc : docs) {
            jdbc.update("""
                    INSERT INTO public.sync_cotizacion_projection
                      (session_id,cotizacion_uuid,erp_id,estado,version,agente_id,changed_at,last_event_id,visible)
                    VALUES (:s,:uuid,:erp,:state,:v,:agent,:updated,0,true)
                    ON CONFLICT (session_id,cotizacion_uuid) DO UPDATE SET
                      erp_id=EXCLUDED.erp_id,estado=EXCLUDED.estado,version=EXCLUDED.version,
                      agente_id=EXCLUDED.agente_id,changed_at=EXCLUDED.changed_at,visible=true,
                      updated_at=CURRENT_TIMESTAMP
                    """, new MapSqlParameterSource().addValue("s", id).addValue("uuid", doc.cotizacionUuid())
                    .addValue("erp", doc.erpId()).addValue("state", doc.estado()).addValue("v", doc.version())
                    .addValue("agent", doc.agenteId()).addValue("updated", timestamp(doc.updatedAt())));
            insertDeliverySnapshot(session, doc);
        }
        insertSnapshotEnd(session);
        List<ErpEvent> buffered = jdbc.query("""
                SELECT * FROM public.sync_cotizacion_event_inbox WHERE session_id=:id ORDER BY event_id
                """, Map.of("id", id), CotizacionSyncRepository::eventRow);
        for (ErpEvent event : buffered) {
            applyProjection(id, event);
            insertDeliveryEvent(id, event);
            jdbc.update("UPDATE public.sync_cotizacion_event_inbox SET delivered=true WHERE session_id=:s AND event_id=:e",
                    Map.of("s", id, "e", event.eventId()));
        }
        jdbc.update("""
                UPDATE public.sync_cotizacion_sesion SET snapshot_complete=true,updated_at=CURRENT_TIMESTAMP,
                  row_version=row_version+1 WHERE session_id=:id
                """, Map.of("id", id));
    }

    @Transactional
    public boolean ingestEventPage(UUID id, UUID owner, long expectedCursor, long nextCursor, boolean hasMore,
            List<ErpEvent> events, Instant leaseUntil, boolean renewLease, long maxPendingEntries,
            long maxEstimatedBytes) {
        SessionRow session = lockSession(id);
        requireLease(id, owner);
        if (!List.of("INICIALIZANDO", "ACTIVA").contains(session.state()))
            throw restart("La sesión no admite eventos.");
        if (session.erpCursor() == null || session.erpCursor() != expectedCursor)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El cursor ERP cambió; vuelva a consultar el estado.");
        if (nextCursor < expectedCursor || (events.isEmpty() && nextCursor != expectedCursor)
                || (hasMore && events.isEmpty()) || (!events.isEmpty() && nextCursor != events.getLast().eventId()))
            throw restart("La página incremental no respeta cursor, orden o progreso ERP.");
        long newEntries = 0;
        long newBytes = 0;
        long priorEvent = expectedCursor;
        for (ErpEvent event : events) {
            if (event.eventId() <= priorEvent) throw restart("Los eventos ERP no están en orden ascendente.");
            priorEvent = event.eventId();
            String existing = jdbc.query("""
                    SELECT payload_sha256 FROM public.sync_cotizacion_event_ledger WHERE session_id=:s AND event_id=:e
                    """, Map.of("s", id, "e", event.eventId()), rs -> rs.next() ? rs.getString(1) : null);
            if (isCompatibleLedgerReplay(existing, event.payloadSha256())) continue;
            newEntries++;
            newBytes += estimateEventBytes(event);
        }
        QueueUsage usage = queueUsage(id);
        if (session.queueSaturated() || usage.pendingEntries() + newEntries > maxPendingEntries
                || usage.estimatedBytes() + newBytes > maxEstimatedBytes) {
            setQueueSaturated(id, true);
            return false;
        }
        setQueueSaturated(id, false);
        for (ErpEvent event : events) {
            String existing = jdbc.query("""
                    SELECT payload_sha256 FROM public.sync_cotizacion_event_ledger WHERE session_id=:s AND event_id=:e
                    """, Map.of("s", id, "e", event.eventId()), rs -> rs.next() ? rs.getString(1) : null);
            if (isCompatibleLedgerReplay(existing, event.payloadSha256())) continue;
            jdbc.update("""
                    INSERT INTO public.sync_cotizacion_event_ledger(session_id,event_id,payload_sha256)
                    VALUES (:s,:event,:hash)
                    """, Map.of("s", id, "event", event.eventId(), "hash", event.payloadSha256()));
            jdbc.update("""
                    INSERT INTO public.sync_cotizacion_event_inbox
                      (session_id,event_id,cotizacion_uuid,erp_id,estado,version,changed_at,event_type,
                       agente_id,visible,payload_sha256)
                    VALUES (:s,:event,:uuid,:erp,:state,:version,:changed,:type,:agent,:visible,:hash)
                    """, new MapSqlParameterSource().addValue("s", id).addValue("event", event.eventId())
                    .addValue("uuid", event.cotizacionUuid()).addValue("erp", event.erpId())
                    .addValue("state", event.estado()).addValue("version", event.version())
                    .addValue("changed", timestamp(event.changedAt())).addValue("type", event.eventType())
                    .addValue("agent", event.agenteId()).addValue("visible", event.visible())
                    .addValue("hash", event.payloadSha256()));
            if (session.snapshotComplete()) {
                applyProjection(id, event);
                insertDeliveryEvent(id, event);
                jdbc.update("UPDATE public.sync_cotizacion_event_inbox SET delivered=true WHERE session_id=:s AND event_id=:e",
                        Map.of("s", id, "e", event.eventId()));
            }
        }
        jdbc.update("""
                UPDATE public.sync_cotizacion_sesion SET erp_cursor=:next,
                  offline_valid_until=CASE WHEN :renew THEN :lease ELSE offline_valid_until END,
                  updated_at=CURRENT_TIMESTAMP,row_version=row_version+1 WHERE session_id=:id
                """, new MapSqlParameterSource().addValue("next", nextCursor).addValue("renew", renewLease)
                .addValue("lease", timestamp(leaseUntil)).addValue("id", id));
        return true;
    }

    @Transactional
    public QueueUsage queueUsage(UUID id, long maxPendingEntries, long maxEstimatedBytes,
            long resumePendingEntries, long resumeEstimatedBytes) {
        SessionRow session = lockSession(id);
        QueueUsage current = queueUsage(id);
        boolean saturated = shouldSuspendIngestion(session.queueSaturated(), current,
                maxPendingEntries, maxEstimatedBytes, resumePendingEntries, resumeEstimatedBytes);
        setQueueSaturated(id, saturated);
        return new QueueUsage(current.pendingEntries(), current.estimatedBytes(), saturated);
    }

    private QueueUsage queueUsage(UUID id) {
        return jdbc.query("""
                SELECT COALESCE(SUM(entries),0) AS pending_entries,COALESCE(SUM(bytes),0) AS estimated_bytes
                FROM (
                  SELECT COUNT(*) AS entries,COALESCE(SUM(pg_column_size(d)),0) AS bytes
                  FROM public.sync_cotizacion_delivery d
                  JOIN public.sync_cotizacion_sesion s ON s.session_id=d.session_id
                  WHERE d.session_id=:s AND d.delivery_sequence>s.delivery_acked_sequence
                  UNION ALL
                  SELECT COUNT(*) AS entries,COALESCE(SUM(pg_column_size(i)),0) AS bytes
                  FROM public.sync_cotizacion_event_inbox i
                  WHERE i.session_id=:s AND NOT i.delivered
                ) q
                """, Map.of("s", id), rs -> {
                    rs.next();
                    return new QueueUsage(rs.getLong("pending_entries"), rs.getLong("estimated_bytes"), false);
                });
    }

    private void setQueueSaturated(UUID id, boolean saturated) {
        jdbc.update("""
                UPDATE public.sync_cotizacion_sesion
                SET queue_saturated=:saturated,
                    queue_saturated_at=CASE WHEN :saturated THEN COALESCE(queue_saturated_at,CURRENT_TIMESTAMP) ELSE NULL END,
                    updated_at=CURRENT_TIMESTAMP,row_version=row_version+1
                WHERE session_id=:id AND queue_saturated<>:saturated
                """, Map.of("id", id, "saturated", saturated));
    }

    private static long estimateEventBytes(ErpEvent event) {
        return 256L + utf8Length(event.estado()) + utf8Length(event.eventType());
    }

    private static int utf8Length(String value) {
        return value == null ? 0 : value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    static boolean isCompatibleLedgerReplay(String existingHash, String incomingHash) {
        if (existingHash == null) return false;
        if (!existingHash.equals(incomingHash))
            throw restart("Un eventId se recibió con contenido contradictorio.");
        return true;
    }

    static boolean shouldSuspendIngestion(boolean previouslySuspended, QueueUsage usage,
            long maxPendingEntries, long maxEstimatedBytes, long resumePendingEntries,
            long resumeEstimatedBytes) {
        if (previouslySuspended)
            return usage.pendingEntries() > resumePendingEntries || usage.estimatedBytes() > resumeEstimatedBytes;
        return usage.pendingEntries() >= maxPendingEntries || usage.estimatedBytes() >= maxEstimatedBytes;
    }

    private void applyProjection(UUID sessionId, ErpEvent event) {
        Projection prior = jdbc.query("""
                SELECT erp_id,version,estado,agente_id,visible FROM public.sync_cotizacion_projection
                WHERE session_id=:s AND cotizacion_uuid=:uuid FOR UPDATE
                """, Map.of("s", sessionId, "uuid", event.cotizacionUuid()), rs ->
                rs.next() ? new Projection(rs.getLong("erp_id"), rs.getLong("version"), rs.getString("estado"),
                        nullableInteger(rs, "agente_id"), rs.getBoolean("visible")) : null);
        Long tombstoneVersion = jdbc.query("""
                SELECT version FROM public.sync_cotizacion_tombstone
                WHERE session_id=:s AND cotizacion_uuid=:uuid
                """, Map.of("s", sessionId, "uuid", event.cotizacionUuid()), rs -> rs.next() ? rs.getLong(1) : null);
        if (!event.visible()) {
            if ((prior == null || event.version() >= prior.version())
                    && (tombstoneVersion == null || event.version() >= tombstoneVersion)) {
                jdbc.update("""
                        INSERT INTO public.sync_cotizacion_projection
                          (session_id,cotizacion_uuid,erp_id,estado,version,agente_id,changed_at,last_event_id,visible)
                        VALUES (:s,:uuid,:erp,NULL,:v,NULL,:changed,:event,false)
                        ON CONFLICT (session_id,cotizacion_uuid) DO UPDATE SET
                          erp_id=EXCLUDED.erp_id,estado=NULL,version=EXCLUDED.version,agente_id=NULL,
                          changed_at=EXCLUDED.changed_at,last_event_id=EXCLUDED.last_event_id,visible=false,
                          updated_at=CURRENT_TIMESTAMP
                        """, projectionParams(sessionId, event));
                jdbc.update("""
                        INSERT INTO public.sync_cotizacion_tombstone(session_id,cotizacion_uuid,version,event_id)
                        VALUES (:s,:uuid,:v,:event)
                        ON CONFLICT (session_id,cotizacion_uuid) DO UPDATE SET
                          version=EXCLUDED.version,event_id=EXCLUDED.event_id,revoked_at=CURRENT_TIMESTAMP
                        WHERE EXCLUDED.version >= public.sync_cotizacion_tombstone.version
                        """, new MapSqlParameterSource().addValue("s", sessionId).addValue("uuid", event.cotizacionUuid())
                        .addValue("v", event.version()).addValue("event", event.eventId()));
            }
            return;
        }
        if ((tombstoneVersion != null && event.version() <= tombstoneVersion)
                || (prior != null && event.version() < prior.version())) return;
        if (prior != null && event.version() == prior.version()) {
            if (!prior.visible() || prior.erpId() != event.erpId()
                    || !java.util.Objects.equals(prior.estado(), event.estado())
                    || !java.util.Objects.equals(prior.agenteId(), event.agenteId()))
                throw restart("La misma versión ERP contiene estados contradictorios.");
            return;
        }
        jdbc.update("""
                INSERT INTO public.sync_cotizacion_projection
                  (session_id,cotizacion_uuid,erp_id,estado,version,agente_id,changed_at,last_event_id,visible)
                VALUES (:s,:uuid,:erp,:state,:v,:agent,:changed,:event,true)
                ON CONFLICT (session_id,cotizacion_uuid) DO UPDATE SET
                  erp_id=EXCLUDED.erp_id,estado=EXCLUDED.estado,version=EXCLUDED.version,
                  agente_id=EXCLUDED.agente_id,changed_at=EXCLUDED.changed_at,
                  last_event_id=EXCLUDED.last_event_id,visible=true,updated_at=CURRENT_TIMESTAMP
                """, projectionParams(sessionId, event));
    }

    private MapSqlParameterSource projectionParams(UUID sessionId, ErpEvent event) {
        return new MapSqlParameterSource().addValue("s", sessionId).addValue("uuid", event.cotizacionUuid())
                .addValue("erp", event.erpId()).addValue("state", event.estado()).addValue("v", event.version())
                .addValue("agent", event.agenteId()).addValue("changed", timestamp(event.changedAt()))
                .addValue("event", event.eventId());
    }

    private void insertDeliverySnapshot(SessionRow session, SnapshotDocument d) {
        appendDelivery(session.sessionId(), "SNAPSHOT", null, d.cotizacionUuid(), d.erpId(), d.estado(),
                d.version(), d.updatedAt(), null, d.agenteId(), true);
    }

    private void insertSnapshotEnd(SessionRow session) {
        appendDelivery(session.sessionId(), "SNAPSHOT_END", null, null, null, null, null, null, null, null, null);
    }

    private void insertDeliveryEvent(UUID sessionId, ErpEvent e) {
        appendDelivery(sessionId, "EVENT", e.eventId(), e.cotizacionUuid(), e.erpId(), e.estado(),
                e.version(), e.changedAt(), e.eventType(), e.agenteId(), e.visible());
    }

    private void appendDelivery(UUID sessionId, String type, Long eventId, UUID uuid, Long erpId,
            String state, Long version, Instant changed, String eventType, Integer agent, Boolean visible) {
        long seq = jdbc.queryForObject("""
                UPDATE public.sync_cotizacion_sesion SET next_delivery_sequence=next_delivery_sequence+1,
                  updated_at=CURRENT_TIMESTAMP,row_version=row_version+1
                WHERE session_id=:s RETURNING next_delivery_sequence-1
                """, Map.of("s", sessionId), Long.class);
        jdbc.update("""
                INSERT INTO public.sync_cotizacion_delivery
                  (session_id,delivery_sequence,item_type,event_id,cotizacion_uuid,erp_id,estado,version,
                   changed_at,event_type,agente_id,visible)
                VALUES (:s,:seq,:type,:event,:uuid,:erp,:state,:version,:changed,:eventType,:agent,:visible)
                """, new MapSqlParameterSource().addValue("s", sessionId).addValue("seq", seq)
                .addValue("type", type).addValue("event", eventId).addValue("uuid", uuid).addValue("erp", erpId)
                .addValue("state", state).addValue("version", version).addValue("changed", timestamp(changed))
                .addValue("eventType", eventType).addValue("agent", agent).addValue("visible", visible));
    }

    @Transactional
    public DeliveryPage offer(UUID id, String type, long afterSequence, int limit) {
        // Linearization point: the locked active-session check and offered watermark commit together.
        // A revocation committed first is observed here and cannot create a new offer.
        SessionRow session = lockSession(id);
        requireState(session, "INICIALIZANDO", "ACTIVA");
        if (afterSequence < session.deliveryAckedSequence()) {
            Long floor = jdbc.queryForObject("SELECT delivery_retention_floor FROM public.sync_cotizacion_sesion WHERE session_id=:s",
                    Map.of("s", id), Long.class);
            if (floor != null && afterSequence < floor - 1)
                throw new ResponseStatusException(HttpStatus.GONE, "CURSOR_DE_ENTREGA_EXPIRADO");
        }
        if (afterSequence != session.deliveryAckedSequence())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La secuencia solicitada no coincide con el ACK vigente.");
        String filter = "SNAPSHOT".equals(type)
                ? "item_type IN ('SNAPSHOT','SNAPSHOT_END')"
                : "item_type='EVENT'";
        if ("EVENT".equals(type) && !"ACTIVA".equals(session.state()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El snapshot aún no fue confirmado.");
        List<DeliveryRow> rows = jdbc.query("""
                SELECT delivery_sequence,item_type,event_id,cotizacion_uuid,erp_id,estado,version,
                       changed_at,event_type,agente_id,visible
                FROM public.sync_cotizacion_delivery WHERE session_id=:s AND delivery_sequence>:after
                  AND %s ORDER BY delivery_sequence LIMIT :lim
                """.formatted(filter), Map.of("s", id, "after", afterSequence, "lim", limit + 1),
                CotizacionSyncRepository::deliveryRow);
        boolean hasMore = rows.size() > limit;
        List<DeliveryRow> page = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
        long offered = page.isEmpty() ? session.lastOfferedSequence() : page.getLast().deliverySequence();
        jdbc.update("""
                UPDATE public.sync_cotizacion_sesion SET last_offered_sequence=GREATEST(last_offered_sequence,:offered),
                  updated_at=CURRENT_TIMESTAMP,row_version=row_version+1 WHERE session_id=:s
                """, Map.of("s", id, "offered", offered));
        return new DeliveryPage(page, hasMore);
    }

    @Transactional
    public long acknowledge(UUID id, long ackThrough) {
        SessionRow session = lockSession(id);
        if (ackThrough == session.deliveryAckedSequence()) return ackThrough;
        if (ackThrough < session.deliveryAckedSequence() || ackThrough > session.lastOfferedSequence())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "ACK fuera del rango ofrecido.");
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM public.sync_cotizacion_delivery
                WHERE session_id=:s AND delivery_sequence>:from AND delivery_sequence<=:to
                """, Map.of("s", id, "from", session.deliveryAckedSequence(), "to", ackThrough), Long.class);
        if (count == null || count != ackThrough - session.deliveryAckedSequence())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "ACK no contiguo.");
        jdbc.update("""
                UPDATE public.sync_cotizacion_sesion SET delivery_acked_sequence=:ack,
                  snapshot_acked_sequence=CASE WHEN state='INICIALIZANDO' THEN :ack ELSE snapshot_acked_sequence END,
                  state=CASE WHEN state='INICIALIZANDO' AND :ack >= (
                    SELECT COALESCE(MAX(delivery_sequence),0) FROM public.sync_cotizacion_delivery
                    WHERE session_id=:s AND item_type IN ('SNAPSHOT','SNAPSHOT_END')) THEN 'ACTIVA' ELSE state END,
                  updated_at=CURRENT_TIMESTAMP,row_version=row_version+1 WHERE session_id=:s
                """, Map.of("ack", ackThrough, "s", id));
        return ackThrough;
    }

    @Transactional
    public void renewLease(UUID id, Instant validUntil) {
        SessionRow session = lockSession(id);
        requireState(session, "INICIALIZANDO", "ACTIVA");
        jdbc.update("""
                UPDATE public.sync_cotizacion_sesion SET offline_valid_until=:until,updated_at=CURRENT_TIMESTAMP,
                  row_version=row_version+1 WHERE session_id=:id
                """, new MapSqlParameterSource().addValue("until", timestamp(validUntil)).addValue("id", id));
    }

    @Transactional
    public void revoke(UUID id, String reason) {
        SessionRow session = lockSession(id);
        if ("REVOCADA".equals(session.state())) return;
        jdbc.update("DELETE FROM public.sync_cotizacion_delivery WHERE session_id=:id", Map.of("id", id));
        jdbc.update("DELETE FROM public.sync_cotizacion_snapshot WHERE session_id=:id", Map.of("id", id));
        jdbc.update("DELETE FROM public.sync_cotizacion_projection WHERE session_id=:id", Map.of("id", id));
        jdbc.update("DELETE FROM public.sync_cotizacion_tombstone WHERE session_id=:id", Map.of("id", id));
        jdbc.update("DELETE FROM public.sync_cotizacion_event_inbox WHERE session_id=:id", Map.of("id", id));
        jdbc.update("""
                UPDATE public.sync_cotizacion_sesion SET state='REVOCADA',reason_code=:reason,
                  offline_valid_until=NULL,lease_owner=NULL,lease_until=NULL,queue_saturated=false,
                  queue_saturated_at=NULL,updated_at=CURRENT_TIMESTAMP,
                  row_version=row_version+1 WHERE session_id=:id
                """, Map.of("id", id, "reason", reason));
    }

    @Transactional
    public void requireRestart(UUID id, String reason) {
        jdbc.update("""
                UPDATE public.sync_cotizacion_sesion SET state='REQUIERE_REINICIO',reason_code=:reason,
                  lease_owner=NULL,lease_until=NULL,queue_saturated=false,queue_saturated_at=NULL,
                  updated_at=CURRENT_TIMESTAMP,row_version=row_version+1
                WHERE session_id=:id AND state NOT IN ('REVOCADA','REQUIERE_REINICIO','SESSION_EXPIRADA')
                """, Map.of("id", id, "reason", reason));
    }

    @Transactional
    public void closeForReset(UUID id) {
        SessionRow session = lockSession(id);
        if (!List.of("REVOCADA", "SESSION_EXPIRADA").contains(session.state())) {
            jdbc.update("""
                    UPDATE public.sync_cotizacion_sesion SET state='REQUIERE_REINICIO',reason_code='RESET_EXPLICITO',
                      offline_valid_until=NULL,lease_owner=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP,
                      row_version=row_version+1 WHERE session_id=:id
                    """, Map.of("id", id));
            purgePayload(id);
        }
    }

    @Transactional
    public boolean claimWorker(UUID sessionId, UUID owner, Instant leaseUntil) {
        return jdbc.update("""
                UPDATE public.sync_cotizacion_sesion SET lease_owner=:owner,lease_until=:until,
                  updated_at=CURRENT_TIMESTAMP,row_version=row_version+1
                WHERE session_id=:id AND state IN ('BOOTSTRAP_PENDIENTE','INICIALIZANDO','ACTIVA')
                  AND (lease_until IS NULL OR lease_until < CURRENT_TIMESTAMP OR lease_owner=:owner)
                """, new MapSqlParameterSource().addValue("id", sessionId).addValue("owner", owner)
                .addValue("until", timestamp(leaseUntil))) == 1;
    }

    @Transactional
    public void deferWorker(UUID sessionId) {
        jdbc.update("""
                UPDATE public.sync_cotizacion_sesion SET retry_count=LEAST(retry_count+1,8),
                  retry_after=CURRENT_TIMESTAMP + make_interval(secs => LEAST(300, 5 * power(2, LEAST(retry_count,6)))::INTEGER),
                  updated_at=CURRENT_TIMESTAMP
                WHERE session_id=:id AND state IN ('BOOTSTRAP_PENDIENTE','INICIALIZANDO','ACTIVA')
                """, Map.of("id", sessionId));
    }

    @Transactional
    public void clearWorkerBackoff(UUID sessionId) {
        jdbc.update("UPDATE public.sync_cotizacion_sesion SET retry_count=0,retry_after=CURRENT_TIMESTAMP WHERE session_id=:id",
                Map.of("id", sessionId));
    }

    @Transactional
    public void releaseWorker(UUID sessionId, UUID owner) {
        jdbc.update("""
                UPDATE public.sync_cotizacion_sesion SET lease_owner=NULL,lease_until=NULL,
                  updated_at=CURRENT_TIMESTAMP WHERE session_id=:id AND lease_owner=:owner
                """,
                Map.of("id", sessionId, "owner", owner));
    }

    public List<UUID> workerCandidates(int max) {
        return jdbc.query("""
                SELECT session_id FROM public.sync_cotizacion_sesion
                WHERE state IN ('BOOTSTRAP_PENDIENTE','INICIALIZANDO','ACTIVA')
                  AND retry_after <= CURRENT_TIMESTAMP
                  AND (lease_until IS NULL OR lease_until < CURRENT_TIMESTAMP)
                ORDER BY updated_at LIMIT :max
                """, Map.of("max", max), (rs, n) -> rs.getObject(1, UUID.class));
    }

    public long countUnacked(UUID id) {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM public.sync_cotizacion_delivery WHERE session_id=:s AND delivery_sequence>(SELECT delivery_acked_sequence FROM public.sync_cotizacion_sesion WHERE session_id=:s)",
                Map.of("s", id), Long.class);
        return value == null ? 0 : value;
    }

    @Transactional
    public int pruneAcknowledgedData(int retentionDays, int batchSize) {
        Map<String, Object> params = Map.of("days", retentionDays, "batch", batchSize);
        List<UUID> expired = jdbc.query("""
                UPDATE public.sync_cotizacion_sesion SET state='SESSION_EXPIRADA',reason_code='SESSION_EXPIRADA',
                  offline_valid_until=NULL,lease_owner=NULL,lease_until=NULL,queue_saturated=false,
                  queue_saturated_at=NULL,updated_at=CURRENT_TIMESTAMP,
                  row_version=row_version+1
                WHERE state IN ('BOOTSTRAP_PENDIENTE','INICIALIZANDO','ACTIVA')
                  AND last_client_activity_at < CURRENT_TIMESTAMP - INTERVAL '180 days'
                RETURNING session_id
                """, Map.of(), (rs, n) -> rs.getObject(1, UUID.class));
        for (UUID id : expired) purgePayload(id);
        Long deliveriesResult = jdbc.queryForObject("""
                WITH deleted AS (
                  DELETE FROM public.sync_cotizacion_delivery d
                  WHERE (d.session_id,d.delivery_sequence) IN (
                    SELECT d2.session_id,d2.delivery_sequence
                    FROM public.sync_cotizacion_delivery d2
                    JOIN public.sync_cotizacion_sesion s ON s.session_id=d2.session_id
                    WHERE d2.created_at < CURRENT_TIMESTAMP - make_interval(days => :days)
                      AND d2.delivery_sequence <= s.delivery_acked_sequence
                    ORDER BY d2.created_at LIMIT :batch
                  ) RETURNING session_id,delivery_sequence
                ), floors AS (
                  SELECT session_id,MAX(delivery_sequence)+1 AS floor FROM deleted GROUP BY session_id
                ), updated AS (
                UPDATE public.sync_cotizacion_sesion s SET delivery_retention_floor=GREATEST(s.delivery_retention_floor,f.floor)
                FROM floors f WHERE s.session_id=f.session_id
                RETURNING s.session_id
                )
                SELECT COUNT(*) FROM deleted
                """, params, Long.class);
        int deliveries = deliveriesResult == null ? 0 : Math.toIntExact(deliveriesResult);
        int snapshots = jdbc.update("""
                DELETE FROM public.sync_cotizacion_snapshot d
                WHERE (d.session_id,d.snapshot_ordinal) IN (
                  SELECT d2.session_id,d2.snapshot_ordinal
                  FROM public.sync_cotizacion_snapshot d2
                  JOIN public.sync_cotizacion_sesion s ON s.session_id=d2.session_id
                  WHERE s.state='ACTIVA' AND s.snapshot_complete
                    AND d2.created_at < CURRENT_TIMESTAMP - make_interval(days => :days)
                  ORDER BY d2.created_at LIMIT :batch
                )
                """, params);
        int inbox = jdbc.update("""
                DELETE FROM public.sync_cotizacion_event_inbox d
                WHERE (d.session_id,d.event_id) IN (
                  SELECT d2.session_id,d2.event_id FROM public.sync_cotizacion_event_inbox d2
                  JOIN public.sync_cotizacion_sesion s ON s.session_id=d2.session_id
                  WHERE d2.delivered AND d2.received_at < CURRENT_TIMESTAMP - make_interval(days => :days)
                    AND d2.event_id <= s.erp_cursor
                    AND NOT EXISTS (
                      SELECT 1 FROM public.sync_cotizacion_delivery pending
                      WHERE pending.session_id=d2.session_id AND pending.event_id=d2.event_id
                        AND pending.delivery_sequence>s.delivery_acked_sequence
                    )
                  ORDER BY d2.received_at LIMIT :batch
                )
                """, params);
        int oldSessions = jdbc.update("""
                DELETE FROM public.sync_cotizacion_sesion
                WHERE state IN ('REVOCADA','REQUIERE_REINICIO','SESSION_EXPIRADA')
                  AND updated_at < CURRENT_TIMESTAMP - INTERVAL '90 days'
                """, Map.of());
        return expired.size() + oldSessions + deliveries + snapshots + inbox;
    }

    private void purgePayload(UUID id) {
        jdbc.update("DELETE FROM public.sync_cotizacion_delivery WHERE session_id=:id", Map.of("id", id));
        jdbc.update("DELETE FROM public.sync_cotizacion_snapshot WHERE session_id=:id", Map.of("id", id));
        jdbc.update("DELETE FROM public.sync_cotizacion_projection WHERE session_id=:id", Map.of("id", id));
        jdbc.update("DELETE FROM public.sync_cotizacion_tombstone WHERE session_id=:id", Map.of("id", id));
        jdbc.update("DELETE FROM public.sync_cotizacion_event_inbox WHERE session_id=:id", Map.of("id", id));
    }

    private static void requireState(SessionRow row, String... valid) {
        for (String state : valid) if (state.equals(row.state())) return;
        throw restart("La sesión requiere reinicio.");
    }

    private void requireLease(UUID sessionId, UUID owner) {
        Boolean valid = jdbc.query("""
                SELECT lease_owner=:owner AND lease_until>CURRENT_TIMESTAMP
                FROM public.sync_cotizacion_sesion WHERE session_id=:id
                """, Map.of("id", sessionId, "owner", owner), rs -> rs.next() && rs.getBoolean(1));
        if (!Boolean.TRUE.equals(valid))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El lease del consumidor cambiÃ³; se descarta la pÃ¡gina ERP.");
    }

    private static ResponseStatusException restart(String message) {
        return new ResponseStatusException(HttpStatus.GONE, message);
    }

    private static SessionRow sessionRow(ResultSet rs, int n) throws SQLException {
        return new SessionRow(rs.getObject("session_id", UUID.class), rs.getLong("usuario_erp_id"),
                rs.getInt("agente_erp_id"), rs.getObject("device_id", UUID.class), rs.getLong("generation"),
                rs.getString("state"), rs.getBoolean("bootstrap_started"), nullableLong(rs, "start_cursor"),
                nullableLong(rs, "erp_cursor"), nullableLong(rs, "max_cotizacion_id"), rs.getLong("snapshot_after_id"),
                rs.getBoolean("snapshot_source_complete"), rs.getBoolean("snapshot_complete"),
                rs.getLong("delivery_acked_sequence"), rs.getLong("last_offered_sequence"),
                rs.getLong("next_delivery_sequence"), rs.getTimestamp("offline_valid_until") == null ? null
                        : rs.getTimestamp("offline_valid_until").toInstant(), rs.getString("reason_code"),
                rs.getBoolean("queue_saturated"));
    }

    private static Long nullableLong(ResultSet rs, String name) throws SQLException {
        long value = rs.getLong(name); return rs.wasNull() ? null : value;
    }

    static Integer nullableInteger(ResultSet rs, String name) throws SQLException {
        Object value = rs.getObject(name);
        if (value == null) return null;
        if (!(value instanceof Number number)) throw new SQLException("Expected numeric value for " + name);
        long numeric = number.longValue();
        if (numeric < Integer.MIN_VALUE || numeric > Integer.MAX_VALUE)
            throw new SQLException("Value out of Integer range for " + name);
        return (int) numeric;
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static SnapshotDocument snapshotRow(ResultSet rs) throws SQLException {
        return new SnapshotDocument(rs.getObject("cotizacion_uuid", UUID.class), rs.getLong("erp_id"),
                rs.getString("estado"), rs.getLong("version"), nullableInteger(rs, "agente_id"),
                rs.getTimestamp("updated_at") == null ? null : rs.getTimestamp("updated_at").toInstant());
    }

    private static ErpEvent eventRow(ResultSet rs, int n) throws SQLException {
        return new ErpEvent(rs.getLong("event_id"), rs.getObject("cotizacion_uuid", UUID.class),
                rs.getLong("erp_id"), rs.getString("estado"), rs.getLong("version"),
                rs.getTimestamp("changed_at") == null ? null : rs.getTimestamp("changed_at").toInstant(),
                rs.getString("event_type"), nullableInteger(rs, "agente_id"), rs.getBoolean("visible"),
                rs.getString("payload_sha256"));
    }

    private static DeliveryRow deliveryRow(ResultSet rs, int n) throws SQLException {
        long event = rs.getLong("event_id"); Long eventId = rs.wasNull() ? null : event;
        long erp = rs.getLong("erp_id"); Long erpId = rs.wasNull() ? null : erp;
        long version = rs.getLong("version"); Long v = rs.wasNull() ? null : version;
        int agent = rs.getInt("agente_id"); Integer a = rs.wasNull() ? null : agent;
        Boolean visible = rs.getObject("visible", Boolean.class);
        return new DeliveryRow(rs.getLong("delivery_sequence"), rs.getString("item_type"), eventId,
                rs.getObject("cotizacion_uuid", UUID.class), erpId, rs.getString("estado"), v,
                rs.getTimestamp("changed_at") == null ? null : rs.getTimestamp("changed_at").toInstant(),
                rs.getString("event_type"), a, visible);
    }

    private record Projection(long erpId, long version, String estado, Integer agenteId, boolean visible) { }
    public record SessionAcquisition(SessionRow session, boolean created) { }
}
