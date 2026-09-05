package com.cyssoftware.effyFlow.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import com.cyssoftware.effyFlow.entity.Usuario;

@Repository
public interface UsuarioRepository extends JpaRepository<Usuario, Integer>, JpaRepositoryWithPaging<Usuario> {
	List<Usuario> findByUpdatedAtAfter(LocalDateTime updatedAt);
	
	Optional<Usuario> findByEmail(String email);
	boolean existsByEmailAndStatus(String email, String status);
}
