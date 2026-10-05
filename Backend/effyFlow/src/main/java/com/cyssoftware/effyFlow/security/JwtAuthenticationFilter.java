package com.cyssoftware.effyFlow.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.security.core.AuthenticationException;
import org.springframework.dao.DataAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private final JwtTokenProvider tokens;
    private final CustomUserDetailsService users;
    public JwtAuthenticationFilter(JwtTokenProvider tokens, CustomUserDetailsService users) {
        this.tokens = tokens;
        this.users = users;
    }
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return ("GET".equals(request.getMethod()) && "/api/health".equals(path))
                || ("POST".equals(request.getMethod()) && ("/api/auth/login".equals(path) || "/api/auth/refresh".equals(path)));
    }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            if (tokens.validateAccessToken(token)) {
                try {
                    var user = users.loadUserByUsername(tokens.getEmailFromToken(token));
                    if (user.isEnabled() && user.isAccountNonLocked() && user.isAccountNonExpired()
                            && user.isCredentialsNonExpired()) {
                        var auth = new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
                        auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                        SecurityContextHolder.getContext().setAuthentication(auth);
                    }
                } catch (AuthenticationException | IllegalArgumentException | io.jsonwebtoken.JwtException ex) {
                    SecurityContextHolder.clearContext();
                } catch (DataAccessException ex) {
                    log.error("No se pudo validar el usuario", ex);
                    response.setStatus(500);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"error\":\"Error interno al procesar la solicitud\"}");
                    return;
                }
            }
        }
        chain.doFilter(request, response);
    }
}
