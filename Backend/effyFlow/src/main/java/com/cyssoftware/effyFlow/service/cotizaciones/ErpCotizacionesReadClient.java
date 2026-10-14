package com.cyssoftware.effyFlow.service.cotizaciones;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Dedicated read-only ERP client. It never reads erp.* or erp.cotizaciones.* fallback credentials. */
public class ErpCotizacionesReadClient {
    private final RestClient client;
    private final String baseUrl;
    private final String username;
    private final String password;
    private final String expectedTechnicalUsername;
    private final boolean enabled;
    private volatile String sessionToken;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    public ErpCotizacionesReadClient(RestClient client, String baseUrl, String username, String password,
            String expectedTechnicalUsername, boolean enabled) {
        this.client = client;
        this.baseUrl = trimSlashes(baseUrl);
        this.username = clean(username);
        this.password = clean(password);
        this.expectedTechnicalUsername = clean(expectedTechnicalUsername);
        this.enabled = enabled;
    }

    public boolean enabled() { return enabled; }

    public boolean configurationReady() {
        if (!enabled || baseUrl.isBlank() || username.isBlank() || password.isBlank()
                || expectedTechnicalUsername.isBlank() || !username.equals(expectedTechnicalUsername)) return false;
        try {
            URI uri = URI.create(baseUrl);
            return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && uri.getHost() != null
                    && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null;
        } catch (IllegalArgumentException ex) { return false; }
    }

    public JsonNode bootstrap(Supplier<CotizacionReadAssertionService.Assertion> assertion) {
        return get("/api/cotizaciones/sync/bootstrap", assertion);
    }

    public JsonNode snapshot(long afterId, long maxCotizacionId, int limit,
            Supplier<CotizacionReadAssertionService.Assertion> assertion) {
        if (afterId < 0 || maxCotizacionId < 0 || limit < 1 || limit > 100)
            throw new IllegalArgumentException("Snapshot parameters out of range");
        return get("/api/cotizaciones/sync/snapshot?afterId=" + afterId
                + "&maxCotizacionId=" + maxCotizacionId + "&limit=" + limit, assertion);
    }

    public JsonNode events(long cursor, int limit,
            Supplier<CotizacionReadAssertionService.Assertion> assertion) {
        if (cursor < 0 || limit < 1 || limit > 100)
            throw new IllegalArgumentException("Event parameters out of range");
        return get("/api/cotizaciones/sync/estados?cursor=" + cursor + "&limit=" + limit, assertion);
    }

    private JsonNode get(String path, Supplier<CotizacionReadAssertionService.Assertion> assertionSupplier) {
        validateConfiguration();
        String token = technicalToken();
        try {
            return send(path, assertionSupplier.get(), token);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() != 401) throw map(ex, false);
            synchronized (this) {
                if (token.equals(sessionToken)) sessionToken = null;
                token = technicalToken();
            }
            try { return send(path, assertionSupplier.get(), token); }
            catch (RestClientResponseException retry) { throw map(retry, true); }
            catch (ResourceAccessException timeout) { throw new ErpReadException(504, Failure.TRANSPORT, true); }
            catch (RuntimeException invalid) {
                if (invalid instanceof ErpReadException e) throw e;
                throw new ErpReadException(502, Failure.PROTOCOL, false);
            }
        } catch (ResourceAccessException timeout) {
            throw new ErpReadException(504, Failure.TRANSPORT, true);
        } catch (ErpReadException ex) {
            throw ex;
        } catch (RuntimeException invalid) {
            throw new ErpReadException(502, Failure.PROTOCOL, false);
        }
    }

    private JsonNode send(String path, CotizacionReadAssertionService.Assertion assertion, String bearer) {
        if (assertion == null || assertion.token() == null || assertion.token().isBlank())
            throw new ErpReadException(503, Failure.ASSERTION, false);
        try {
            String body = client.get().uri(baseUrl + path).headers(h -> {
                h.setBearerAuth(bearer);
                h.set("X-Cotizacion-Assertion", assertion.token());
                h.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
            }).retrieve().body(String.class);
            JsonNode json = body == null ? null : mapper.readTree(body);
            if (json == null || !json.isObject()) throw new ErpReadException(502, Failure.PROTOCOL, false);
            return json;
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new ErpReadException(502, Failure.PROTOCOL, false);
        }
    }

    private synchronized String technicalToken() {
        if (sessionToken != null) return sessionToken;
        try {
            JsonNode body = client.post().uri(baseUrl + "/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("usernameOrEmail", username, "password", password))
                    .retrieve().body(JsonNode.class);
            if (body == null || !body.path("token").isTextual() || body.path("token").asText().isBlank())
                throw new ErpReadException(502, Failure.PROTOCOL, false);
            sessionToken = body.path("token").asText();
            return sessionToken;
        } catch (RestClientResponseException ex) {
            throw new ErpReadException(ex.getStatusCode().value(), Failure.TECHNICAL_AUTHENTICATION,
                    ex.getStatusCode().value() == 429 || ex.getStatusCode().is5xxServerError());
        } catch (ResourceAccessException ex) {
            throw new ErpReadException(504, Failure.TRANSPORT, true);
        } catch (ErpReadException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new ErpReadException(502, Failure.PROTOCOL, false);
        }
    }

    private ErpReadException map(RestClientResponseException ex, boolean afterTechnicalRefresh) {
        int status = ex.getStatusCode().value();
        Failure failure = switch (status) {
            case 401 -> afterTechnicalRefresh ? Failure.ASSERTION_OR_TECHNICAL : Failure.ASSERTION;
            case 403 -> Failure.COMMERCIAL_AUTHORIZATION;
            case 408, 429 -> Failure.TRANSPORT;
            default -> status >= 500 ? Failure.TRANSPORT : Failure.PROTOCOL;
        };
        return new ErpReadException(status, failure, status == 408 || status == 429 || status >= 500);
    }

    private void validateConfiguration() {
        if (!enabled) throw new ErpReadException(503, Failure.DISABLED, false);
        if (!configurationReady())
            throw new ErpReadException(503, Failure.CONFIGURATION, false);
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
    private static String trimSlashes(String value) { return clean(value).replaceAll("/+$", ""); }

    public enum Failure {
        DISABLED, CONFIGURATION, TECHNICAL_AUTHENTICATION, ASSERTION,
        ASSERTION_OR_TECHNICAL, COMMERCIAL_AUTHORIZATION, TRANSPORT, PROTOCOL
    }

    public static final class ErpReadException extends RuntimeException {
        private final int status;
        private final Failure failure;
        private final boolean retryable;
        public ErpReadException(int status, Failure failure, boolean retryable) {
            super("ERP Cotizaciones read failed: " + failure);
            this.status = status;
            this.failure = failure;
            this.retryable = retryable;
        }
        public int status() { return status; }
        public Failure failure() { return failure; }
        public boolean retryable() { return retryable; }
    }
}
