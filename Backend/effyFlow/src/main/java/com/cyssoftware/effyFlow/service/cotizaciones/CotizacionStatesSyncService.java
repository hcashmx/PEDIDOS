package com.cyssoftware.effyFlow.service.cotizaciones;

import com.cyssoftware.effyFlow.repository.CotizacionSyncRepository;
import com.cyssoftware.effyFlow.service.cotizaciones.CotizacionSyncRepositoryModels.DeliveryPage;
import com.cyssoftware.effyFlow.service.cotizaciones.CotizacionSyncRepositoryModels.DeliveryRow;
import com.cyssoftware.effyFlow.service.cotizaciones.CotizacionSyncRepositoryModels.ErpEvent;
import com.cyssoftware.effyFlow.service.cotizaciones.CotizacionSyncRepositoryModels.SessionRow;
import com.cyssoftware.effyFlow.service.cotizaciones.CotizacionSyncRepositoryModels.SnapshotDocument;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CotizacionStatesSyncService {
    private static final int ERP_LIMIT = 100;
    private static final long MIN_ESTIMATED_BYTES_FOR_ERP_PAGE = ERP_LIMIT * (256L + 80L * 4 * 2);
    private static final int MAX_SNAPSHOT_PAGES_PER_RUN = 1;
    private static final int MAX_EVENT_PAGES_PER_RUN = 1;

    private final CotizacionReadIdentityService identities;
    private final CotizacionReadAssertionService assertions;
    private final ErpCotizacionesReadClient erp;
    private final CotizacionSyncRepository repository;
    private final ObjectMapper mapper;
    private final int offlineHours;
    private final long workerLeaseSeconds;
    private final long maxPendingEntries;
    private final long maxQueueBytes;
    private final long resumePendingEntries;
    private final long resumeQueueBytes;
    private final Counter queueSaturationCounter;
    private final DistributionSummary queueEntriesSummary;
    private final DistributionSummary queueBytesSummary;

    public CotizacionStatesSyncService(CotizacionReadIdentityService identities,
            CotizacionReadAssertionService assertions,
            @Qualifier("erpCotizacionesReadClient") ErpCotizacionesReadClient erp,
            CotizacionSyncRepository repository, ObjectMapper mapper,
            @Value("${sync.cotizaciones.offline-authorization-hours:24}") int offlineHours,
            @Value("${sync.cotizaciones.worker.lease-seconds:60}") long workerLeaseSeconds,
            @Value("${sync.cotizaciones.queue.max-pending-events:0}") long maxPendingEntries,
            @Value("${sync.cotizaciones.queue.max-estimated-bytes:0}") long maxQueueBytes,
            @Value("${sync.cotizaciones.queue.resume-pending-events:0}") long resumePendingEntries,
            @Value("${sync.cotizaciones.queue.resume-estimated-bytes:0}") long resumeQueueBytes,
            MeterRegistry meterRegistry) {
        this.identities = identities;
        this.assertions = assertions;
        this.erp = erp;
        this.repository = repository;
        this.mapper = mapper;
        this.offlineHours = offlineHours;
        this.workerLeaseSeconds = workerLeaseSeconds;
        this.maxPendingEntries = maxPendingEntries;
        this.maxQueueBytes = maxQueueBytes;
        this.resumePendingEntries = resumePendingEntries;
        this.resumeQueueBytes = resumeQueueBytes;
        this.queueSaturationCounter = Counter.builder("sync.cotizaciones.queue.saturation_checks")
                .description("ERP ingestas pausadas por saturación de la cola durable")
                .register(meterRegistry);
        this.queueEntriesSummary = DistributionSummary.builder("sync.cotizaciones.queue.pending_entries")
                .description("Entregas pendientes y eventos aún no materializados por sesión")
                .register(meterRegistry);
        this.queueBytesSummary = DistributionSummary.builder("sync.cotizaciones.queue.estimated_bytes")
                .description("Tamaño estimado en tuplas PostgreSQL de la cola pendiente")
                .baseUnit("bytes").register(meterRegistry);
    }

    public OpenResult open(String email, String deviceValue) {
        requireReady();
        UUID deviceId = uuid(deviceValue, "dispositivoId");
        CotizacionReadIdentityService.Actor actor = identities.requireReadActor(email);
        var acquisition = repository.getOrCreateCurrent(actor.usuarioErpId(), actor.agenteErpId(), deviceId);
        SessionRow session = acquisition.session();
        boolean created = acquisition.created();
        runClaimed(session.sessionId(), UUID.randomUUID(), true, actor);
        session = repository.findById(session.sessionId());
        return new OpenResult(created ? 201 : 200, sessionResponse(session));
    }

    public Map<String, Object> snapshot(String email, UUID sessionId, String deviceHeader,
            long afterSequence, int limit) {
        checkLimit(limit);
        checkSequence(afterSequence);
        SessionRow session = boundSession(email, sessionId, deviceHeader);
        if (List.of("REVOCADA", "REQUIERE_REINICIO", "SESSION_EXPIRADA").contains(session.state())) throw stateError(session);
        requireReady();
        // A client delivery request performs an ERP authorization check and durably records that result first.
        refreshFromErp(session.sessionId(), true);
        session = boundSession(email, sessionId, deviceHeader);
        if (List.of("REVOCADA", "REQUIERE_REINICIO", "SESSION_EXPIRADA").contains(session.state()))
            throw stateError(session);
        if (!session.snapshotComplete()) throw new ResponseStatusException(HttpStatus.ACCEPTED,
                "El snapshot sigue inicializándose; consulte el estado de sesión.");
        DeliveryPage page = repository.offer(sessionId, "SNAPSHOT", afterSequence, limit);
        var items = page.rows().stream().map(CotizacionStatesSyncService::snapshotItem).toList();
        long from = page.rows().isEmpty() ? afterSequence : page.rows().getFirst().deliverySequence();
        long to = page.rows().isEmpty() ? afterSequence : page.rows().getLast().deliverySequence();
        boolean terminal = page.rows().stream().anyMatch(r -> "SNAPSHOT_END".equals(r.itemType()));
        return Map.of("sessionId", sessionId, "generation", session.generation(), "items", items,
                "fromSequence", from, "toSequence", to, "snapshotComplete", terminal,
                "hasMore", page.hasMore());
    }

    public Map<String, Object> events(String email, UUID sessionId, String deviceHeader,
            long afterSequence, int limit) {
        checkLimit(limit);
        checkSequence(afterSequence);
        SessionRow session = boundSession(email, sessionId, deviceHeader);
        if (List.of("REVOCADA", "REQUIERE_REINICIO", "SESSION_EXPIRADA").contains(session.state())) throw stateError(session);
        requireReady();
        refreshFromErp(sessionId, true);
        session = boundSession(email, sessionId, deviceHeader);
        if (List.of("REVOCADA", "REQUIERE_REINICIO", "SESSION_EXPIRADA").contains(session.state()))
            throw stateError(session);
        if (!"ACTIVA".equals(session.state()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El snapshot todavía no tiene ACK terminal.");
        DeliveryPage page = repository.offer(sessionId, "EVENT", afterSequence, limit);
        var events = page.rows().stream().map(CotizacionStatesSyncService::eventDto).toList();
        long from = page.rows().isEmpty() ? afterSequence : page.rows().getFirst().deliverySequence();
        long to = page.rows().isEmpty() ? afterSequence : page.rows().getLast().deliverySequence();
        return Map.of("sessionId", sessionId, "generation", session.generation(), "events", events,
                "fromSequence", from, "toSequence", to, "hasMore", page.hasMore());
    }

    public Map<String, Object> acknowledge(String email, UUID sessionId, String deviceHeader, long ackThrough) {
        boundSession(email, sessionId, deviceHeader);
        long ack = repository.acknowledge(sessionId, ackThrough);
        SessionRow current = repository.findById(sessionId);
        return Map.of("ackThroughSequence", ack, "state", current.state());
    }

    public Map<String, Object> state(String email, UUID sessionId, String deviceHeader) {
        SessionRow session = boundSession(email, sessionId, deviceHeader);
        long available = session.nextDeliverySequence() - 1;
        boolean leaseValid = session.offlineValidUntil() != null && session.offlineValidUntil().isAfter(Instant.now());
        var result = new LinkedHashMap<String, Object>();
        result.put("sessionId", session.sessionId());
        result.put("generation", session.generation());
        result.put("state", externalState(session.state()));
        result.put("snapshotComplete", session.snapshotComplete());
        result.put("lastAckedSequence", session.deliveryAckedSequence());
        result.put("lastAvailableSequence", available);
        var queue = repository.queueUsage(sessionId, maxPendingEntries, maxQueueBytes,
                resumePendingEntries, resumeQueueBytes);
        recordQueueUsage(queue);
        result.put("pendingQueueEntries", queue.pendingEntries());
        result.put("pendingQueueEstimatedBytes", queue.estimatedBytes());
        result.put("ingestionSuspended", queue.saturated());
        result.put("capacityCode", queue.saturated() ? "COTIZACIONES_COLA_SATURADA" : "");
        result.put("offlineAuthorization", Map.of("valid", leaseValid,
                "validUntil", session.offlineValidUntil() == null ? "" : session.offlineValidUntil().toString()));
        result.put("reasonCode", session.reasonCode() == null ? "" : session.reasonCode());
        return result;
    }

    public Map<String, Object> revalidate(String email, UUID sessionId, String deviceHeader) {
        boundSession(email, sessionId, deviceHeader);
        requireReady();
        refreshFromErp(sessionId, true);
        SessionRow session = repository.findById(sessionId);
        Instant validUntil = session.offlineValidUntil();
        return Map.of("authorized", true, "validUntil", validUntil.toString(), "state", session.state(),
                "lastAvailableSequence", session.nextDeliverySequence() - 1);
    }

    public Map<String, Object> reset(String email, UUID sessionId, String deviceValue, long expectedGeneration) {
        requireReady();
        UUID deviceId = uuid(deviceValue, "dispositivoId");
        CotizacionReadIdentityService.Actor actor = identities.requireReadActor(email);
        SessionRow old = repository.findById(sessionId);
        requireBoundActorAndDevice(old, actor, deviceId);
        if (old.generation() != expectedGeneration)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La generación de sesión cambió.");
        repository.closeForReset(sessionId);
        String oldState = repository.findById(sessionId).state();
        SessionRow fresh = repository.createSession(actor.usuarioErpId(), actor.agenteErpId(), deviceId);
        runClaimed(fresh.sessionId(), UUID.randomUUID(), true, actor);
        fresh = repository.findById(fresh.sessionId());
        var response = new LinkedHashMap<>(sessionResponse(fresh));
        response.put("oldSessionState", oldState);
        response.put("purgeRequired", true);
        return response;
    }

    /** Called by the scheduled worker; does not renew a device's offline lease. */
    public void runWorker(UUID sessionId) {
        requireReady();
        UUID owner = UUID.randomUUID();
        if (!repository.claimWorker(sessionId, owner, Instant.now().plusSeconds(workerLeaseSeconds))) return;
        try {
            SessionRow session = repository.findById(sessionId);
            CotizacionReadIdentityService.Actor actor = identities.requireReadActor(session.usuarioErpId());
            if (actor.agenteErpId() != session.agenteErpId()) {
                repository.revoke(sessionId, "IDENTIDAD_CAMBIADA");
                return;
            }
            progress(session, owner, actor, false);
            repository.clearWorkerBackoff(sessionId);
        } catch (ErpCotizacionesReadClient.ErpReadException ex) {
            handleErpFailure(sessionId, ex);
            if (ex.retryable() || ex.failure() == ErpCotizacionesReadClient.Failure.TECHNICAL_AUTHENTICATION)
                repository.deferWorker(sessionId);
        } catch (ResponseStatusException ex) {
            if (ex.getStatusCode().value() == 403) repository.revoke(sessionId, "IDENTIDAD_NO_AUTORIZADA");
            else if (ex.getStatusCode().value() == 502 || ex.getStatusCode().value() == 503
                    || ex.getStatusCode().value() == 504) repository.deferWorker(sessionId);
            else if (ex.getStatusCode().value() != 409) repository.requireRestart(sessionId, "ERROR_DE_PROCESAMIENTO");
        } finally {
            repository.releaseWorker(sessionId, owner);
        }
    }

    private boolean runClaimed(UUID sessionId, UUID owner, boolean renewLease,
            CotizacionReadIdentityService.Actor actor) {
        if (!repository.claimWorker(sessionId, owner, Instant.now().plusSeconds(workerLeaseSeconds))) return false;
        try {
            progress(repository.findById(sessionId), owner, actor, renewLease);
            repository.clearWorkerBackoff(sessionId);
            return true;
        }
        catch (ErpCotizacionesReadClient.ErpReadException ex) {
            handleErpFailure(sessionId, ex);
            if (ex.retryable() || ex.failure() == ErpCotizacionesReadClient.Failure.TECHNICAL_AUTHENTICATION)
                repository.deferWorker(sessionId);
            throw translate(ex);
        }
        catch (RuntimeException ex) {
            if (ex instanceof ResponseStatusException status && status.getStatusCode().value() == 403)
                repository.revoke(sessionId, "IDENTIDAD_NO_AUTORIZADA");
            else if (ex instanceof ResponseStatusException status
                    && (status.getStatusCode().value() == 502 || status.getStatusCode().value() == 503
                    || status.getStatusCode().value() == 504)) repository.deferWorker(sessionId);
            else if (ex instanceof ResponseStatusException status && status.getStatusCode().value() == 410)
                repository.requireRestart(sessionId, "ERROR_DE_INTEGRIDAD");
            throw ex;
        } finally { repository.releaseWorker(sessionId, owner); }
    }

    private void progress(SessionRow session, UUID owner, CotizacionReadIdentityService.Actor actor, boolean renewLease) {
        final UUID sessionId = session.sessionId();
        final UUID deviceId = session.deviceId();
        if ("BOOTSTRAP_PENDIENTE".equals(session.state())) {
            if (session.bootstrapStarted()) {
                repository.requireRestart(session.sessionId(), "BOOTSTRAP_AMBIGUO");
                return;
            }
            if (!repository.claimBootstrap(session.sessionId())) return;
            try {
                JsonNode response = erp.bootstrap(() -> assertions.issue(actor, deviceId.toString()));
                long cursor = nonNegativeLong(response, "startCursor");
                long maxId = nonNegativeLong(response, "maxCotizacionId");
                repository.saveBootstrap(session.sessionId(), owner, cursor, maxId);
            } catch (ErpCotizacionesReadClient.ErpReadException ex) {
                handleErpFailure(session.sessionId(), ex);
                throw ex;
            } catch (RuntimeException invalid) {
                repository.requireRestart(session.sessionId(), "BOOTSTRAP_PROTOCOLO_INVALIDO");
                throw invalid;
            }
            session = repository.findById(session.sessionId());
        }

        for (int i = 0; i < MAX_SNAPSHOT_PAGES_PER_RUN && "INICIALIZANDO".equals(session.state())
                && !session.snapshotSourceComplete(); i++) {
            long after = session.snapshotAfterId();
            try {
                JsonNode page = erp.snapshot(after, session.maxCotizacionId(), ERP_LIMIT,
                        () -> assertions.issue(actor, deviceId.toString()));
                List<SnapshotDocument> docs = parseSnapshot(page.path("documents"));
                long next = nonNegativeLong(page, "nextAfterId");
                boolean hasMore = requiredBoolean(page, "hasMore");
                if (next < after || (hasMore && next == after)
                        || (!docs.isEmpty() && next != docs.getLast().erpId())) {
                    repository.requireRestart(sessionId, "SNAPSHOT_CURSOR_INVALIDO");
                    throw protocol("Respuesta snapshot sin progreso o fuera de orden.");
                }
                repository.saveSnapshotPage(sessionId, owner, after, next, hasMore, docs);
            } catch (ErpCotizacionesReadClient.ErpReadException ex) {
                handleErpFailure(sessionId, ex);
                throw ex;
            } catch (ResponseStatusException ex) {
                if (ex.getStatusCode().value() == 502 || ex.getStatusCode().value() == 410)
                    repository.requireRestart(sessionId, "SNAPSHOT_PROTOCOLO_INVALIDO");
                throw ex;
            }
            session = repository.findById(session.sessionId());
        }
        session = repository.findById(session.sessionId());
        if (session.snapshotSourceComplete() && !session.snapshotComplete()) {
            repository.materializeSnapshot(session.sessionId(), owner);
            session = repository.findById(session.sessionId());
        }
        if (session.erpCursor() != null)
            drainEvents(session.sessionId(), owner, actor, renewLease ? leaseUntil() : null);
    }

    private void refreshFromErp(UUID sessionId, boolean renewLease) {
        SessionRow session = repository.findById(sessionId);
        CotizacionReadIdentityService.Actor actor = identities.requireReadActor(session.usuarioErpId());
        if (actor.agenteErpId() != session.agenteErpId()) {
            repository.revoke(sessionId, "IDENTIDAD_CAMBIADA");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "La identidad comercial cambió.");
        }
        if (!runClaimed(sessionId, UUID.randomUUID(), renewLease, actor))
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "La sesiÃ³n estÃ¡ siendo atendida; reintente para revalidar autorizaciÃ³n ERP.");
    }

    private void drainEvents(UUID sessionId, UUID owner, CotizacionReadIdentityService.Actor actor, Instant leaseUntil) {
        for (int i = 0; i < MAX_EVENT_PAGES_PER_RUN; i++) {
            SessionRow session = repository.findById(sessionId);
            if (session.erpCursor() == null || !List.of("INICIALIZANDO", "ACTIVA").contains(session.state())) return;
            var usage = repository.queueUsage(sessionId, maxPendingEntries, maxQueueBytes,
                    resumePendingEntries, resumeQueueBytes);
            recordQueueUsage(usage);
            if (usage.saturated()) {
                queueSaturationCounter.increment();
                return;
            }
            long cursor = session.erpCursor();
            try {
                JsonNode response = erp.events(cursor, ERP_LIMIT,
                        () -> assertions.issue(actor, session.deviceId().toString()));
                List<ErpEvent> events = parseEvents(response.path("events"));
                long next = nonNegativeLong(response, "nextCursor");
                boolean hasMore = requiredBoolean(response, "hasMore");
                boolean persisted = repository.ingestEventPage(sessionId, owner, cursor, next, hasMore, events,
                        leaseUntil == null ? Instant.now() : leaseUntil, leaseUntil != null,
                        maxPendingEntries, maxQueueBytes);
                if (!persisted) {
                    queueSaturationCounter.increment();
                    return;
                }
                if (!hasMore) return;
            } catch (ErpCotizacionesReadClient.ErpReadException ex) {
                handleErpFailure(sessionId, ex);
                throw ex;
            } catch (ResponseStatusException ex) {
                if (ex.getStatusCode().value() == 502 || ex.getStatusCode().value() == 410)
                    repository.requireRestart(sessionId, "EVENTOS_PROTOCOLO_INVALIDO");
                throw ex;
            }
        }
    }

    private void handleErpFailure(UUID id, ErpCotizacionesReadClient.ErpReadException ex) {
        if (ex.failure() == ErpCotizacionesReadClient.Failure.COMMERCIAL_AUTHORIZATION) {
            repository.revoke(id, "ACCESS_DENIED");
        } else if (ex.failure() == ErpCotizacionesReadClient.Failure.ASSERTION_OR_TECHNICAL
                || ex.failure() == ErpCotizacionesReadClient.Failure.ASSERTION) {
            repository.requireRestart(id, "ERP_ASSERTION_REJECTED");
        }
    }

    private ResponseStatusException translate(ErpCotizacionesReadClient.ErpReadException ex) {
        int status = ex.failure() == ErpCotizacionesReadClient.Failure.COMMERCIAL_AUTHORIZATION ? 403
                : ex.failure() == ErpCotizacionesReadClient.Failure.CONFIGURATION
                    || ex.failure() == ErpCotizacionesReadClient.Failure.DISABLED
                    || ex.failure() == ErpCotizacionesReadClient.Failure.TECHNICAL_AUTHENTICATION ? 503
                : ex.failure() == ErpCotizacionesReadClient.Failure.PROTOCOL ? 502
                : ex.retryable() ? 503 : 502;
        String code = ex.failure() == ErpCotizacionesReadClient.Failure.TECHNICAL_AUTHENTICATION
                ? "ERP_TECHNICAL_AUTH_FAILED"
                : status == 503 ? "ERP_UNAVAILABLE" : status == 502 ? "ERP_PROTOCOL_ERROR"
                : "ERP_ESTADOS_ERROR";
        return new ResponseStatusException(HttpStatus.valueOf(status), code);
    }

    private SessionRow boundSession(String email, UUID id, String deviceHeader) {
        CotizacionReadIdentityService.Actor actor = identities.requireReadActor(email);
        UUID device = uuid(deviceHeader, "X-Sync-Device-Id");
        SessionRow session = repository.findById(id);
        if (!session.deviceId().equals(device) || session.usuarioErpId() != actor.usuarioErpId()
                || session.agenteErpId() != actor.agenteErpId())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Sesión no encontrada.");
        repository.touchClientActivity(id);
        return session;
    }

    private void requireBoundActorAndDevice(SessionRow session, CotizacionReadIdentityService.Actor actor, UUID device) {
        if (session.usuarioErpId() != actor.usuarioErpId() || session.agenteErpId() != actor.agenteErpId()
                || !session.deviceId().equals(device))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Sesión no encontrada.");
    }

    private Map<String, Object> sessionResponse(SessionRow session) {
        var response = new LinkedHashMap<String, Object>();
        response.put("sessionId", session.sessionId());
        response.put("generation", session.generation());
        response.put("state", externalState(session.state()));
        response.put("snapshot", Map.of("nextDeliverySequence", session.deliveryAckedSequence() + 1,
                "hasMore", !session.snapshotComplete()));
        response.put("lastAckedSequence", session.deliveryAckedSequence());
        response.put("offlineAuthorization", Map.of("valid", session.offlineValidUntil() != null
                        && session.offlineValidUntil().isAfter(Instant.now()),
                "validUntil", session.offlineValidUntil() == null ? "" : session.offlineValidUntil().toString()));
        return response;
    }

    public record OpenResult(int httpStatus, Map<String, Object> body) { }

    private static Map<String, Object> snapshotItem(DeliveryRow row) {
        if ("SNAPSHOT_END".equals(row.itemType()))
            return Map.of("deliverySequence", row.deliverySequence(), "type", "SNAPSHOT_END");
        var document = new LinkedHashMap<String, Object>();
        document.put("erpId", row.erpId()); document.put("cotizacionUuid", row.cotizacionUuid());
        document.put("estado", row.estado()); document.put("version", row.version());
        document.put("agenteId", row.agenteId());
        document.put("updatedAt", row.changedAt() == null ? null : row.changedAt().toString());
        var item = new LinkedHashMap<String, Object>(); item.put("deliverySequence", row.deliverySequence());
        item.put("document", document); return item;
    }

    private static Map<String, Object> eventDto(DeliveryRow row) {
        var event = new LinkedHashMap<String, Object>();
        event.put("deliverySequence", row.deliverySequence()); event.put("eventId", row.eventId());
        event.put("cotizacionUuid", row.cotizacionUuid()); event.put("erpId", row.erpId());
        event.put("estado", row.estado()); event.put("version", row.version());
        event.put("changedAt", row.changedAt() == null ? null : row.changedAt().toString());
        event.put("eventType", row.eventType()); event.put("agenteId", row.agenteId());
        event.put("visible", row.visible()); return event;
    }

    private List<SnapshotDocument> parseSnapshot(JsonNode documents) {
        if (!documents.isArray()) throw protocol("ERP no devolvió documents como arreglo.");
        List<SnapshotDocument> result = new ArrayList<>();
        java.util.HashSet<UUID> seenUuids = new java.util.HashSet<>();
        for (JsonNode doc : documents) {
            UUID uuid = uuid(requiredText(doc, "cotizacionUuid"), "cotizacionUuid");
            long erpId = positiveLong(doc, "erpId");
            String state = requiredText(doc, "estado");
            long version = nonNegativeLong(doc, "version");
            Integer agent = nullableInt(doc, "agenteId");
            Instant updated = parseInstant(requiredText(doc, "updatedAt"));
            if (!seenUuids.add(uuid)) throw protocol("Snapshot repitiÃ³ una cotizacionUuid.");
            result.add(new SnapshotDocument(uuid, erpId, state, version, agent, updated));
        }
        for (int i = 1; i < result.size(); i++)
            if (result.get(i).erpId() <= result.get(i - 1).erpId()) throw protocol("Snapshot no está en orden ascendente.");
        return List.copyOf(result);
    }

    private List<ErpEvent> parseEvents(JsonNode events) {
        if (!events.isArray()) throw protocol("ERP no devolvió events como arreglo.");
        List<ErpEvent> result = new ArrayList<>();
        for (JsonNode event : events) {
            long eventId = positiveLong(event, "eventId");
            UUID uuid = uuid(requiredText(event, "cotizacionUuid"), "cotizacionUuid");
            long erpId = positiveLong(event, "erpId");
            long version = nonNegativeLong(event, "version");
            boolean visible = requiredBoolean(event, "visible");
            String state = nullableText(event, "estado");
            Integer agent = nullableInt(event, "agenteId");
            if (!visible && (state != null || agent != null))
                throw protocol("ERP incluyó estado o agente en una revocación visible=false.");
            if (visible && (state == null || state.isBlank())) throw protocol("Evento visible sin estado.");
            Instant changedAt = parseInstant(requiredText(event, "changedAt"));
            String eventType = requiredText(event, "eventType");
            result.add(new ErpEvent(eventId, uuid, erpId, state, version, changedAt, eventType, agent, visible,
                    eventHash(eventId, uuid, erpId, state, version, changedAt, eventType, agent, visible)));
        }
        for (int i = 1; i < result.size(); i++)
            if (result.get(i).eventId() <= result.get(i - 1).eventId()) throw protocol("Eventos no están en orden ascendente.");
        return List.copyOf(result);
    }

    private String eventHash(long id, UUID uuid, long erpId, String state, long version, Instant changed,
            String type, Integer agent, boolean visible) {
        String canonical = id + "\n" + uuid + "\n" + erpId + "\n" + state + "\n" + version + "\n"
                + changed + "\n" + type + "\n" + agent + "\n" + visible;
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new IllegalStateException("SHA-256 unavailable", ex); }
    }

    private static Instant parseInstant(String value) {
        try { return Instant.parse(value); }
        catch (RuntimeException ex) {
            try { return OffsetDateTime.parse(value).toInstant(); }
            catch (RuntimeException invalid) { throw protocol("Fecha ERP inválida."); }
        }
    }

    private static String requiredText(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isTextual() || value.asText().isBlank()) throw protocol("Campo ERP inválido: " + name);
        return value.asText();
    }

    private static String nullableText(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw protocol("Campo ERP inválido: " + name);
        return value.asText();
    }

    private static Integer nullableInt(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || value.isNull()) return null;
        if (!value.canConvertToInt()) throw protocol("Campo ERP inválido: " + name);
        return value.asInt();
    }

    private static long positiveLong(JsonNode node, String name) {
        long value = nonNegativeLong(node, name);
        if (value == 0) throw protocol("Campo ERP debe ser positivo: " + name);
        return value;
    }

    private static long nonNegativeLong(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.canConvertToLong() || value.asLong() < 0)
            throw protocol("Campo ERP inválido: " + name);
        return value.asLong();
    }

    private static boolean requiredBoolean(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isBoolean()) throw protocol("Campo ERP inválido: " + name);
        return value.asBoolean();
    }

    private UUID uuid(String value, String field) {
        if (!CotizacionReadAssertionService.isCanonicalUuid(value))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " debe ser UUID canónico.");
        return UUID.fromString(value);
    }

    private void checkLimit(int limit) {
        if (limit < 1 || limit > 100)
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "limit debe estar entre 1 y 100.");
    }

    private static void checkSequence(long sequence) {
        if (sequence < 0)
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "afterSequence debe ser no negativo.");
    }

    private void requireReady() {
        if (!erp.configurationReady() || !assertions.configurationReady())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "La lectura de estados de Cotizaciones no está habilitada o su configuración está incompleta.");
        if (offlineHours < 1 || offlineHours > 24)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "La vigencia offline configurada debe estar entre 1 y 24 horas.");
        if (maxPendingEntries < ERP_LIMIT || maxQueueBytes < MIN_ESTIMATED_BYTES_FOR_ERP_PAGE
                || resumePendingEntries < 0 || resumeQueueBytes < 0
                || resumePendingEntries >= maxPendingEntries || resumeQueueBytes >= maxQueueBytes)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "La política de capacidad de Cotizaciones no está configurada de forma segura.");
    }

    private void recordQueueUsage(CotizacionSyncRepositoryModels.QueueUsage usage) {
        queueEntriesSummary.record(usage.pendingEntries());
        queueBytesSummary.record(usage.estimatedBytes());
    }

    private Instant leaseUntil() { return Instant.now().plusSeconds(offlineHours * 3600L); }

    private static String externalState(String state) {
        return "BOOTSTRAP_PENDIENTE".equals(state) ? "INICIALIZANDO" : state;
    }

    private ResponseStatusException stateError(SessionRow session) {
        int status = "REVOCADA".equals(session.state()) ? 403 : 410;
        String code = "SESSION_EXPIRADA".equals(session.state()) ? "SESSION_EXPIRADA"
                : "REVOCADA".equals(session.state()) ? "SESSION_REVOCADA"
                : "SESSION_REQUIERE_REINICIO";
        return new ResponseStatusException(HttpStatus.valueOf(status), code);
    }

    private static ResponseStatusException protocol(String message) {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, message);
    }
}
