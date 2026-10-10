package com.cyssoftware.effyFlow.service.cotizaciones;

import com.cyssoftware.effyFlow.service.pedido.ErpPedidoClient;
import com.cyssoftware.effyFlow.service.pedido.ErpCapturaException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RevisionComercialSyncServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ErpPedidoClient erp = mock(ErpPedidoClient.class);
    private final RevisionComercialSyncService service = new RevisionComercialSyncService(erp, mapper);

    @Test void descargaLaRevisionActualCompletaYLaVerificaContraMetadata() throws Exception {
        UUID id = UUID.randomUUID();
        JsonNode content = content(id, validSnapshot());
        when(erp.obtenerRevisionComercialActual()).thenReturn(metadata(content));
        when(erp.obtenerRevisionComercial(id)).thenReturn(content);

        RevisionComercialPaquete result = service.actual();

        assertEquals(id, result.id());
        assertEquals(3, result.versionEsquema());
        assertEquals("sha256:" + result.huellaSha256(), result.referenciaComercial());
        assertTrue(result.datosComerciales().path("precios_lista").isArray());
        verify(erp).obtenerRevisionComercial(id);
    }

    @Test void rechazaSnapshotAlteradoAunqueLasDemAsSeccionesEstEnCompletas() throws Exception {
        UUID id = UUID.randomUUID();
        ObjectNode snapshot = validSnapshot();
        JsonNode content = content(id, snapshot);
        ((ObjectNode) content.get("datosComerciales")).putArray("precios_lista").addObject()
                .put("producto_id", 8).put("precio", 1);
        when(erp.obtenerRevisionComercial(id)).thenReturn(content);

        assertEquals(502, assertThrows(ErpCapturaException.class, () -> service.porId(id)).status());
    }

    @Test void rechazaSnapshotParcialYEsquemaNoSoportado() throws Exception {
        UUID id = UUID.randomUUID();
        ObjectNode partial = validSnapshot(); partial.remove("impuestos_producto");
        JsonNode incomplete = content(id, partial);
        when(erp.obtenerRevisionComercial(id)).thenReturn(incomplete);
        assertEquals(502, assertThrows(ErpCapturaException.class, () -> service.porId(id)).status());

        ObjectNode unsupported = validSnapshot().put("version_esquema", 4);
        JsonNode future = content(id, unsupported).deepCopy();
        ((ObjectNode) future).put("versionEsquema", 4);
        when(erp.obtenerRevisionComercial(id)).thenReturn(future);
        assertEquals(502, assertThrows(ErpCapturaException.class, () -> service.porId(id)).status());
    }

    @Test void actualNoCombinaMetadataDeUnaPublicacionConOtra() throws Exception {
        UUID requested = UUID.randomUUID(); UUID different = UUID.randomUUID();
        JsonNode metadata = metadata(content(requested, validSnapshot()));
        when(erp.obtenerRevisionComercialActual()).thenReturn(metadata);
        when(erp.obtenerRevisionComercial(requested)).thenReturn(content(different, validSnapshot()));
        assertEquals(502, assertThrows(ErpCapturaException.class, service::actual).status());
    }

    private ObjectNode validSnapshot() {
        ObjectNode snapshot = mapper.createObjectNode().put("version_esquema", 3);
        for (String key : new String[]{"clientes_lista", "productos", "unidades", "tipos_precio", "precios_lista",
                "precios_volumen", "presentaciones", "promociones", "promociones_productos", "promociones_listas",
                "impuestos_producto", "kits_composicion"}) snapshot.putArray(key);
        snapshot.putArray("configuracion_comercial").addObject().put("iva_incluido", "0");
        return snapshot;
    }

    private ObjectNode content(UUID id, JsonNode snapshot) throws Exception {
        String hash = sha(canonical(snapshot));
        return mapper.createObjectNode().put("id", id.toString()).put("versionEsquema", 3)
                .put("publicadaEn", "2026-10-10T12:00:00Z").put("referenciaComercial", "sha256:" + hash)
                .put("huellaSha256", hash).set("datosComerciales", snapshot.deepCopy());
    }

    private JsonNode metadata(JsonNode content) {
        return mapper.createObjectNode().put("id", content.path("id").asText())
                .put("huellaSha256", content.path("huellaSha256").asText())
                .put("publicadaEn", content.path("publicadaEn").asText()).put("versionEsquema", 3);
    }

    private String canonical(JsonNode node) throws Exception { return mapper.writeValueAsString(sorted(node)); }
    private JsonNode sorted(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = mapper.createObjectNode(); var names = new ArrayList<String>(); node.fieldNames().forEachRemaining(names::add);
            Collections.sort(names); for (String name : names) result.set(name, sorted(node.get(name))); return result;
        }
        if (node.isArray()) { ArrayNode result = mapper.createArrayNode(); node.forEach(v -> result.add(sorted(v))); return result; }
        return node.deepCopy();
    }
    private String sha(String value) throws Exception { return java.util.HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
}
