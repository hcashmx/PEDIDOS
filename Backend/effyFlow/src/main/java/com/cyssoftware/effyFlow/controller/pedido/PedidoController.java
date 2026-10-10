package com.cyssoftware.effyFlow.controller.pedido;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.cyssoftware.effyFlow.dto.PedidoConDetallesDTO;
import com.cyssoftware.effyFlow.service.pedido.PedidoService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/sync/pedidos")
@RequiredArgsConstructor
public class PedidoController {
    private final PedidoService pedidoService;
    @ExceptionHandler(com.cyssoftware.effyFlow.service.pedido.ErpCapturaException.class)
    public ResponseEntity<Map<String, Object>> errorErp(com.cyssoftware.effyFlow.service.pedido.ErpCapturaException ex) {
        return ResponseEntity.status(ex.status()).body(ex.respuesta());
    }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> entradaInvalida(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("resultado", "ERROR_NO_REINTENTABLE",
                "reintentable", false, "origenError", "SYNC", "error", ex.getMessage()));
    }
    @PostMapping
    public ResponseEntity<Map<String, Object>> recibirPedido(@Valid @RequestBody PedidoConDetallesDTO data) {
        return ResponseEntity.ok(pedidoService.guardarPedidoDesdeApp(data));
    }
}
