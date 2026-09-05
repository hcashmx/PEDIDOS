package com.cyssoftware.effyFlow.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import com.cyssoftware.effyFlow.entity.UnidadMedida;

@Repository
public interface UnidadMedidaRepository extends JpaRepository<UnidadMedida, Integer>,JpaRepositoryWithPaging<UnidadMedida> { 
	List<UnidadMedida> findByUpdatedAtAfter(LocalDateTime updatedAt);
}
