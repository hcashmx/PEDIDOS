package com.cyssoftware.effyFlow.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import com.cyssoftware.effyFlow.entity.SeriesFoliosUsuarios;

@Repository
public interface SeriesFoliosUsuariosRepository extends JpaRepository<SeriesFoliosUsuarios, Integer>, JpaRepositoryWithPaging<SeriesFoliosUsuarios> { 
	List<SeriesFoliosUsuarios> findByUpdatedAtAfter(LocalDateTime updatedAt);
}
