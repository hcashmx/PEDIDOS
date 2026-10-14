package com.cyssoftware.effyFlow.service.cotizaciones;

import java.time.Instant;
import java.util.UUID;

public final class CotizacionSyncRepositoryModels {
    private CotizacionSyncRepositoryModels() { }

    public record SessionRow(UUID sessionId, long usuarioErpId, int agenteErpId, UUID deviceId,
            long generation, String state, boolean bootstrapStarted, Long startCursor, Long erpCursor,
            Long maxCotizacionId, long snapshotAfterId, boolean snapshotSourceComplete,
            boolean snapshotComplete, long deliveryAckedSequence, long lastOfferedSequence,
            long nextDeliverySequence, Instant offlineValidUntil, String reasonCode,
            boolean queueSaturated) { }

    public record SnapshotDocument(UUID cotizacionUuid, long erpId, String estado, long version,
            Integer agenteId, Instant updatedAt) { }

    public record ErpEvent(long eventId, UUID cotizacionUuid, long erpId, String estado, long version,
            Instant changedAt, String eventType, Integer agenteId, boolean visible, String payloadSha256) { }

    public record DeliveryRow(long deliverySequence, String itemType, Long eventId, UUID cotizacionUuid,
            Long erpId, String estado, Long version, Instant changedAt, String eventType,
            Integer agenteId, Boolean visible) { }

    public record DeliveryPage(java.util.List<DeliveryRow> rows, boolean hasMore) { }

    public record QueueUsage(long pendingEntries, long estimatedBytes, boolean saturated) { }
}
