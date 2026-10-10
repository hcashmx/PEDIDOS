package com.cyssoftware.effyFlow.security;

import com.cyssoftware.effyFlow.service.LoginService;
import com.cyssoftware.effyFlow.repository.UsuarioRepository;
import com.cyssoftware.effyFlow.entity.Usuario;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.authentication.BadCredentialsException;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LoginServiceSecurityTest {
    final JwtTokenProvider tokens = new JwtTokenProvider(JwtTokenProviderTest.SECRET, 15, 7);
    final UsuarioRepository repo = mock(UsuarioRepository.class);
    final PasswordEncoder encoder = mock(PasswordEncoder.class);
    final com.cyssoftware.effyFlow.service.pedido.SyncService authorization = mock(com.cyssoftware.effyFlow.service.pedido.SyncService.class);
    final LoginService service = new LoginService(repo, tokens, encoder, authorization);
    Usuario user() {
        var u = new Usuario(); u.setEmail("user"); u.setRoles("AGENTE"); u.setActive(true); u.setStatus("ACTIVO"); return u;
    }
    @Test void accessCannotRefreshAndNoDatabaseLookupOccurs() {
        assertThrows(BadCredentialsException.class, () -> service.refresh(tokens.generateAccessToken("user")));
        verifyNoInteractions(repo);
    }
    @Test void refreshRevalidatesUserAndKeepsOriginalRefreshExpiry() {
        var u = user(); when(repo.findByEmail("user")).thenReturn(Optional.of(u));
        String refresh = tokens.generateRefreshToken("user");
        var response = service.refresh(refresh);
        assertEquals(refresh, response.get("refreshToken"));
        assertTrue(tokens.validateAccessToken((String) response.get("accessToken")));
        verify(repo).findByEmail("user");
    }
    @Test void inactiveDeletedAndDisabledUsersCannotRefresh() {
        String refresh = tokens.generateRefreshToken("user"); var u = user();
        when(repo.findByEmail("user")).thenReturn(Optional.of(u));
        u.setActive(false); assertThrows(BadCredentialsException.class, () -> service.refresh(refresh));
        u.setActive(true); u.setStatus("INACTIVO"); assertThrows(BadCredentialsException.class, () -> service.refresh(refresh));
        when(repo.findByEmail("user")).thenReturn(Optional.empty());
        assertThrows(BadCredentialsException.class, () -> service.refresh(refresh));
    }
    @Test void loginRejectsInactiveStatus() {
        var u = user(); u.setStatus("INACTIVO"); when(repo.findByEmail("user")).thenReturn(Optional.of(u));
        when(encoder.matches("password", u.getPassword())).thenReturn(true);
        assertThrows(BadCredentialsException.class, () -> service.login("user", "password"));
    }
    @Test void vendedorCanLoginAndRefreshWithoutAdministrativePermissions() {
        var u = user(); u.setRoles("VENDEDOR");
        when(repo.findByEmail("user")).thenReturn(Optional.of(u));
        when(encoder.matches("password", u.getPassword())).thenReturn(true);
        var response = service.login("user", "password");
        assertEquals("VENDEDOR", response.get("roles"));
        assertTrue(tokens.validateAccessToken((String) response.get("accessToken")));
        assertTrue(tokens.validateAccessToken((String) service.refresh(
                (String) response.get("refreshToken")).get("accessToken")));
        assertEquals(Rol.AGENTE.getPermisos(), Rol.VENDEDOR.getPermisos());
        assertFalse(Rol.VENDEDOR.getPermisos().contains(Permiso.VER_PEDIDOS_OTROS));
        assertFalse(Rol.VENDEDOR.getPermisos().contains(Permiso.MODIFICAR_PLAZO_CLIENTE));
    }
    @Test void usernameEnrollmentReturnsOnlySafeCredentialMetadata() {
        var u = user(); u.setId(5L); u.setUsername("seller"); u.setRoles("VENDEDOR"); u.setCredentialVersion(8L);
        when(repo.findByUsername("seller")).thenReturn(Optional.of(u));
        when(encoder.matches("password", u.getPassword())).thenReturn(true);
        when(authorization.usuarioAutorizado("user")).thenReturn(true);
        var response = service.login("seller", "password");
        assertEquals("seller", response.get("username")); assertEquals(8L, response.get("credentialVersion"));
        assertEquals(true, response.get("active")); assertEquals(true, response.get("appAuthorized"));
        assertFalse(response.containsKey("password")); assertFalse(response.containsKey("verifier"));
        verify(repo, never()).findByEmail("seller");
    }
    @Test void vendedorStillRejectsWrongPasswordAndDisabledAuthorization() {
        var u = user(); u.setRoles("VENDEDOR");
        when(repo.findByEmail("user")).thenReturn(Optional.of(u));
        assertThrows(BadCredentialsException.class, () -> service.login("user", "wrong"));
        when(encoder.matches("password", u.getPassword())).thenReturn(true);
        u.setActive(false);
        assertThrows(BadCredentialsException.class, () -> service.login("user", "password"));
        assertThrows(BadCredentialsException.class, () -> service.refresh(tokens.generateRefreshToken("user")));
        u.setActive(true); u.setStatus("INACTIVO");
        assertThrows(BadCredentialsException.class, () -> service.login("user", "password"));
    }
}
