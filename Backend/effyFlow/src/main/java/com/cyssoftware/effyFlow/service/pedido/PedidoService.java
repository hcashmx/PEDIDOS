package com.cyssoftware.effyFlow.service.pedido;

import com.cyssoftware.effyFlow.dto.DetallePedidoDTO;
import com.cyssoftware.effyFlow.dto.PedidoConDetallesDTO;
import com.cyssoftware.effyFlow.dto.PedidoDTO;
import com.cyssoftware.effyFlow.entity.Cliente;
import com.cyssoftware.effyFlow.entity.ClienteUbicacion;
import com.cyssoftware.effyFlow.entity.DetallePedido;
import com.cyssoftware.effyFlow.entity.Pedido;
import com.cyssoftware.effyFlow.entity.Producto;
import com.cyssoftware.effyFlow.entity.SeriesFolios;
import com.cyssoftware.effyFlow.repository.AgenteRepository;
import com.cyssoftware.effyFlow.repository.BitacoraOtRepository;
import com.cyssoftware.effyFlow.repository.ClienteRepository;
import com.cyssoftware.effyFlow.repository.ClienteUbicacionRepository;
import com.cyssoftware.effyFlow.repository.DetallePedidoRepository;
import com.cyssoftware.effyFlow.repository.PedidoRepository;
import com.cyssoftware.effyFlow.repository.ProductoRepository;
import com.cyssoftware.effyFlow.repository.SeriesFoliosRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class PedidoService {

    private static final int ESTADO_RECIBIDO_ID = 1;

    private final PedidoRepository pedidoRepo;
    private final DetallePedidoRepository detalleRepo;
    private final ClienteRepository clienteRepo;
    private final ClienteUbicacionRepository clienteUbicacionRepo;
    private final ProductoRepository productoRepo;
    private final SeriesFoliosRepository seriesRepo;
    private final AgenteRepository agenteRepo;
    @SuppressWarnings("unused")
    private final BitacoraOtRepository bitacoraRepo;
    private final PlatformTransactionManager transactionManager;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public Map<String, Object> guardarPedidoDesdeApp(PedidoConDetallesDTO dto) {
        String uuidLocal = normalizarUuidLocal(dto);

        try {
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            return tx.execute(status -> guardarPedidoEnTransaccion(dto, uuidLocal));
        } catch (DataIntegrityViolationException ex) {
            if (uuidLocal != null) {
                Optional<Pedido> existente = pedidoRepo.findByUuidLocal(uuidLocal);
                if (existente.isPresent()) {
                    return respuestaPedido(existente.get(), uuidLocal, true);
                }
            }
            throw ex;
        }
    }

    private Map<String, Object> guardarPedidoEnTransaccion(PedidoConDetallesDTO dto, String uuidLocal) {
        PedidoDTO pedidoDTO = dto.getPedido();

        if (uuidLocal != null) {
            Optional<Pedido> existente = pedidoRepo.findByUuidLocal(uuidLocal);
            if (existente.isPresent()) {
                return respuestaPedido(existente.get(), uuidLocal, true);
            }
        }

        Cliente cliente = validarClienteActivo(pedidoDTO.getClienteId().intValue());
        validarSerieYFolio(pedidoDTO.getSerieId().intValue(), pedidoDTO.getFolio().intValue());

        SeriesFolios serie = seriesRepo.findByIdForUpdate(pedidoDTO.getSerieId().intValue())
                .orElseThrow(() -> new IllegalArgumentException("Serie no encontrada."));
        if (!Boolean.TRUE.equals(serie.getActiva())) {
            throw new IllegalArgumentException("La serie no se encuentra activa (RN-01).");
        }

        Integer agenteId = pedidoDTO.getAgenteVentasId().intValue();
        if (!agenteRepo.existsById(agenteId)) {
            throw new IllegalArgumentException("Agente no valido (RN-03).");
        }

        Integer folioDefinitivo = resolverFolioDefinitivo(serie, pedidoDTO.getFolio().intValue());
        DireccionEntregaResuelta direccionEntrega = resolverDireccionEntrega(cliente, pedidoDTO.getUbicacionId());
        OffsetDateTime fechaCaptura = OffsetDateTime.now();

        Pedido pedido = new Pedido();
        pedido.setClienteId(pedidoDTO.getClienteId().intValue());
        pedido.setAgenteVentasId(agenteId);
        pedido.setUsuarioCreoId(pedidoDTO.getUsuarioCreoId().intValue());
        pedido.setSerieId(serie.getIdSerie());
        pedido.setFolio(folioDefinitivo);
        pedido.setFechaCreacion(fechaCaptura.toLocalDate());
        pedido.setPlazo(pedidoDTO.getPlazo().intValue());
        pedido.setObservaciones(pedidoDTO.getObservaciones());
        pedido.setTotalSolicitado(pedidoDTO.getTotalSolicitado());
        pedido.setTotalAutorizado(pedidoDTO.getTotalAutorizado());
        pedido.setTotalFacturado(pedidoDTO.getTotalFacturado());
        pedido.setEstadoId(ESTADO_RECIBIDO_ID);
        pedido.setUuidLocal(uuidLocal);
        pedido.setOrigen("APP");
        pedido.setUbicacionId(direccionEntrega.ubicacionId());
        pedido.setDireccionEntregaSnapshot(direccionEntrega.snapshot());
        pedido.setFechaCaptura(fechaCaptura);

        pedidoRepo.saveAndFlush(pedido);

        for (DetallePedidoDTO detDTO : dto.getDetalles()) {
            validarProducto(detDTO);

            DetallePedido detalle = new DetallePedido();
            detalle.setPedidoId(pedido.getIdPedido());
            detalle.setProductoId(detDTO.getProductoId().intValue());
            detalle.setPresentacionId(detDTO.getPresentacionId() != null ? detDTO.getPresentacionId().intValue() : null);
            detalle.setAlmacenId(detDTO.getAlmacenId() != null ? detDTO.getAlmacenId().intValue() : null);
            detalle.setCantidadSolicitada(detDTO.getCantidadSolicitada());
            detalle.setCantidadAutorizada(detDTO.getCantidadAutorizada());
            detalle.setCantidadReservada(detDTO.getCantidadReservada());
            detalle.setCantidadSurtida(detDTO.getCantidadSurtida());
            detalle.setCantidadCancelada(detDTO.getCantidadCancelada());
            detalle.setPrecioUnitario(detDTO.getPrecioUnitario());
            detalle.setDescuento(detDTO.getDescuento() != null ? detDTO.getDescuento() : BigDecimal.ZERO);
            detalle.setTotalLinea(detDTO.getTotalLinea());
            detalle.setEstadoLinea(detDTO.getEstadoLinea() != null ? detDTO.getEstadoLinea() : "PENDIENTE");
            detalle.setImpuestos(resolverImpuestosJson(detDTO));
            detalleRepo.save(detalle);
        }

        avanzarSiguienteFolio(serie, folioDefinitivo);

        return respuestaPedido(pedido, uuidLocal, false);
    }

    private Integer resolverFolioDefinitivo(SeriesFolios serie, Integer folioSolicitado) {
        if (!Boolean.TRUE.equals(serie.getEsConsecutiva())) {
            if (pedidoRepo.existsBySerieIdAndFolio(serie.getIdSerie(), folioSolicitado)) {
                throw new IllegalArgumentException("Ya existe un pedido con esa serie y ese folio.");
            }
            return folioSolicitado;
        }

        int siguienteServidor = serie.getSiguienteFolio() != null ? serie.getSiguienteFolio() : 0;
        int candidato = folioSolicitado != null ? folioSolicitado : siguienteServidor;

        if (candidato < siguienteServidor || pedidoRepo.existsBySerieIdAndFolio(serie.getIdSerie(), candidato)) {
            candidato = Math.max(siguienteServidor, pedidoRepo.findMaxFolioBySerieId(serie.getIdSerie()).orElse(siguienteServidor - 1) + 1);
        }

        while (pedidoRepo.existsBySerieIdAndFolio(serie.getIdSerie(), candidato)) {
            candidato++;
        }

        return candidato;
    }

    private void avanzarSiguienteFolio(SeriesFolios serie, Integer folioUsado) {
        if (Boolean.TRUE.equals(serie.getEsConsecutiva())) {
            int siguienteActual = serie.getSiguienteFolio() != null ? serie.getSiguienteFolio() : 0;
            int siguienteCalculado = folioUsado + 1;
            if (siguienteCalculado > siguienteActual) {
                serie.setSiguienteFolio(siguienteCalculado);
                seriesRepo.save(serie);
            }
        }
    }

    private Map<String, Object> respuestaPedido(Pedido pedido, String uuidLocal, boolean idempotent) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("uuidServer", pedido.getIdPedido());
        response.put("folio", pedido.getFolio());
        response.put("serieId", pedido.getSerieId());
        response.put("uuidLocal", uuidLocal);
        response.put("estado", "RECIBIDO");
        response.put("idempotent", idempotent);
        response.put("mensaje", idempotent ? "Pedido recibido previamente." : "Pedido guardado correctamente.");
        return response;
    }

    private String normalizarUuidLocal(PedidoConDetallesDTO dto) {
        if (dto == null || dto.getPedido() == null || dto.getPedido().getUuidLocal() == null) {
            return null;
        }
        String value = dto.getPedido().getUuidLocal().trim();
        return value.isEmpty() ? null : value;
    }

    private Cliente validarClienteActivo(Integer clienteId) {
        Cliente cliente = clienteRepo.findById(clienteId)
                .orElseThrow(() -> new IllegalArgumentException("Cliente no encontrado."));
        if (!Boolean.TRUE.equals(cliente.getActivo())) {
            throw new IllegalArgumentException("El cliente no esta activo.");
        }
        return cliente;
    }

    private DireccionEntregaResuelta resolverDireccionEntrega(Cliente cliente, Long ubicacionId) {
        if (ubicacionId == null) {
            return new DireccionEntregaResuelta(null, serializarSnapshotDireccion(new DireccionEntregaSnapshot(
                    null, null, null, cliente.getDireccion(), null, null, cliente.getColonia(), cliente.getLocalidad(),
                    cliente.getMunicipio(), cliente.getEstado(), cliente.getPais(), cliente.getCodigoPostal(), null,
                    cliente.getLatitud(), cliente.getLongitud())));
        }

        int ubicacion = Math.toIntExact(ubicacionId);
        ClienteUbicacion seleccionada = clienteUbicacionRepo.findById(ubicacion)
                .orElseThrow(() -> new IllegalArgumentException("Ubicación no encontrada."));
        if (!cliente.getIdCliente().equals(seleccionada.getIdCliente())) {
            throw new IllegalArgumentException("La ubicación no pertenece al cliente seleccionado.");
        }
        return new DireccionEntregaResuelta(ubicacion, serializarSnapshotDireccion(new DireccionEntregaSnapshot(
                seleccionada.getAlias(), seleccionada.getContactoPersona(), seleccionada.getContactoTelefono(),
                seleccionada.getDireccion(), seleccionada.getNoExterior(), seleccionada.getNoInterior(),
                seleccionada.getColonia(), seleccionada.getLocalidad(), seleccionada.getMunicipio(),
                seleccionada.getEstado(), seleccionada.getPais(), seleccionada.getCodigoPostal(),
                seleccionada.getReferencia(), seleccionada.getLatitud(), seleccionada.getLongitud())));
    }

    private String serializarSnapshotDireccion(DireccionEntregaSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("No se pudo serializar el snapshot de dirección.", ex);
        }
    }

    private record DireccionEntregaResuelta(Integer ubicacionId, String snapshot) {
    }

    private record DireccionEntregaSnapshot(
            @com.fasterxml.jackson.annotation.JsonProperty("alias") String alias,
            @com.fasterxml.jackson.annotation.JsonProperty("contacto_persona") String contactoPersona,
            @com.fasterxml.jackson.annotation.JsonProperty("contacto_telefono") String contactoTelefono,
            @com.fasterxml.jackson.annotation.JsonProperty("direccion") String direccion,
            @com.fasterxml.jackson.annotation.JsonProperty("no_exterior") String noExterior,
            @com.fasterxml.jackson.annotation.JsonProperty("no_interior") String noInterior,
            @com.fasterxml.jackson.annotation.JsonProperty("colonia") String colonia,
            @com.fasterxml.jackson.annotation.JsonProperty("localidad") String localidad,
            @com.fasterxml.jackson.annotation.JsonProperty("municipio") String municipio,
            @com.fasterxml.jackson.annotation.JsonProperty("estado") String estado,
            @com.fasterxml.jackson.annotation.JsonProperty("pais") String pais,
            @com.fasterxml.jackson.annotation.JsonProperty("codigo_postal") String codigoPostal,
            @com.fasterxml.jackson.annotation.JsonProperty("referencia") String referencia,
            @com.fasterxml.jackson.annotation.JsonProperty("latitud") BigDecimal latitud,
            @com.fasterxml.jackson.annotation.JsonProperty("longitud") BigDecimal longitud) {
    }

    private void validarSerieYFolio(Integer serieId, Integer folio) {
        if (serieId == null || folio == null) {
            throw new IllegalArgumentException("Serie y folio son requeridos.");
        }
        if (folio < 0) {
            throw new IllegalArgumentException("El folio no puede ser negativo.");
        }
    }

    private void validarProducto(DetallePedidoDTO detDTO) {
        Producto producto = productoRepo.findById(detDTO.getProductoId().intValue())
                .orElseThrow(() -> new IllegalArgumentException("Producto no encontrado."));
        if (!Boolean.TRUE.equals(producto.getActivo())) {
            throw new IllegalArgumentException("Producto descontinuado.");
        }

        if (detDTO.getCantidadSolicitada() == null || detDTO.getCantidadSolicitada().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Cantidad solicitada debe ser mayor a 0.");
        }

        if (detDTO.getPrecioUnitario() != null && detDTO.getPrecioUnitario().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Precio unitario invalido.");
        }
    }

    private String calcularImpuestosJson(DetallePedidoDTO detDTO) {
        BigDecimal cantidad = valor(detDTO.getCantidadSolicitada());
        BigDecimal precio = valor(detDTO.getPrecioUnitario());
        BigDecimal descuento = valor(detDTO.getDescuento());
        BigDecimal base = precio.subtract(descuento).multiply(cantidad).setScale(2, RoundingMode.HALF_UP);

        List<Map<String, Object>> impuestos = jdbcTemplate.query("""
                select
                    i.id_impuesto,
                    i.nombre,
                    i.tipo_aplicacion,
                    i.tipo_factor,
                    i.tasa_o_cuota,
                    i.c_impuesto,
                    i.signo
                from public.productos_impuestos pi
                join public.impuestos i on i.id_impuesto = pi.id_impuesto
                where pi.id_producto = ?
                  and coalesce(i.activo, true) = true
                order by pi.orden nulls last, i.id_impuesto
                """,
                (rs, rowNum) -> {
                    BigDecimal tasa = valor(rs.getBigDecimal("tasa_o_cuota"));
                    BigDecimal importe = "Tasa".equalsIgnoreCase(rs.getString("tipo_factor"))
                            ? base.multiply(tasa).setScale(2, RoundingMode.HALF_UP)
                            : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

                    Map<String, Object> impuesto = new LinkedHashMap<>();
                    impuesto.put("idImpuesto", rs.getInt("id_impuesto"));
                    impuesto.put("impuesto", rs.getString("nombre"));
                    impuesto.put("tipoAplicacion", rs.getString("tipo_aplicacion"));
                    impuesto.put("tipoFactor", rs.getString("tipo_factor"));
                    impuesto.put("tasa", tasa);
                    impuesto.put("base", base);
                    impuesto.put("importe", importe);
                    impuesto.put("cImpuesto", rs.getString("c_impuesto"));
                    impuesto.put("signo", rs.getObject("signo"));
                    return impuesto;
                },
                detDTO.getProductoId().intValue()
        );

        try {
            return objectMapper.writeValueAsString(new ArrayList<>(impuestos));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("No se pudo serializar impuestos del detalle.", e);
        }
    }

    private String resolverImpuestosJson(DetallePedidoDTO detDTO) {
        String impuestosSnapshot = detDTO.getImpuestos();
        if (impuestosSnapshot != null && !impuestosSnapshot.isBlank()) {
            try {
                JsonNode parsed = objectMapper.readTree(impuestosSnapshot);
                if (esSnapshotFiscalValido(parsed)) {
                    return objectMapper.writeValueAsString(parsed);
                }
            } catch (JsonProcessingException ignored) {
                // Compatibilidad con pedidos locales antiguos que guardaban "IVA:89.60".
            }
        }
        return calcularImpuestosJson(detDTO);
    }

    private boolean esSnapshotFiscalValido(JsonNode parsed) {
        if (parsed == null || !parsed.isObject()) {
            return false;
        }

        JsonNode impuestos = parsed.get("impuestos");
        if (impuestos == null || !impuestos.isArray() || impuestos.isEmpty()) {
            return false;
        }

        for (JsonNode impuesto : impuestos) {
            if (!impuesto.isObject()
                    || !impuesto.hasNonNull("impuesto")
                    || !impuesto.hasNonNull("tipoAplicacion")
                    || !impuesto.hasNonNull("tipoFactor")
                    || !impuesto.hasNonNull("base")
                    || !impuesto.hasNonNull("importe")
                    || !impuesto.hasNonNull("cImpuesto")
                    || !impuesto.hasNonNull("signo")
                    || !(impuesto.hasNonNull("tasa") || impuesto.hasNonNull("tasaOCuota"))) {
                return false;
            }
        }

        return true;
    }

    private BigDecimal valor(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
