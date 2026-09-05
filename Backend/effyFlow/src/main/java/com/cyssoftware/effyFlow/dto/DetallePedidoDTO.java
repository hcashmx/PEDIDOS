package com.cyssoftware.effyFlow.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;
import java.math.BigDecimal;


public class DetallePedidoDTO {
	 	@JsonProperty("id_detalle_pedido")
	    private Long idDetallePedido;

	    @JsonProperty("pedido_id")
	    private Long pedidoId;

	    @JsonProperty("producto_id")
	    private Long productoId;

	    @JsonProperty("presentacion_id")
	    private Long presentacionId;

	    @JsonProperty("almacen_id")
	    private Long almacenId;

	    @JsonProperty("cantidad_solicitada")
	    private BigDecimal cantidadSolicitada;

	    @JsonProperty("cantidad_autorizada")
	    private BigDecimal cantidadAutorizada;

	    @JsonProperty("cantidad_reservada")
	    private BigDecimal cantidadReservada;

	    @JsonProperty("cantidad_surtida")
	    private BigDecimal cantidadSurtida;

	    @JsonProperty("cantidad_cancelada")
	    private BigDecimal cantidadCancelada;

	    @JsonProperty("precio_unitario")
	    private BigDecimal precioUnitario;

	    @JsonProperty("descuento")
	    private BigDecimal descuento;

	    @JsonProperty("impuestos")
	    private String impuestos;

	    @JsonProperty("total_linea")
	    private BigDecimal totalLinea;

	    @JsonProperty("estado_linea")
	    private String estadoLinea;

	    @JsonProperty("updated_at")
	    private String updatedAt;
    // Getters y Setters
    public Long getIdDetallePedido() { return idDetallePedido; }
    public void setIdDetallePedido(Long idDetallePedido) { this.idDetallePedido = idDetallePedido; }

    public Long getPedidoId() { return pedidoId; }
    public void setPedidoId(Long pedidoId) { this.pedidoId = pedidoId; }

    public Long getProductoId() { return productoId; }
    public void setProductoId(Long productoId) { this.productoId = productoId; }

    public Long getPresentacionId() { return presentacionId; }
    public void setPresentacionId(Long presentacionId) { this.presentacionId = presentacionId; }

    public Long getAlmacenId() { return almacenId; }
    public void setAlmacenId(Long almacenId) { this.almacenId = almacenId; }

    public BigDecimal getCantidadSolicitada() { return cantidadSolicitada; }
    public void setCantidadSolicitada(BigDecimal cantidadSolicitada) { this.cantidadSolicitada = cantidadSolicitada; }

    public BigDecimal getCantidadAutorizada() { return cantidadAutorizada; }
    public void setCantidadAutorizada(BigDecimal cantidadAutorizada) { this.cantidadAutorizada = cantidadAutorizada; }

    public BigDecimal getCantidadReservada() { return cantidadReservada; }
    public void setCantidadReservada(BigDecimal cantidadReservada) { this.cantidadReservada = cantidadReservada; }

    public BigDecimal getCantidadSurtida() { return cantidadSurtida; }
    public void setCantidadSurtida(BigDecimal cantidadSurtida) { this.cantidadSurtida = cantidadSurtida; }

    public BigDecimal getCantidadCancelada() { return cantidadCancelada; }
    public void setCantidadCancelada(BigDecimal cantidadCancelada) { this.cantidadCancelada = cantidadCancelada; }

    public BigDecimal getPrecioUnitario() { return precioUnitario; }
    public void setPrecioUnitario(BigDecimal precioUnitario) { this.precioUnitario = precioUnitario; }

    public BigDecimal getDescuento() { return descuento; }
    public void setDescuento(BigDecimal descuento) { this.descuento = descuento; }

    public String getImpuestos() { return impuestos; }
    public void setImpuestos(String impuestos) { this.impuestos = impuestos; }

    public BigDecimal getTotalLinea() { return totalLinea; }
    public void setTotalLinea(BigDecimal totalLinea) { this.totalLinea = totalLinea; }

    public String getEstadoLinea() { return estadoLinea; }
    public void setEstadoLinea(String estadoLinea) { this.estadoLinea = estadoLinea; }

    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
}
