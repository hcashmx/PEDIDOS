package com.cyssoftware.effyFlow.service.pedido;

import java.util.LinkedHashMap;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;

public class ErpCapturaException extends RuntimeException {
    private final int status;
    private final JsonNode detalle;
    private final String origen;
    public ErpCapturaException(int status, JsonNode detalle) {
        this(status, detalle, null);
    }
    public ErpCapturaException(int status, JsonNode detalle, String origen) {
        super("ERP captura HTTP " + status);
        this.status = status;
        this.detalle = detalle;
        this.origen = origen;
    }
    public int status() { return status; }
    public String origen() { return origen; }
    public Map<String, Object> respuesta() {
        boolean retry = status >= 500 || status == 408 || status == 429;
        String resultado = status == 409
                ? (detalle != null && detalle.path("diferencias").isArray() && !detalle.path("diferencias").isEmpty()
                    ? "DIFERENCIAS_COMERCIALES" : "CONFLICTO")
                : retry ? "ERROR_REINTENTABLE" : "ERROR_NO_REINTENTABLE";
        var body = new LinkedHashMap<String, Object>();
        body.put("resultado", resultado);
        body.put("reintentable", retry);
        body.put("origenError", "ERP");
        body.put("error", "No se confirmó la captura ERP (HTTP " + status + ")");
        body.put("detalleErp", detalle);
        return body;
    }
}
