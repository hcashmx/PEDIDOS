package com.cyssoftware.effyFlow.service.cotizaciones;

import com.cyssoftware.effyFlow.repository.CotizacionSyncRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Multi-instance safe poller. It logs only a session correlation ID, never credentials or ERP bodies. */
@Component
@ConditionalOnProperty(name = "sync.cotizaciones.read.enabled", havingValue = "true")
public class CotizacionStatesSyncWorker {
    private static final Logger log = LoggerFactory.getLogger(CotizacionStatesSyncWorker.class);
    private final CotizacionSyncRepository repository;
    private final CotizacionStatesSyncService service;

    public CotizacionStatesSyncWorker(CotizacionSyncRepository repository, CotizacionStatesSyncService service) {
        this.repository = repository;
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${sync.cotizaciones.worker.interval-ms:5000}")
    public void poll() {
        for (UUID sessionId : repository.workerCandidates(50)) {
            try { service.runWorker(sessionId); }
            catch (RuntimeException failure) {
                log.warn("Cotizaciones Sync worker deferred session {} ({}).", sessionId,
                        failure.getClass().getSimpleName());
            }
        }
    }
}
