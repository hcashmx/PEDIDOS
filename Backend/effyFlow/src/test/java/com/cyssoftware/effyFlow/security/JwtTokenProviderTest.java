package com.cyssoftware.effyFlow.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import static org.junit.jupiter.api.Assertions.*;

class JwtTokenProviderTest {
    static final String SECRET = "test-only-secret-0123456789-abcdef-0123456789";
    final JwtTokenProvider provider = new JwtTokenProvider(SECRET, 15, 7);
    final javax.crypto.SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    Claims claims(String token) { return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload(); }
    @Test void accessHasExpirationAndType() {
        String token = provider.generateAccessToken("user@test.invalid");
        var c = claims(token);
        assertEquals("access", c.get("token_type"));
        assertEquals(15 * 60_000L, c.getExpiration().getTime() - c.getIssuedAt().getTime());
        assertTrue(provider.validateAccessToken(token));
        assertFalse(provider.validateRefreshToken(token));
        assertEquals("HS256", Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getHeader().getAlgorithm());
    }
    @Test void refreshHasExpirationAndType() {
        String token = provider.generateRefreshToken("user@test.invalid");
        var c = claims(token);
        assertEquals("refresh", c.get("token_type"));
        assertEquals(7 * 86_400_000L, c.getExpiration().getTime() - c.getIssuedAt().getTime());
        assertTrue(provider.validateRefreshToken(token));
        assertFalse(provider.validateAccessToken(token));
    }
    @Test void rejectsExpiredWrongSignatureMalformedAndLegacyTokens() {
        assertFalse(provider.validateToken(Jwts.builder().subject("user").claim("token_type", "access")
                .expiration(new Date(1)).signWith(key, Jwts.SIG.HS256).compact()));
        var other = Keys.hmacShaKeyFor("other-test-secret-0123456789-abcdef".getBytes(StandardCharsets.UTF_8));
        assertFalse(provider.validateToken(Jwts.builder().subject("user").claim("token_type", "access")
                .expiration(new Date(System.currentTimeMillis()+60000)).signWith(other, Jwts.SIG.HS256).compact()));
        assertFalse(provider.validateToken("bad.token"));
        assertFalse(provider.validateToken(Jwts.builder().subject("user").signWith(key).compact()));
    }
    @Test void requiresSecretAndPositiveExpirations() {
        assertThrows(IllegalArgumentException.class, () -> new JwtTokenProvider("", 15, 7));
        assertThrows(IllegalArgumentException.class, () -> new JwtTokenProvider("short", 15, 7));
        assertThrows(IllegalArgumentException.class, () -> new JwtTokenProvider(SECRET, 0, 7));
    }
    @Test void supportsConfiguredExpirations() {
        var custom = new JwtTokenProvider(SECRET, 3, 2);
        var a = claims(custom.generateAccessToken("user"));
        var r = claims(custom.generateRefreshToken("user"));
        assertEquals(180000, a.getExpiration().getTime() - a.getIssuedAt().getTime());
        assertEquals(172800000, r.getExpiration().getTime() - r.getIssuedAt().getTime());
    }
}
