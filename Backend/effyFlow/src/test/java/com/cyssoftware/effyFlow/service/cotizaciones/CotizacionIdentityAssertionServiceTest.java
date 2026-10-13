package com.cyssoftware.effyFlow.service.cotizaciones;

import com.cyssoftware.effyFlow.service.pedido.SyncService;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CotizacionIdentityAssertionServiceTest {
    private final SyncService users = mock(SyncService.class);
    private final RevisionComercialSyncService revisions = mock(RevisionComercialSyncService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private KeyPair keys;
    private CotizacionIdentityAssertionService service;
    private UUID uuid;
    private UUID revision;
    private String device;

    @BeforeEach void setUp() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); keys = generator.generateKeyPair();
        service = new CotizacionIdentityAssertionService(users, revisions, mapper,
                Base64.getEncoder().encodeToString(keys.getPrivate().getEncoded()),
                "effyflow-sync", "erp-cotizaciones", "uat-key-1", 60);
        when(users.identidadCotizaciones("vendedor@example.test"))
                .thenReturn(new SyncService.IdentidadComercial(123L, 45));
        uuid = UUID.randomUUID(); revision = UUID.randomUUID(); device = "device-A";
        when(revisions.porId(revision)).thenReturn(new RevisionComercialPaquete(revision, 3,
                java.time.OffsetDateTime.parse("2026-10-10T12:00:00Z"), "sha256:" + "c".repeat(64),
                "c".repeat(64), mapper.createObjectNode().put("version_esquema", 3)));
    }

    @Test void firmaAsersionLigadaAlActorAgenteOperacionYContenido() throws Exception {
        var response = service.emitir("vendedor@example.test", request(uuid, revision, 45));
        var signed = Jwts.parser().verifyWith(keys.getPublic()).requireIssuer("effyflow-sync")
                .requireAudience("erp-cotizaciones").build().parseSignedClaims(response.token());
        var claims = signed.getPayload();
        assertEquals("RS256", signed.getHeader().getAlgorithm());
        assertEquals("uat-key-1", signed.getHeader().getKeyId());
        assertEquals("123", claims.getSubject());
        assertEquals("CREAR_COTIZACION", claims.get("operation"));
        assertEquals(45, ((Number) claims.get("agenteErpId")).intValue());
        assertEquals(uuid.toString(), claims.get("uuidCotizacion"));
        assertEquals(revision.toString(), claims.get("revisionComercialId"));
        assertEquals("c".repeat(64), claims.get("revisionHuellaSha256"));
        assertEquals(response.requestSha256(), claims.get("requestSha256"));
        assertEquals(response.jti(), claims.getId());
        assertTrue(response.expiresAt().isAfter(response.issuedAt()));
    }

    @Test void rechazaAgenteUuidORevisionQueNoCoincidenConElRequestFirmado() {
        assertEquals(403, assertThrows(ResponseStatusException.class,
                () -> service.emitir("vendedor@example.test", request(uuid, revision, 46))).getStatusCode().value());
        var payload = mapper.createObjectNode().put("uuid", UUID.randomUUID().toString())
                .put("revisionComercialId", revision.toString());
        payload.set("oferta", mapper.createObjectNode().put("agenteVentasId", 45));
        assertEquals(403, assertThrows(ResponseStatusException.class,
                () -> service.emitir("vendedor@example.test", request(uuid, revision, payload))).getStatusCode().value());
        UUID anotherRevision = UUID.randomUUID();
        var mismatched = mapper.createObjectNode().put("uuid", uuid.toString()).put("revisionComercialId", revision.toString());
        mismatched.set("oferta", mapper.createObjectNode().put("agenteVentasId", 45));
        assertEquals(403, assertThrows(ResponseStatusException.class,
                () -> service.emitir("vendedor@example.test", request(uuid, anotherRevision, mismatched))).getStatusCode().value());
        var arbitraryActor = mapper.createObjectNode().put("uuid", uuid.toString())
                .put("revisionComercialId", revision.toString());
        arbitraryActor.set("oferta", mapper.createObjectNode().put("agenteVentasId", 45).put("usuarioId", 999));
        assertEquals(403, assertThrows(ResponseStatusException.class,
                () -> service.emitir("vendedor@example.test", request(uuid, revision, arbitraryActor))).getStatusCode().value());
    }

    @Test void usuarioRevocadoNoPuedePedirNuevaAsercion() {
        when(users.identidadCotizaciones("vendedor@example.test")).thenReturn(null);
        assertEquals(403, assertThrows(ResponseStatusException.class,
                () -> service.emitir("vendedor@example.test", request(uuid, revision, 45))).getStatusCode().value());
    }

    @Test void reintentoConMismoContenidoConservaHashPeroRecibeJtiNuevo() throws Exception {
        var first = service.emitir("vendedor@example.test", request(uuid, revision, 45));
        var second = service.emitir("vendedor@example.test", request(uuid, revision, 45));
        assertEquals(first.requestSha256(), second.requestSha256());
        assertEquals(first.uuidCotizacion(), second.uuidCotizacion());
        assertNotEquals(first.jti(), second.jti());
    }

    @Test void vectoresCompartidosConErpProducenJsonYHashIdenticos() throws Exception {
        ObjectMapper exact = mapper.copy().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        try (var fixture = getClass().getResourceAsStream("/cotizaciones/rs256-canonical-vectors.json")) {
            assertNotNull(fixture);
            JsonNode vectors = mapper.readTree(fixture);
            for (JsonNode vector : vectors) {
                JsonNode payload = exact.readTree(vector.path("inputJson").asText());
                String canonical = service.canonicalJson(payload);
                assertEquals(vector.path("canonicalJson").asText(), canonical, vector.path("name").asText());
                String hash = java.util.HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
                assertEquals(vector.path("sha256").asText(), hash, vector.path("name").asText());
            }
        }
    }

    @Test void firmaElVectorUatConIdentidadYHashEsperados() throws Exception {
        JsonNode vector;
        try (var fixture = getClass().getResourceAsStream("/cotizaciones/rs256-canonical-vectors.json")) {
            vector = mapper.readTree(fixture).get(0);
        }
        JsonNode payload = mapper.copy().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .readTree(vector.path("inputJson").asText());
        UUID uatUuid = UUID.fromString(payload.path("uuid").asText());
        UUID uatRevision = UUID.fromString(payload.path("revisionComercialId").asText());
        when(users.identidadCotizaciones("vendedor@example.test"))
                .thenReturn(new SyncService.IdentidadComercial(28L, 73));
        when(revisions.porId(uatRevision)).thenReturn(new RevisionComercialPaquete(uatRevision, 3,
                java.time.OffsetDateTime.parse("2026-10-10T12:00:00Z"), "sha256:" + "c".repeat(64),
                "c".repeat(64), mapper.createObjectNode().put("version_esquema", 3)));
        var signed = service.emitir("vendedor@example.test",
                new CotizacionIdentityAssertionService.SolicitudAsercion(uatUuid.toString(),
                        uatRevision.toString(), device, payload));
        var claims = Jwts.parser().verifyWith(keys.getPublic()).build().parseSignedClaims(signed.token()).getPayload();
        assertEquals(28L, signed.usuarioErpId());
        assertEquals(73, signed.agenteErpId());
        assertEquals(vector.path("sha256").asText(), claims.get("requestSha256"));
        assertEquals(uatUuid.toString(), claims.get("uuidCotizacion"));
    }

    @Test void firmaAlteradaAudienciaEquivocadaYAsersionExpiradaFallanVerificacion() throws Exception {
        var response = service.emitir("vendedor@example.test", request(uuid, revision, 45));
        String token = response.token();
        String[] parts = token.split("\\.");
        char replacement = parts[2].charAt(0) == 'a' ? 'b' : 'a';
        String altered = parts[0] + "." + parts[1] + "." + replacement + parts[2].substring(1);
        assertThrows(RuntimeException.class, () -> Jwts.parser().verifyWith(keys.getPublic()).build().parseSignedClaims(altered));
        assertThrows(RuntimeException.class, () -> Jwts.parser().verifyWith(keys.getPublic())
                .requireAudience("audiencia-distinta").build().parseSignedClaims(token));
        assertThrows(RuntimeException.class, () -> Jwts.parser().verifyWith(keys.getPublic())
                .clock(() -> Date.from(Instant.now().plusSeconds(600))).build().parseSignedClaims(token));
    }

    private CotizacionIdentityAssertionService.SolicitudAsercion request(UUID id, UUID revisionId, int agent) {
        var payload = mapper.createObjectNode().put("uuid", id.toString())
                .put("revisionComercialId", revisionId.toString());
        payload.set("oferta", mapper.createObjectNode().put("agenteVentasId", agent).put("clienteId", 8));
        return request(id, revisionId, payload);
    }
    private CotizacionIdentityAssertionService.SolicitudAsercion request(UUID id, UUID revisionId,
            com.fasterxml.jackson.databind.JsonNode payload) {
        return new CotizacionIdentityAssertionService.SolicitudAsercion(id.toString(), revisionId.toString(), device, payload);
    }
}
