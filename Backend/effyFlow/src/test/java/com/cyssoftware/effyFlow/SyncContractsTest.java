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
    @Test void usersAreAuthorizedFullSnapshotWithVersionAndNoPasswordFields() {
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class))).thenReturn(0L);
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            assertTrue(sql.contains("role IN ('AGENTE', 'VENDEDOR', 'ADMIN')"));
            assertTrue(sql.contains("r.name IN ('AGENTE', 'VENDEDOR', 'ADMIN', 'super_admin')"));
            assertTrue(sql.contains("credential_version AS \"credentialVersion\""));
            assertTrue(sql.contains("AS active")); assertFalse(sql.contains(":updatedAfter"));
            // Only projected fields leave the database; the inner SELECT is not serialized.
            String projection = sql.substring(0, sql.indexOf("FROM"));
            assertFalse(projection.contains("password")); assertFalse(projection.contains("remember_token"));
            return List.of();
        });
        new SyncService(jdbc).obtenerCatalogo("usuarios", java.time.LocalDateTime.now(), 0, 500);
    }
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
}
