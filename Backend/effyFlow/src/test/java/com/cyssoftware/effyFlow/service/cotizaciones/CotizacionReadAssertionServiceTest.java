package com.cyssoftware.effyFlow.service.cotizaciones;

import io.jsonwebtoken.Jwts;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CotizacionReadAssertionServiceTest {
    private KeyPair keys;
    private CotizacionReadAssertionService service;
    private final UUID device = UUID.randomUUID();

    @BeforeEach void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keys = generator.generateKeyPair();
        service = new CotizacionReadAssertionService(Base64.getEncoder().encodeToString(keys.getPrivate().getEncoded()),
                "effyflow-sync", "erp-cotizaciones", "read-kid-1", 60);
    }

    @Test void signsReadOnlyIdentityWithFreshCanonicalJtiAndKid() {
        var actor = new CotizacionReadIdentityService.Actor(54L, 12);
        var first = service.issue(actor, device.toString());
        var second = service.issue(actor, device.toString());
        var parsed = Jwts.parser().verifyWith(keys.getPublic()).requireIssuer("effyflow-sync")
                .requireAudience("erp-cotizaciones").build().parseSignedClaims(first.token());

        assertEquals("RS256", parsed.getHeader().getAlgorithm());
        assertEquals("read-kid-1", parsed.getHeader().getKeyId());
        assertEquals("54", parsed.getPayload().getSubject());
        assertEquals("LEER_ESTADOS_COTIZACIONES", parsed.getPayload().get("operation"));
        assertEquals(54L, ((Number) parsed.getPayload().get("usuarioErpId")).longValue());
        assertEquals(12, ((Number) parsed.getPayload().get("agenteErpId")).intValue());
        assertEquals(device.toString(), parsed.getPayload().get("dispositivoId"));
        assertEquals(first.jti().toString(), parsed.getPayload().getId());
        assertNotEquals(first.jti(), second.jti());
        assertTrue(first.expiresAt().isAfter(first.issuedAt()));
        assertFalse(parsed.getPayload().containsKey("uuidCotizacion"));
        assertFalse(parsed.getPayload().containsKey("requestSha256"));
        assertFalse(parsed.getPayload().containsKey("revisionComercialId"));
    }

    @Test void rejectsInvalidDeviceAndReadAssertionLifetimeAboveErpMaximum() {
        var actor = new CotizacionReadIdentityService.Actor(54L, 12);
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.issue(actor, "desktop-" + device));
        var tooLong = new CotizacionReadAssertionService(Base64.getEncoder().encodeToString(keys.getPrivate().getEncoded()),
                "effyflow-sync", "erp-cotizaciones", "read-kid-1", 301);
        assertEquals(503, assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> tooLong.issue(actor, device.toString())).getStatusCode().value());
    }
}
