package com.cyssoftware.effyFlow.dto;

import java.util.List;

public record PedidoEstadosBatch(long lastId, long untilId, boolean hasMore, List<Evento> data) {
    public record Evento(long historialId, long idPedido, String uuidLocal,
                         int estadoId, String estado, String fechaCambio) {}
}
