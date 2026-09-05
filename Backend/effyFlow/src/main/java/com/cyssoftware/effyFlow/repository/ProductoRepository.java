package com.cyssoftware.effyFlow.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import com.cyssoftware.effyFlow.entity.Producto;

@Repository
public interface ProductoRepository extends JpaRepository<Producto, Integer>, JpaRepositoryWithPaging<Producto> { 
	List<Producto> findByUpdatedAtAfter(LocalDateTime updatedAt);
	Boolean existsByIdProductoAndActivoTrue(Integer idProducto);
}

