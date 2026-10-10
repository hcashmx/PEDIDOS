package com.cyssoftware.effyFlow.service.cotizaciones;

import com.cyssoftware.effyFlow.service.pedido.SyncService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IdentidadComercialSyncTest {
    @Test void soloResuelveUsuarioActivoConPermisoYVinculoUnico() {
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class)))
                .thenAnswer(call -> {
                    String sql = call.getArgument(0);
                    assertTrue(sql.contains("JOIN public.agentes a ON a.id_usuario = u.id"));
                    assertTrue(sql.contains("p.name='cotizaciones.crear'"));
                    assertTrue(sql.contains("u.active = true"));
                    assertTrue(sql.contains("upper(u.role) IN ('AGENTE','VENDEDOR')"));
                    return List.of(Map.of("usuario_id", 123L, "agente_id", 45));
                });
        var identity = new SyncService(jdbc).identidadCotizaciones("seller@example.test");
        assertEquals(new SyncService.IdentidadComercial(123L, 45), identity);
    }

    @Test void usuarioInactivoSinPermisoOSinAgenteUnicoNoObtieneIdentidad() {
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class))).thenReturn(List.of());
        assertNull(new SyncService(jdbc).identidadCotizaciones("revoked@example.test"));
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class)))
                .thenReturn(List.of(Map.of("usuario_id", 1L, "agente_id", 4), Map.of("usuario_id", 1L, "agente_id", 5)));
        assertNull(new SyncService(jdbc).identidadCotizaciones("duplicate@example.test"));
    }
}
