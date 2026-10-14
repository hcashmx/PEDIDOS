package com.cyssoftware.effyFlow.service.cotizaciones;

import io.jsonwebtoken.Jwts;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Read-only ERP identity assertion. Deliberately separate from the CREAR_COTIZACION signer. */
@Service
public class CotizacionReadAssertionService {
    private final String privateKeyBase64;
    private final String issuer;
    private final String audience;
    private final String keyId;
    private final long validitySeconds;

    public CotizacionReadAssertionService(
            @Value("${app.cotizaciones.assertion.private-key-base64:}") String privateKeyBase64,
            @Value("${app.cotizaciones.assertion.issuer:effyflow-sync}") String issuer,
            @Value("${app.cotizaciones.assertion.audience:erp-cotizaciones}") String audience,
            @Value("${app.cotizaciones.assertion.key-id:}") String keyId,
            @Value("${app.cotizaciones.assertion.validity-seconds:60}") long validitySeconds) {
        this.privateKeyBase64 = clean(privateKeyBase64);
        this.issuer = clean(issuer);
        this.audience = clean(audience);
        this.keyId = clean(keyId);
        this.validitySeconds = validitySeconds;
    }

    public Assertion issue(CotizacionReadIdentityService.Actor actor, String deviceId) {
        if (actor == null || !isCanonicalUuid(deviceId))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Identidad de lectura inválida.");
        if (privateKeyBase64.isBlank() || issuer.isBlank() || audience.isBlank() || keyId.isBlank()
                || validitySeconds < 15 || validitySeconds > 300)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "La integración RS256 de lectura no está configurada.");

        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plusSeconds(validitySeconds);
        UUID jti = UUID.randomUUID();
        try {
            String token = Jwts.builder()
                    .issuer(issuer)
                    .subject(Long.toString(actor.usuarioErpId()))
                    .audience().add(audience).and()
                    .issuedAt(Date.from(issuedAt))
                    .expiration(Date.from(expiresAt))
                    .id(jti.toString())
                    .claim("operation", "LEER_ESTADOS_COTIZACIONES")
                    .claim("usuarioErpId", actor.usuarioErpId())
                    .claim("agenteErpId", actor.agenteErpId())
                    .claim("dispositivoId", deviceId)
                    .header().keyId(keyId).type("JWT").and()
                    .signWith(loadPrivateKey(), Jwts.SIG.RS256)
                    .compact();
            return new Assertion(token, jti, issuedAt, expiresAt);
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "No fue posible emitir la aserción RS256 de lectura.");
        }
    }

    static boolean isCanonicalUuid(String value) {
        if (value == null) return false;
        try { return UUID.fromString(value).toString().equalsIgnoreCase(value); }
        catch (IllegalArgumentException ex) { return false; }
    }

    public boolean configurationReady() {
        if (privateKeyBase64.isBlank() || issuer.isBlank() || audience.isBlank() || keyId.isBlank()
                || validitySeconds < 15 || validitySeconds > 300) return false;
        try { loadPrivateKey(); return true; }
        catch (Exception ex) { return false; }
    }

    private PrivateKey loadPrivateKey() throws Exception {
        byte[] encoded = Base64.getDecoder().decode(privateKeyBase64);
        PrivateKey key = KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(encoded));
        if (!(key instanceof RSAPrivateKey rsa) || rsa.getModulus().bitLength() < 2048)
            throw new IllegalArgumentException("RSA key is too small");
        return key;
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }

    public record Assertion(String token, UUID jti, Instant issuedAt, Instant expiresAt) { }
}
