package com.cyssoftware.effyFlow.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
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
@Table(name = "pedidos")
@Data
public class Pedido {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_pedido")
    private Long idPedido;

    @Column(name = "cliente_id")
    private Integer clienteId;

    @Column(name = "agente_ventas_id")
    private Integer agenteVentasId;

    @Column(name = "usuario_creo_id")
    private Integer usuarioCreoId;

    @Column(name = "usuario_autorizo_id")
    private Integer usuarioAutorizoId;

    @Column(name = "serie_id")
    private Integer serieId;

    @Column(name = "folio")
    private Integer folio;

    @Column(name = "fecha_creacion")
    private LocalDate fechaCreacion;

    @Column(name = "fecha_autorizacion")
    private LocalDate fechaAutorizacion;

    @Column(name = "fecha_envio_almacen")
    private LocalDate fechaEnvioAlmacen;

    @Column(name = "fecha_cierre")
    private LocalDate fechaCierre;

    @Column(name = "plazo")
    private Integer plazo;

    /*@Column(name = "estado")
    private String estado;
*/
    @Column(name = "observaciones")
    private String observaciones;

    @Column(name = "venta_id")
    private Integer ventaId;

    @Column(name = "total_solicitado")
    private BigDecimal totalSolicitado;

    @Column(name = "total_autorizado")
    private BigDecimal totalAutorizado;

    @Column(name = "total_facturado")
    private BigDecimal totalFacturado;

    @Column(name = "estado_id")
    private Integer estadoId;

    @Column(name = "uuid_local")
    private String uuidLocal;

    @Column(name = "origen")
    private String origen;

    @Column(name = "ubicacion_id")
    private Integer ubicacionId;

    @Column(name = "direccion_entrega_snapshot", columnDefinition = "jsonb")
    @Type(JsonBinaryType.class)
    private String direccionEntregaSnapshot;

    @Column(name = "fecha_captura")
    private OffsetDateTime fechaCaptura;
    
    public Long getIdPedido() {
        return idPedido;
    }
    
    
}
