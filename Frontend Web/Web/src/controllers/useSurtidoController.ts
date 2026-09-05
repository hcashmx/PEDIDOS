// src/controllers/useSurtidoController.ts
import { useState, useEffect } from 'react';
import api from '../api/axiosConfig';
import { PedidoDetalleInfoDTO, PedidoFiltros, DetallePedidoVisualDTO } from '../models/Pedido';

export const useSurtidoController = () => {
    const [pedidos, setPedidos] = useState<PedidoDetalleInfoDTO[]>([]);
    const [loading, setLoading] = useState(false);
    const [isProcessing, setIsProcessing] = useState(false);
    const [filtros, setFiltros] = useState<PedidoFiltros>({
        clienteId: '', agenteId: '', serieId: '', folio: '',
        estadoId: '', inicio: '', fin: ''
    });

    const [detallesPedido, setDetallesPedido] = useState<DetallePedidoVisualDTO[]>([]);
    const [loadingDetalles, setLoadingDetalles] = useState(false);

    const cargarPedidos = async () => {
        setLoading(true);
        try {
            const params = Object.fromEntries(
                Object.entries(filtros).filter(([_, v]) => v !== "" && v !== null)
            );
            const response = await api.get<PedidoDetalleInfoDTO[]>('/administracion/pedidos', { params });
            
            // FILTRO DE NEGOCIO: Solo mostrar Autorizado, En Surtido y Surtido Parcial
            const pedidosAlmacen = (response.status === 204 ? [] : response.data).filter(p => 
                ['AUTORIZADO', 'EN_SURTIDO', 'SURTIDO_PARCIAL','SURTIDO_COMPLETO'].includes(p.estado)
            );
            
            setPedidos(pedidosAlmacen);
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
            // Asegurarnos de que cantidadSurtida inicie en 0 si viene null
            const detallesProcesados = res.data.map(d => ({
                ...d,
                cantidadSurtida: d.cantidadSurtida ?? 0
            }));
            setDetallesPedido(detallesProcesados);
        } catch (error) {
            console.error("Error cargando detalles", error);
            setDetallesPedido([]);
        } finally {
            setLoadingDetalles(false);
        }
    };

    const cambiarEstadoSurtido = async (pedidoId: number, accion: 'iniciar' | 'reanudar' | 'cerrar') => {
        setIsProcessing(true);
        try {
            await api.post(`/almacen/surtido/${pedidoId}/${accion}`);
            await cargarPedidos();
            return { success: true };
        } catch (error: any) {
            const mensaje = error.response?.data || `Error al ${accion} el surtido`;
            console.error(`Error en transición ${accion}:`, mensaje);
            return { success: false, error: mensaje };
        } finally {
            setIsProcessing(false);
        }
    };

    const actualizarCantidadSurtida = async (idDetalle: number, nuevaCantidad: number) => {
        try {
            // Optimistic UI Update
            setDetallesPedido(prev => prev.map(d => 
                d.idDetalle === idDetalle ? { ...d, cantidadSurtida: nuevaCantidad } : d
            ));

            await api.post('/almacen/surtido/detalle/actualizar', {
                idDetalle,
                cantidadSurtida: nuevaCantidad
            });
            console.log("Guardado automático de surtido exitoso");
        } catch (error: any) {
            alert("Error al guardar la cantidad surtida: " + (error.response?.data || error.message));
        }
    };

    const surtirTodo = async () => {
        const detallesActualizados = detallesPedido.map(det => ({
            ...det,
            cantidadSurtida: det.cantidadAutorizada // Surtimos todo lo autorizado
        }));

        setDetallesPedido(detallesActualizados);

        try {
            await Promise.all(
                detallesActualizados.map(det => 
                    actualizarCantidadSurtida(det.idDetalle, det.cantidadAutorizada)
                )
            );
        } catch (error) {
            console.error("Error al surtir todos los productos", error);
        }
    };

    const handleFilterChange = (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) => {
        setFiltros(prev => ({ ...prev, [e.target.name]: e.target.value }));
    };

    useEffect(() => { cargarPedidos(); }, []);

    return { 
        pedidos, loading, isProcessing, filtros, handleFilterChange, aplicarFiltros: cargarPedidos,
        detallesPedido, loadingDetalles, cargarDetallesPedido, actualizarCantidadSurtida,
        cambiarEstadoSurtido, surtirTodo, setDetallesPedido
    };
};