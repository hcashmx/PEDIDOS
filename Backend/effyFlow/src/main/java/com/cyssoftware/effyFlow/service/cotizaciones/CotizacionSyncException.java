package com.cyssoftware.effyFlow.service.cotizaciones;

import java.util.LinkedHashMap;
import java.util.Map;

/** Safe, classifiable response for a failed ERP handoff. */
public class CotizacionSyncException extends RuntimeException {
    private final int status;
    private final Map<String, Object> body;

    public CotizacionSyncException(int status, String classification, boolean retryable, String message,
            Map<String, Object> details) {
        super(message);
        this.status = status;
        var response = new LinkedHashMap<String, Object>();
        response.put("resultado", classification);
        response.put("reintentable", retryable);
        response.put("origenError", "ERP");
        response.put("error", message);
        if (details != null && !details.isEmpty()) response.put("detalle", details);
        this.body = Map.copyOf(response);
    }
    public int status() { return status; }
    public Map<String, Object> body() { return body; }
}
