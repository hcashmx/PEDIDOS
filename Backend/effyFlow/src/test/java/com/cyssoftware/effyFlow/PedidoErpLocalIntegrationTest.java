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
        var labels = java.util.List.of("normal", "volumen", "promocion", "volumen_excluye_promocion",
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
                assertTrue(result.getStatus() == 400 || result.getStatus() == 422 || result.getStatus() == 409,
                        "Descuento rechazado HTTP " + result.getStatus());
                assertFalse(json.readTree(result.getContentAsString()).has("idPedido"));
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
                assertTrue(json.readTree(details.get(0).get("impuestos").toString()).hasNonNull("kit"));
                assertEquals(0, ((java.math.BigDecimal) details.get(0).get("cantidad_reservada")).signum());
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
            dto.getDetalles().get(0).setPrecioUnitario(new java.math.BigDecimal("999999.99"));
            var diff = json.readTree(mvc.perform(post("/api/sync/pedidos").contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsBytes(dto))).andExpect(status().isConflict()).andReturn().getResponse().getContentAsString());
            assertEquals("DIFERENCIAS_COMERCIALES", diff.path("resultado").asText());
            assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM pedidos WHERE uuid_local=?", Long.class,
                    dto.getPedido().getUuidLocal()));
        }
        // Lose the HTTP response only AFTER the real ERP has committed the order.
        var timeoutDto = json.treeToValue(cases.path("normal").path("request"), PedidoConDetallesDTO.class);
        timeoutDto.getPedido().setUuidLocal(UUID.randomUUID().toString());
        var timeoutRequest = service.mapear(timeoutDto);
        var committed = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var erpResponse = new java.util.concurrent.atomic.AtomicReference<JsonNode>();
        var erpStatus = new java.util.concurrent.atomic.AtomicInteger();
        var proxy = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var executor = java.util.concurrent.Executors.newCachedThreadPool();
        proxy.setExecutor(executor);
        proxy.createContext("/api/", exchange -> {
            try {
                var forward = java.net.http.HttpRequest.newBuilder(java.net.URI.create(required("ERP_BASE_URL")
                        + exchange.getRequestURI().getPath())).timeout(java.time.Duration.ofSeconds(30))
                        .header("Content-Type", "application/json");
                String bearer = exchange.getRequestHeaders().getFirst("Authorization");
                if (bearer != null) forward.header("Authorization", bearer);
                var actual = java.net.http.HttpClient.newHttpClient().send(forward.POST(
                        java.net.http.HttpRequest.BodyPublishers.ofByteArray(exchange.getRequestBody().readAllBytes())).build(),
                        java.net.http.HttpResponse.BodyHandlers.ofByteArray());
                if (exchange.getRequestURI().getPath().equals("/api/pedidos/captura")) {
                    erpStatus.set(actual.statusCode()); erpResponse.set(json.readTree(actual.body()));
                    committed.countDown();
                    release.await(5, java.util.concurrent.TimeUnit.SECONDS);
                } else {
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(actual.statusCode(), actual.body().length);
                    exchange.getResponseBody().write(actual.body());
                }
            } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        proxy.start();
        try {
            var factory = new org.springframework.http.client.JdkClientHttpRequestFactory();
            factory.setReadTimeout(java.time.Duration.ofSeconds(1));
            var delayed = new ErpPedidoClient(RestClient.builder().requestFactory(factory).build(),
                    "http://127.0.0.1:" + proxy.getAddress().getPort(), "", env("ERP_USERNAME"), env("ERP_PASSWORD"));
            var timeout = assertThrows(ErpCapturaException.class, () -> delayed.crear(timeoutRequest));
            assertEquals(504, timeout.status()); assertEquals(true, timeout.respuesta().get("reintentable"));
            assertTrue(committed.await(10, java.util.concurrent.TimeUnit.SECONDS), "ERP no terminó la captura");
            assertEquals(200, erpStatus.get());
            int nextFolio = jdbc.queryForObject("SELECT siguiente_folio FROM series_folios WHERE id_serie=?",
                    Integer.class, timeoutRequest.serieId());
            var recovered = client.crear(timeoutRequest);
            assertEquals(erpResponse.get().path("idPedido"), recovered.path("idPedido"));
            assertEquals(erpResponse.get().path("folio"), recovered.path("folio"));
            assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM pedidos WHERE uuid_local=?", Long.class,
                    timeoutRequest.uuidLocal()));
            assertEquals(nextFolio, jdbc.queryForObject("SELECT siguiente_folio FROM series_folios WHERE id_serie=?",
                    Integer.class, timeoutRequest.serieId()));
        } finally {
            release.countDown(); proxy.stop(0); executor.shutdownNow();
        }
    }
}
