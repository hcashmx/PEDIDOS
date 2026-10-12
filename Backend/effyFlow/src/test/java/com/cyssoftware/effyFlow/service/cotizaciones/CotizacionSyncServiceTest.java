package com.cyssoftware.effyFlow.service.cotizaciones;

import com.cyssoftware.effyFlow.service.pedido.ErpCapturaException;
import com.cyssoftware.effyFlow.service.pedido.ErpPedidoClient;
import com.cyssoftware.effyFlow.service.pedido.SyncService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CotizacionSyncServiceTest {
    private final SyncService users = mock(SyncService.class);
    private final RevisionComercialSyncService revisions = mock(RevisionComercialSyncService.class);
    private final CotizacionIdentityAssertionService assertions = mock(CotizacionIdentityAssertionService.class);
    private final ErpPedidoClient erp = mock(ErpPedidoClient.class);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private CotizacionSyncService service;
    private UUID uuid, revision;
    private CotizacionCapturaRequest request;
    private RevisionComercialPaquete packageRevision;

    @BeforeEach void setup() {
        service = new CotizacionSyncService(users, revisions, assertions, erp, mapper);
        uuid = UUID.randomUUID(); revision = UUID.randomUUID();
        request = new CotizacionCapturaRequest(uuid.toString(), java.time.LocalDate.of(2026,10,11), revision.toString(),
                "a".repeat(64), "desk:one", 9, 7, CotizacionCapturaRequest.DocumentoSolicitado.FACTURA, "obs",
                List.of(new CotizacionCapturaRequest.Renglon(4, 2, new BigDecimal("2.5"), new BigDecimal("99.90"), new BigDecimal("3"))));
        packageRevision = new RevisionComercialPaquete(revision, 3, OffsetDateTime.parse("2026-10-10T10:00:00Z"),
                "pub-3", "a".repeat(64), mapper.createObjectNode().put("productos", 1));
        when(users.identidadCotizaciones("seller")).thenReturn(new SyncService.IdentidadComercial(11L, 9));
        when(revisions.porId(revision)).thenReturn(packageRevision);
        when(assertions.emitir(eq("seller"), any())).thenAnswer(inv -> {
            var req = inv.getArgument(1, CotizacionIdentityAssertionService.SolicitudAsercion.class);
            assertEquals(uuid.toString(), req.payload().path("uuid").asText());
            assertEquals(9, req.payload().path("oferta").path("agenteVentasId").asInt());
            assertEquals("FACTURA", req.payload().path("oferta").path("documentoSolicitado").asText());
            assertEquals(new BigDecimal("99.90"), req.payload().path("oferta").path("renglones").get(0).path("precioUnitario").decimalValue());
            return new CotizacionIdentityAssertionService.Asercion("signed", "Bearer", 11L, 9, "desk:one", uuid,
                    revision, "a".repeat(64), "b".repeat(64), UUID.randomUUID().toString(),
                    java.time.Instant.now(), java.time.Instant.now().plusSeconds(60));
        });
    }

    @Test void buildsCanonicalErpContractAndMapsAuthorizedCreation() throws Exception {
        when(erp.crearCotizacion(any(), eq("signed"))).thenAnswer(inv -> {
            var sent = inv.getArgument(0, com.fasterxml.jackson.databind.JsonNode.class);
            assertEquals("2026-10-11", sent.path("oferta").path("fecha").asText());
            assertEquals("2.5", sent.path("oferta").path("renglones").get(0).path("cantidad").asText());
            assertFalse(sent.toString().contains("revisionHuellaSha256"));
            return new ErpPedidoClient.ErpCotizacionResponse(201, mapper.readTree("""
                {"cabecera":{"id":42,"uuid":"%s","estado":"BORRADOR","version":0,"fecha":"2026-10-11","documentoSolicitado":"FACTURA","subtotal":250,"impuestos":40,"total":290},"renglones":[{"productoId":4,"cantidad":2.5,"precioUnitario":100,"total":290,"kitSnapshot":{"componentes":[]},"impuestosSnapshot":[]}]}
                """.formatted(uuid)));
        });
        var result = service.enviar("seller", request);
        assertFalse(result.recuperada());
        assertEquals("ACEPTADA", result.respuesta().get("resultado"));
        assertEquals(42L, result.respuesta().get("idCotizacion"));
        assertEquals("BORRADOR", result.respuesta().get("estado"));
        assertEquals("CREADA", result.respuesta().get("resultadoIdempotencia"));
        verify(erp).crearCotizacion(any(), eq("signed"));
    }

    @Test void http200IsReportedAsIdempotentRecovery() throws Exception {
        when(erp.crearCotizacion(any(), anyString())).thenReturn(new ErpPedidoClient.ErpCotizacionResponse(200,
                mapper.readTree("{\"cabecera\":{\"id\":42,\"uuid\":\"" + uuid + "\",\"estado\":\"BORRADOR\",\"version\":0,\"fecha\":\"2026-10-11\",\"documentoSolicitado\":\"FACTURA\",\"subtotal\":1,\"impuestos\":0,\"total\":1},\"renglones\":[{\"productoId\":4,\"cantidad\":1,\"precioUnitario\":1,\"total\":1}]}")));
        var result = service.enviar("seller", request);
        assertTrue(result.recuperada());
        assertEquals(true, result.respuesta().get("idempotente"));
    }

    @Test void mismatchedAgentAndRevisionFingerprintAreRejectedBeforeErp() {
        var foreign = new CotizacionCapturaRequest(request.uuid(), request.fechaComercial(), request.revisionComercialId(),
                request.revisionHuellaSha256(), request.dispositivoId(), 10, request.clienteId(), request.documentoSolicitado(),
                request.observaciones(), request.renglones());
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> service.enviar("seller", foreign));
        var changedRevision = new CotizacionCapturaRequest(request.uuid(), request.fechaComercial(), request.revisionComercialId(),
                "c".repeat(64), request.dispositivoId(), 9, request.clienteId(), request.documentoSolicitado(),
                request.observaciones(), request.renglones());
        assertEquals(409, assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.enviar("seller", changedRevision)).getStatusCode().value());
        verifyNoInteractions(erp);
    }

    @Test void permanentConflictAndAmbiguousServerErrorRemainClassified() {
        when(erp.crearCotizacion(any(), anyString())).thenThrow(new ErpCapturaException(409,
                mapper.createObjectNode().put("message", "uuid conflict")));
        var conflict = assertThrows(CotizacionSyncException.class, () -> service.enviar("seller", request));
        assertEquals(409, conflict.status());
        assertEquals("CONFLICTO_COMERCIAL_O_IDEMPOTENCIA", conflict.body().get("resultado"));
        assertEquals(false, conflict.body().get("reintentable"));
        reset(erp);
        when(erp.crearCotizacion(any(), anyString())).thenThrow(new ErpCapturaException(504, null));
        var timeout = assertThrows(CotizacionSyncException.class, () -> service.enviar("seller", request));
        assertEquals(504, timeout.status());
        assertEquals(true, timeout.body().get("reintentable"));
    }
}
