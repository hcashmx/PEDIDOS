package com.cyssoftware.effyFlow;

import com.cyssoftware.effyFlow.dto.DetallePedidoDTO;
import com.cyssoftware.effyFlow.dto.PedidoConDetallesDTO;
import com.cyssoftware.effyFlow.dto.PedidoDTO;
import com.cyssoftware.effyFlow.service.pedido.PedidoService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.show-sql=false"
})
@Transactional
@Rollback
class PedidoIdempotenciaFolioTest {

    @Autowired
    PedidoService pedidoService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void mismoUuidNoCreaSegundoPedidoYDevuelveMismoId() {
        String uuid = UUID.randomUUID().toString();

        Map<String, Object> primero = pedidoService.guardarPedidoDesdeApp(dto(uuid, 700000L));
        Map<String, Object> reintento = pedidoService.guardarPedidoDesdeApp(dto(uuid, 700000L));

        assertEquals(primero.get("uuidServer"), reintento.get("uuidServer"));
        assertEquals(primero.get("folio"), reintento.get("folio"));
        assertEquals(Boolean.TRUE, reintento.get("idempotent"));
    }

    @Test
    void uuidDiferenteCreaPedidoDiferente() {
        Map<String, Object> primero = pedidoService.guardarPedidoDesdeApp(dto(UUID.randomUUID().toString(), 710000L));
        Map<String, Object> segundo = pedidoService.guardarPedidoDesdeApp(dto(UUID.randomUUID().toString(), 710000L));

        assertNotEquals(primero.get("uuidServer"), segundo.get("uuidServer"));
        assertEquals(710000, primero.get("folio"));
        assertEquals(710001, segundo.get("folio"));
    }

    @Test
    void baseProtegeUuidLocalDuplicado() {
        DatosMinimos datos = datosMinimos();
        String uuid = UUID.randomUUID().toString();

        jdbcTemplate.update("""
                insert into public.pedidos
                    (cliente_id, agente_ventas_id, usuario_creo_id, serie_id, folio, fecha_creacion, plazo, total_solicitado, uuid_local)
                values (?, ?, ?, 10, 720000, current_date, 0, 649.60, ?)
                """, datos.clienteId(), datos.agenteId(), datos.usuarioId(), uuid);

        assertThrows(DataIntegrityViolationException.class, () ->
                jdbcTemplate.update("""
                        insert into public.pedidos
                            (cliente_id, agente_ventas_id, usuario_creo_id, serie_id, folio, fecha_creacion, plazo, total_solicitado, uuid_local)
                        values (?, ?, ?, 10, 720001, current_date, 0, 649.60, ?)
                        """, datos.clienteId(), datos.agenteId(), datos.usuarioId(), uuid)
        );
    }

    @Test
    void precisionMonetariaUsaBigDecimal() {
        BigDecimal base = new BigDecimal("560.00");
        BigDecimal impuesto = base.multiply(new BigDecimal("0.16")).setScale(2);
        BigDecimal total = base.add(impuesto).setScale(2);

        assertEquals(new BigDecimal("89.60"), impuesto);
        assertEquals(new BigDecimal("649.60"), total);
    }

    @Test
    void guardaDesgloseImpuestosJsonEnDetalle() {
        Map<String, Object> response = pedidoService.guardarPedidoDesdeApp(dto(UUID.randomUUID().toString(), 730000L));

        String impuestos = jdbcTemplate.queryForObject(
                "select impuestos::text from public.detalle_pedidos where pedido_id = ?",
                String.class,
                response.get("uuidServer")
        );

        assertNotNull(impuestos);
        assertTrue(impuestos.contains("idImpuesto"));
        assertTrue(impuestos.contains("importe"));
        assertTrue(impuestos.contains("89.60") || impuestos.contains("89.6"));
    }

    @Test
    void aceptaPedidoConExistenciaSuficiente() {
        Map<String, Object> response = pedidoService.guardarPedidoDesdeApp(dtoConCantidad(UUID.randomUUID().toString(), 740000L, new BigDecimal("1.00")));

        assertNotNull(response.get("uuidServer"));
    }

    @Test
    void aceptaPedidoConCantidadIgualALaExistencia() {
        Map<String, Object> response = pedidoService.guardarPedidoDesdeApp(dtoConCantidad(UUID.randomUUID().toString(), 740100L, new BigDecimal("18.00")));

        assertNotNull(response.get("uuidServer"));
    }

    @Test
    void aceptaPedidoConCantidadMayorALaExistencia() {
        Map<String, Object> response = pedidoService.guardarPedidoDesdeApp(dtoConCantidad(UUID.randomUUID().toString(), 740200L, new BigDecimal("20.00")));

        assertNotNull(response.get("uuidServer"));
    }

    @Test
    void aceptaPedidoConProductoSinExistencia() {
        DatosMinimos datos = datosMinimos();
        Integer productoSinExistencia = productoActivoSinExistencia();

        Map<String, Object> response = pedidoService.guardarPedidoDesdeApp(
                dto(UUID.randomUUID().toString(), 740300L, datos, productoSinExistencia, new BigDecimal("1.00"))
        );

        assertNotNull(response.get("uuidServer"));
    }

