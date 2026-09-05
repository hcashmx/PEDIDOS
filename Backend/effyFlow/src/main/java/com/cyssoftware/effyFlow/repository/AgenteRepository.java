package com.cyssoftware.effyFlow.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.cyssoftware.effyFlow.entity.Agente;

@Repository
public interface AgenteRepository extends JpaRepository<Agente, Integer>, JpaRepositoryWithPaging<Agente> { 
    List<Agente> findByUpdatedAtAfter(LocalDateTime updatedAt);
}

