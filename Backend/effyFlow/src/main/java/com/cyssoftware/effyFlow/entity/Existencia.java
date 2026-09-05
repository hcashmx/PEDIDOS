package com.cyssoftware.effyFlow.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

@Entity
@Table(name = "existencias")
@Data
public class Existencia {

    @Id
    @Column(name = "id")
    private Long id;

    @Column(name = "producto_id")
    private Long productoId;

    @Column(name = "almacen_id")
    private Long almacenId;

    @Column(name = "cantidad_disponible")
    private BigDecimal cantidadDisponible;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
