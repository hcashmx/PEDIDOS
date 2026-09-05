package com.cyssoftware.effyFlow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "productos")
@Data
public class Producto {

    @Id
    @Column(name = "id_producto")
    private Integer idProducto;

    @Column(name = "codigo")
    private String codigo;

    @Column(name = "nombre")
    private String nombre;

    @Column(name = "descripcion")
    private String descripcion;

    @Column(name = "activo")
    private Boolean activo;

    @Column(name = "id_categoria")
    private Integer idCategoria;

    @Column(name = "upc")
    private String upc;

    @Column(name = "largo")
    private BigDecimal largo;

    @Column(name = "ancho")
    private BigDecimal ancho;

    @Column(name = "alto")
    private BigDecimal alto;

    @Column(name = "grueso")
    private BigDecimal grueso;

    @Column(name = "peso")
    private BigDecimal peso;

    @Column(name = "tipo")
    private String tipo;

    @Column(name = "clave_sat")
    private String claveSat;

    @Column(name = "id_unidad")
    private Integer idUnidad;

    @Column(name = "costo_ultimo")
    private BigDecimal costoUltimo;

    @Column(name = "costo_reposicion")
    private BigDecimal costoReposicion;

    @Column(name = "costo_anterior")
    private BigDecimal costoAnterior;

    @Column(name = "costo_promedio")
    private BigDecimal costoPromedio;

    @Column(name = "descuento_maximo")
    private BigDecimal descuentoMax;

    @Column(name = "tipo_articulo")
    private String tipoArticulo;

    @Column(name = "id_marca")
    private Integer idMarca;

    @Column(name = "id_medida")
    private Integer idMedida;

    @Column(name = "permite_negativo")
    private Boolean permiteNegativo;

    @Column(name = "inner")
    private BigDecimal inner;

    @Column(name = "master")
    private BigDecimal master;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
