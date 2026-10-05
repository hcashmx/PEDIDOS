package com.cyssoftware.effyFlow.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Duration;
import java.util.Date;

@Component
public class JwtTokenProvider {
    private final SecretKey key;
    private final Duration accessValidity;
    private final Duration refreshValidity;
    public JwtTokenProvider(@Value("${app.jwt.secret:}") String secret,
            @Value("${app.jwt.access-expiration-minutes:15}") long accessMinutes,
            @Value("${app.jwt.refresh-expiration-days:7}") long refreshDays) {
        if (secret == null || secret.isBlank() || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("APP_JWT_SECRET es obligatorio y debe contener al menos 32 bytes UTF-8");
        }
        if (accessMinutes < 1 || refreshDays < 1) {
            throw new IllegalArgumentException("Las expiraciones JWT deben ser positivas");
        }
        key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        accessValidity = Duration.ofMinutes(accessMinutes);
        refreshValidity = Duration.ofDays(refreshDays);
    }
    public String generateAccessToken(String email) { return generate(email, "access", accessValidity); }
    public String generateRefreshToken(String email) { return generate(email, "refresh", refreshValidity); }
    private String generate(String email, String type, Duration validity) {
        Instant now = Instant.now();
        return Jwts.builder().subject(email).claim("token_type", type)
                .issuedAt(Date.from(now)).expiration(Date.from(now.plus(validity)))
                .signWith(key, Jwts.SIG.HS256).compact();
    }
    public boolean validateToken(String token) { return validateAccessToken(token); }
    public boolean validateAccessToken(String token) { return validType(token, "access"); }
    public boolean validateRefreshToken(String token) { return validType(token, "refresh"); }
    private boolean validType(String token, String type) {
        try {
            Claims claims = claims(token);
            return claims.getExpiration() != null && claims.getSubject() != null
                    && !claims.getSubject().isBlank() && type.equals(claims.get("token_type", String.class));
        } catch (JwtException | IllegalArgumentException ex) { return false; }
    }
    private Claims claims(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }
    public String getEmailFromToken(String token) { return claims(token).getSubject(); }
}
