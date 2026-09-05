package com.cyssoftware.effyFlow.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

@Entity
@Table(name = "clientes")
@Data
public class Cliente {

    @Id
    @Column(name = "id_cliente")
    private Integer idCliente;

    @Column(name = "nombre")
    private String nombre;

    @Column(name = "rfc")
    private String rfc;

    @Column(name = "razon_social")
    private String razonSocial;

    @Column(name = "email")
    private String email;

    @Column(name = "telefono")
    private String telefono;

    @Column(name = "direccion")
    private String direccion;
    
    @Column(name = "colonia", length = 60) // ✨ Nuevo campo
    private String colonia;

    @Column(name = "codigo_postal")
    private String codigoPostal;

    @Column(name = "localidad")
    private String localidad;

    @Column(name = "municipio")
    private String municipio;

    @Column(name = "estado")
    private String estado;

    @Column(name = "pais")
    private String pais;
    
    @Column(name = "latitud", precision = 10, scale = 7) // ✨ Nuevo campo
    private BigDecimal latitud;

    @Column(name = "longitud", precision = 10, scale = 7) // ✨ Nuevo campo
    private BigDecimal longitud;

    @Column(name = "uso_cfdi_id")
    private Integer usoCfdiId;

    @Column(name = "forma_pago_sat")
    private Integer formaPagoSat;

    @Column(name = "activo")
    private Boolean activo;

    @Column(name = "numero_precio_id")
    private Integer numeroPrecioId;

    @Column(name = "limite_credito")
    private Integer limiteCredito;

    @Column(name = "agente_id")
    private Integer agenteId;

    @Column(name = "tipo_clente_id")
    private Integer tipoClenteId;

    @Column(name = "ruta_id")
    private Integer rutaId;

    @Column(name = "plazo")
    private Integer plazo;
    
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
