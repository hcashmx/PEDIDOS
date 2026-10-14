package com.cyssoftware.effyFlow.controller.cotizaciones;

import com.cyssoftware.effyFlow.service.cotizaciones.CotizacionStatesSyncService;
import com.cyssoftware.effyFlow.service.pedido.SyncService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/sync/cotizaciones/estados")
public class CotizacionEstadosSyncController {
    private final CotizacionStatesSyncService states;
    private final SyncService users;

    public CotizacionEstadosSyncController(CotizacionStatesSyncService states, SyncService users) {
        this.states = states;
        this.users = users;
    }

    @PostMapping("/sesion")
    public ResponseEntity<Map<String, Object>> open(@Valid @RequestBody DeviceRequest request,
            Authentication authentication) {
        requireSync(authentication);
        var result = states.open(authentication.getName(), request.dispositivoId());
        return ResponseEntity.status(result.httpStatus()).body(result.body());
    }

    @GetMapping("/sesion/{sessionId}/snapshot")
    public ResponseEntity<Map<String, Object>> snapshot(@PathVariable UUID sessionId,
            @RequestHeader("X-Sync-Device-Id") String deviceId,
            @RequestParam(defaultValue = "0") long afterSequence,
            @RequestParam(defaultValue = "50") int limit, Authentication authentication) {
        requireSync(authentication);
        return ResponseEntity.ok(states.snapshot(authentication.getName(), sessionId, deviceId, afterSequence, limit));
    }

    @GetMapping("/sesion/{sessionId}/eventos")
    public Map<String, Object> events(@PathVariable UUID sessionId,
            @RequestHeader("X-Sync-Device-Id") String deviceId,
            @RequestParam(defaultValue = "0") long afterSequence,
            @RequestParam(defaultValue = "50") int limit, Authentication authentication) {
        requireSync(authentication);
        return states.events(authentication.getName(), sessionId, deviceId, afterSequence, limit);
    }

    @PostMapping("/sesion/{sessionId}/ack")
    public Map<String, Object> acknowledge(@PathVariable UUID sessionId,
            @RequestHeader("X-Sync-Device-Id") String deviceId,
            @Valid @RequestBody AckRequest request, Authentication authentication) {
        requireSync(authentication);
        return states.acknowledge(authentication.getName(), sessionId, deviceId, request.ackThroughSequence());
    }

    @GetMapping("/sesion/{sessionId}")
    public Map<String, Object> state(@PathVariable UUID sessionId,
            @RequestHeader("X-Sync-Device-Id") String deviceId, Authentication authentication) {
        requireSync(authentication);
        return states.state(authentication.getName(), sessionId, deviceId);
    }

    @PostMapping("/sesion/{sessionId}/revalidar")
    public Map<String, Object> revalidate(@PathVariable UUID sessionId,
            @RequestHeader("X-Sync-Device-Id") String deviceId, Authentication authentication) {
        requireSync(authentication);
        return states.revalidate(authentication.getName(), sessionId, deviceId);
    }

    @PostMapping("/sesion/{sessionId}/reiniciar")
    public ResponseEntity<Map<String, Object>> reset(@PathVariable UUID sessionId,
            @Valid @RequestBody ResetRequest request, Authentication authentication) {
        requireSync(authentication);
        Map<String, Object> response = states.reset(authentication.getName(), sessionId,
                request.dispositivoId(), request.expectedGeneration());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorBody> status(ResponseStatusException ex) {
        int status = ex.getStatusCode().value();
        String reason = ex.getReason() == null ? "" : ex.getReason();
        boolean retryable = status == 429 || status == 504
                || (status == 503 && !reason.contains("ERP_TECHNICAL_AUTH_FAILED"));
        return ResponseEntity.status(ex.getStatusCode()).body(new ErrorBody(errorCode(status, reason),
                safeMessage(status, reason), retryable, "ERP_ESTADOS", sessionState(status, reason), UUID.randomUUID().toString()));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ErrorBody> storage(DataAccessException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new ErrorBody(
                "SYNC_STORAGE_UNAVAILABLE", "El almacenamiento de sincronización no está disponible.",
                true, "SYNC_STORAGE", null, UUID.randomUUID().toString()));
    }

    private void requireSync(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication.getAuthorities().stream().noneMatch(a -> "SINCRONIZAR_DATOS".equals(a.getAuthority()))
                || !users.usuarioAutorizado(authentication.getName()))
            throw new AccessDeniedException("Usuario sin autorización vigente para sincronizar.");
    }

