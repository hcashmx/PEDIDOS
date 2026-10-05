package com.cyssoftware.effyFlow;

import com.cyssoftware.effyFlow.dto.PedidoEstadosBatch.Evento;
import com.cyssoftware.effyFlow.service.pedido.PedidoEstadosService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.*;
import java.util.*;
import java.sql.ResultSet;
import java.util.stream.LongStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PedidoEstadosServiceTest {
    @Test void defaultCursorFiltersOrdersAndMapsMatchingStateIncludingNullUuid() throws Exception {
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class))).thenReturn(20L);
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), org.mockito.ArgumentMatchers.<RowMapper<Evento>>any()))
                .thenAnswer(call -> {
                    String sql = call.getArgument(0);
                    MapSqlParameterSource params = call.getArgument(1);
                    assertTrue(sql.contains("h.id_historial > :afterId"));
                    assertTrue(sql.contains("h.id_historial <= :untilId"));
                    assertTrue(sql.contains("ORDER BY h.id_historial ASC"));
                    assertTrue(sql.contains("e.id_estado = h.estado_nuevo"));
                    assertEquals(0L, params.getValue("afterId"));
                    assertEquals(20L, params.getValue("untilId"));
                    RowMapper<Evento> mapper = call.getArgument(2);
                    var rs = mock(ResultSet.class);
                    when(rs.getLong("id_historial")).thenReturn(20L);
                    when(rs.getLong("pedido_id")).thenReturn(80L);
                    when(rs.getInt("estado_id")).thenReturn(2);
                    when(rs.getString("estado")).thenReturn("TEST_STATE_2");
                    when(rs.getString("fecha_cambio")).thenReturn("2026-10-01 12:00:00+00");
                    return List.of(mapper.mapRow(rs, 0));
                });
        var batch = new PedidoEstadosService(jdbc).obtener(0, null, 500);
        assertEquals(20, batch.lastId()); assertFalse(batch.hasMore());
        assertNull(batch.data().getFirst().uuidLocal());
        assertEquals(2, batch.data().getFirst().estadoId());
        assertEquals("TEST_STATE_2", batch.data().getFirst().estado());
    }
    @Test void capsAt500AndReturnsAscendingCursorWithoutDroppingLookahead() {
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        var rows = LongStream.rangeClosed(101, 601).mapToObj(id -> new Evento(id, 80, "uuid", 2, "TEST_STATE_2", "date")).toList();
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), org.mockito.ArgumentMatchers.<RowMapper<Evento>>any()))
                .thenAnswer(call -> {
                    MapSqlParameterSource params = call.getArgument(1);
                    assertEquals(100L, params.getValue("afterId"));
                    assertEquals(700L, params.getValue("untilId"));
                    assertEquals(501, params.getValue("limit"));
                    return rows;
                });
        var batch = new PedidoEstadosService(jdbc).obtener(100, 700L, Integer.MAX_VALUE);
        assertEquals(500, batch.data().size()); assertEquals(600, batch.lastId()); assertTrue(batch.hasMore());
        assertEquals("uuid", batch.data().getFirst().uuidLocal());
        for (int i = 1; i < batch.data().size(); i++) assertTrue(batch.data().get(i).historialId() > batch.data().get(i-1).historialId());
    }
    @Test void emptyPagePreservesCursorAndMinimumSizeIsOne() {
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), org.mockito.ArgumentMatchers.<RowMapper<Evento>>any()))
                .thenAnswer(call -> {
                    MapSqlParameterSource params = call.getArgument(1);
                    assertEquals(2, params.getValue("limit"));
                    return List.of();
                });
        var batch = new PedidoEstadosService(jdbc).obtener(100, 100L, 0);
        assertEquals(100, batch.lastId()); assertFalse(batch.hasMore());
    }
    @Test void rejectsInvalidCursorBeforeJdbc() {
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        assertThrows(IllegalArgumentException.class, () -> new PedidoEstadosService(jdbc).obtener(-1, null, 500));
        assertThrows(IllegalArgumentException.class, () -> new PedidoEstadosService(jdbc).obtener(100, 99L, 500));
        verifyNoInteractions(jdbc);
    }
}
