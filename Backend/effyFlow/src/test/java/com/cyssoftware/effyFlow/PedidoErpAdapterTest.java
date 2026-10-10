package com.cyssoftware.effyFlow;

import com.cyssoftware.effyFlow.dto.*;
import com.cyssoftware.effyFlow.service.pedido.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PedidoErpAdapterTest {
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    static PedidoConDetallesDTO payload() throws Exception {
        return JSON.readValue("""
            {"pedido":{"cliente_id":200,"agente_ventas_id":1,"serie_id":10,"folio":99999,
            "uuid_local":"a8888888-1111-2222-3333-444444444444","tipo_documento":"FACTURA",
            "tipo_venta":"CONTADO","fecha_creacion":"2026-10-08T09:10:11.123-06:00",
            "plazo":30,"observaciones":"captura offline","ubicacion_id":null,"ruta_id":3},
            "detalles":[{"producto_id":4,"presentacion_id":2,"almacen_id":1,"cantidad_solicitada":12,
            "precio_unitario":10,"descuento":5,"total_linea":132.24,
            "impuestos":"{\\"subtotal\\":114,\\"totalImpuestos\\":18.24,\\"total\\":132.24}"}]}
            """, PedidoConDetallesDTO.class);
    }
    PedidoService service(ErpPedidoClient client, JdbcTemplate jdbc) { return new PedidoService(client, JSON, jdbc); }

    @ParameterizedTest @CsvSource({"FACTURA,CONTADO", "REMISION,CONTADO", "FACTURA,CREDITO", "REMISION,CREDITO"})
    void documentoYTipoVentaSonIndependientesYLaReferenciaNoEsAutoridad(String doc, String venta) throws Exception {
        var dto = payload(); dto.getPedido().setTipoDocumento(doc); dto.getPedido().setTipoVenta(venta);
        var req = service(mock(ErpPedidoClient.class), mock(JdbcTemplate.class)).mapear(dto);
        assertEquals(doc, req.documentoSolicitado()); assertEquals(venta, req.tipoVenta());
        assertEquals(dto.getPedido().getUuidLocal(), req.uuidLocal());
        assertEquals(OffsetDateTime.parse(dto.getPedido().getFechaCreacion()), req.fechaCapturaComercial());
        assertNull(req.folioManual()); assertNull(req.operacionKitId()); assertEquals(3, req.rutaId());
        assertEquals(2, req.renglones().getFirst().presentacionId());
        assertEquals(new BigDecimal("5"), req.renglones().getFirst().descuentoPorcentaje());
        assertEquals(new BigDecimal("114"), req.referenciaApp().renglones().getFirst().subtotal());
        assertEquals(new BigDecimal("18.24"), req.referenciaApp().renglones().getFirst().impuestos());
        var wire = JSON.valueToTree(req);
        assertFalse(wire.path("renglones").get(0).has("precio"));
        assertFalse(wire.has("total"));
    }
    @Test void aceptaImportesERPYCualquierEstadoActualDelRetry() throws Exception {
        var client = mock(ErpPedidoClient.class); var jdbc = mock(JdbcTemplate.class);
        when(client.crear(any())).thenReturn(JSON.readTree("""
            {"idPedido":42,"origen":"APP","estado":"RECIBIDO","serieId":10,"serie":"PED",
             "folio":123,"version":0,"fechaCaptura":"2026-10-09T13:00:00Z","total":999.99}
            """));
        when(jdbc.queryForList(anyString(), eq(Integer.class), eq("RECIBIDO"))).thenReturn(List.of(7));
        var s = service(client, jdbc); var dto = payload();
        var first = s.guardarPedidoDesdeApp(dto); var retry = s.guardarPedidoDesdeApp(dto);
        assertEquals("ACEPTADO", first.get("resultado")); assertEquals(42L, first.get("uuidServer"));
        assertEquals(new BigDecimal("999.99"), first.get("total")); assertEquals(7, first.get("estadoId"));
        assertEquals(first, retry);
        verify(client, times(2)).crear(argThat(req -> req.uuidLocal().equals(dto.getPedido().getUuidLocal())));
        verify(jdbc, never()).update(anyString());
    }
    @Test void kitSeEnviaSinComponentesSnapshotsNiReservas() throws Exception {
        var dto = payload(); dto.getDetalles().getFirst().setProductoId(500L);
        var req = service(mock(ErpPedidoClient.class), mock(JdbcTemplate.class)).mapear(dto);
        assertEquals(1, req.renglones().size()); assertEquals(500, req.renglones().getFirst().productoId());
        assertFalse(JSON.valueToTree(req).path("renglones").get(0).has("cantidadReservada"));
    }
    @Test void rechazaFaltantesYValoresAmbiguosAntesDeLlamarERP() throws Exception {
        var client = mock(ErpPedidoClient.class); var s = service(client, mock(JdbcTemplate.class));
        var dto = payload(); dto.getPedido().setTipoDocumento("CREDITO");
        assertThrows(IllegalArgumentException.class, () -> s.guardarPedidoDesdeApp(dto));
        dto.getPedido().setTipoDocumento("FACTURA"); dto.getPedido().setTipoVenta(null);
        assertThrows(IllegalArgumentException.class, () -> s.guardarPedidoDesdeApp(dto));
        dto.getPedido().setTipoVenta("CONTADO"); dto.getPedido().setFechaCreacion("2026-10-08T09:10:11");
        assertThrows(IllegalArgumentException.class, () -> s.guardarPedidoDesdeApp(dto));
        verifyNoInteractions(client);
    }
    @Test void noRecalculaSnapshotsLegacyNiAceptaAlmacenesDistintos() throws Exception {
        var s = service(mock(ErpPedidoClient.class), mock(JdbcTemplate.class)); var dto = payload();
        dto.getDetalles().getFirst().setImpuestos("IVA:18.24");
        final var legacy = dto;
        assertThrows(IllegalArgumentException.class, () -> s.mapear(legacy));
        dto = payload(); var other = payload().getDetalles().getFirst(); other.setAlmacenId(2L);
        dto.setDetalles(List.of(dto.getDetalles().getFirst(), other));
        final var mixed = dto;
        assertThrows(IllegalArgumentException.class, () -> s.mapear(mixed));
    }
}
