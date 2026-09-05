package com.cyssoftware.effyFlow.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import com.cyssoftware.effyFlow.entity.Marca;

@Repository
public interface MarcaRepository extends JpaRepository<Marca, Integer>, JpaRepositoryWithPaging<Marca> {
	List<Marca> findByUpdatedAtAfter(LocalDateTime updatedAt);
}
