export interface PedidoDetalleInfoDTO {
    idPedido: number;
    // Serie y Folio
    serieId: number;
    serie: string;
    pedidoFolio: string;

    // Cliente
    clienteId: number;
    clienteNombre: string;
    clienteRFC: string;
    clienteRazonSocial: string;
    plazoCliente: number;

    // Importes y Fechas
    plazoPedido: number;
    totalSolicitado: number;
    fechaCreacion: string; // Se recibe como string ISO o formato fecha
    totalAutorizado: number;
    fechaAutorizacion: string | null;
    totalFacturado: number;
    estado: string;

    // Usuarios y Agente
    agenteVentasId: number;
    agenteVentas: string;
    usuarioCreoId: number;
    usuarioCreo: string;
    usuarioAutorizoId: number | null;
    usuarioAutorizo: string | null;
    usuarioFacturoId: number | null;
    usuarioFacturo: string | null;
}

/**
 * Interfaz para la petición de Autorización/Rechazo
 * Basado en TransicionPedidoRequest (usado en el Controller)
 */
export interface TransicionPedidoRequest {
    pedidoId: number;
    nuevoEstadoId: number;
    comentario: string;
}

/**
 * Interfaz para los filtros de búsqueda
 */
export interface PedidoFiltros {
    clienteId?: number | string;
    agenteId?: number | string;
    serieId?: number | string;
    folio?: string;
    estadoId?: number | string;
    inicio?: string; // Formato ISO para LocalDateTime
    fin?: string;    // Formato ISO para LocalDateTime
}


export interface DetallePedidoVisualDTO {
  idDetalle: number;          // ID de la línea (id_detalle_pedido)
  productoId: number;        
  nombreProducto: string;     // Nombre real desde tabla productos
  sku: string;                // Código desde tabla productos
  cantidadSolicitada: number; 
  cantidadAutorizada: number; // Campo que el usuario editará
  precioUnitario: number;  
  cantidadSurtida: number;   
  importeTotal: number;       // (cantidadAutorizada * precioUnitario)
}


export interface UpdateCantidadRequest {
  idDetalle: number;
  nuevaCantidad: number;
}