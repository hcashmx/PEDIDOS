package com.cyssoftware.effyFlow.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import com.cyssoftware.effyFlow.entity.Pedido;

import org.springframework.data.jpa.repository.Query; // Importante

@Repository
public interface PedidoRepository extends JpaRepository<Pedido, Long> { 
	 boolean existsBySerieIdAndFolio(Integer serieId, Integer folio);
	 Optional<Pedido> findBySerieIdAndFolio(Integer serieId, Integer folio);
	 Optional<Pedido> findByUuidLocal(String uuidLocal);
	 
	 @Query("SELECT MAX(p.folio) FROM Pedido p WHERE p.serieId = :serieId")
	    Optional<Integer> findMaxFolioBySerieId(Integer serieId);
	
}

