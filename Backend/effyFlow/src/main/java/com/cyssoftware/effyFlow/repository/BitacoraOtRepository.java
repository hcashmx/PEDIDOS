package com.cyssoftware.effyFlow.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.cyssoftware.effyFlow.entity.BitacoraOt;

import java.util.List;

@Repository
public interface BitacoraOtRepository extends JpaRepository<BitacoraOt, Long> {
    List<BitacoraOt> findByIdOt(Long idOt);
    List<BitacoraOt> findByIdUsuario(Long idUsuario);
}