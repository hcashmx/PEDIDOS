package com.cyssoftware.effyFlow.service;

import com.cyssoftware.effyFlow.repository.UsuarioRepository;
import com.cyssoftware.effyFlow.security.CustomUserDetails;
import com.cyssoftware.effyFlow.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class LoginService {
    private final UsuarioRepository usuarioRepo;
    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordEncoder passwordEncoder;
    public Map<String, Object> login(String email, String password) {
        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            throw new BadCredentialsException("Credenciales inválidas");
        }
        var usuario = usuarioRepo.findByEmail(email)
                .orElseThrow(() -> new BadCredentialsException("Credenciales inválidas"));
        if (!passwordEncoder.matches(password, usuario.getPassword()) || !new CustomUserDetails(usuario).isEnabled()) {
            throw new BadCredentialsException("Credenciales inválidas");
        }
        Map<String, Object> response = new HashMap<>();
        response.put("usuarioId", usuario.getId());
        response.put("nombre", usuario.getNombre());
        response.put("roles", usuario.getRoles());
        response.put("accessToken", jwtTokenProvider.generateAccessToken(usuario.getEmail()));
        response.put("refreshToken", jwtTokenProvider.generateRefreshToken(usuario.getEmail()));
        response.put("mensaje", "Inicio de sesión exitoso");
        return response;
    }
    public Map<String, Object> refresh(String refreshToken) {
        if (!jwtTokenProvider.validateRefreshToken(refreshToken)) {
            throw new BadCredentialsException("Token inválido");
        }
        var usuario = usuarioRepo.findByEmail(jwtTokenProvider.getEmailFromToken(refreshToken))
                .orElseThrow(() -> new BadCredentialsException("Token inválido"));
        if (!new CustomUserDetails(usuario).isEnabled()) {
            throw new BadCredentialsException("Token inválido");
        }
        return Map.of("accessToken", jwtTokenProvider.generateAccessToken(usuario.getEmail()),
                "refreshToken", refreshToken, "mensaje", "Token renovado correctamente");
    }
}
