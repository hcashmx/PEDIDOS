package com.cyssoftware.effyFlow.repository;

import java.time.LocalDateTime;
import java.util.List;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import com.cyssoftware.effyFlow.entity.SeriesFolios;

@Repository
public interface SeriesFoliosRepository extends JpaRepository<SeriesFolios, Integer>, JpaRepositoryWithPaging<SeriesFolios> {
	List<SeriesFolios> findByUpdatedAtAfter(LocalDateTime updatedAt);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select s from SeriesFolios s where s.idSerie = :idSerie")
	java.util.Optional<SeriesFolios> findByIdForUpdate(@Param("idSerie") Integer idSerie);
}
