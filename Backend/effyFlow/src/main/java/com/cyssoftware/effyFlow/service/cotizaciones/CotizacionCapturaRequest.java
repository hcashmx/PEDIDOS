package com.cyssoftware.effyFlow.service.cotizaciones;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Captura comercial móvil. Los importes enviados son referencias y ERP los vuelve a autorizar. */
public record CotizacionCapturaRequest(
        @NotBlank @Size(max = 36) String uuid,
        @NotNull LocalDate fechaComercial,
        @NotBlank @Size(max = 36) String revisionComercialId,
        @NotBlank @jakarta.validation.constraints.Pattern(regexp = "(?i)[0-9a-f]{64}") String revisionHuellaSha256,
        @Size(max = 128) String dispositivoId,
        Integer agenteId,
        @NotNull @Positive Integer clienteId,
        @NotNull DocumentoSolicitado documentoSolicitado,
        @Size(max = 4000) String observaciones,
        @NotNull @Size(min = 1, max = 500) List<@NotNull @Valid Renglon> renglones) {

    public enum DocumentoSolicitado { FACTURA, REMISION }

    public record Renglon(
            @NotNull @Positive Integer productoId,
            @Positive Integer presentacionId,
            @NotNull @DecimalMin(value = "0", inclusive = false) @jakarta.validation.constraints.Digits(integer = 8, fraction = 6) BigDecimal cantidad,
            @NotNull @DecimalMin("0") @jakarta.validation.constraints.Digits(integer = 8, fraction = 6) BigDecimal precioReferencia,
            @NotNull @DecimalMin("0") @DecimalMax("100") @jakarta.validation.constraints.Digits(integer = 3, fraction = 2) BigDecimal descuentoSolicitado) { }
}
