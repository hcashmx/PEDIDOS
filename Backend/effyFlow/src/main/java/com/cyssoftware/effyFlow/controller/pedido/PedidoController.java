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
    @PostMapping
    public ResponseEntity<Map<String, Object>> recibirPedido(@Valid @RequestBody PedidoConDetallesDTO data) {
        return ResponseEntity.ok(pedidoService.guardarPedidoDesdeApp(data));
    }
}
