package com.cyssoftware.effyFlow.service.cotizaciones;

import com.cyssoftware.effyFlow.service.pedido.SyncService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.jsonwebtoken.Jwts;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Emite una prueba temporal y ligada a una sola captura móvil autenticada. */
@Service
public class CotizacionIdentityAssertionService {
    private static final long MAX_PAYLOAD_BYTES = 1_000_000;
    private final SyncService usuarios;
    private final RevisionComercialSyncService revisiones;
    private final ObjectMapper mapper;
    private final String privateKeyBase64;
    private final String issuer;
    private final String audience;
    private final String keyId;
    private final long validitySeconds;

    public CotizacionIdentityAssertionService(SyncService usuarios, RevisionComercialSyncService revisiones,
            ObjectMapper mapper,
            @Value("${app.cotizaciones.assertion.private-key-base64:}") String privateKeyBase64,
            @Value("${app.cotizaciones.assertion.issuer:effyflow-sync}") String issuer,
            @Value("${app.cotizaciones.assertion.audience:erp-cotizaciones}") String audience,
            @Value("${app.cotizaciones.assertion.key-id:}") String keyId,
            @Value("${app.cotizaciones.assertion.validity-seconds:60}") long validitySeconds) {
        this.usuarios = usuarios;
        this.revisiones = revisiones;
        this.mapper = mapper.copy().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        this.privateKeyBase64 = privateKeyBase64 == null ? "" : privateKeyBase64.trim();
        this.issuer = issuer == null ? "" : issuer.trim();
        this.audience = audience == null ? "" : audience.trim();
        this.keyId = keyId == null ? "" : keyId.trim();
        this.validitySeconds = validitySeconds;
    }

    public Asercion emitir(String emailAutenticado, SolicitudAsercion request) {
        if (emailAutenticado == null || emailAutenticado.isBlank()) denegado("No hay usuario remoto autenticado.");
        if (request == null || request.payload() == null || !request.payload().isObject())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El payload comercial debe ser un objeto JSON.");
        UUID uuid = uuid(request.uuidCotizacion(), "uuidCotizacion");
        UUID revision = uuid(request.revisionComercialId(), "revisionComercialId");
        if (request.dispositivoId() == null || !request.dispositivoId().matches("[A-Za-z0-9._:-]{1,128}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "dispositivoId debe ser un identificador opaco de hasta 128 caracteres.");
        validarConfiguracion();

        SyncService.IdentidadComercial actor = usuarios.identidadCotizaciones(emailAutenticado);
        if (actor == null) denegado("El usuario debe estar activo, contar con cotizaciones.crear y tener exactamente un agente asociado.");
        RevisionComercialPaquete revisionVerificada = revisiones.porId(revision);
        validarVinculosPayload(request.payload(), uuid, revision, actor.agenteErpId());
        String canonical = canonicalJson(request.payload());
        byte[] canonicalBytes = canonical.getBytes(StandardCharsets.UTF_8);
        if (canonicalBytes.length > MAX_PAYLOAD_BYTES)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El payload comercial excede el tamaño permitido.");
        String payloadHash = sha256(canonicalBytes);
        String jti = UUID.randomUUID().toString();
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plusSeconds(validitySeconds);
        String token;
        try {
            token = Jwts.builder()
                    .issuer(issuer)
                    .subject(Long.toString(actor.usuarioErpId()))
                    .audience().add(audience).and()
                    .issuedAt(java.util.Date.from(issuedAt))
                    .expiration(java.util.Date.from(expiresAt))
                    .id(jti)
                    .claim("operation", "CREAR_COTIZACION")
                    .claim("usuarioErpId", actor.usuarioErpId())
                    .claim("agenteErpId", actor.agenteErpId())
                    .claim("dispositivoId", request.dispositivoId())
                    .claim("uuidCotizacion", uuid.toString())
                    .claim("revisionComercialId", revision.toString())
                    .claim("revisionHuellaSha256", revisionVerificada.huellaSha256())
                    .claim("requestSha256", payloadHash)
                    .header().keyId(keyId).type("JWT").and()
                    .signWith(cargarClave(), Jwts.SIG.RS256)
                    .compact();
        } catch (ResponseStatusException ex) { throw ex; }
        catch (Exception ex) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "No fue posible emitir la aserción comercial."); }
        return new Asercion(token, "Bearer", actor.usuarioErpId(), actor.agenteErpId(),
                request.dispositivoId(), uuid, revision, revisionVerificada.huellaSha256(), payloadHash, jti, issuedAt, expiresAt);
    }

