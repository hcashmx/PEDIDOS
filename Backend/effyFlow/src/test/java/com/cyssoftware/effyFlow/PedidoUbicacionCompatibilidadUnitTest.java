package com.cyssoftware.effyFlow;

import com.cyssoftware.effyFlow.dto.PedidoConDetallesDTO;
import com.cyssoftware.effyFlow.dto.PedidoDTO;
import com.cyssoftware.effyFlow.entity.Cliente;
import com.cyssoftware.effyFlow.entity.ClienteUbicacion;
import com.cyssoftware.effyFlow.entity.Pedido;
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
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PedidoUbicacionCompatibilidadUnitTest {

    @Test
    void pedidoNuevoAppPersisteUbicacionYSnapshotCanonico() {
        Fixture fixture = fixture();
        ClienteUbicacion ubicacion = new ClienteUbicacion();
        ubicacion.setIdClienteUbicacion(15);
        ubicacion.setIdCliente(1);
        ubicacion.setAlias("Sucursal norte");
        ubicacion.setContactoPersona("Ana");
        ubicacion.setContactoTelefono("55501020");
        ubicacion.setDireccion("Av. Central");
        ubicacion.setNoExterior("100");
        ubicacion.setNoInterior("2");
        ubicacion.setColonia("Centro");
        ubicacion.setLocalidad("Ciudad");
        ubicacion.setMunicipio("Municipio");
        ubicacion.setEstado("Estado");
        ubicacion.setPais("MX");
        ubicacion.setCodigoPostal("12345");
        ubicacion.setReferencia("Frente al parque");
        when(fixture.ubicaciones.findById(15)).thenReturn(Optional.of(ubicacion));

        fixture.service.guardarPedidoDesdeApp(dto(15L, fixture.uuidLocal));

        Pedido guardado = fixture.pedidoGuardado();
        assertEquals("APP", guardado.getOrigen());
        assertEquals(15, guardado.getUbicacionId());
        assertTrue(guardado.getDireccionEntregaSnapshot().contains("\"alias\":\"Sucursal norte\""));
        assertTrue(guardado.getDireccionEntregaSnapshot().contains("\"contacto_persona\":\"Ana\""));
        assertTrue(guardado.getDireccionEntregaSnapshot().contains("\"no_exterior\":\"100\""));
        assertTrue(guardado.getDireccionEntregaSnapshot().contains("\"referencia\":\"Frente al parque\""));
        assertEquals(guardado.getFechaCaptura().toLocalDate(), guardado.getFechaCreacion());
        assertEquals(guardado.getUuidLocal(), fixture.uuidLocal);
    }

    @Test
    void rechazaUbicacionDeOtroCliente() {
        Fixture fixture = fixture();
        ClienteUbicacion ubicacion = new ClienteUbicacion();
        ubicacion.setIdClienteUbicacion(15);
        ubicacion.setIdCliente(2);
        when(fixture.ubicaciones.findById(15)).thenReturn(Optional.of(ubicacion));

        assertThrows(IllegalArgumentException.class, () -> fixture.service.guardarPedidoDesdeApp(dto(15L, fixture.uuidLocal)));
        verify(fixture.pedidos, never()).saveAndFlush(any(Pedido.class));
    }

    @Test
    void sinUbicacionUsaSnapshotDeDireccionPrincipal() {
        Fixture fixture = fixture();
        fixture.cliente.setDireccion("Calle principal");
        fixture.cliente.setColonia("Colonia principal");
        fixture.cliente.setCodigoPostal("54321");

        fixture.service.guardarPedidoDesdeApp(dto(null, fixture.uuidLocal));

        Pedido guardado = fixture.pedidoGuardado();
        assertNull(guardado.getUbicacionId());
        assertTrue(guardado.getDireccionEntregaSnapshot().contains("\"direccion\":\"Calle principal\""));
        assertTrue(guardado.getDireccionEntregaSnapshot().contains("\"colonia\":\"Colonia principal\""));
        assertTrue(guardado.getDireccionEntregaSnapshot().contains("\"codigo_postal\":\"54321\""));
    }

    @Test
    void reintentoUuidLocalNoSobrescribeUbicacionNiSnapshotExistentes() {
        Fixture fixture = fixture();
        Pedido existente = new Pedido();
        existente.setIdPedido(800L);
        existente.setSerieId(10);
        existente.setFolio(701);
        existente.setUuidLocal(fixture.uuidLocal);
        existente.setUbicacionId(44);
        existente.setDireccionEntregaSnapshot("{\"direccion\":\"Histórica\"}");
        when(fixture.pedidos.findByUuidLocal(fixture.uuidLocal)).thenReturn(Optional.of(existente));

        var respuesta = fixture.service.guardarPedidoDesdeApp(dto(15L, fixture.uuidLocal));

        assertEquals(true, respuesta.get("idempotent"));
        assertEquals(44, existente.getUbicacionId());
        assertEquals("{\"direccion\":\"Histórica\"}", existente.getDireccionEntregaSnapshot());
        verify(fixture.pedidos, never()).saveAndFlush(any(Pedido.class));
        verify(fixture.ubicaciones, never()).findById(anyInt());
    }

    private static PedidoConDetallesDTO dto(Long ubicacionId, String uuidLocal) {
        PedidoDTO pedido = new PedidoDTO();
        pedido.setClienteId(1L);
        pedido.setAgenteVentasId(1L);
        pedido.setUsuarioCreoId(1L);
        pedido.setSerieId(10L);
        pedido.setFolio(700L);
        pedido.setPlazo(0L);
        pedido.setUuidLocal(uuidLocal);
        pedido.setUbicacionId(ubicacionId);
        PedidoConDetallesDTO dto = new PedidoConDetallesDTO();
        dto.setPedido(pedido);
        dto.setDetalles(List.of());
        return dto;
    }

    private static Fixture fixture() {
        PedidoRepository pedidos = mock(PedidoRepository.class);
        DetallePedidoRepository detalles = mock(DetallePedidoRepository.class);
        ClienteRepository clientes = mock(ClienteRepository.class);
        ClienteUbicacionRepository ubicaciones = mock(ClienteUbicacionRepository.class);
        ProductoRepository productos = mock(ProductoRepository.class);
        SeriesFoliosRepository series = mock(SeriesFoliosRepository.class);
        AgenteRepository agentes = mock(AgenteRepository.class);
        Cliente cliente = new Cliente();
        cliente.setIdCliente(1);
        cliente.setActivo(true);
        SeriesFolios serie = new SeriesFolios();
        serie.setIdSerie(10);
        serie.setActiva(true);
        serie.setEsConsecutiva(false);
        String uuid = UUID.randomUUID().toString();
        when(pedidos.findByUuidLocal(anyString())).thenReturn(Optional.empty());
        when(pedidos.existsBySerieIdAndFolio(anyInt(), anyInt())).thenReturn(false);
        when(clientes.findById(1)).thenReturn(Optional.of(cliente));
        when(series.findByIdForUpdate(10)).thenReturn(Optional.of(serie));
        when(agentes.existsById(1)).thenReturn(true);
        when(pedidos.saveAndFlush(any(Pedido.class))).thenAnswer(invocation -> {
            Pedido pedido = invocation.getArgument(0);
            pedido.setIdPedido(900L);
            return pedido;
        });
        PedidoService service = new PedidoService(pedidos, detalles, clientes, ubicaciones, productos, series, agentes,
                mock(BitacoraOtRepository.class), new NoopTransactionManager(), mock(JdbcTemplate.class));
        return new Fixture(service, pedidos, ubicaciones, cliente, uuid);
    }

    private record Fixture(PedidoService service, PedidoRepository pedidos, ClienteUbicacionRepository ubicaciones,
                           Cliente cliente, String uuidLocal) {
        Pedido pedidoGuardado() {
            ArgumentCaptor<Pedido> captor = ArgumentCaptor.forClass(Pedido.class);
            verify(pedidos).saveAndFlush(captor.capture());
            return captor.getValue();
        }
    }

    private static final class NoopTransactionManager extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }
}
