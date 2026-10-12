package com.cyssoftware.effyFlow.controller.cotizaciones;

import com.cyssoftware.effyFlow.service.cotizaciones.CotizacionIdentityAssertionService;
import com.cyssoftware.effyFlow.service.cotizaciones.CotizacionCapturaRequest;
import com.cyssoftware.effyFlow.service.cotizaciones.CotizacionSyncException;
import com.cyssoftware.effyFlow.service.cotizaciones.CotizacionSyncService;
import com.cyssoftware.effyFlow.service.cotizaciones.RevisionComercialPaquete;
import com.cyssoftware.effyFlow.service.cotizaciones.RevisionComercialSyncService;
import com.cyssoftware.effyFlow.service.pedido.SyncService;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Contratos de preparación; no crea ni persiste Cotizaciones. */
@RestController
@RequestMapping("/api/sync/cotizaciones")
public class CotizacionSyncController {
    private final RevisionComercialSyncService revisiones;
    private final CotizacionIdentityAssertionService aserciones;
    private final SyncService usuarios;
    private final CotizacionSyncService envios;

    public CotizacionSyncController(RevisionComercialSyncService revisiones,
            CotizacionIdentityAssertionService aserciones, SyncService usuarios, CotizacionSyncService envios) {
        this.revisiones = revisiones;
        this.aserciones = aserciones;
        this.usuarios = usuarios;
        this.envios = envios;
    }

    @GetMapping("/revisiones-comerciales/actual")
    public RevisionComercialPaquete actual(Authentication authentication) {
        requireSync(authentication);
        return revisiones.actual();
    }

    @GetMapping("/revisiones-comerciales/{id}")
    public RevisionComercialPaquete revision(@PathVariable UUID id, Authentication authentication) {
        requireSync(authentication);
        return revisiones.porId(id);
    }

    @PostMapping("/aserciones-identidad")
    public CotizacionIdentityAssertionService.Asercion asercion(
            @RequestBody CotizacionIdentityAssertionService.SolicitudAsercion request,
            Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated())
            throw new AccessDeniedException("Autenticación requerida.");
        return aserciones.emitir(authentication.getName(), request);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> enviar(@Valid @RequestBody CotizacionCapturaRequest request,
            Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated())
            throw new AccessDeniedException("Autenticación requerida.");
        var envio = envios.enviar(authentication.getName(), request);
        return ResponseEntity.status(envio.recuperada() ? 200 : 201).body(envio.respuesta());
    }

    @ExceptionHandler(CotizacionSyncException.class)
    public ResponseEntity<Map<String, Object>> errorEnvio(CotizacionSyncException ex) {
        return ResponseEntity.status(ex.status()).body(ex.body());
    }

    /** Conserva los estados controlados del servicio para que el manejador global no los convierta en HTTP 500. */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> status(ResponseStatusException ex) {
        String message = ex.getReason() == null || ex.getReason().isBlank()
                ? "No fue posible procesar la solicitud de identidad."
                : ex.getReason();
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of("error", message));
    }

    private void requireSync(Authentication authentication) {
        boolean permiso = authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> "SINCRONIZAR_DATOS".equals(a.getAuthority()));
        if (!permiso || !usuarios.usuarioAutorizado(authentication.getName()))
            throw new AccessDeniedException("Usuario sin autorización vigente para sincronizar.");
    }
}
