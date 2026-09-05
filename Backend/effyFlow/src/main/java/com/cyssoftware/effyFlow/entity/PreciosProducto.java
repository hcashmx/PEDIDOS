package com.cyssoftware.effyFlow.entity;


import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

@Entity
@Table(name = "precios_producto")
@Data
public class PreciosProducto {

    @Id
    @Column(name = "producto_id")
    private Integer productoId;

    @Column(name = "tipo_precio_id")
    private Integer tipoPrecioId;

    @Column(name = "precio")
    private BigDecimal precio;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}