    private static String errorCode(int status, String reason) {
        return switch (status) {
            case 400 -> "REQUEST_INVALIDA";
            case 401 -> "SYNC_AUTH_REQUIRED";
            case 403 -> reason.contains("COTIZACIONES_SIN_PERMISO")
                    ? "COTIZACIONES_SIN_PERMISO" : "COTIZACIONES_AUTORIZACION_REVOCADA";
            case 404 -> "SESION_NO_ENCONTRADA";
            case 409 -> reason.toLowerCase().contains("ack") ? "ACK_NO_CONTIGUO"
                    : reason.toLowerCase().contains("identidad") ? "IDENTIDAD_CAMBIADA"
                    : reason.toLowerCase().contains("cursor") ? "ERP_CURSOR_CONFLICT" : "SESSION_STATE_CONFLICT";
            case 410 -> reason.contains("CURSOR_DE_ENTREGA_EXPIRADO") ? "CURSOR_DE_ENTREGA_EXPIRADO"
                    : reason.contains("SESSION_EXPIRADA") ? "SESSION_EXPIRADA" : "SESSION_REQUIERE_REINICIO";
            case 422 -> reason.toLowerCase().contains("limit") ? "LIMIT_FUERA_DE_RANGO" : "DISPOSITIVO_UUID_INVALIDO";
            case 429 -> "RATE_LIMITED";
            case 502 -> "ERP_PROTOCOL_ERROR";
            case 503 -> reason.contains("ERP_TECHNICAL_AUTH_FAILED") ? "ERP_TECHNICAL_AUTH_FAILED" : "ERP_UNAVAILABLE";
            default -> "SYNC_COTIZACIONES_ERROR";
        };
    }

    private static String sessionState(int status, String reason) {
        if (status == 403 && !reason.contains("COTIZACIONES_SIN_PERMISO")) return "REVOCADA";
        if (status == 410 && reason.contains("SESSION_EXPIRADA")) return "SESSION_EXPIRADA";
        if (status == 410) return "REQUIERE_REINICIO";
        return null;
    }

    private static String safeMessage(int status, String reason) {
        return switch (status) {
            case 400 -> "La solicitud no es válida.";
            case 403 -> reason.contains("COTIZACIONES_SIN_PERMISO")
                    ? "El usuario no tiene permiso para leer Cotizaciones."
                    : "La autorización de Cotizaciones no está vigente.";
            case 404 -> "Sesión no encontrada.";
            case 409 -> "La sesión o el checkpoint requiere consulta de estado.";
            case 410 -> "La sesión requiere un reinicio autorizado.";
            case 422 -> "Los parámetros de sincronización no son válidos.";
            case 429 -> "Se alcanzó el límite de solicitudes.";
            case 502 -> "ERP devolvió una respuesta inválida o rechazó la aserción.";
            case 503 -> "La integración ERP de lectura no está disponible.";
            default -> "No fue posible procesar la sincronización de Cotizaciones.";
        };
    }

    public record DeviceRequest(@NotBlank @Size(max = 36) String dispositivoId) { }
    public record AckRequest(@PositiveOrZero long ackThroughSequence) { }
    public record ResetRequest(@NotBlank @Size(max = 36) String dispositivoId,
            @PositiveOrZero long expectedGeneration) { }
    public record ErrorBody(String code, String message, boolean retryable, String phase,
            String sessionState, String correlationId) { }
}
