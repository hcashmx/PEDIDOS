package com.cyssoftware.effyFlow.service.cotizaciones;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class ErpCotizacionesReadClientTest {
    @Test void readClientUsesExplicitTechnicalAccountAndFreshAssertionAfterTechnical401() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var client = new ErpCotizacionesReadClient(builder.build(), "http://erp.test", "sync-read", "pw",
                "sync-read", true);
        AtomicInteger assertions = new AtomicInteger();
        server.expect(requestTo("http://erp.test/api/auth/login"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"usernameOrEmail\":\"sync-read\",\"password\":\"pw\"}"))
                .andRespond(withSuccess("{\"token\":\"tech-1\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://erp.test/api/cotizaciones/sync/bootstrap"))
                .andExpect(method(HttpMethod.GET)).andExpect(header("Authorization", "Bearer tech-1"))
                .andExpect(header("X-Cotizacion-Assertion", "assertion-1"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON).body("{}"));
        server.expect(requestTo("http://erp.test/api/auth/login"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"token\":\"tech-2\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://erp.test/api/cotizaciones/sync/bootstrap"))
                .andExpect(method(HttpMethod.GET)).andExpect(header("Authorization", "Bearer tech-2"))
                .andExpect(header("X-Cotizacion-Assertion", "assertion-2"))
                .andRespond(withSuccess("{\"startCursor\":8,\"maxCotizacionId\":13}", MediaType.APPLICATION_JSON));

        var result = client.bootstrap(() -> assertion("assertion-" + assertions.incrementAndGet()));

        assertEquals(8, result.path("startCursor").asLong());
        assertEquals(2, assertions.get());
        server.verify();
    }

    @Test void disabledOrIncompletelyConfiguredReadFailsClosedWithoutFallback() {
        var disabled = new ErpCotizacionesReadClient(RestClient.builder().build(), "", "", "", "", false);
        assertFalse(disabled.configurationReady());
        var ex = assertThrows(ErpCotizacionesReadClient.ErpReadException.class,
                () -> disabled.bootstrap(() -> assertion("should-not-be-used")));
        assertEquals(ErpCotizacionesReadClient.Failure.DISABLED, ex.failure());

        var fallbackOnly = new ErpCotizacionesReadClient(RestClient.builder().build(), "http://erp.test", "", "", "", true);
        assertFalse(fallbackOnly.configurationReady());
    }

    private static CotizacionReadAssertionService.Assertion assertion(String token) {
        Instant now = Instant.now();
        return new CotizacionReadAssertionService.Assertion(token, UUID.randomUUID(), now, now.plusSeconds(60));
    }
}
