import { useState, useEffect } from 'react';
import api from '../api/axiosConfig';
import { PedidoDetalleInfoDTO, PedidoFiltros, DetallePedidoVisualDTO } from '../models/Pedido';


export const usePedidosController = () => {
    const [pedidos, setPedidos] = useState<PedidoDetalleInfoDTO[]>([]);
    const [loading, setLoading] = useState(false);
    const [isProcessing, setIsProcessing] = useState(false); // Estado para bloquear botones durante la carga
    const [filtros, setFiltros] = useState<PedidoFiltros>({
        clienteId: '', agenteId: '', serieId: '', folio: '',
        estadoId: '', inicio: '', fin: ''
    });

    const [detallesPedido, setDetallesPedido] = useState<DetallePedidoVisualDTO[]>([]);
    const [loadingDetalles, setLoadingDetalles] = useState(false);
    const totalPedido = detallesPedido.reduce((acc, item) => 
         acc + (item.cantidadAutorizada * item.precioUnitario), 0
    );

    const cargarPedidos = async () => {
        setLoading(true);
        try {
            const params = Object.fromEntries(
                Object.entries(filtros).filter(([_, v]) => v !== "" && v !== null)
            );

            const response = await api.get<PedidoDetalleInfoDTO[]>('/administracion/pedidos', { params });
            setPedidos(response.status === 204 ? [] : response.data);
        } catch (error) {
            console.error("Error al obtener pedidos:", error);
            setPedidos([]);
        } finally {
            setLoading(false);
        }
    };

    const cargarDetallesPedido = async (pedidoId: number) => {
        setLoadingDetalles(true);
        try {
            const res = await api.get<DetallePedidoVisualDTO[]>(`/administracion/pedidos/${pedidoId}/detalles`);
            setDetallesPedido(res.data);
        } catch (error) {
            console.error("Error cargando detalles", error);
            setDetallesPedido([]);
        } finally {
            setLoadingDetalles(false);
        }
    };

    // NUEVA: Actualizar línea (Guardado automático)
    const actualizarCantidadLinea = async (idDetalle: number, nuevaCantidad: number) => {
        try {
            // Optimistic UI Update (Actualizar visualmente antes de confirmar)
            setDetallesPedido(prev => prev.map(d => 
                d.idDetalle === idDetalle ? { ...d, cantidadAutorizada: nuevaCantidad } : d
            ));

            await api.post('/administracion/pedidos/detalle/actualizar-cantidad', {
                idDetalle,
                nuevaCantidad
            });
            console.log("Guardado automático exitoso");
        } catch (error: any) {
            alert("Error al guardar la cantidad: " + (error.response?.data || error.message));
            // Revertir cambios si falla (volver a cargar)
             // cargarDetallesPedido(...) 
        }
    };

    const autorizarTodo = async () => {
        // 1. Creamos una copia del estado actual con las cantidades al máximo
        const detallesActualizados = detallesPedido.map(det => ({
            ...det,
            cantidadAutorizada: det.cantidadSolicitada
        }));

        // 2. Actualizamos el estado visual inmediatamente
        setDetallesPedido(detallesActualizados);

        // 3. Ejecutamos las peticiones al servidor para cada producto
        // Usamos Promise.all para que sea eficiente
        try {
            await Promise.all(
                detallesActualizados.map(det => 
                    actualizarCantidadLinea(det.idDetalle, det.cantidadSolicitada)
                )
            );
            console.log("Todos los productos han sido autorizados al máximo.");
        } catch (error) {
            console.error("Error al autorizar todos los productos", error);
        }
    };

    

    // NUEVA FUNCIÓN: Integración con /pedidos/autorizar-rechazar
    const gestionarPedido = async (pedidoId: number, estadoTexto: string = "", comentario: string = "") => {
        setIsProcessing(true);
        try {
            // El objeto debe usar 'nuevoEstadoId' para coincidir con el DTO de Java
            // y enviar el texto recibido (AUTORIZADO/RECHAZADO)
            const payload = {
                pedidoId: pedidoId,
                nuevoEstado: estadoTexto, // Asegúrate de que aquí diga 'nuevoEstadoId'
                comentario: comentario
            };

            // La ruta base en tu PedidoController es /api/administracion
            await api.post('/administracion/pedidos/autorizar-rechazar', payload);
        
            
            // Refrescamos la lista para ver el nuevo estado en la tabla
            await cargarPedidos(); 
            return { success: true };
        } catch (error: any) {
            // Capturamos el mensaje de error enviado por el ResponseEntity del backend
            const mensaje = error.response?.data || "Error al procesar la solicitud";
            console.error("Error en transición:", mensaje);
            return { success: false, error: mensaje };
        } finally {
            setIsProcessing(false);
        }
    };

    const handleFilterChange = (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) => {
        setFiltros(prev => ({ ...prev, [e.target.name]: e.target.value }));
    };

    useEffect(() => { cargarPedidos(); }, []);

    return { 
        pedidos, 
        loading, 
        isProcessing, // Exportamos para deshabilitar botones en la vista
        filtros, 
        handleFilterChange, 
        aplicarFiltros: cargarPedidos,
        gestionarPedido, // Exportamos la nueva función
        // Exports nuevos
        detallesPedido,
        loadingDetalles,
        cargarDetallesPedido,
        actualizarCantidadLinea,
        autorizarTodo,
        setDetallesPedido // Para inputs controlados
    };
};