package com.cyssoftware.effyFlow.service.cotizaciones;

import com.cyssoftware.effyFlow.repository.CotizacionSyncRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Deletes only acknowledged delivery history and completed bootstrap staging; tombstones remain durable. */
@Component
@ConditionalOnProperty(name = "sync.cotizaciones.read.enabled", havingValue = "true")
public class CotizacionSyncRetentionWorker {
    private static final Logger log = LoggerFactory.getLogger(CotizacionSyncRetentionWorker.class);
    private final CotizacionSyncRepository repository;
    private final int retentionDays;

    public CotizacionSyncRetentionWorker(CotizacionSyncRepository repository,
            @Value("${sync.cotizaciones.delivery.retention-days:30}") int retentionDays) {
        this.repository = repository;
        this.retentionDays = retentionDays;
    }

    @Scheduled(fixedDelayString = "${sync.cotizaciones.retention.interval-ms:86400000}", initialDelay = 60000)
    public void prune() {
        if (retentionDays < 1 || retentionDays > 3650) {
            log.error("Cotizaciones retention skipped: configured days outside 1..3650.");
            return;
        }
        int removed = repository.pruneAcknowledgedData(retentionDays, 1000);
        if (removed > 0) log.info("Cotizaciones acknowledged retention pruned {} rows.", removed);
    }
}
