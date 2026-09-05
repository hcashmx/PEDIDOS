package com.cyssoftware.effyFlow.entity;

import java.time.LocalDateTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

@Entity
@Table(name = "agentes")
@Data
public class Agente {

    @Id
    @Column(name = "id_agente")
    private Integer idAgente;

    @Column(name = "nombre")
    private String nombre;

    @Column(name = "id_usuario")
    private Integer idUsuario;

    @Column(name = "tipo_agente_id")
    private Integer tipoAgenteId;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
