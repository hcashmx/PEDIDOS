package com.cyssoftware.effyFlow.entity;


import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

@Entity
@Table(name = "stock_productos")
@Data
public class StockProducto {

    @Id
    @Column(name = "id_producto")
    private Integer idProducto;

    @Column(name = "id_ubicacion")
    private Integer idUbicacion;

    @Column(name = "cantidad")
    private BigDecimal cantidad;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}