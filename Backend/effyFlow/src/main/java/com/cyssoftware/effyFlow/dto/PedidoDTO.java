package com.cyssoftware.effyFlow.dto;

import jakarta.validation.constraints.*;
import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;



public class PedidoDTO {
	 @JsonProperty("id_pedido")
	    private Long idPedido;

	    @JsonProperty("cliente_id")
	    private Long clienteId;

	    @JsonProperty("agente_ventas_id")
	    private Long agenteVentasId;

	    @JsonProperty("usuario_creo_id")
	    private Long usuarioCreoId;

	    @JsonProperty("usuario_autorizo_id")
	    private Long usuarioAutorizoId;

	    @JsonProperty("serie_id")
	    private Long serieId;

	    @JsonProperty("folio")
	    private Long folio;

	    @JsonProperty("fecha_creacion")
	    private String fechaCreacion;

	    @JsonProperty("fecha_autorizacion")
	    private String fechaAutorizacion;

	    @JsonProperty("fecha_envio_almacen")
	    private String fechaEnvioAlmacen;

	    @JsonProperty("fecha_cierre")
	    private String fechaCierre;

	    @JsonProperty("plazo")
	    private Long plazo;

	    @JsonProperty("estado")
	    private String estado;

	    @JsonProperty("observaciones")
	    private String observaciones;

	    @JsonProperty("venta_id")
	    private Long ventaId;

	    @JsonProperty("total_solicitado")
	    private BigDecimal totalSolicitado;

	    @JsonProperty("total_autorizado")
	    private BigDecimal totalAutorizado;

	    @JsonProperty("total_facturado")
	    private BigDecimal totalFacturado;

	    @NotBlank(message = "uuid_local es obligatorio")
         @Size(max = 36, message = "uuid_local debe tener como máximo 36 caracteres")
         @Pattern(regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}", message = "uuid_local debe ser un UUID canónico")
         @JsonProperty("uuid_local")
	    private String uuidLocal;

	    @JsonProperty("ubicacion_id")
	    private Long ubicacionId;

	    @JsonProperty("uuid_server")
	    private String uuidServer;
	    
	    @JsonProperty("tipo_documento")
	    private String tipoDocumento;

	    @JsonProperty("updated_at")
	    private String updatedAt;

	    @JsonProperty("synced_at")
	    private String syncedAt;
	    

    // Getters y Setters
    public Long getIdPedido() { return idPedido; }
    public void setIdPedido(Long idPedido) { this.idPedido = idPedido; }

    public Long getClienteId() { return clienteId; }
    public void setClienteId(Long clienteId) { this.clienteId = clienteId; }

    public Long getAgenteVentasId() { return agenteVentasId; }
    public void setAgenteVentasId(Long agenteVentasId) { this.agenteVentasId = agenteVentasId; }

    public Long getUsuarioCreoId() { return usuarioCreoId; }
    public void setUsuarioCreoId(Long usuarioCreoId) { this.usuarioCreoId = usuarioCreoId; }

    public Long getUsuarioAutorizoId() { return usuarioAutorizoId; }
    public void setUsuarioAutorizoId(Long usuarioAutorizoId) { this.usuarioAutorizoId = usuarioAutorizoId; }

    public Long getSerieId() { return serieId; }
    public void setSerieId(Long serieId) { this.serieId = serieId; }

    public Long getFolio() { return folio; }
    public void setFolio(Long folio) { this.folio = folio; }

    public String getFechaCreacion() { return fechaCreacion; }
    public void setFechaCreacion(String fechaCreacion) { this.fechaCreacion = fechaCreacion; }

    public String getFechaAutorizacion() { return fechaAutorizacion; }
    public void setFechaAutorizacion(String fechaAutorizacion) { this.fechaAutorizacion = fechaAutorizacion; }

    public String getFechaEnvioAlmacen() { return fechaEnvioAlmacen; }
    public void setFechaEnvioAlmacen(String fechaEnvioAlmacen) { this.fechaEnvioAlmacen = fechaEnvioAlmacen; }

    public String getFechaCierre() { return fechaCierre; }
    public void setFechaCierre(String fechaCierre) { this.fechaCierre = fechaCierre; }

    public Long getPlazo() { return plazo; }
    public void setPlazo(Long plazo) { this.plazo = plazo; }

    public String getEstado() { return estado; }
    public void setEstado(String estado) { this.estado = estado; }

    public String getObservaciones() { return observaciones; }
    public void setObservaciones(String observaciones) { this.observaciones = observaciones; }

    public Long getVentaId() { return ventaId; }
    public void setVentaId(Long ventaId) { this.ventaId = ventaId; }

    public BigDecimal getTotalSolicitado() { return totalSolicitado; }
    public void setTotalSolicitado(BigDecimal totalSolicitado) { this.totalSolicitado = totalSolicitado; }

    public BigDecimal getTotalAutorizado() { return totalAutorizado; }
    public void setTotalAutorizado(BigDecimal totalAutorizado) { this.totalAutorizado = totalAutorizado; }

    public BigDecimal getTotalFacturado() { return totalFacturado; }
    public void setTotalFacturado(BigDecimal totalFacturado) { this.totalFacturado = totalFacturado; }

    public String getUuidLocal() { return uuidLocal; }
    public void setUuidLocal(String uuidLocal) { this.uuidLocal = uuidLocal; }

    public Long getUbicacionId() { return ubicacionId; }
    public void setUbicacionId(Long ubicacionId) { this.ubicacionId = ubicacionId; }

    public String getUuidServer() { return uuidServer; }
    public void setUuidServer(String uuidServer) { this.uuidServer = uuidServer; }
    
    public String getTipoDocumento() { return tipoDocumento; }
    public void setTipoDocumento(String tipoDocumento) { this.tipoDocumento = tipoDocumento; }

    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }

    public String getSyncedAt() { return syncedAt; }
    public void setSyncedAt(String syncedAt) { this.syncedAt = syncedAt; }
}
