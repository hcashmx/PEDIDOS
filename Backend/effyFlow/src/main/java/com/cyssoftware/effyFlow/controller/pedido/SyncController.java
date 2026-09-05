package com.cyssoftware.effyFlow.controller.pedido;


import java.time.LocalDateTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.cyssoftware.effyFlow.service.pedido.SyncService;

import org.springframework.http.MediaType;
@RestController
@RequestMapping("/api/sync/catalogos")
@RequiredArgsConstructor
public class SyncController {

    private final SyncService syncService;
    //@PreAuthorize("hasAuthority('SINCRONIZAR_DATOS')")
    @GetMapping(
            // 1. Usa 'value' para la ruta.
            value = "/{nombre}",
            // 2. Usa 'produces' para forzar la codificación UTF-8.
            produces = "application/json; charset=UTF-8"
    	    )
    public ResponseEntity<Map<String, Object>> getCatalogo(
            @PathVariable String nombre,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime updatedAfter,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "500") int size) {

        Map<String, Object> result = syncService.obtenerCatalogo(nombre, updatedAfter, page, size);
        
        return ResponseEntity.ok(result);
    }
}


