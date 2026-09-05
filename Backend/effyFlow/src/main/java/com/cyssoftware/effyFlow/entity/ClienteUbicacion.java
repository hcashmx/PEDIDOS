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
@Table(name = "clientes_ubicaciones")
@Data
public class ClienteUbicacion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_cliente_ubicacion")
    private Integer idClienteUbicacion;

    @Column(name = "id_cliente", nullable = false)
    private Integer idCliente;

    @Column(name = "alias")
    private String alias;

    @Column(name = "activo")
    private Boolean activo;

    @Column(name = "contacto_persona")
    private String contactoPersona;

    @Column(name = "contacto_telefono")
    private String contactoTelefono;

    @Column(name = "direccion")
    private String direccion;

    @Column(name = "no_exterior")
    private String noExterior;

    @Column(name = "no_interior")
    private String noInterior;

    @Column(name = "colonia")
    private String colonia;

    @Column(name = "localidad")
    private String localidad;

    @Column(name = "municipio")
    private String municipio;

    @Column(name = "estado")
    private String estado;

    @Column(name = "pais")
    private String pais;

    @Column(name = "codigo_postal")
    private String codigoPostal;

    @Column(name = "referencia")
    private String referencia;

    @Column(name = "latitud")
    private BigDecimal latitud;

    @Column(name = "longitud")
    private BigDecimal longitud;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
