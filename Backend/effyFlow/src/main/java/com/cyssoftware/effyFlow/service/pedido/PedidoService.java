package com.cyssoftware.effyFlow.service.pedido;

import com.cyssoftware.effyFlow.dto.PedidoConDetallesDTO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Transport adapter only. Commercial validation, persistence and identity belong to ERP. */
@Service
@RequiredArgsConstructor
public class PedidoService {
    private final ErpPedidoClient erp;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;

    public Map<String, Object> guardarPedidoDesdeApp(PedidoConDetallesDTO dto) {
        ErpCapturaRequest request = mapear(dto);
        JsonNode accepted = erp.crear(request);
        if (!accepted.path("idPedido").isIntegralNumber() || !accepted.path("folio").isIntegralNumber()
                || !accepted.path("estado").isTextual() || !accepted.path("total").isNumber()
                || !accepted.hasNonNull("serieId") || !accepted.hasNonNull("fechaCaptura"))
            throw new ErpCapturaException(502, null);
        var response = new LinkedHashMap<String, Object>();
        accepted.fields().forEachRemaining(entry -> response.put(entry.getKey(), mapper.convertValue(entry.getValue(), Object.class)));
        response.put("uuidLocal", request.uuidLocal());
        response.put("uuidServer", accepted.path("idPedido").longValue());
        response.put("total", accepted.path("total").decimalValue());
        // Technical catalog lookup preserves the numeric state consumed by current Kotlin.
        try {
            var estados = jdbc.queryForList("SELECT id_estado FROM public.pedido_estados WHERE estado = ?",
                    Integer.class, accepted.path("estado").asText());
            if (estados.size() != 1) throw new ErpCapturaException(502, null);
            response.put("estadoId", estados.getFirst());
        } catch (org.springframework.dao.DataAccessException ex) {
            throw new ErpCapturaException(503, null);
        }
        response.put("fechaRecepcion", accepted.get("fechaCaptura").asText());
        response.put("resultado", "ACEPTADO");
        response.put("mensaje", "Pedido aceptado por ERP.");
        return response;
    }

    public ErpCapturaRequest mapear(PedidoConDetallesDTO dto) {
        if (dto == null || dto.getPedido() == null || dto.getDetalles() == null || dto.getDetalles().isEmpty())
            throw invalido("pedido y detalles son obligatorios");
        var p = dto.getPedido();
        String uuid = p.getUuidLocal();
        if (uuid == null || !uuid.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw invalido("uuid_local debe ser UUID canónico");
        String documento = p.getTipoDocumento();
        if (documento == null || !Set.of("FACTURA", "REMISION").contains(documento))
            throw invalido("tipo_documento debe ser FACTURA o REMISION");
        if (p.getTipoVenta() == null || !Set.of("CONTADO", "CREDITO").contains(p.getTipoVenta()))
            throw invalido("tipo_venta es obligatorio: CONTADO o CREDITO; no se deduce del plazo");
        OffsetDateTime fecha;
        try { fecha = OffsetDateTime.parse(p.getFechaCreacion()); }
        catch (DateTimeParseException | NullPointerException ex) {
            throw invalido("fecha_creacion debe conservar fecha/hora original con desplazamiento UTC; requiere PED-COM-SYNC-04");
        }
        Integer almacen = null;
        var renglones = new ArrayList<ErpCapturaRequest.Renglon>();
        var referencias = new ArrayList<ErpCapturaRequest.ValorVisto>();
        for (var d : dto.getDetalles()) {
            if (d == null) throw invalido("detalle inválido");
            int actual = id(d.getAlmacenId(), "almacen_id");
            if (almacen != null && almacen != actual) throw invalido("ERP requiere un solo almacén por captura");
            almacen = actual;
            if (d.getCantidadSolicitada() == null || d.getCantidadSolicitada().signum() <= 0)
                throw invalido("cantidad_solicitada debe ser positiva");
            renglones.add(new ErpCapturaRequest.Renglon(id(d.getProductoId(), "producto_id"),
                    opcional(d.getPresentacionId(), "presentacion_id"), d.getCantidadSolicitada(), d.getDescuento()));
            JsonNode snapshot = null;
            if (d.getImpuestos() != null && !d.getImpuestos().isBlank()) {
                try { snapshot = mapper.readTree(d.getImpuestos()); }
                catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
                    throw invalido("impuestos debe contener el snapshot JSON offline; no se recalcula en Sync");
                }
                if (snapshot == null || !snapshot.isObject()) throw invalido("snapshot offline inválido");
            }
            referencias.add(new ErpCapturaRequest.ValorVisto(d.getPrecioUnitario(), d.getDescuento(),
                    numero(snapshot, "subtotal"), numero(snapshot, "totalImpuestos"), d.getTotalLinea(), null));
        }
        return new ErpCapturaRequest(id(p.getClienteId(), "cliente_id"), opcional(p.getUbicacionId(), "ubicacion_id"),
                id(p.getAgenteVentasId(), "agente_ventas_id"), opcional(p.getRutaId(), "ruta_id"), almacen,
                id(p.getSerieId(), "serie_id"), null, p.getTipoVenta(), p.getObservaciones(), renglones,
                null, documento, uuid, fecha, new ErpCapturaRequest.Referencia(referencias));
    }
    private static BigDecimal numero(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) return null;
        if (!node.get(field).isNumber()) throw invalido("snapshot " + field + " debe ser numérico");
        return node.get(field).decimalValue();
    }
    private static int id(Long value, String field) {
        if (value == null || value <= 0 || value > Integer.MAX_VALUE) throw invalido(field + " debe ser un ID positivo");
        return value.intValue();
    }
    private static Integer opcional(Long value, String field) { return value == null ? null : id(value, field); }
    private static IllegalArgumentException invalido(String message) { return new IllegalArgumentException(message); }
}
