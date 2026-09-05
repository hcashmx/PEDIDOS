package com.cyssoftware.effyFlow.entity;

import java.time.LocalDateTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

@Entity
@Table(name = "marcas")
@Data
public class Marca {

    @Id
    @Column(name = "id_marca")
    private Integer idMarca;

    @Column(name = "nombre")
    private String nombre;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}