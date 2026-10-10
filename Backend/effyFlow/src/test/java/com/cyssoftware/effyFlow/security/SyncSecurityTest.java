package com.cyssoftware.effyFlow.security;

import com.cyssoftware.effyFlow.controller.*;
import com.cyssoftware.effyFlow.exception.GlobalExceptionHandler;
import com.cyssoftware.effyFlow.controller.pedido.*;
import com.cyssoftware.effyFlow.controller.cotizaciones.CotizacionSyncController;
import com.cyssoftware.effyFlow.config.WebConfig;
import com.cyssoftware.effyFlow.service.LoginService;
import com.cyssoftware.effyFlow.service.pedido.*;
import com.cyssoftware.effyFlow.service.cotizaciones.*;
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
        PedidoController.class, PedidoEstadosController.class, SyncController.class, CotizacionSyncController.class,
        GlobalExceptionHandler.class})
class SyncSecurityTest {
    @Autowired MockMvc mvc;
    @Autowired JwtTokenProvider tokens;
    @MockitoBean CustomUserDetailsService users;
    @MockitoBean LoginService login;
    @MockitoBean PedidoService pedidos;
    @MockitoBean SyncService sync;
    @MockitoBean PedidoEstadosService estados;
    @MockitoBean RevisionComercialSyncService revisiones;
    @MockitoBean CotizacionIdentityAssertionService aserciones;
    static final String VALID = "{\"pedido\":{\"uuid_local\":\"12345678-1234-1234-1234-123456789abc\"},\"detalles\":[]}";
    String access() {
        when(users.loadUserByUsername("user")).thenReturn(User.withUsername("user").password("unused")
                .authorities("SINCRONIZAR_DATOS").build());
        return tokens.generateAccessToken("user");
    }
    @Test void vendedorCanDownloadUserCatalogWithItsOwnIdentity() throws Exception {
        var vendedor = new com.cyssoftware.effyFlow.entity.Usuario();
        vendedor.setEmail("seller"); vendedor.setRoles("VENDEDOR"); vendedor.setActive(true);
        when(users.loadUserByUsername("seller")).thenReturn(new CustomUserDetails(vendedor));
        when(sync.usuarioAutorizado("seller")).thenReturn(true);
        when(sync.obtenerCatalogo(eq("usuarios"), isNull(), eq(0), eq(500))).thenAnswer(invocation -> {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            assertEquals("seller", authentication.getName());
            assertTrue(authentication.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("SINCRONIZAR_DATOS")));
            assertFalse(authentication.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("VER_PEDIDOS_OTROS")));
            return Map.of("data", java.util.List.of());
        });
        mvc.perform(get("/api/sync/catalogos/usuarios").header("Authorization",
                        "Bearer " + tokens.generateAccessToken("seller")))
                .andExpect(status().isOk());
    }
    @Test void userWithoutSyncAuthorizationCannotProvisionCatalog() throws Exception {
        when(users.loadUserByUsername("collector")).thenReturn(User.withUsername("collector").password("unused")
                .authorities("VER_MODULO_PEDIDOS").build());
        mvc.perform(get("/api/sync/catalogos/usuarios").header("Authorization", "Bearer " + tokens.generateAccessToken("collector")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(sync);
    }
    @Test void revisionCompleteRequiresRemoteSyncAuthorization() throws Exception {
        mvc.perform(get("/api/sync/cotizaciones/revisiones-comerciales/actual"))
                .andExpect(status().isUnauthorized());
        String token = access();
        when(sync.usuarioAutorizado("user")).thenReturn(false);
        mvc.perform(get("/api/sync/cotizaciones/revisiones-comerciales/actual")
                .header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        verifyNoInteractions(revisiones);
    }
    @Test void revisionReturnsOneCompleteVerifiedPackage() throws Exception {
        String token = access(); when(sync.usuarioAutorizado("user")).thenReturn(true);
        var id = java.util.UUID.randomUUID();
        var datos = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("version_esquema", 3);
        datos.putArray("productos");
        when(revisiones.actual()).thenReturn(new RevisionComercialPaquete(id, 3,
                java.time.OffsetDateTime.parse("2026-10-10T12:00:00Z"), "sha256:" + "a".repeat(64),
                "a".repeat(64), datos));
        mvc.perform(get("/api/sync/cotizaciones/revisiones-comerciales/actual")
                .header("Authorization", "Bearer " + token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.versionEsquema").value(3))
                .andExpect(jsonPath("$.datosComerciales.productos").isArray());
        verify(revisiones).actual();
    }
    @Test void asercionEndpointOnlyForwardsAuthenticatedRemotePrincipal() throws Exception {
        String token = access();
        var uuid = java.util.UUID.randomUUID(); var revision = java.util.UUID.randomUUID();
        when(aserciones.emitir(eq("user"), any())).thenReturn(new CotizacionIdentityAssertionService.Asercion(
                "signed-assertion", "Bearer", 3L, 9, "desktop-1", uuid, revision, "a".repeat(64), "b".repeat(64),
                "jti-1", java.time.Instant.now(), java.time.Instant.now().plusSeconds(60)));
        String payload = "{\"uuidCotizacion\":\"" + uuid + "\",\"revisionComercialId\":\"" + revision
                + "\",\"dispositivoId\":\"desktop-1\",\"payload\":{\"uuid\":\"" + uuid
                + "\",\"revisionComercialId\":\"" + revision + "\",\"oferta\":{\"agenteVentasId\":9}}}";
        mvc.perform(post("/api/sync/cotizaciones/aserciones-identidad").header("Authorization", "Bearer " + token)
                .contentType("application/json").content(payload)).andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("signed-assertion"))
                .andExpect(jsonPath("$.agenteErpId").value(9));
        mvc.perform(post("/api/sync/cotizaciones/aserciones-identidad")
                .contentType("application/json").content(payload)).andExpect(status().isUnauthorized());
    }
    @Test void legacySellerRoleDoesNotAuthorizeUnrelatedErpProfile() throws Exception {
        when(users.loadUserByUsername("doctor")).thenReturn(User.withUsername("doctor").password("unused")
                .authorities("SINCRONIZAR_DATOS").build());
        when(sync.usuarioAutorizado("doctor")).thenReturn(false);
        mvc.perform(get("/api/sync/catalogos/usuarios").header("Authorization", "Bearer " + tokens.generateAccessToken("doctor")))
                .andExpect(status().isForbidden());
        verify(sync, never()).obtenerCatalogo(any(),any(),anyInt(),anyInt());
    }
    @Test void stateFeedRequiresAccessBearer() throws Exception {
        mvc.perform(get("/api/sync/pedidos/estados")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/sync/pedidos/estados").header("Authorization", "Bearer " + tokens.generateRefreshToken("user")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/other")).andExpect(status().isUnauthorized());
        verifyNoInteractions(estados);
    }
    @Test void stateFeedAcceptsAccessAndReturnsMinimalEventContract() throws Exception {
        when(estados.obtener(10, null, 500)).thenReturn(new com.cyssoftware.effyFlow.dto.PedidoEstadosBatch(
                11, 11, false, java.util.List.of(new com.cyssoftware.effyFlow.dto.PedidoEstadosBatch.Evento(
                11, 90, null, 2, "TEST_STATE_2", "2026-10-01 12:00:00+00"))));
        mvc.perform(get("/api/sync/pedidos/estados").param("afterId", "10").header("Authorization", "Bearer " + access()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.lastId").value(11))
                .andExpect(jsonPath("$.data[0].uuidLocal").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data[0].estadoId").value(2))
                .andExpect(jsonPath("$.data[0].estado").value("TEST_STATE_2"));
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
