package com.cyssoftware.effyFlow.repository.projections;

public interface PedidoProjection {
    
    Integer getSerieId(); 
    Integer getFolio();
    Integer getIdPedido();
    Integer getEstadoId();
    
    java.time.LocalDateTime getUpdatedAt(); 
    
}
