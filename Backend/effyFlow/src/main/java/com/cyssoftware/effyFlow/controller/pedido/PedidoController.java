package com.cyssoftware.effyFlow.controller.pedido;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cyssoftware.effyFlow.dto.PedidoConDetallesDTO;
import com.cyssoftware.effyFlow.dto.PedidoDTO;
import com.cyssoftware.effyFlow.service.pedido.PedidoService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;



@RestController
@RequestMapping("/api/sync/pedidos")
@RequiredArgsConstructor
public class PedidoController {

    private final PedidoService pedidoService;


    @PostMapping
    public ResponseEntity<Map<String, Object>> recibirPedido(@RequestBody PedidoConDetallesDTO data) {
        try {
            Map<String, Object> response = pedidoService.guardarPedidoDesdeApp(data);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException ex) {
            // Errores de validación: cliente inactivo, producto no encontrado, etc.
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        } catch (Exception ex) {
            // Otros errores inesperados
            return ResponseEntity.status(500).body(Map.of("error", "Error interno: " + ex.getMessage()));
        }
    }
}