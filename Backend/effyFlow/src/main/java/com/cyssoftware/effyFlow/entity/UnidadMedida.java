package com.cyssoftware.effyFlow.entity;

import java.time.LocalDateTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

@Entity
@Table(name = "unidades_medida")
@Data
public class UnidadMedida {

    @Id
    @Column(name = "id_unidad")
    private Integer idUnidad;

    @Column(name = "nombre")
    private String nombre;

    @Column(name = "clave_sat")
    private String claveSat;

    @Column(name = "abreviatura")
    private String abreviatura;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}