package com.cyssoftware.effyFlow;

import com.cyssoftware.effyFlow.controller.pedido.*;
import com.cyssoftware.effyFlow.dto.PedidoConDetallesDTO;
import com.cyssoftware.effyFlow.service.pedido.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Opt-in UAT: writes exclusively through ERP API; JDBC is used only for assertions/feed. */
@EnabledIfEnvironmentVariable(named="PED_COM_SYNC_03_ENABLED", matches="true")
class PedidoErpLocalIntegrationTest {
    private static final java.util.Properties LOCAL = localConfig();
    private static java.util.Properties localConfig() {
        var config = new java.util.Properties();
        Path path = Path.of(".env.local");
        if (Files.exists(path)) {
            try (var reader = Files.newBufferedReader(path)) { config.load(reader); }
            catch (java.io.IOException ex) { throw new IllegalStateException("No se pudo leer configuración local", ex); }
        }
        return config;
    }
    private static String required(String name) {
        String value = env(name);
        assertFalse(value.isBlank(), "Falta variable " + name);
        return value;
    }
    private static String env(String name) { return System.getenv().getOrDefault(name, LOCAL.getProperty(name, "")); }
    @Test void movilERPPostgresHistorialYFeedConEscenariosComerciales() throws Exception {
        var json = PedidoErpAdapterTest.JSON;
        var datasource = new DriverManagerDataSource(required("ERP_TEST_DB_URL"), required("ERP_TEST_DB_USERNAME"),
                required("ERP_TEST_DB_PASSWORD"));
        var jdbc = new JdbcTemplate(datasource);
        var client = new ErpPedidoClient(RestClient.builder().build(), required("ERP_BASE_URL"),
                env("ERP_TOKEN"), env("ERP_USERNAME"), env("ERP_PASSWORD"));
        var service = new PedidoService(client, json, jdbc);
        var mvc = MockMvcBuilders.standaloneSetup(new PedidoController(service),
                new PedidoEstadosController(new PedidoEstadosService(new NamedParameterJdbcTemplate(datasource)))).build();
        JsonNode cases = json.readTree(Files.readString(Path.of(required("ERP_TEST_SCENARIOS_FILE"))));
        assertTrue(cases.isObject());
        Set<String> labels = Set.of("normal", "volumen", "promocion", "volumen_excluye_promocion",
                "presentacion_factor", "descuento_permitido", "descuento_rechazado", "kit");
        for (String label : labels) assertTrue(cases.has(label), "Falta fixture " + label);
        for (String label : labels) {
            var fixture = cases.get(label);
            var dto = json.treeToValue(fixture.path("request"), PedidoConDetallesDTO.class);
            dto.getPedido().setUuidLocal(UUID.randomUUID().toString());
            var request = service.mapear(dto);
            Map<String,Object> previewRequest = json.convertValue(request, new com.fasterxml.jackson.core.type.TypeReference<>() {});
            previewRequest.remove("uuidLocal"); previewRequest.remove("operacionKitId"); previewRequest.remove("referenciaApp");
            if (label.equals("descuento_rechazado")) {
                var result = mvc.perform(post("/api/sync/pedidos").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(dto))).andReturn().getResponse();
                assertTrue(result.getStatus() == 400 || result.getStatus() == 422);
                assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM pedidos WHERE uuid_local=?", Long.class,
                        dto.getPedido().getUuidLocal()));
                continue;
            }
            JsonNode preview = client.preview(previewRequest);
            // Fixture includes expected ERP values to prove the intended catalog rule is active.
            var expected = fixture.path("expected");
            assertTrue(expected.has("precioUnitario"), "Fixture requiere precio ERP esperado: " + label);
            assertEquals(0, preview.path("renglones").get(0).path("precioUnitario").decimalValue()
                    .compareTo(expected.path("precioUnitario").decimalValue()), label);
            if (expected.has("promocionCodigo")) assertEquals(expected.get("promocionCodigo"),
                    preview.path("renglones").get(0).get("promocionCodigo"), label);
            for (int i=0; i<dto.getDetalles().size(); i++) {
                var erp = preview.path("renglones").get(i); var d = dto.getDetalles().get(i);
                d.setPrecioUnitario(erp.path("precioUnitario").decimalValue());
                d.setDescuento(erp.path("descuentoPorcentaje").decimalValue());
                d.setTotalLinea(erp.path("total").decimalValue());
                d.setImpuestos(json.writeValueAsString(Map.of("subtotal", erp.path("base").decimalValue(),
                        "totalImpuestos", erp.path("impuestos").decimalValue())));
            }
            long after = jdbc.queryForObject("SELECT COALESCE(MAX(id_historial),0) FROM pedidos_estados_historial", Long.class);
            byte[] body = json.writeValueAsBytes(dto);
            JsonNode accepted = json.readTree(mvc.perform(post("/api/sync/pedidos").contentType(MediaType.APPLICATION_JSON)
                    .content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            JsonNode retry = json.readTree(mvc.perform(post("/api/sync/pedidos").contentType(MediaType.APPLICATION_JSON)
                    .content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            assertEquals(accepted.path("idPedido"), retry.path("idPedido"));
            assertEquals(accepted.path("folio"), retry.path("folio"));
            assertEquals(0, preview.path("total").decimalValue().compareTo(accepted.path("total").decimalValue()));
            assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM pedidos WHERE uuid_local=?", Long.class,
                    dto.getPedido().getUuidLocal()));
            var stored = jdbc.queryForMap("SELECT documento_solicitado, fecha_captura_comercial FROM pedidos WHERE id_pedido=?",
                    accepted.path("idPedido").longValue());
            assertEquals(dto.getPedido().getTipoDocumento(), stored.get("documento_solicitado"));
            var storedDate = jdbc.queryForObject("SELECT fecha_captura_comercial FROM pedidos WHERE id_pedido=?",
                    (rs,row) -> rs.getObject(1, java.time.OffsetDateTime.class), accepted.path("idPedido").longValue());
            assertEquals(java.time.OffsetDateTime.parse(dto.getPedido().getFechaCreacion())
                    .truncatedTo(java.time.temporal.ChronoUnit.MICROS).toInstant(), storedDate.toInstant());
            var details = jdbc.queryForList("SELECT producto_id, precio_unitario, total_linea, impuestos::text AS impuestos, cantidad_reservada "
                    + "FROM detalle_pedidos WHERE pedido_id=? ORDER BY id_detalle_pedido", accepted.path("idPedido").longValue());
            assertEquals(dto.getDetalles().size(), details.size());
            for (int i=0; i<details.size(); i++) {
                var detail = details.get(i); var calculated = preview.path("renglones").get(i);
                assertEquals(0, ((java.math.BigDecimal) detail.get("precio_unitario"))
                        .compareTo(calculated.path("precioUnitario").decimalValue()));
                assertEquals(0, ((java.math.BigDecimal) detail.get("total_linea"))
                        .compareTo(calculated.path("total").decimalValue()));
            }
            if (label.equals("kit")) {
                assertTrue(json.readTree(details.getFirst().get("impuestos").toString()).hasNonNull("kit"));
                assertEquals(0, ((java.math.BigDecimal) details.getFirst().get("cantidad_reservada")).signum());
            }
            JsonNode feed = json.readTree(mvc.perform(get("/api/sync/pedidos/estados").param("afterId", Long.toString(after)))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            assertTrue(java.util.stream.StreamSupport.stream(feed.path("data").spliterator(), false).anyMatch(event ->
                    event.path("uuidLocal").asText().equals(dto.getPedido().getUuidLocal())
                    && event.path("idPedido").equals(accepted.path("idPedido"))
                    && event.path("estado").asText().equals("RECIBIDO")), label);
            dto.getPedido().setObservaciones("captura distinta");
            mvc.perform(post("/api/sync/pedidos").contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsBytes(dto))).andExpect(status().isConflict());
            dto.getPedido().setUuidLocal(UUID.randomUUID().toString());
            dto.getDetalles().getFirst().setPrecioUnitario(new java.math.BigDecimal("999999.99"));
            var diff = json.readTree(mvc.perform(post("/api/sync/pedidos").contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsBytes(dto))).andExpect(status().isConflict()).andReturn().getResponse().getContentAsString());
            assertEquals("DIFERENCIAS_COMERCIALES", diff.path("resultado").asText());
            assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM pedidos WHERE uuid_local=?", Long.class,
                    dto.getPedido().getUuidLocal()));
        }
    }
}
