package com.cyssoftware.effyFlow.entity;

import java.time.LocalDateTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;


@Entity
@Table(name = "categorias")
@Data
public class Categoria {

    @Id
    @Column(name = "id_categoria")
    private Integer idCategoria;

    @Column(name = "nombre_categoria")
    private String nombreCategoria;

    @Column(name = "id_padre")
    private Integer idPadre;

    @Column(name = "nivel")
    private Short nivel;

    @Column(name = "activo")
    private Boolean activo;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}