package com.cyssoftware.effyFlow.exception;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingServletRequestParameterException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    @ExceptionHandler({AuthenticationException.class, io.jsonwebtoken.JwtException.class})
    public ResponseEntity<?> authentication(Exception ex) {
        return ResponseEntity.status(401).header("WWW-Authenticate", "Bearer")
                .body(Map.of("error", "Credenciales o token inválidos"));
    }
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<?> denied(AccessDeniedException ex) {
        return ResponseEntity.status(403).body(Map.of("error", "Acceso denegado"));
    }
    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    public ResponseEntity<?> validation(Exception ex) {
        return ResponseEntity.badRequest().body(Map.of("error", "Solicitud inválida"));
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<?> conflict(DataIntegrityViolationException ex) {
        log.warn("Conflicto de integridad al procesar solicitud", ex);
        return ResponseEntity.status(409).body(Map.of("error", "Conflicto de integridad al procesar la solicitud"));
    }
    @ExceptionHandler(jakarta.persistence.EntityNotFoundException.class)
    public ResponseEntity<?> notFound(Exception ex) {
        return ResponseEntity.status(404).body(Map.of("error", "Recurso no encontrado"));
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> unexpected(Exception ex) {
        log.error("Error interno al procesar solicitud", ex);
        return ResponseEntity.internalServerError().body(Map.of("error", "Error interno al procesar la solicitud"));
    }
}
