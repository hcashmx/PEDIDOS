package com.cyssoftware.effyFlow.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.cyssoftware.effyFlow.entity.Existencia;

@Repository
public interface ExistenciaRepository extends JpaRepository<Existencia, Long> {
    List<Existencia> findByProductoId(Long productoId);
}
