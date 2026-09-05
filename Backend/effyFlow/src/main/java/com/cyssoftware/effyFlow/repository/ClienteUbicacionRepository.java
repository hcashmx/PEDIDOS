package com.cyssoftware.effyFlow.repository;


import com.cyssoftware.effyFlow.entity.ClienteUbicacion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ClienteUbicacionRepository extends JpaRepository<ClienteUbicacion, Integer>, JpaRepositoryWithPaging<ClienteUbicacion> {
    
    // Busca ubicaciones actualizadas recientemente
    List<ClienteUbicacion> findByUpdatedAtAfter(LocalDateTime updatedAt);
    
    // Verifica si la ubicación existe y está activa
    boolean existsByIdClienteUbicacionAndActivoTrue(Integer idClienteUbicacion);
    
    // Búsqueda por cliente
    List<ClienteUbicacion> findByIdCliente(Integer idCliente);
}
