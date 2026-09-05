package com.cyssoftware.effyFlow.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import com.cyssoftware.effyFlow.entity.ProductoImpuesto;

@Repository
public interface ProductoImpuestoRepository extends JpaRepository<ProductoImpuesto, Integer>, JpaRepositoryWithPaging<ProductoImpuesto> { 
	List<ProductoImpuesto> findByUpdatedAtAfter(LocalDateTime updatedAt);
}
