package com.cyssoftware.effyFlow.security;

import com.cyssoftware.effyFlow.controller.*;
import com.cyssoftware.effyFlow.exception.GlobalExceptionHandler;
import com.cyssoftware.effyFlow.controller.pedido.*;
import com.cyssoftware.effyFlow.config.WebConfig;
import com.cyssoftware.effyFlow.service.LoginService;
import com.cyssoftware.effyFlow.service.pedido.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(properties = {"app.jwt.secret=test-only-secret-0123456789-abcdef-0123456789", "logging.level.root=WARN"})
@ContextConfiguration(classes = {SecurityConfig.class, WebConfig.class, JwtTokenProvider.class,
        JwtAuthenticationFilter.class, LoginController.class, HealthController.class,
        PedidoController.class, SyncController.class, GlobalExceptionHandler.class})
class SyncSecurityTest {
    @Autowired MockMvc mvc;
    @Autowired JwtTokenProvider tokens;
    @MockitoBean CustomUserDetailsService users;
    @MockitoBean LoginService login;
    @MockitoBean PedidoService pedidos;
    @MockitoBean SyncService sync;
    static final String VALID = "{\"pedido\":{\"uuid_local\":\"12345678-1234-1234-1234-123456789abc\"},\"detalles\":[]}";
    String access() {
        when(users.loadUserByUsername("user")).thenReturn(User.withUsername("user").password("unused")
                .authorities("SINCRONIZAR_DATOS").build());
        return tokens.generateAccessToken("user");
    }
    @Test void publicEndpointsReachControllers() throws Exception {
        when(login.login(any(), any())).thenReturn(Map.of("accessToken", "test"));
        when(login.refresh(any())).thenReturn(Map.of("accessToken", "test"));
        mvc.perform(post("/api/auth/login").contentType("application/json").content("{}")) .andExpect(status().isOk());
        mvc.perform(post("/api/auth/refresh").contentType("application/json").content("{}")) .andExpect(status().isOk());
        mvc.perform(get("/api/health")).andExpect(status().isOk()).andExpect(content().json("{\"status\":\"UP\"}"));
        verifyNoInteractions(users, pedidos, sync);
    }
    @Test void healthDoesNotReadDatabaseEvenWithBearer() throws Exception {
        mvc.perform(get("/api/health").header("Authorization", "Bearer " + tokens.generateAccessToken("user")))
                .andExpect(status().isOk());
        verifyNoInteractions(users, sync, pedidos);
    }
    @Test void authenticationAndAuthorizationErrorsUse401And403() throws Exception {
        doThrow(new org.springframework.security.authentication.BadCredentialsException("internal-detail")).when(login).refresh(any());
        mvc.perform(post("/api/auth/refresh").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        String token = access();
        doThrow(new org.springframework.security.access.AccessDeniedException("internal-detail")).when(pedidos).guardarPedidoDesdeApp(any());
        mvc.perform(post("/api/sync/pedidos").header("Authorization", "Bearer " + token).contentType("application/json").content(VALID))
                .andExpect(status().isForbidden()).andExpect(content().json("{\"error\":\"Acceso denegado\"}"));
    }
    @Test void syncRequiresAccessToken() throws Exception {
        mvc.perform(get("/api/sync/catalogos/clientes")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/sync/pedidos").contentType("application/json").content(VALID)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/sync/catalogos/clientes").header("Authorization", "Bearer " + tokens.generateRefreshToken("user")))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(pedidos, sync, users);
    }
    @Test void accessReachesControllersWithRealAuthorities() throws Exception {
        String token = access();
        when(sync.obtenerCatalogo(any(), any(), anyInt(), anyInt())).thenAnswer(invocation -> {
            assertTrue(SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("SINCRONIZAR_DATOS")));
            return Map.of("data", java.util.List.of());
        });
        when(pedidos.guardarPedidoDesdeApp(any())).thenReturn(Map.of("estadoId", 3));
        mvc.perform(get("/api/sync/catalogos/clientes").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mvc.perform(post("/api/sync/pedidos").header("Authorization", "Bearer " + token)
                .contentType("application/json").content(VALID)).andExpect(status().isOk()).andExpect(jsonPath("$.estadoId").value(3));
    }
    @Test void invalidUuidRejectedBeforeService() throws Exception {
        String token = access();
        for (String value : new String[]{"null", "\"\"", "\" \"", "\"invalid\"", "\"1-1-1-1-1\""}) {
            mvc.perform(post("/api/sync/pedidos").header("Authorization", "Bearer " + token)
                    .contentType("application/json").content("{\"pedido\":{\"uuid_local\":" + value + "}}"))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(pedidos);
    }
    @Test void disabledUserRejected() throws Exception {
        when(users.loadUserByUsername("user")).thenReturn(User.withUsername("user").password("unused").authorities("SINCRONIZAR_DATOS").disabled(true).build());
        mvc.perform(get("/api/sync/catalogos/clientes").header("Authorization", "Bearer " + tokens.generateAccessToken("user")))
                .andExpect(status().isUnauthorized());
    }
    @Test void errorsAreSanitized() throws Exception {
        String token = access();
        when(pedidos.guardarPedidoDesdeApp(any())).thenThrow(new RuntimeException("SQL internal-host password token"));
        mvc.perform(post("/api/sync/pedidos").header("Authorization", "Bearer " + token).contentType("application/json").content(VALID))
                .andExpect(status().isInternalServerError()).andExpect(content().json("{\"error\":\"Error interno al procesar la solicitud\"}"));
        doThrow(new org.springframework.dao.DataIntegrityViolationException("SQL secret")).when(pedidos).guardarPedidoDesdeApp(any());
        mvc.perform(post("/api/sync/pedidos").header("Authorization", "Bearer " + token).contentType("application/json").content(VALID))
                .andExpect(status().isConflict());
    }
    @Test void corsRejectsUnknownWebOriginButAllowsDesktop() throws Exception {
        mvc.perform(get("/api/health").header("Origin", "https://untrusted.invalid")).andExpect(status().isForbidden());
        mvc.perform(get("/api/health")).andExpect(status().isOk());
    }
}
