package com.cyssoftware.effyFlow.repository;


import com.cyssoftware.effyFlow.entity.KitDetalle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface KitDetalleRepository extends JpaRepository<KitDetalle, Integer>, JpaRepositoryWithPaging<KitDetalle> {
    
    List<KitDetalle> findByUpdatedAtAfter(LocalDateTime updatedAt);
    
    // Busca todos los componentes de un kit específico
    List<KitDetalle> findByIdProductoKitOrderByOrdenAsc(Integer idProductoKit);
}