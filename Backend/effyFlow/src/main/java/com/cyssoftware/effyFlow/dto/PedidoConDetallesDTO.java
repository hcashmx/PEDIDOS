package com.cyssoftware.effyFlow.dto;

import java.util.List;

import com.cyssoftware.effyFlow.entity.DetallePedido;
import com.cyssoftware.effyFlow.entity.Pedido;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

public class PedidoConDetallesDTO {

    @jakarta.validation.Valid
    @jakarta.validation.constraints.NotNull(message = "pedido es obligatorio")
    @JsonProperty("pedido")
    private PedidoDTO pedido;

    @JsonProperty("detalles")
    private List<DetallePedidoDTO> detalles;

    public PedidoDTO getPedido() { return pedido; }
    public void setPedido(PedidoDTO pedido) { this.pedido = pedido; }

    public List<DetallePedidoDTO> getDetalles() { return detalles; }
    public void setDetalles(List<DetallePedidoDTO> detalles) { this.detalles = detalles; }
}