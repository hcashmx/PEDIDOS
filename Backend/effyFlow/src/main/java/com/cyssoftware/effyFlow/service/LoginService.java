package com.cyssoftware.effyFlow.service;

import com.cyssoftware.effyFlow.entity.Usuario;
import com.cyssoftware.effyFlow.repository.UsuarioRepository;
import com.cyssoftware.effyFlow.security.JwtTokenProvider;

import lombok.RequiredArgsConstructor;

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
        Usuario usuario = usuarioRepo.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Usuario no encontrado"));

    

        /*if (!usuario.getPass().equals(password)) {
            throw new IllegalArgumentException("Contraseña incorrecta");
        }*/
        
        if (!passwordEncoder.matches(password, usuario.getPassword())) {
            throw new RuntimeException("Credenciales inválidas");
        }

        // 3. Verificamos si el usuario está activo (opcional pero recomendado)
        if (usuario.getActive() != null && !usuario.getActive()) {
            throw new RuntimeException("El usuario se encuentra desactivado");
        }
        

        String accessToken = jwtTokenProvider.generateAccessToken(email);
        String refreshToken = jwtTokenProvider.generateRefreshToken(email);
       
        Map<String, Object> response = new HashMap<>();
        response.put("usuarioId", usuario.getId());
        response.put("nombre", usuario.getNombre());
        response.put("roles", usuario.getRoles());
        response.put("accessToken", accessToken);
        response.put("refreshToken", refreshToken);
        response.put("mensaje", "Inicio de sesión exitoso");

        return response;
    }

    public Map<String, Object> refresh(String refreshToken) {
        if (!jwtTokenProvider.validateToken(refreshToken)) {
            throw new IllegalArgumentException("Refresh token inválido o expirado");
        }

        String email = jwtTokenProvider.getEmailFromToken(refreshToken);
        String newAccessToken = jwtTokenProvider.generateAccessToken(email);

        return Map.of(
                "accessToken", newAccessToken,
                "mensaje", "Token renovado correctamente"
        );
    }
}
