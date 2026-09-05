package com.cyssoftware.effyFlow.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import com.cyssoftware.effyFlow.entity.ProductoSustituto;

@Repository
public interface ProductoSustitutoRepository extends JpaRepository<ProductoSustituto, Integer>, JpaRepositoryWithPaging<ProductoSustituto> {
	List<ProductoSustituto> findByUpdatedAtAfter(LocalDateTime updatedAt);
	 List<ProductoSustituto> findByProductoId(Integer productoId);
}
