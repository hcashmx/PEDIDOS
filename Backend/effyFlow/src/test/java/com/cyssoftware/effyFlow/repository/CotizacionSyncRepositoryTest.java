package com.cyssoftware.effyFlow.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import com.cyssoftware.effyFlow.service.cotizaciones.CotizacionSyncRepositoryModels.QueueUsage;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CotizacionSyncRepositoryTest {
    @Test void mapsPostgresBigintAgentIdWithoutIntegerCastFailure() throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getObject("agente_id")).thenReturn(47L);

        assertEquals(47, CotizacionSyncRepository.nullableInteger(row, "agente_id"));
    }

    @Test void preservesNullAgentAndRejectsIdsOutsideDtoRange() throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getObject("agente_id")).thenReturn(null, 2_147_483_648L);

        assertNull(CotizacionSyncRepository.nullableInteger(row, "agente_id"));
        assertThrows(SQLException.class, () -> CotizacionSyncRepository.nullableInteger(row, "agente_id"));
    }

    @Test void replayAfterInboxCompactionClassifiesTheRetainedHashAsANoOp() {
        String retainedLedgerHash = "ab".repeat(32);

        assertTrue(CotizacionSyncRepository.isCompatibleLedgerReplay(retainedLedgerHash, retainedLedgerHash));
        assertFalse(CotizacionSyncRepository.isCompatibleLedgerReplay(null, retainedLedgerHash));
        assertThrows(ResponseStatusException.class, () ->
                CotizacionSyncRepository.isCompatibleLedgerReplay(retainedLedgerHash, "cd".repeat(32)));
    }

    @Test void capacitySuspendsAtLimitAndResumesOnlyBelowBothLowWaterMarks() {
        QueueUsage full = new QueueUsage(100, 90_000, false);
        QueueUsage partlyDrained = new QueueUsage(20, 19_000, false);
        QueueUsage drained = new QueueUsage(10, 9_000, false);

        assertTrue(CotizacionSyncRepository.shouldSuspendIngestion(false, full, 100, 90_000, 10, 9_000));
        assertTrue(CotizacionSyncRepository.shouldSuspendIngestion(true, partlyDrained, 100, 90_000, 10, 9_000));
        assertFalse(CotizacionSyncRepository.shouldSuspendIngestion(true, drained, 100, 90_000, 10, 9_000));
        assertFalse(CotizacionSyncRepository.shouldSuspendIngestion(false,
                new QueueUsage(0, 0, false), 100, 90_000, 10, 9_000));
    }
}
