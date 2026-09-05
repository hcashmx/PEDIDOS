package com.cyssoftware.effyFlow.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.cyssoftware.effyFlow.entity.Categoria;

@Repository
public interface CategoriaRepository extends JpaRepository<Categoria, Integer>,JpaRepositoryWithPaging<Categoria> {
    List<Categoria> findByUpdatedAtAfter(LocalDateTime updatedAt);
}

