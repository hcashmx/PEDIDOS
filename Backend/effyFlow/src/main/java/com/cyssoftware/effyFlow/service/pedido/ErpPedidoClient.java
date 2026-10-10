package com.cyssoftware.effyFlow.service.pedido;

import java.net.URI;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** No automatic business retry: an ambiguous timeout is retried by the queue with the same UUID. */
public class ErpPedidoClient {
    private final RestClient client;
    private final String url, configuredToken, username, password;
    private volatile String sessionToken;
    private final com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper()
            .findAndRegisterModules()
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    public ErpPedidoClient(RestClient client, String url, String token, String username, String password) {
        this.client = client;
        this.url = url.replaceAll("/+$", "");
        this.configuredToken = token; this.username = username; this.password = password;
    }
    public JsonNode crear(ErpCapturaRequest request) { return ejecutar("/api/pedidos/captura", request); }
    public JsonNode obtenerRevisionComercialActual() {
        return ejecutarGet("/api/cotizaciones/revisiones-comerciales/actual");
    }
    public JsonNode obtenerRevisionComercial(java.util.UUID id) {
        if (id == null) throw new ErpCapturaException(400, null);
        return ejecutarGet("/api/cotizaciones/revisiones-comerciales/" + id);
    }
    // Optional preview uses the exact ERP preview shape (no uuidLocal/operacionKitId).
    public JsonNode preview(Map<String, Object> request) { return ejecutar("/api/pedidos/captura/preview", request); }
    private JsonNode ejecutar(String path, Object request) {
        validarConfiguracion();
        String token = obtenerToken();
        try {
            return post(path, request, token);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 401 && configuredToken.isBlank()) {
                synchronized (this) {
                    if (token.equals(sessionToken)) sessionToken = null;
                    token = obtenerToken();
                }
                try { return post(path, request, token); }
                catch (RestClientResponseException second) { throw convertir(second); }
                catch (ResourceAccessException timeout) { throw new ErpCapturaException(504, null); }
                catch (org.springframework.web.client.RestClientException invalid) { throw new ErpCapturaException(502, null); }
            }
            throw convertir(ex);
        } catch (ResourceAccessException ex) { throw new ErpCapturaException(504, null); }
        catch (org.springframework.web.client.RestClientException invalid) { throw new ErpCapturaException(502, null); }
    }
    private void validarConfiguracion() {
        if (url.isBlank()) throw new ErpCapturaException(503, null);
        URI uri;
        try { uri = URI.create(url); }
        catch (IllegalArgumentException ex) { throw new ErpCapturaException(503, null); }
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
            throw new ErpCapturaException(503, null);
    }
    private synchronized String obtenerToken() {
        if (!configuredToken.isBlank()) return configuredToken;
        if (sessionToken != null) return sessionToken;
        if (username.isBlank() || password.isBlank()) throw new ErpCapturaException(503, null);
        try {
            JsonNode login = client.post().uri(url + "/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("usernameOrEmail", username, "password", password)).retrieve().body(JsonNode.class);
            if (login == null || !login.path("token").isTextual() || login.path("token").asText().isBlank())
                throw new ErpCapturaException(502, null);
            sessionToken = login.path("token").asText();
            return sessionToken;
        } catch (RestClientResponseException ex) {
            // Do not forward login response bodies: credentials and session details are transport-private.
            throw new ErpCapturaException(ex.getStatusCode().value(), null);
        } catch (ResourceAccessException ex) { throw new ErpCapturaException(504, null); }
        catch (org.springframework.web.client.RestClientException invalid) { throw new ErpCapturaException(502, null); }
    }
    private JsonNode post(String path, Object request, String token) {
        String wire;
        try { wire = json.writeValueAsString(request); }
        catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new ErpCapturaException(502, null); }
        String body = client.post().uri(url + path).contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> headers.setBearerAuth(token)).body(wire).retrieve().body(String.class);
        JsonNode result;
        try { result = body == null ? null : json.readTree(body); }
        catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new ErpCapturaException(502, null); }
        if (result == null) throw new ErpCapturaException(502, null);
        return result;
    }
    private JsonNode ejecutarGet(String path) {
        validarConfiguracion();
        String token = obtenerToken();
        try {
            return get(path, token);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 401 && configuredToken.isBlank()) {
                synchronized (this) {
                    if (token.equals(sessionToken)) sessionToken = null;
                    token = obtenerToken();
                }
                try { return get(path, token); }
                catch (RestClientResponseException second) { throw convertir(second); }
                catch (ResourceAccessException timeout) { throw new ErpCapturaException(504, null); }
                catch (org.springframework.web.client.RestClientException invalid) { throw new ErpCapturaException(502, null); }
            }
            throw convertir(ex);
        } catch (ResourceAccessException ex) { throw new ErpCapturaException(504, null); }
        catch (org.springframework.web.client.RestClientException invalid) { throw new ErpCapturaException(502, null); }
    }
    private JsonNode get(String path, String token) {
        String body = client.get().uri(url + path).headers(headers -> headers.setBearerAuth(token))
                .retrieve().body(String.class);
        try {
            JsonNode result = body == null ? null : json.readTree(body);
            if (result == null) throw new ErpCapturaException(502, null);
            return result;
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new ErpCapturaException(502, null); }
    }
    private ErpCapturaException convertir(RestClientResponseException ex) {
        JsonNode detail;
        try { detail = json.readTree(ex.getResponseBodyAsString()); }
        catch (com.fasterxml.jackson.core.JsonProcessingException | RuntimeException invalid) { detail = null; }
        return new ErpCapturaException(ex.getStatusCode().value(), detail);
    }
}
