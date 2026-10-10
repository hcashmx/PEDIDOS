package com.cyssoftware.effyFlow.service.cotizaciones;

import java.time.OffsetDateTime;
import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;

/** Publicación comercial completa validada antes de distribuirse a Kotlin. */
public record RevisionComercialPaquete(
        UUID id,
        int versionEsquema,
        OffsetDateTime publicadaEn,
        String referenciaComercial,
        String huellaSha256,
        JsonNode datosComerciales) { }
