package com.cyssoftware.effyFlow;

import com.cyssoftware.effyFlow.service.pedido.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.net.SocketTimeoutException;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class ErpPedidoClientTest {
    @Test void descargaRevisionPorGetConElMecanismoAutenticadoExistente() throws Exception {
        var builder = RestClient.builder(); var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://erp.test/api/cotizaciones/revisiones-comerciales/actual"))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer external"))
                .andRespond(withSuccess("{\"id\":\"12345678-1234-1234-1234-123456789abc\"}", MediaType.APPLICATION_JSON));
        var client = new ErpPedidoClient(builder.build(), "http://erp.test", "external", "", "");
        assertEquals("12345678-1234-1234-1234-123456789abc",
                client.obtenerRevisionComercialActual().path("id").asText());
        server.verify();
    }

    @Test void reautenticaUnaVezAlDescargarRevisionSiElAccessTokenExpira() throws Exception {
        var builder = RestClient.builder(); var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://erp.test/api/auth/login"))
                .andRespond(withSuccess("{\"token\":\"one\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://erp.test/api/cotizaciones/revisiones-comerciales/12345678-1234-1234-1234-123456789abc"))
                .andExpect(method(org.springframework.http.HttpMethod.GET)).andExpect(header("Authorization", "Bearer one"))
                .andRespond(withUnauthorizedRequest());
        server.expect(requestTo("http://erp.test/api/auth/login"))
                .andRespond(withSuccess("{\"token\":\"two\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://erp.test/api/cotizaciones/revisiones-comerciales/12345678-1234-1234-1234-123456789abc"))
                .andExpect(method(org.springframework.http.HttpMethod.GET)).andExpect(header("Authorization", "Bearer two"))
                .andRespond(withSuccess("{\"id\":\"12345678-1234-1234-1234-123456789abc\"}", MediaType.APPLICATION_JSON));
        var client = new ErpPedidoClient(builder.build(), "http://erp.test", "", "uat", "fixture");
        assertEquals("12345678-1234-1234-1234-123456789abc", client.obtenerRevisionComercial(
                java.util.UUID.fromString("12345678-1234-1234-1234-123456789abc")).path("id").asText());
        server.verify();
    }

    @ParameterizedTest @ValueSource(ints={400,401,403,409,422,500,503,429})
    void conservaStatusYCuerpoYClasificaRetries(int status) throws Exception {
        var builder = RestClient.builder(); var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://erp.test/api/pedidos/captura"))
                .andExpect(header("Authorization", "Bearer external"))
                .andExpect(jsonPath("$.fechaCapturaComercial").value("2026-10-08T09:10:11.123-06:00"))
                .andRespond(withStatus(HttpStatusCode.valueOf(status)).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"contrato real\"}"));
        var client = new ErpPedidoClient(builder.build(), "http://erp.test", "external", "", "");
        var req = new PedidoService(client, PedidoErpAdapterTest.JSON, null).mapear(PedidoErpAdapterTest.payload());
        var ex = assertThrows(ErpCapturaException.class, () -> client.crear(req));
        assertEquals(status, ex.status());
        assertEquals(status >= 500 || status == 429, ex.respuesta().get("reintentable"));
        assertEquals("contrato real", ((com.fasterxml.jackson.databind.JsonNode)ex.respuesta().get("detalleErp")).path("message").asText());
        server.verify();
    }
    @Test void reloginUnaVezAnte401YReenviaMismoUuid() throws Exception {
        var builder = RestClient.builder(); var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://erp.test/api/auth/login"))
                .andExpect(content().json("{\"usernameOrEmail\":\"uat\",\"password\":\"fixture\"}"))
                .andRespond(withSuccess("{\"token\":\"one\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://erp.test/api/pedidos/captura"))
                .andExpect(header("Authorization", "Bearer one"))
                .andExpect(jsonPath("$.uuidLocal").value("a8888888-1111-2222-3333-444444444444"))
                .andRespond(withUnauthorizedRequest());
        server.expect(requestTo("http://erp.test/api/auth/login"))
                .andRespond(withSuccess("{\"token\":\"two\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://erp.test/api/pedidos/captura"))
                .andExpect(header("Authorization", "Bearer two"))
                .andExpect(jsonPath("$.uuidLocal").value("a8888888-1111-2222-3333-444444444444"))
                .andRespond(withSuccess("{\"idPedido\":42,\"folio\":123}", MediaType.APPLICATION_JSON));
        var client = new ErpPedidoClient(builder.build(), "http://erp.test", "", "uat", "fixture");
        var req = new PedidoService(client, PedidoErpAdapterTest.JSON, null).mapear(PedidoErpAdapterTest.payload());
        assertEquals(42, client.crear(req).path("idPedido").asInt()); server.verify();
    }
    @Test void diferenciasNoSeConfirmanYNoHaySegundoCreate() throws Exception {
        var builder = RestClient.builder(); var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://erp.test/api/pedidos/captura"))
                .andRespond(withStatus(HttpStatusCode.valueOf(409)).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"coincide\":false,\"diferencias\":[{\"precioApp\":10,\"precioErp\":20}]}"));
        var client = new ErpPedidoClient(builder.build(), "http://erp.test", "external", "", "");
        var service = new PedidoService(client, PedidoErpAdapterTest.JSON, null);
        var dto = PedidoErpAdapterTest.payload();
        var ex = assertThrows(ErpCapturaException.class, () -> service.guardarPedidoDesdeApp(dto));
        assertEquals("DIFERENCIAS_COMERCIALES", ex.respuesta().get("resultado"));
        assertFalse(ex.respuesta().containsKey("idPedido")); server.verify();
    }
    @Test void timeoutPermiteRetryConMismoUuidSinReintentoOculto() throws Exception {
        var builder = RestClient.builder(); var server = MockRestServiceServer.bindTo(builder).build();
        String uuid = "a8888888-1111-2222-3333-444444444444";
        server.expect(requestTo("http://erp.test/api/pedidos/captura"))
                .andExpect(jsonPath("$.uuidLocal").value(uuid)).andRespond(withException(new SocketTimeoutException()));
        server.expect(requestTo("http://erp.test/api/pedidos/captura"))
                .andExpect(jsonPath("$.uuidLocal").value(uuid))
                .andRespond(withSuccess("{\"idPedido\":42,\"folio\":123}", MediaType.APPLICATION_JSON));
        var client = new ErpPedidoClient(builder.build(), "http://erp.test", "external", "", "");
        var req = new PedidoService(client, PedidoErpAdapterTest.JSON, null).mapear(PedidoErpAdapterTest.payload());
        var ex = assertThrows(ErpCapturaException.class, () -> client.crear(req));
        assertEquals(504, ex.status()); assertEquals(true, ex.respuesta().get("reintentable"));
        assertEquals(123, client.crear(req).path("folio").asInt()); server.verify();
    }
}