    /** Hash para el futuro body ERP: objetos por nombre, arrays conservan orden, sin datos de transporte. */
    String canonicalJson(JsonNode payload) {
        try { return mapper.writeValueAsString(canonicalizar(payload)); }
        catch (Exception ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No se pudo canonicalizar el payload."); }
    }

    private JsonNode canonicalizar(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = mapper.createObjectNode();
            var names = new ArrayList<String>(); node.fieldNames().forEachRemaining(names::add);
            Collections.sort(names);
            for (String name : names) sorted.set(name, canonicalizar(node.get(name)));
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode ordered = mapper.createArrayNode(); node.forEach(value -> ordered.add(canonicalizar(value)));
            return ordered;
        }
        return node.deepCopy();
    }

    private void validarVinculosPayload(JsonNode payload, UUID uuid, UUID revision, Integer agenteId) {
        JsonNode oferta = payload.path("oferta");
        if (!uuid.toString().equals(payload.path("uuid").asText())
                || !revision.toString().equals(payload.path("revisionComercialId").asText())
                || !oferta.isObject() || !oferta.path("agenteVentasId").canConvertToInt()
                || oferta.path("agenteVentasId").asInt() != agenteId
                || !soloCampos(payload, java.util.Set.of("uuid", "revisionComercialId", "oferta"))
                || contieneIdentidadArbitraria(payload))
            denegado("La identidad, agente, UUID y revisión del payload deben coincidir con la sesión autenticada.");
    }

    private boolean soloCampos(JsonNode objeto, java.util.Set<String> permitidos) {
        var fields = objeto.fieldNames();
        while (fields.hasNext()) if (!permitidos.contains(fields.next())) return false;
        return true;
    }

    private boolean contieneIdentidadArbitraria(JsonNode node) {
        if (node.isObject()) {
            var fields = node.fieldNames();
            while (fields.hasNext()) {
                String field = fields.next();
                String normalized = field.replace("_", "").toLowerCase(java.util.Locale.ROOT);
                if (java.util.Set.of("usuarioid", "userid", "agenteid").contains(normalized)
                        || contieneIdentidadArbitraria(node.get(field))) return true;
            }
        } else if (node.isArray()) {
            for (JsonNode value : node) if (contieneIdentidadArbitraria(value)) return true;
        }
        return false;
    }

    private PrivateKey cargarClave() throws Exception {
        byte[] encoded = Base64.getDecoder().decode(privateKeyBase64);
        PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(encoded));
        if (!(key instanceof java.security.interfaces.RSAPrivateKey rsa) || rsa.getModulus().bitLength() < 2048)
            throw new IllegalArgumentException("La clave RSA no tiene tamaño suficiente.");
        return key;
    }

    private void validarConfiguracion() {
        if (privateKeyBase64.isBlank() || issuer.isBlank() || audience.isBlank() || keyId.isBlank())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "La firma de identidad no está configurada.");
        if (validitySeconds < 15 || validitySeconds > 300)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "La vigencia de la aserción debe ser de 15 a 300 segundos.");
    }

    private UUID uuid(String value, String field) {
        try {
            if (value == null || !value.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
                throw new IllegalArgumentException();
            return UUID.fromString(value);
        } catch (RuntimeException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " debe ser UUID canónico."); }
    }

    private String sha256(byte[] bytes) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 no disponible.", ex); }
    }

    private void denegado(String message) { throw new ResponseStatusException(HttpStatus.FORBIDDEN, message); }

    public record SolicitudAsercion(String uuidCotizacion, String revisionComercialId,
            String dispositivoId, JsonNode payload) { }
    public record Asercion(String token, String tokenType, Long usuarioErpId, Integer agenteErpId,
            String dispositivoId, UUID uuidCotizacion, UUID revisionComercialId, String revisionHuellaSha256, String requestSha256,
            String jti, Instant issuedAt, Instant expiresAt) { }
}
