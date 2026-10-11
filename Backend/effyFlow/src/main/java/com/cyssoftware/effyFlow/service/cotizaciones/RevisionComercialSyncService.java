package com.cyssoftware.effyFlow.service.cotizaciones;

import com.cyssoftware.effyFlow.service.pedido.ErpPedidoClient;
import com.cyssoftware.effyFlow.service.pedido.ErpCapturaException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Qualifier;

/** Descarga paquetes de revisión completos; no mezcla páginas del catálogo mutable. */
@Service
public class RevisionComercialSyncService {
    private static final int ESQUEMA_SOPORTADO = 3;
    private static final List<String> CONJUNTOS = List.of("clientes_lista", "productos", "unidades",
            "tipos_precio", "precios_lista", "precios_volumen", "presentaciones", "promociones",
            "promociones_productos", "promociones_listas", "impuestos_producto", "kits_composicion",
            "configuracion_comercial");

    private final ErpPedidoClient erp;
    private final ObjectMapper mapper;

    public RevisionComercialSyncService(@Qualifier("erpCotizacionClient") ErpPedidoClient erp, ObjectMapper mapper) {
        this.erp = erp;
        this.mapper = mapper.copy().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }

    public RevisionComercialPaquete actual() {
        JsonNode metadata = erp.obtenerRevisionComercialActual();
        UUID id = uuid(metadata, "id");
        return validar(metadata, erp.obtenerRevisionComercial(id));
    }

    public RevisionComercialPaquete porId(UUID id) {
        if (id == null) throw new IllegalArgumentException("El ID de revisión es obligatorio.");
        return validar(null, erp.obtenerRevisionComercial(id));
    }

    private RevisionComercialPaquete validar(JsonNode metadata, JsonNode contenido) {
        if (contenido == null || !contenido.isObject()) throw invalida("La respuesta de ERP está vacía o incompleta.");
        UUID id = uuid(contenido, "id");
        int esquema = contenido.path("versionEsquema").asInt(-1);
        String hash = contenido.path("huellaSha256").asText("");
        String referencia = contenido.path("referenciaComercial").asText("");
        String publicadaTexto = contenido.path("publicadaEn").asText("");
        JsonNode datos = contenido.get("datosComerciales");
        if (esquema != ESQUEMA_SOPORTADO) throw invalida("Versión de revisión comercial no soportada: " + esquema);
        if (!hash.matches("[0-9a-f]{64}") || !referencia.equals("sha256:" + hash))
            throw invalida("La referencia o huella de la revisión comercial no es válida.");
        if (publicadaTexto.isBlank() || datos == null || !datos.isObject()
                || datos.path("version_esquema").asInt(-1) != ESQUEMA_SOPORTADO)
            throw invalida("La revisión comercial no contiene metadatos o snapshot completos.");
        for (String conjunto : CONJUNTOS) {
            if (!datos.path(conjunto).isArray()) throw invalida("Falta el conjunto completo " + conjunto + " en la revisión.");
        }
        if (datos.path("configuracion_comercial").isEmpty())
            throw invalida("La revisión no contiene configuración comercial.");
        if (!sha256(canonicalJson(datos)).equals(hash)) throw invalida("La huella SHA-256 de la revisión no coincide.");

        java.time.OffsetDateTime publicada;
        try { publicada = java.time.OffsetDateTime.parse(publicadaTexto); }
        catch (java.time.format.DateTimeParseException ex) { throw invalida("La fecha de publicación no es válida."); }
        if (metadata != null) {
            if (!id.equals(uuid(metadata, "id"))
                    || esquema != metadata.path("versionEsquema").asInt(-1)
                    || !hash.equals(metadata.path("huellaSha256").asText())
                    || !publicadaTexto.equals(metadata.path("publicadaEn").asText()))
                throw invalida("La revisión actual cambió entre metadata y descarga.");
        }
        return new RevisionComercialPaquete(id, esquema, publicada, referencia, hash, datos.deepCopy());
    }

    /** Replica el canonicalizador ERP: llaves de objetos ordenadas, arreglos sin reordenar. */
    String canonicalJson(JsonNode value) {
        try { return mapper.writeValueAsString(canonicalizar(value)); }
        catch (Exception ex) { throw invalida("No se pudo canonicalizar la revisión comercial."); }
    }

    private JsonNode canonicalizar(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = mapper.createObjectNode();
            List<String> names = new ArrayList<>(); node.fieldNames().forEachRemaining(names::add);
            Collections.sort(names);
            for (String name : names) sorted.set(name, canonicalizar(node.get(name)));
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode array = mapper.createArrayNode(); node.forEach(item -> array.add(canonicalizar(item)));
            return array;
        }
        return node.deepCopy();
    }

    private String sha256(String canonical) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonical.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 no disponible.", ex); }
    }

    private UUID uuid(JsonNode node, String field) {
        try { return UUID.fromString(node.path(field).asText()); }
        catch (RuntimeException ex) { throw invalida("La respuesta de ERP no contiene un UUID válido."); }
    }

    private ErpCapturaException invalida(String mensaje) {
        return new ErpCapturaException(502, com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                .put("resultado", "REVISION_COMERCIAL_INVALIDA").put("mensaje", mensaje));
    }
}
