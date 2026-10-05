package com.cyssoftware.effyFlow;

import com.cyssoftware.effyFlow.service.pedido.*;
import com.cyssoftware.effyFlow.dto.*;
import com.cyssoftware.effyFlow.entity.Pedido;
import com.cyssoftware.effyFlow.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SyncContractsTest {
    @Test void corsAllowsOnlyConfiguredOrigins() {
        var config = new com.cyssoftware.effyFlow.config.WebConfig();
        var request = new org.springframework.mock.web.MockHttpServletRequest("GET", "/api/health");
        var source = config.corsConfigurationSource(" https://one.invalid,https://two.invalid ");
        assertEquals(List.of("https://one.invalid", "https://two.invalid"), source.getCorsConfiguration(request).getAllowedOrigins());
        assertTrue(config.corsConfigurationSource("").getCorsConfiguration(request).getAllowedOrigins().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> config.corsConfigurationSource("*"));
    }
    @Test void paginationClampsSizeAndAvoidsOffsetOverflow() {
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class))).thenReturn(0L);
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class))).thenAnswer(invocation -> {
            MapSqlParameterSource params = invocation.getArgument(1);
            assertEquals(500, params.getValue("limit"));
            assertEquals((long) Integer.MAX_VALUE * 500, params.getValue("offset"));
            return List.of();
        });
        new SyncService(jdbc).obtenerCatalogo("clientes", null, Integer.MAX_VALUE, 999);
        reset(jdbc);
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class))).thenAnswer(invocation -> {
            MapSqlParameterSource params = invocation.getArgument(1);
            assertEquals(1, params.getValue("limit"));
            assertEquals(0L, params.getValue("offset"));
            return List.of();
        });
        new SyncService(jdbc).obtenerCatalogo("clientes", null, -1, 0);
    }
    @Test void idempotentResponseUsesCurrentStateAndNeverInserts() {
        var repo = mock(PedidoRepository.class);
        var jdbc = mock(JdbcTemplate.class);
        var tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        String uuid = UUID.randomUUID().toString();
        var pedido = new Pedido();
        pedido.setIdPedido(42L); pedido.setEstadoId(1); pedido.setUuidLocal(uuid); pedido.setSerieId(10); pedido.setFolio(20);
        when(repo.findByUuidLocal(uuid)).thenReturn(Optional.of(pedido));
        // Fixture only: names deliberately do not assume the live catalog values.
        String stateSql = "SELECT estado FROM public.pedido_estados WHERE id_estado = ?";
        when(jdbc.queryForObject(stateSql, String.class, 1)).thenReturn("NOMBRE_CATALOGO_ID_1");
        when(jdbc.queryForObject(stateSql, String.class, 2)).thenReturn("NOMBRE_CATALOGO_ID_2");
        var service = new PedidoService(repo, mock(DetallePedidoRepository.class), mock(ClienteRepository.class),
                mock(ClienteUbicacionRepository.class), mock(ProductoRepository.class), mock(SeriesFoliosRepository.class),
                mock(AgenteRepository.class), mock(BitacoraOtRepository.class), tx, jdbc);
        var dto = new PedidoConDetallesDTO(); var header = new PedidoDTO(); header.setUuidLocal(uuid); dto.setPedido(header);
        var response = service.guardarPedidoDesdeApp(dto);
        assertEquals(1, response.get("estadoId"));
        assertEquals("NOMBRE_CATALOGO_ID_1", response.get("estado"));
        verify(jdbc).queryForObject(stateSql, String.class, 1);
        // The same UUID now resolves to the persisted order with a changed state.
        pedido.setEstadoId(2);
        var retry = service.guardarPedidoDesdeApp(dto);
        assertEquals(2, retry.get("estadoId"));
        assertEquals("NOMBRE_CATALOGO_ID_2", retry.get("estado"));
        assertEquals(true, retry.get("idempotent"));
        verify(jdbc).queryForObject(stateSql, String.class, 2);
        assertEquals(true, response.get("idempotent")); assertEquals(42L, response.get("uuidServer"));
        assertEquals(uuid, response.get("uuidLocal"));
        verify(repo, never()).saveAndFlush(any());
    }
}
