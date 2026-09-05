package com.cyssoftware.effyFlow.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

@Entity
@Table(name = "productos_impuestos")
@Data
public class ProductoImpuesto {

    @Id
    @Column(name = "id_producto")
    private Integer idProducto;

    @Column(name = "id_impuesto")
    private Integer idImpuesto;

    @Column(name = "orden")
    private Integer orden;

    @Column(name = "base_incluye_impuestos_previos")
    private Boolean baseIncluyeImpuestosPrevios;

    @Column(name = "aplica_descuentos_en_base")
    private Boolean aplicaDescuentosEnBase;

    @Column(name = "multiplicador_unidades")
    private BigDecimal multiplicadorUnidades;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}