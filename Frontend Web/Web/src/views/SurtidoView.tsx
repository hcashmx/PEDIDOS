// src/views/SurtidoView.tsx
import React, { useState } from 'react';
import { useSurtidoController } from '../controllers/useSurtidoController';
import { 
  Search, Filter, User, Hash, Play, Pause, RotateCcw, Box, PackageCheck, Package, X 
} from 'lucide-react';

export const SurtidoView = () => {
    const { 
        pedidos, loading, isProcessing, filtros, handleFilterChange, aplicarFiltros,
        detallesPedido, loadingDetalles, cargarDetallesPedido, actualizarCantidadSurtida,
        cambiarEstadoSurtido, surtirTodo, setDetallesPedido 
    } = useSurtidoController();

    const [modalOpen, setModalOpen] = useState(false);
    const [pedidoSeleccionado, setPedidoSeleccionado] = useState<any>(null);

    const manejarAccionSurtido = async (pedido: any, accion: 'iniciar' | 'reanudar') => {
        const res = await cambiarEstadoSurtido(pedido.idPedido, accion);
        if (res.success) {
            // Si tiene éxito, abrimos el modal para que empiece a surtir
            setPedidoSeleccionado({...pedido, estado: 'EN_SURTIDO'});
            await cargarDetallesPedido(pedido.idPedido);
            setModalOpen(true);
        } else {
            alert("Error: " + res.error);
        }
    };

    const abrirModalLectura = (pedido: any) => {
        setPedidoSeleccionado(pedido);
        cargarDetallesPedido(pedido.idPedido);
        setModalOpen(true);
    };

    const cerrarSurtido = async () => {
        const res = await cambiarEstadoSurtido(pedidoSeleccionado.idPedido, 'cerrar');
        if (res.success) {
            setModalOpen(false);
        } else {
            alert("Error: " + res.error);
        }
    };

    const handleCantidadChange = (id: number, val: string) => {
        const num = parseFloat(val);
        if (isNaN(num)) return;
        setDetallesPedido(prev => prev.map(d => 
            d.idDetalle === id ? { ...d, cantidadSurtida: num } : d
        ));
    };

    return (
        <div className="flex flex-col gap-6 p-6 bg-slate-50 min-h-screen">
            {/* PANEL DE FILTROS (Igual que en Cobranza, omitido por brevedad, usa el mismo código) */}
            
            {/* TABLA DE RESULTADOS DE ALMACÉN */}
            <div className="bg-white rounded-xl border border-slate-200 overflow-hidden shadow-lg">
                <div className="bg-slate-800 p-3 text-white font-bold flex items-center gap-2">
                    <Box size={18} /> Panel de Surtido de Pedidos
                </div>
                <div className="overflow-x-auto">
                    <table className="w-full text-left border-collapse table-fixed min-w-[1000px]">
                        <thead className="bg-slate-100 text-slate-600 text-xs uppercase tracking-widest border-b">
                            <tr>
                                <th className="p-3 w-[150px]">Pedido</th>
                                <th className="p-3 w-[250px]">Cliente</th>
                                <th className="p-3 w-[180px]">Estado Actual</th>
                                <th className="p-3 w-[150px]">Agente</th>
                                <th className="p-3 w-[180px] text-center">Acciones de Almacén</th>
                            </tr>
                        </thead>
                        <tbody className="divide-y divide-slate-200">
                            {pedidos.map((p) => (
                                <tr key={p.idPedido} className="hover:bg-blue-50 transition-colors text-sm">
                                    <td className="p-4 align-middle">
                                        <div className="font-bold text-slate-900 flex items-center gap-1">
                                            <Hash size={14} className="text-blue-500"/>{p.pedidoFolio}
                                        </div>
                                        <div className="text-xs text-slate-500">ID: {p.idPedido}</div>
                                    </td>
                                    <td className="p-4 align-middle">
                                        <div className="font-bold text-slate-800">{p.clienteNombre}</div>
                                        <div className="text-xs text-slate-500">{p.clienteRFC}</div>
                                    </td>
                                    <td className="p-4 align-middle">
                                      <span className={`px-3 py-1 rounded-full text-xs font-bold border ${
                                                p.estado === 'SURTIDO_COMPLETO' ? 'bg-orange-50 text-orange-700 border-orange-200' :
                                                p.estado === 'EN_SURTIDO' ? 'bg-blue-50 text-blue-700 border-blue-200' :
                                                p.estado === 'SURTIDO_PARCIAL' ? 'bg-yellow-50 text-yellow-700 border-yellow-200' :
                                                p.estado === 'AUTORIZADO' ? 'bg-emerald-50 text-emerald-700 border-emerald-200' :
                                                p.estado === 'RECHAZADO' ? 'bg-red-50 text-red-700 border-red-200' :
                                                'bg-gray-50 text-gray-700 border-gray-200'
                                            }`}>
                                            {p.estado.replace('_', ' ')}
                                        </span>
                                    </td>
                                    <td className="p-4 align-middle text-slate-600 text-xs">{p.agenteVentas}</td>
                                    
                                    <td className="p-4 align-middle text-center">
                                        <div className="flex justify-center gap-2">
                                            {p.estado === 'AUTORIZADO' && (
                                                <button onClick={() => manejarAccionSurtido(p, 'iniciar')} disabled={isProcessing} className="flex items-center gap-1 px-3 py-1.5 bg-blue-600 text-white rounded hover:bg-blue-700 text-xs font-bold">
                                                    <Play size={14} /> Iniciar Surtido
                                                </button>
                                            )}
                                            {p.estado === 'SURTIDO_PARCIAL' && (
                                                <button onClick={() => manejarAccionSurtido(p, 'reanudar')} disabled={isProcessing} className="flex items-center gap-1 px-3 py-1.5 bg-orange-500 text-white rounded hover:bg-orange-600 text-xs font-bold">
                                                    <RotateCcw size={14} /> Reanudar Surtido
                                                </button>
                                            )}
                                            {p.estado === 'EN_SURTIDO' && (
                                                <button onClick={() => abrirModalLectura(p)} className="flex items-center gap-1 px-3 py-1.5 bg-indigo-100 text-indigo-700 border border-indigo-200 rounded hover:bg-indigo-200 text-xs font-bold">
                                                    <Package size={14} /> Continuar Surtido
                                                </button>
                                            )}
                                        </div>
                                    </td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                </div>
            </div>

            {/* MODAL DE SURTIDO FÍSICO */}
            {modalOpen && (
                <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/80 backdrop-blur-sm p-4">
                    <div className="bg-white rounded-2xl shadow-2xl w-full max-w-5xl flex flex-col overflow-hidden">
                        
                        <div className="bg-indigo-900 p-4 flex justify-between items-center text-white">                            
                            <h3 className="font-bold flex items-center gap-2 text-lg">
                                <PackageCheck size={24} className="text-indigo-400"/>
                                Surtido de Pedido: {pedidoSeleccionado?.pedidoFolio}
                            </h3>
                            <button onClick={() => setModalOpen(false)} className="hover:bg-white/20 p-1 rounded-full">
                                <X size={24} />
                            </button>
                        </div>
                        
                        <div className="flex-1 overflow-y-auto p-6 bg-slate-50">
                            <div className="bg-white rounded-lg border border-slate-200 overflow-hidden shadow-sm">
                                <table className="w-full text-left text-sm">
                                    <thead className="bg-slate-100 text-xs text-slate-600 uppercase font-bold">
                                        <tr>
                                            <th className="p-3 pl-4">Producto</th>
                                            <th className="p-3 text-center">Autorizado</th>
                                            <th className="p-3 text-center bg-indigo-50 text-indigo-700 w-[150px]">Cantidad Surtida</th>
                                            <th className="p-3 text-center text-orange-600">Backorder (Pendiente)</th>
                                        </tr>
                                    </thead>
                                    <tbody className="divide-y divide-slate-100">
                                        {detallesPedido.map((det) => {
                                            const pendiente = det.cantidadAutorizada - (det.cantidadSurtida || 0);
                                            return (
                                            <tr key={det.idDetalle} className="hover:bg-slate-50">
                                                <td className="p-3 pl-4">
                                                    <div className="font-bold text-slate-700">{det.nombreProducto}</div>
                                                    <div className="text-xs text-slate-400">{det.sku}</div>
                                                </td>
                                                <td className="p-3 text-center font-bold text-slate-600">
                                                    {det.cantidadAutorizada}
                                                </td>
                                                
                                                {/* COLUMNA EDITABLE: CANTIDAD SURTIDA */}
                                                <td className="p-2 text-center bg-indigo-50/30">
                                                    <input 
                                                        type="number" min="0" max={det.cantidadAutorizada}
                                                        disabled={pedidoSeleccionado?.estado !== 'EN_SURTIDO'}
                                                        className="w-full text-center font-bold rounded outline-none py-1.5 border-indigo-200 focus:ring-2 focus:ring-indigo-400"
                                                        value={det.cantidadSurtida || 0}
                                                        onChange={(e) => handleCantidadChange(det.idDetalle, e.target.value)}
                                                        onBlur={(e) => actualizarCantidadSurtida(det.idDetalle, parseFloat(e.target.value))}
                                                    />
                                                </td>

                                                {/* CÁLCULO DE BACKORDER */}
                                                <td className={`p-3 text-center font-bold ${pendiente > 0 ? 'text-orange-600' : 'text-green-600'}`}>
                                                    {pendiente > 0 ? pendiente : <span className="flex items-center justify-center gap-1"><PackageCheck size={14}/> Listo</span>}
                                                </td>
                                            </tr>
                                        )})}
                                    </tbody>
                                </table>
                            </div>

                            {pedidoSeleccionado?.estado === 'EN_SURTIDO' && (
                                <div className="mt-4 flex justify-start">
                                    <button onClick={surtirTodo} className="text-xs font-bold text-indigo-600 bg-indigo-50 px-3 py-1.5 rounded hover:bg-indigo-100 border border-indigo-200">
                                        Marcar todo como surtido (Al 100%)
                                    </button>
                                </div>
                            )}
                        </div>

                        <div className="p-4 bg-slate-50 border-t border-slate-200 flex justify-end gap-3">
                            <button onClick={() => setModalOpen(false)} className="px-6 py-2.5 rounded-xl font-bold text-slate-600 hover:bg-slate-200">
                                Cerrar Ventana
                            </button>

                            {pedidoSeleccionado?.estado === 'EN_SURTIDO' && (
                                <button 
                                    onClick={cerrarSurtido} disabled={isProcessing}
                                    className="px-8 py-2.5 rounded-xl font-bold text-white bg-indigo-600 hover:bg-indigo-700 flex items-center gap-2 shadow-lg"
                                >
                                    {isProcessing ? <div className="animate-spin rounded-full h-5 w-5 border-2 border-white border-t-transparent" /> : (
                                        <><PackageCheck size={18}/> Finalizar Surtido</>
                                    )}
                                </button>
                            )}
                        </div>
                    </div>
                </div>
            )}
        </div>
    );
};