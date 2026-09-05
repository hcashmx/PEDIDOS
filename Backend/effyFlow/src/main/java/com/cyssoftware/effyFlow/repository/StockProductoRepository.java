package com.cyssoftware.effyFlow.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import com.cyssoftware.effyFlow.entity.StockProducto;

@Repository
public interface StockProductoRepository extends JpaRepository<StockProducto, Integer>, JpaRepositoryWithPaging<StockProducto> { 
	List<StockProducto> findByUpdatedAtAfter(LocalDateTime updatedAt);
	List<StockProducto> findByIdProducto(Integer idProducto);
}
