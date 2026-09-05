package com.cyssoftware.effyFlow.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtTokenProviderTest {

    private static final SecretKey TEST_KEY = Keys.hmacShaKeyFor(
            "clave-de-prueba-jjwt-0123456789-abcdef".getBytes(StandardCharsets.UTF_8));
    private static final SecretKey OTHER_TEST_KEY = Keys.hmacShaKeyFor(
            "otra-clave-de-prueba-jjwt-0123456789".getBytes(StandardCharsets.UTF_8));

    private final JwtTokenProvider tokenProvider = new JwtTokenProvider(TEST_KEY);

    @Test
    void generaYValidaAccessTokenConElUsuarioEsperado() {
        String token = tokenProvider.generateAccessToken("usuario@prueba.test");

        assertTrue(tokenProvider.validateToken(token));
        assertEquals("usuario@prueba.test", tokenProvider.getEmailFromToken(token));
    }

    @Test
    void conservaHS256EnElTokenGenerado() {
        String token = tokenProvider.generateAccessToken("usuario@prueba.test");

        assertEquals("HS256", Jwts.parser().verifyWith(TEST_KEY).build()
                .parseSignedClaims(token).getHeader().getAlgorithm());
    }

    @Test
    void rechazaTokenFirmadoConOtraClaveDePrueba() {
        String token = Jwts.builder()
                .subject("usuario@prueba.test")
                .signWith(OTHER_TEST_KEY, Jwts.SIG.HS256)
                .compact();

        assertFalse(tokenProvider.validateToken(token));
    }

    @Test
    void rechazaTokenMalformadoSinPropagarExcepcion() {
        assertDoesNotThrow(() -> assertFalse(tokenProvider.validateToken("token.no.es.valido")));
    }

    @Test
    void refreshConservaUsuarioYNoAgregaExpiracion() {
        String refreshToken = tokenProvider.generateRefreshToken("usuario@prueba.test");
        Claims claims = Jwts.parser().verifyWith(TEST_KEY).build()
                .parseSignedClaims(refreshToken).getPayload();

        assertTrue(tokenProvider.validateToken(refreshToken));
        assertEquals("usuario@prueba.test", tokenProvider.getEmailFromToken(refreshToken));
        assertNull(claims.getExpiration());
    }
}
