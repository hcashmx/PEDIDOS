package com.cyssoftware.effyFlow.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

@Entity
@Table(name = "impuestos")
@Data
public class Impuesto {

    @Id
    @Column(name = "id_impuesto")
    private Integer idImpuesto;

    @Column(name = "nombre")
    private String nombre;

    @Column(name = "tipo_aplicacion")
    private String tipoAplicacion;

    @Column(name = "tipo_factor")
    private String tipoFactor;

    @Column(name = "tasa_o_cuota")
    private BigDecimal tasaOCuota;

    @Column(name = "unidad_cuota")
    private String unidadCuota;

    @Column(name = "c_impuesto")
    private String cImpuesto;

    @Column(name = "nombre_sat")
    private String nombreSat;

    @Column(name = "activo")
    private Boolean activo;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}