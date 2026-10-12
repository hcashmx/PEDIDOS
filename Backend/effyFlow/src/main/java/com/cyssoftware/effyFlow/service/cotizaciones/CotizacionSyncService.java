package com.cyssoftware.effyFlow.service.cotizaciones;

import com.cyssoftware.effyFlow.service.pedido.ErpCapturaException;
import com.cyssoftware.effyFlow.service.pedido.ErpPedidoClient;
import com.cyssoftware.effyFlow.service.pedido.SyncService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Orchestrates the immutable commercial payload, signed assertion, and ERP response. */
@Service
public class CotizacionSyncService {
    private final SyncService usuarios;
    private final RevisionComercialSyncService revisiones;
    private final CotizacionIdentityAssertionService aserciones;
    private final ErpPedidoClient erp;
    private final ObjectMapper mapper;

    public CotizacionSyncService(SyncService usuarios, RevisionComercialSyncService revisiones,
            CotizacionIdentityAssertionService aserciones,
            @Qualifier("erpCotizacionClient") ErpPedidoClient erp, ObjectMapper mapper) {
        this.usuarios = usuarios;
        this.revisiones = revisiones;
        this.aserciones = aserciones;
        this.erp = erp;
        this.mapper = mapper;
    }

    public Envio enviar(String usuarioAutenticado, CotizacionCapturaRequest captura) {
        if (captura == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Captura requerida.");
        UUID uuid = uuid(captura.uuid(), "uuid");
        UUID revisionId = uuid(captura.revisionComercialId(), "revisionComercialId");
        if (captura.dispositivoId() == null || !captura.dispositivoId().matches("[A-Za-z0-9._:-]{1,128}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "dispositivoId debe identificar el dispositivo.");

        SyncService.IdentidadComercial actor = usuarios.identidadCotizaciones(usuarioAutenticado);
        if (actor == null) throw new org.springframework.security.access.AccessDeniedException(
                "El usuario debe estar activo, autorizado para cotizaciones.crear y vinculado a un agente.");
        if (captura.agenteId() != null && !captura.agenteId().equals(actor.agenteErpId()))
            throw new org.springframework.security.access.AccessDeniedException("agenteId no coincide con la identidad comercial autenticada.");

        RevisionComercialPaquete revision;
        try { revision = revisiones.porId(revisionId); }
        catch (ErpCapturaException ex) { throw traducir(ex); }
        if (!revision.huellaSha256().equalsIgnoreCase(captura.revisionHuellaSha256()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "La huella no corresponde a la revisión comercial capturada; conserva la captura para revisión.");

        JsonNode payload = construirPayload(captura, uuid, revisionId, actor.agenteErpId());
        CotizacionIdentityAssertionService.Asercion assertion;
        try {
            assertion = aserciones.emitir(usuarioAutenticado,
                    new CotizacionIdentityAssertionService.SolicitudAsercion(uuid.toString(), revisionId.toString(),
                            captura.dispositivoId(), payload));
        } catch (ErpCapturaException ex) { throw traducir(ex); }
        if (!assertion.revisionHuellaSha256().equalsIgnoreCase(captura.revisionHuellaSha256()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La revisión cambió durante la validación de la captura.");

        ErpPedidoClient.ErpCotizacionResponse erpResult;
        try {
            // The same JsonNode used to calculate requestSha256 is sent as the ERP body.
            erpResult = erp.crearCotizacion(payload, assertion.token());
        } catch (ErpCapturaException ex) {
            throw traducir(ex);
        }
        boolean recovered = erpResult.status() == 200;
        return new Envio(mapearRespuesta(uuid, revisionId, erpResult.body(), recovered), recovered);
    }

    private JsonNode construirPayload(CotizacionCapturaRequest c, UUID uuid, UUID revisionId, int agenteId) {
        ObjectNode root = mapper.createObjectNode().put("uuid", uuid.toString())
                .put("revisionComercialId", revisionId.toString());
        ObjectNode oferta = root.putObject("oferta").put("clienteId", c.clienteId())
                .put("agenteVentasId", agenteId).put("fecha", c.fechaComercial().toString())
                .put("documentoSolicitado", c.documentoSolicitado().name());
        if (c.observaciones() != null) oferta.put("observaciones", c.observaciones());
        var lines = oferta.putArray("renglones");
        for (var line : c.renglones()) {
            ObjectNode item = lines.addObject().put("productoId", line.productoId())
                    .put("cantidad", line.cantidad()).put("precioUnitario", line.precioReferencia())
                    .put("descuentoPorcentaje", line.descuentoSolicitado());
            if (line.presentacionId() != null) item.put("presentacionId", line.presentacionId());
        }
        return root;
    }

    private Map<String, Object> mapearRespuesta(UUID uuid, UUID revision, JsonNode erp, boolean recovered) {
        JsonNode head = erp.path("cabecera");
        if (!head.path("id").canConvertToLong() || head.path("estado").asText("").isBlank()
                || !head.path("version").canConvertToLong() || !uuid.toString().equals(head.path("uuid").asText())
                || !head.path("fecha").isTextual() || head.path("documentoSolicitado").asText("").isBlank()
                || !head.path("subtotal").isNumber() || !head.path("impuestos").isNumber() || !head.path("total").isNumber()
                || !erp.path("renglones").isArray() || erp.path("renglones").isEmpty())
            throw new CotizacionSyncException(502, "RESPUESTA_ERP_INCOMPLETA", true,
                    "ERP respondió sin una confirmación comercial completa; reintenta con el mismo UUID.", null);
        var response = new LinkedHashMap<String, Object>();
        response.put("resultado", "ACEPTADA");
        response.put("uuidLocal", uuid.toString());
        response.put("idCotizacion", head.path("id").asLong());
        response.put("estado", head.path("estado").asText());
        response.put("revisionComercialId", revision.toString());
        response.put("version", head.path("version").asLong());
        response.put("fechaComercial", head.path("fecha").asText());
        response.put("documentoSolicitado", head.path("documentoSolicitado").asText());
        response.put("subtotal", head.get("subtotal"));
        response.put("impuestos", head.get("impuestos"));
        response.put("total", head.get("total"));
        response.put("renglones", lineasComerciales(erp.path("renglones")));
        response.put("idempotente", recovered);
        response.put("resultadoIdempotencia", recovered ? "RECUPERADA" : "CREADA");
        return response;
    }

    private List<Map<String, Object>> lineasComerciales(JsonNode lines) {
        List<String> allowed = List.of("renglon", "productoId", "presentacionId", "cantidad", "precioUnitario",
                "descuentoPorcentaje", "descuentoImporte", "subtotal", "impuestos", "total",
                "codigoProductoSnapshot", "descripcionSnapshot", "unidadSnapshot");
        var result = new java.util.ArrayList<Map<String, Object>>();
        for (JsonNode line : lines) {
            var mapped = new LinkedHashMap<String, Object>();
            for (String field : allowed) if (line.has(field)) mapped.put(field, line.get(field));
            if (!mapped.containsKey("productoId") || !mapped.containsKey("cantidad")
                    || !mapped.containsKey("precioUnitario") || !mapped.containsKey("total"))
                throw new CotizacionSyncException(502, "RESPUESTA_ERP_INCOMPLETA", true,
                        "ERP respondió con renglones incompletos; reintenta con el mismo UUID.", null);
            result.add(mapped);
        }
        return List.copyOf(result);
    }

    private CotizacionSyncException traducir(ErpCapturaException ex) {
        int status = ex.status();
        boolean retryable = status >= 500 || status == 408 || status == 429 || status == 504;
        String classification;
        String message;
        Map<String, Object> details = new LinkedHashMap<>();
        JsonNode raw = ex.respuesta().get("detalleErp") instanceof JsonNode n ? n : null;
        String erpMessage = raw == null ? "" : raw.path("message").asText(raw.path("error").asText(""));
        switch (status) {
            case 400, 422 -> {
                classification = "SOLICITUD_INVALIDA";
                message = "ERP rechazó un campo o regla comercial de la captura. Revisa el detalle de campos.";
                if (raw != null && raw.path("errors").isObject()) details.put("campos", raw.path("errors"));
            }
            case 401 -> {
                classification = "AUTENTICACION_TECNICA_ERP".equals(ex.origen())
                        ? "CREDENCIAL_TECNICA_ERP_INVALIDA" : clasificarAuth(erpMessage);
                message = "La autenticación ERP o la aserción de identidad fue rechazada; conserva la captura y autentica de nuevo si corresponde.";
                details.put("requiereAutenticacion", true);
            }
            case 403 -> { classification = "SIN_AUTORIZACION"; message = "El vendedor o la cuenta técnica no tiene autorización ERP para crear o recuperar esta Cotización."; }
            case 409 -> { classification = "CONFLICTO_COMERCIAL_O_IDEMPOTENCIA"; message = "ERP detectó un conflicto permanente de UUID, revisión o captura; no se reintentará automáticamente."; }
            case 404 -> { classification = "REVISION_COMERCIAL_NO_DISPONIBLE"; message = "La revisión comercial capturada no está disponible en ERP; conserva la captura para revisión."; }
            default -> {
                classification = retryable ? "ERROR_TECNICO_REINTENTABLE" : "ERROR_ERP";
                message = retryable ? "No se confirmó la respuesta ERP; conserva UUID y captura y reintenta la misma operación." : "ERP no pudo procesar la Cotización.";
            }
        }
        return new CotizacionSyncException(status == 0 ? 502 : status, classification, retryable, message, details);
    }

    private String clasificarAuth(String message) {
        String normalized = message.toLowerCase(java.util.Locale.ROOT);
        if (normalized.contains("aserción") || normalized.contains("assertion") || normalized.contains("sync no es válida") || normalized.contains("ya expir"))
            return "ASERCION_RS256_INVALIDA_O_VENCIDA";
        if (normalized.contains("técnica") || normalized.contains("tecnica") || normalized.contains("credencial"))
            return "CREDENCIAL_TECNICA_ERP_INVALIDA";
        return "AUTENTICACION_ERP_RECHAZADA";
    }

    private UUID uuid(String value, String field) {
        try {
            if (value == null || !value.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
                throw new IllegalArgumentException();
            return UUID.fromString(value);
        } catch (RuntimeException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " debe ser UUID canónico."); }
    }

    public record Envio(Map<String, Object> respuesta, boolean recuperada) { }
}
