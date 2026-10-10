package com.cyssoftware.effyFlow.service.pedido;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** Wire contract audited at ERP b8a22e7. Amounts belong only to referenciaApp. */
public record ErpCapturaRequest(Integer clienteId, Integer ubicacionId, Integer agenteVentasId,
        Integer rutaId, Integer almacenId, Integer serieId, Integer folioManual, String tipoVenta,
        String observaciones, List<Renglon> renglones, String operacionKitId, String documentoSolicitado,
        String uuidLocal, OffsetDateTime fechaCapturaComercial, Referencia referenciaApp) {
    public record Renglon(Integer productoId, Integer presentacionId, BigDecimal cantidad,
            BigDecimal descuentoPorcentaje) { }
    public record Referencia(List<ValorVisto> renglones) { }
    public record ValorVisto(BigDecimal precio, BigDecimal descuento, BigDecimal subtotal,
            BigDecimal impuestos, BigDecimal total, String promocionCodigo) { }
}
