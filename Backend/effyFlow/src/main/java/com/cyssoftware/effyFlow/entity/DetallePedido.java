package com.cyssoftware.effyFlow.entity;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.Type;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;

@Entity
@Table(name = "detalle_pedidos")
@Data
public class DetallePedido {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_detalle_pedido")
    private Long idDetallePedido;

    @Column(name = "pedido_id")
    private Long pedidoId;

    @Column(name = "producto_id")
    private Integer productoId;

    @Column(name = "presentacion_id")
    private Integer presentacionId;

    @Column(name = "almacen_id")
    private Integer almacenId;

    @Column(name = "cantidad_solicitada")
    private BigDecimal cantidadSolicitada;

    @Column(name = "cantidad_autorizada")
    private BigDecimal cantidadAutorizada;

    @Column(name = "cantidad_reservada")
    private BigDecimal cantidadReservada;

    @Column(name = "cantidad_surtida")
    private BigDecimal cantidadSurtida;

    @Column(name = "cantidad_cancelada")
    private BigDecimal cantidadCancelada;

    @Column(name = "precio_unitario")
    private BigDecimal precioUnitario;

    @Column(name = "descuento")
    private BigDecimal descuento;

    @Column(name = "impuestos")
    @Type(JsonBinaryType.class)
    private String impuestos;

    @Column(name = "total_linea")
    private BigDecimal totalLinea;

    @Column(name = "estado_linea")
    private String estadoLinea;

}
