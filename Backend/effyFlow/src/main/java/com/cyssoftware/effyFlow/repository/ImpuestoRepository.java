package com.cyssoftware.effyFlow.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import com.cyssoftware.effyFlow.entity.Impuesto;

@Repository
public interface ImpuestoRepository extends JpaRepository<Impuesto, Integer>,JpaRepositoryWithPaging<Impuesto> {
	List<Impuesto> findByUpdatedAtAfter(LocalDateTime updatedAt);
}

