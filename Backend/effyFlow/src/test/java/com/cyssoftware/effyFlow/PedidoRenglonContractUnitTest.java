package com.cyssoftware.effyFlow;

import com.cyssoftware.effyFlow.dto.DetallePedidoDTO;
import com.cyssoftware.effyFlow.dto.PedidoConDetallesDTO;
import com.cyssoftware.effyFlow.dto.PedidoDTO;
import com.cyssoftware.effyFlow.entity.Agente;
import com.cyssoftware.effyFlow.entity.Cliente;
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
import com.cyssoftware.effyFlow.service.pedido.PedidoService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PedidoRenglonContractUnitTest {

    @Test
    void persistePresentacionEImpuestosDelPayloadSinUsarIdUnidad() {
        PedidoRepository pedidoRepo = mock(PedidoRepository.class);
        DetallePedidoRepository detalleRepo = mock(DetallePedidoRepository.class);
        ClienteRepository clienteRepo = mock(ClienteRepository.class);
        ClienteUbicacionRepository clienteUbicacionRepo = mock(ClienteUbicacionRepository.class);
        ProductoRepository productoRepo = mock(ProductoRepository.class);
        SeriesFoliosRepository seriesRepo = mock(SeriesFoliosRepository.class);
        AgenteRepository agenteRepo = mock(AgenteRepository.class);
        BitacoraOtRepository bitacoraRepo = mock(BitacoraOtRepository.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);

        Cliente cliente = new Cliente();
        cliente.setActivo(true);
        Producto producto = new Producto();
        producto.setActivo(true);
        SeriesFolios serie = new SeriesFolios();
        serie.setIdSerie(10);
        serie.setActiva(true);
        serie.setEsConsecutiva(false);

        when(pedidoRepo.findByUuidLocal(any())).thenReturn(Optional.empty());
        when(pedidoRepo.existsBySerieIdAndFolio(anyInt(), anyInt())).thenReturn(false);
        when(clienteRepo.findById(1)).thenReturn(Optional.of(cliente));
        when(productoRepo.findById(27)).thenReturn(Optional.of(producto));
        when(seriesRepo.findByIdForUpdate(10)).thenReturn(Optional.of(serie));
        when(agenteRepo.existsById(1)).thenReturn(true);
        when(pedidoRepo.saveAndFlush(any(Pedido.class))).thenAnswer(invocation -> {
            Pedido pedido = invocation.getArgument(0);
            pedido.setIdPedido(900L);
            return pedido;
        });

        PedidoService service = new PedidoService(
                pedidoRepo,
                detalleRepo,
                clienteRepo,
                clienteUbicacionRepo,
                productoRepo,
                seriesRepo,
                agenteRepo,
                bitacoraRepo,
                new NoopTransactionManager(),
                jdbcTemplate
        );

        service.guardarPedidoDesdeApp(dtoOrt001());

        ArgumentCaptor<Pedido> pedidoCaptor = ArgumentCaptor.forClass(Pedido.class);
        verify(pedidoRepo).saveAndFlush(pedidoCaptor.capture());
        Pedido pedido = pedidoCaptor.getValue();
        assertEquals(1, pedido.getEstadoId());

        ArgumentCaptor<DetallePedido> captor = ArgumentCaptor.forClass(DetallePedido.class);
        verify(detalleRepo).save(captor.capture());
        DetallePedido detalle = captor.getValue();

        assertEquals(27, detalle.getProductoId());
        assertEquals(31, detalle.getPresentacionId());
        assertNotEquals(1, detalle.getPresentacionId());
        assertNotNull(detalle.getImpuestos());
        assertTrue(detalle.getImpuestos().contains("\"cImpuesto\":\"002\""));
        assertTrue(detalle.getImpuestos().contains("\"importe\":89.6"));
    }

    @Test
    void asignaFechaCapturaDelServidorYMantieneFechaCreacionCompatible() {
        PedidoRepository pedidoRepo = mock(PedidoRepository.class);
        DetallePedidoRepository detalleRepo = mock(DetallePedidoRepository.class);
        ClienteRepository clienteRepo = mock(ClienteRepository.class);
        ClienteUbicacionRepository clienteUbicacionRepo = mock(ClienteUbicacionRepository.class);
        ProductoRepository productoRepo = mock(ProductoRepository.class);
        SeriesFoliosRepository seriesRepo = mock(SeriesFoliosRepository.class);
        AgenteRepository agenteRepo = mock(AgenteRepository.class);
        BitacoraOtRepository bitacoraRepo = mock(BitacoraOtRepository.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);

        Cliente cliente = new Cliente();
        cliente.setActivo(true);
        Producto producto = new Producto();
        producto.setActivo(true);
        SeriesFolios serie = new SeriesFolios();
        serie.setIdSerie(10);
        serie.setActiva(true);
        serie.setEsConsecutiva(false);

        when(pedidoRepo.findByUuidLocal(any())).thenReturn(Optional.empty());
        when(pedidoRepo.existsBySerieIdAndFolio(anyInt(), anyInt())).thenReturn(false);
        when(clienteRepo.findById(1)).thenReturn(Optional.of(cliente));
        when(productoRepo.findById(27)).thenReturn(Optional.of(producto));
        when(seriesRepo.findByIdForUpdate(10)).thenReturn(Optional.of(serie));
        when(agenteRepo.existsById(1)).thenReturn(true);
        when(pedidoRepo.saveAndFlush(any(Pedido.class))).thenAnswer(invocation -> {
            Pedido pedido = invocation.getArgument(0);
            pedido.setIdPedido(902L);
            return pedido;
        });

        PedidoService service = new PedidoService(
                pedidoRepo,
                detalleRepo,
                clienteRepo,
                clienteUbicacionRepo,
                productoRepo,
                seriesRepo,
                agenteRepo,
                bitacoraRepo,
                new NoopTransactionManager(),
                jdbcTemplate
        );

        PedidoConDetallesDTO dto = dtoOrt001();
        dto.getPedido().setFechaCreacion(LocalDateTime.of(2026, 8, 21, 22, 40, 0).toString());

        service.guardarPedidoDesdeApp(dto);

        ArgumentCaptor<Pedido> captor = ArgumentCaptor.forClass(Pedido.class);
        verify(pedidoRepo).saveAndFlush(captor.capture());
        Pedido pedido = captor.getValue();

        assertEquals(1, pedido.getEstadoId());
        assertNotNull(pedido.getFechaCaptura());
        assertEquals(pedido.getFechaCaptura().toLocalDate(), pedido.getFechaCreacion());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void jsonVacioNoSeAceptaComoSnapshotFiscalValido() {
        PedidoRepository pedidoRepo = mock(PedidoRepository.class);
        DetallePedidoRepository detalleRepo = mock(DetallePedidoRepository.class);
        ClienteRepository clienteRepo = mock(ClienteRepository.class);
        ClienteUbicacionRepository clienteUbicacionRepo = mock(ClienteUbicacionRepository.class);
        ProductoRepository productoRepo = mock(ProductoRepository.class);
        SeriesFoliosRepository seriesRepo = mock(SeriesFoliosRepository.class);
        AgenteRepository agenteRepo = mock(AgenteRepository.class);
        BitacoraOtRepository bitacoraRepo = mock(BitacoraOtRepository.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);

        Cliente cliente = new Cliente();
        cliente.setActivo(true);
        Producto producto = new Producto();
        producto.setActivo(true);
        SeriesFolios serie = new SeriesFolios();
        serie.setIdSerie(10);
        serie.setActiva(true);
        serie.setEsConsecutiva(false);

        when(pedidoRepo.findByUuidLocal(any())).thenReturn(Optional.empty());
        when(pedidoRepo.existsBySerieIdAndFolio(anyInt(), anyInt())).thenReturn(false);
        when(clienteRepo.findById(1)).thenReturn(Optional.of(cliente));
        when(productoRepo.findById(27)).thenReturn(Optional.of(producto));
        when(seriesRepo.findByIdForUpdate(10)).thenReturn(Optional.of(serie));
        when(agenteRepo.existsById(1)).thenReturn(true);
        when(pedidoRepo.saveAndFlush(any(Pedido.class))).thenAnswer(invocation -> {
            Pedido pedido = invocation.getArgument(0);
            pedido.setIdPedido(901L);
            return pedido;
        });
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any()))
                .thenReturn(List.of(impuestoFallback()));

        PedidoService service = new PedidoService(
                pedidoRepo,
                detalleRepo,
                clienteRepo,
                clienteUbicacionRepo,
                productoRepo,
                seriesRepo,
                agenteRepo,
                bitacoraRepo,
                new NoopTransactionManager(),
                jdbcTemplate
        );

        PedidoConDetallesDTO dto = dtoOrt001();
        dto.getDetalles().get(0).setImpuestos("{}");

        service.guardarPedidoDesdeApp(dto);

        ArgumentCaptor<DetallePedido> captor = ArgumentCaptor.forClass(DetallePedido.class);
        verify(detalleRepo).save(captor.capture());
        DetallePedido detalle = captor.getValue();

        assertTrue(detalle.getImpuestos().contains("\"cImpuesto\":\"002\""));
        assertTrue(detalle.getImpuestos().contains("\"importe\":89.60"));
    }

    private PedidoConDetallesDTO dtoOrt001() {
        PedidoDTO pedido = new PedidoDTO();
        pedido.setClienteId(1L);
        pedido.setAgenteVentasId(1L);
        pedido.setUsuarioCreoId(1L);
        pedido.setSerieId(10L);
        pedido.setFolio(800000L);
        pedido.setFechaCreacion(OffsetDateTime.now().toString());
        pedido.setPlazo(0L);
        pedido.setTotalSolicitado(new BigDecimal("649.60"));
        pedido.setTotalAutorizado(BigDecimal.ZERO);
        pedido.setTotalFacturado(BigDecimal.ZERO);
        pedido.setUuidLocal(UUID.randomUUID().toString());

        DetallePedidoDTO detalle = new DetallePedidoDTO();
        detalle.setProductoId(27L);
        detalle.setPresentacionId(31L);
        detalle.setAlmacenId(1L);
        detalle.setCantidadSolicitada(BigDecimal.ONE);
        detalle.setCantidadAutorizada(BigDecimal.ZERO);
        detalle.setCantidadReservada(BigDecimal.ZERO);
        detalle.setCantidadSurtida(BigDecimal.ZERO);
        detalle.setCantidadCancelada(BigDecimal.ZERO);
        detalle.setPrecioUnitario(new BigDecimal("560.00"));
        detalle.setDescuento(BigDecimal.ZERO);
        detalle.setTotalLinea(new BigDecimal("649.60"));
        detalle.setEstadoLinea("PENDIENTE");
        detalle.setImpuestos("""
                {"subtotal":560.0,"totalImpuestos":89.6,"total":649.6,"impuestos":[{"idImpuesto":1,"impuesto":"IVA","tipoAplicacion":"Traslado","tipoFactor":"Tasa","tasa":0.16,"base":560.0,"importe":89.6,"cImpuesto":"002","signo":1}]}
                """);

        PedidoConDetallesDTO dto = new PedidoConDetallesDTO();
        dto.setPedido(pedido);
        dto.setDetalles(List.of(detalle));
        return dto;
    }

    private Map<String, Object> impuestoFallback() {
        Map<String, Object> impuesto = new LinkedHashMap<>();
        impuesto.put("idImpuesto", 1);
        impuesto.put("impuesto", "IVA");
        impuesto.put("tipoAplicacion", "Traslado");
        impuesto.put("tipoFactor", "Tasa");
        impuesto.put("tasa", new BigDecimal("0.16"));
        impuesto.put("base", new BigDecimal("560.00"));
        impuesto.put("importe", new BigDecimal("89.60"));
        impuesto.put("cImpuesto", "002");
        impuesto.put("signo", 1);
        return impuesto;
    }

    private static class NoopTransactionManager extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
