package com.cyssoftware.effyFlow.controller.cotizaciones;

import com.cyssoftware.effyFlow.service.cotizaciones.CotizacionIdentityAssertionService;
import com.cyssoftware.effyFlow.service.cotizaciones.RevisionComercialPaquete;
import com.cyssoftware.effyFlow.service.cotizaciones.RevisionComercialSyncService;
import com.cyssoftware.effyFlow.service.pedido.SyncService;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Contratos de preparación; no crea ni persiste Cotizaciones. */
@RestController
@RequestMapping("/api/sync/cotizaciones")
public class CotizacionSyncController {
    private final RevisionComercialSyncService revisiones;
    private final CotizacionIdentityAssertionService aserciones;
    private final SyncService usuarios;

    public CotizacionSyncController(RevisionComercialSyncService revisiones,
            CotizacionIdentityAssertionService aserciones, SyncService usuarios) {
        this.revisiones = revisiones;
        this.aserciones = aserciones;
        this.usuarios = usuarios;
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

    private void requireSync(Authentication authentication) {
        boolean permiso = authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> "SINCRONIZAR_DATOS".equals(a.getAuthority()));
        if (!permiso || !usuarios.usuarioAutorizado(authentication.getName()))
            throw new AccessDeniedException("Usuario sin autorización vigente para sincronizar.");
    }
}
