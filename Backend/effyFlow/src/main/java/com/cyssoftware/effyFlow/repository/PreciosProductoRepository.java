package com.cyssoftware.effyFlow.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import com.cyssoftware.effyFlow.entity.PreciosProducto;

@Repository
public interface PreciosProductoRepository extends JpaRepository<PreciosProducto, Integer>, JpaRepositoryWithPaging<PreciosProducto> {
	List<PreciosProducto> findByUpdatedAtAfter(LocalDateTime updatedAt);
}
