package com.cyssoftware.effyFlow.entity;

import java.time.LocalDateTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

@Entity
@Table(name = "productos_sustitutos")
@Data
public class ProductoSustituto {

    @Id
    @Column(name = "id_producto_sustituto")
    private Integer idProductoSustituto;

    @Column(name = "producto_id")
    private Integer productoId;

    @Column(name = "producto_sustituto_id")
    private Integer productoSustitutoId;

    @Column(name = "prioridad")
    private Short prioridad;

    @Column(name = "motivo")
    private String motivo;

    @Column(name = "vigente")
    private Boolean vigente;

    @Column(name = "observaciones")
    private String observaciones;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}