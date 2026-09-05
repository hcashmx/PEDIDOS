package com.cyssoftware.effyFlow.entity;



import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "bitacora_ot", schema = "public")
@Data
public class BitacoraOt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_bitacora")
    private Long idBitacora;

    @Column(name = "id_ot", nullable = false)
    private Long idOt;

    @Column(name = "id_usuario")
    private Long idUsuario;

    @Column(name = "accion", length = 80)
    private String accion;

    @Column(name = "fecha")
    private LocalDateTime fecha;

    @Column(name = "observaciones", columnDefinition = "text")
    private String observaciones;
   
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
    // 🔹 Constructores
   
}
