import api from '../api/axiosConfig';
import { PedidoDetalleInfoDTO } from '../models/Pedido';

export const fetchPedidos = async (filtros: any): Promise<PedidoDetalleInfoDTO[]> => {
    const response = await api.get('/administracion/pedidos', { params: filtros });
    return response.data;
};