    @Test
    void rechazaCantidadCero() {
        PedidoConDetallesDTO dto = dtoConCantidad(UUID.randomUUID().toString(), 740400L, BigDecimal.ZERO);

        assertThrows(IllegalArgumentException.class, () -> pedidoService.guardarPedidoDesdeApp(dto));
    }

    @Test
    void rechazaCantidadNegativa() {
        PedidoConDetallesDTO dto = dtoConCantidad(UUID.randomUUID().toString(), 740500L, new BigDecimal("-1.00"));

        assertThrows(IllegalArgumentException.class, () -> pedidoService.guardarPedidoDesdeApp(dto));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void falloDeGuardadoNoConsumeSiguienteFolio() {
        Integer siguienteAntes = jdbcTemplate.queryForObject(
                "select siguiente_folio from public.series_folios where id_serie = 10",
                Integer.class
        );
        PedidoConDetallesDTO dto = dto(UUID.randomUUID().toString(), siguienteAntes.longValue());
        dto.getDetalles().get(0).setProductoId(-1L);

        assertThrows(IllegalArgumentException.class, () -> pedidoService.guardarPedidoDesdeApp(dto));

        Integer siguienteDespues = jdbcTemplate.queryForObject(
                "select siguiente_folio from public.series_folios where id_serie = 10",
                Integer.class
        );
        Long pedidosConUuid = jdbcTemplate.queryForObject(
                "select count(*) from public.pedidos where uuid_local = ?",
                Long.class,
                dto.getPedido().getUuidLocal()
        );

        assertEquals(siguienteAntes, siguienteDespues);
        assertEquals(0L, pedidosConUuid);
    }

    private PedidoConDetallesDTO dto(String uuidLocal, Long folio) {
        DatosMinimos datos = datosMinimos();
        return dto(uuidLocal, folio, datos, datos.productoId(), BigDecimal.ONE);
    }

    private PedidoConDetallesDTO dtoConCantidad(String uuidLocal, Long folio, BigDecimal cantidad) {
        DatosMinimos datos = datosMinimos();
        return dto(uuidLocal, folio, datos, datos.productoId(), cantidad);
    }

    private PedidoConDetallesDTO dto(String uuidLocal, Long folio, DatosMinimos datos, Integer productoId, BigDecimal cantidad) {
        PedidoDTO pedido = new PedidoDTO();
        pedido.setClienteId(datos.clienteId().longValue());
        pedido.setAgenteVentasId(datos.agenteId().longValue());
        pedido.setUsuarioCreoId(datos.usuarioId().longValue());
        pedido.setSerieId(10L);
        pedido.setFolio(folio);
        pedido.setFechaCreacion(OffsetDateTime.now().toString());
        pedido.setPlazo(0L);
        pedido.setTotalSolicitado(new BigDecimal("649.60"));
        pedido.setTotalAutorizado(BigDecimal.ZERO);
        pedido.setTotalFacturado(BigDecimal.ZERO);
        pedido.setUuidLocal(uuidLocal);

        DetallePedidoDTO detalle = new DetallePedidoDTO();
        detalle.setProductoId(productoId.longValue());
        detalle.setAlmacenId(datos.almacenId().longValue());
        detalle.setCantidadSolicitada(cantidad);
        detalle.setCantidadAutorizada(BigDecimal.ZERO);
        detalle.setCantidadReservada(BigDecimal.ZERO);
        detalle.setCantidadSurtida(BigDecimal.ZERO);
        detalle.setCantidadCancelada(BigDecimal.ZERO);
        detalle.setPrecioUnitario(new BigDecimal("560.00"));
        detalle.setDescuento(BigDecimal.ZERO);
        detalle.setTotalLinea(new BigDecimal("649.60"));
        detalle.setEstadoLinea("PENDIENTE");

        PedidoConDetallesDTO dto = new PedidoConDetallesDTO();
        dto.setPedido(pedido);
        dto.setDetalles(List.of(detalle));
        return dto;
    }

    private DatosMinimos datosMinimos() {
        return jdbcTemplate.queryForObject("""
                select c.id_cliente, a.id_agente, a.id_usuario, p.id_producto, e.almacen_id
                from public.clientes c
                cross join public.agentes a
                join public.existencias e on e.cantidad_disponible > 0
                join public.productos p on p.id_producto = e.producto_id
                where c.activo = true
                  and a.id_usuario is not null
                  and p.activo = true
                order by c.id_cliente, a.id_agente, p.id_producto
                limit 1
                """,
                (rs, rowNum) -> new DatosMinimos(
                        rs.getInt("id_cliente"),
                        rs.getInt("id_agente"),
                        rs.getInt("id_usuario"),
                        rs.getInt("id_producto"),
                        rs.getInt("almacen_id")
                )
        );
    }

    private Integer productoActivoSinExistencia() {
        return jdbcTemplate.queryForObject("""
                select p.id_producto
                from public.productos p
                where p.activo = true
                  and not exists (
                      select 1
                      from public.existencias e
                      where e.producto_id = p.id_producto
                  )
                order by p.id_producto
                limit 1
                """,
                Integer.class
        );
    }

    private record DatosMinimos(
            Integer clienteId,
            Integer agenteId,
            Integer usuarioId,
            Integer productoId,
            Integer almacenId
    ) {
    }
}
