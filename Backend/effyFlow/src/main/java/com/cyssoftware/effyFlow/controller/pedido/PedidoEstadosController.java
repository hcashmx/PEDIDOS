package com.cyssoftware.effyFlow.controller.pedido;

import com.cyssoftware.effyFlow.dto.PedidoEstadosBatch;
import com.cyssoftware.effyFlow.service.pedido.PedidoEstadosService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/sync/pedidos/estados")
@RequiredArgsConstructor
public class PedidoEstadosController {
    private final PedidoEstadosService service;
    @GetMapping
    public PedidoEstadosBatch estados(@RequestParam(defaultValue = "0") long afterId,
            @RequestParam(required = false) Long untilId, @RequestParam(defaultValue = "500") int size) {
        return service.obtener(afterId, untilId, size);
    }
}
