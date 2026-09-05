package com.cyssoftware.effyFlow.repository;

import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface JpaRepositoryWithPaging<T> {
    Page<T> findByUpdatedAtAfter(LocalDateTime updatedAt, Pageable pageable);
    Page<T> findAll(Pageable pageable);
}