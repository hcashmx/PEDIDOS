package com.cyssoftware.effyflow.pedido.sincronizacion.dto


import kotlinx.serialization.Serializable



@Serializable
data class PedidoConDetallesDTO(
    val pedido: PedidoDTO,
    val detalles: List<DetallePedidoDTO>
)

@Serializable
data class PedidoDTO(
    val id_pedido: Long,
    val cliente_id: Long,
    val agente_ventas_id: Long,
    val usuario_creo_id: Long,
    val usuario_autorizo_id: Long?,
    val serie_id: Long,
    val folio: Long,
    val fecha_creacion: String,
    val fecha_autorizacion: String? = null,
    val fecha_envio_almacen: String? = null,
    val fecha_cierre: String? = null,
    val plazo: Long,
    val estado: String?,
    val observaciones: String? = null,
    val venta_id: Long? = null,
    val total_solicitado: Double?,
    val total_autorizado: Double?,
    val total_facturado: Double?,
    val uuid_local: String?,
    val uuid_server: String? = null,
    val updated_at: String?,
    val synced_at: String?,
    val tipo_documento: String?,
    val ubicacion_id: Long?,
    val tipo_venta: String? = null
)

@Serializable
data class DetallePedidoDTO(
    val id_detalle_pedido: Long,
    val pedido_id: Long,
    val producto_id: Long,
    val presentacion_id: Long?,
    val almacen_id: Long?,
    val cantidad_solicitada: Double,
    val cantidad_autorizada: Double,
    val cantidad_reservada: Double,
    val cantidad_surtida: Double,
    val cantidad_cancelada: Double,
    val precio_unitario: Double?,
    val descuento: Double?,
    val impuestos: String?,
    val total_linea: Double?,
    val estado_linea: String?,
    val updated_at: String?
)
