package com.cyssoftware.effyFlow.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

@Entity
@Table(name = "kits_detalle", schema = "public")
@Data
public class KitDetalle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_kit_detalle")
    private Integer idKitDetalle;

    @Column(name = "id_producto_kit", nullable = false)
    private Integer idProductoKit;

    @Column(name = "id_producto_componente", nullable = false)
    private Integer idProductoComponente;

    @Column(name = "cantidad")
    private BigDecimal cantidad;

    @Column(name = "orden")
    private Integer orden;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}