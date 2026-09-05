// src/views/PedidosView.tsx
import React from 'react';
import { useState } from 'react';
import { usePedidosController } from '../controllers/usePedidosCobranzaController';
import { 
  Search, Filter, User, Hash, DollarSign, CheckCircle,XCircle,CheckCheck,
  Calendar, ShieldCheck, UserCheck, ShoppingCart, FileText, X ,AlertTriangle 
} from 'lucide-react';




export const PedidosView = () => {
    const { pedidos, loading, filtros, handleFilterChange, aplicarFiltros,
         gestionarPedido, isProcessing, detallesPedido, loadingDetalles, 
         cargarDetallesPedido, actualizarCantidadLinea, setDetallesPedido ,autorizarTodo} = usePedidosController();

    const [modalOpen, setModalOpen] = useState(false);
    const [pedidoSeleccionado, setPedidoSeleccionado] = useState<any>(null);
    const [accion, setAccion] = useState<'AUTORIZADO' | 'RECHAZADO' | 'VER'>('AUTORIZADO');
    const [comentario, setComentario] = useState('');

const abrirModal = (pedido: any, tipo: 'AUTORIZADO' | 'RECHAZADO' | 'VER') => {
    setPedidoSeleccionado(pedido);
    // Si es 'VER', internamente lo tratamos como 'AUTORIZADO' para que el modal 
    // se pinte de azul/gris y no de rojo, pero puedes ajustarlo.
    setAccion(tipo); 
    setComentario(pedido.comentario || ''); // Opcional: cargar comentario existente
    setModalOpen(true);
    cargarDetallesPedido(pedido.idPedido);
};

    const handleCantidadChange = (id: number, val: string) => {
        const num = parseFloat(val);
        if (isNaN(num)) return;
        
        setDetallesPedido(prev => prev.map(d => 
            d.idDetalle === id ? { ...d, cantidadAutorizada: num } : d
        ));
    };

    const confirmarAccion = async () => {
        if (accion === 'RECHAZADO' && !comentario.trim()) {
            alert("El comentario es obligatorio para rechazos.");
            return;
        }

        // Aquí usamos p.idPedido que es el campo correcto de tu DTO
        
      
        const res = await gestionarPedido(pedidoSeleccionado.idPedido, accion, comentario);
        if (res.success) {
            setModalOpen(false);
        } else {
            alert("Error: " + res.error);
        }
    };

    const hayAutorizados = detallesPedido.some(
        d => (d.cantidadAutorizada ?? 0) > 0
    );

    return (
        <div className="flex flex-col gap-6 p-6 bg-slate-50 min-h-screen">
            
            {/* PANEL DE FILTROS AVANZADOS (Todos los parámetros del Controller) */}
            <div className="bg-white p-4 rounded-lg border border-slate-200 shadow-sm">
                <div className="flex items-center gap-2 mb-3 text-slate-800 font-bold text-base border-b pb-1">
                    <Filter size={16} className="text-blue-600" /> 
                    <span>Panel de Control de Pedidos</span>
                </div>
                
                <div className="grid grid-cols-1 md:grid-cols-3 lg:grid-cols-4 gap-3">
                    <div>
                    <label className="block text-[10px] font-bold text-slate-500 mb-0.5 uppercase tracking-wider">
                        Folio
                    </label>
                    <input
                        name="folio"
                        value={filtros.folio}
                        onChange={handleFilterChange}
                        placeholder="Buscar folio..."
                        className="w-full border-slate-200 rounded-md p-1.5 h-8 text-xs focus:ring-1 focus:ring-blue-500 outline-none"
                    />
                    </div>

                    <div>
                    <label className="block text-[10px] font-bold text-slate-500 mb-0.5 uppercase tracking-wider">
                        ID Cliente
                    </label>
                    <input
                        name="clienteId"
                        type="number"
                        value={filtros.clienteId}
                        onChange={handleFilterChange}
                        className="w-full border-slate-200 rounded-md p-1.5 h-8 text-xs"
                        placeholder="0"
                    />
                    </div>

                    <div>
                    <label className="block text-[10px] font-bold text-slate-500 mb-0.5 uppercase tracking-wider">
                        ID Agente
                    </label>
                    <input
                        name="agenteId"
                        type="number"
                        value={filtros.agenteId}
                        onChange={handleFilterChange}
                        className="w-full border-slate-200 rounded-md p-1.5 h-8 text-xs"
                        placeholder="0"
                    />
                    </div>

                    <div>
                    <label className="block text-[10px] font-bold text-slate-500 mb-0.5 uppercase tracking-wider">
                        Estado (ID)
                    </label>
                    <input
                        name="estadoId"
                        type="number"
                        value={filtros.estadoId}
                        onChange={handleFilterChange}
                        className="w-full border-slate-200 rounded-md p-1.5 h-8 text-xs"
                        placeholder="ID Estado"
                    />
                    </div>

                    <div>
                    <label className="block text-[10px] font-bold text-slate-500 mb-0.5 uppercase tracking-wider">
                        Fecha Inicio
                    </label>
                    <input
                        name="inicio"
                        type="datetime-local"
                        value={filtros.inicio}
                        onChange={handleFilterChange}
                        className="w-full border-slate-200 rounded-md p-1.5 h-8 text-xs"
                    />
                    </div>

                    <div>
                    <label className="block text-[10px] font-bold text-slate-500 mb-0.5 uppercase tracking-wider">
                        Fecha Fin
                    </label>
                    <input
                        name="fin"
                        type="datetime-local"
                        value={filtros.fin}
                        onChange={handleFilterChange}
                        className="w-full border-slate-200 rounded-md p-1.5 h-8 text-xs"
                    />
                    </div>

                    <div>
                    <label className="block text-[10px] font-bold text-slate-500 mb-0.5 uppercase tracking-wider">
                        ID Serie
                    </label>
                    <input
                        name="serieId"
                        type="number"
                        value={filtros.serieId}
                        onChange={handleFilterChange}
                        className="w-full border-slate-200 rounded-md p-1.5 h-8 text-xs"
                        placeholder="ID Serie"
                    />
                    </div>

                    <div className="flex items-end">
                    <button
                        onClick={aplicarFiltros}
                        disabled={loading}
                        className="w-full bg-blue-600 hover:bg-blue-700 text-white font-semibold py-1.5 rounded-md flex items-center justify-center gap-2 transition-all"
                    >
                        {loading
                        ? <div className="animate-spin rounded-full h-4 w-4 border-2 border-white border-t-transparent" />
                        : <><Search size={16} /> Aplicar</>
                        }
                    </button>
                    </div>
                </div>
            </div>


            {/* TABLA DE RESULTADOS (Visualizando el 100% de PedidoDetalleInfoDTO) */}
            <div className="bg-white rounded-xl border border-slate-200 overflow-hidden shadow-lg">
                <div className="overflow-x-auto">
                    <table className="w-full text-left border-collapse table-fixed min-w-[1000px]">
                        <thead className="bg-slate-900 text-slate-200 text-xs uppercase tracking-widest">
                            <tr>
                                <th className="p-3 w-[120px]">ID / Serie</th>
                                <th className="p-3 w-[200px]">Información Cliente</th>
                                <th className="p-3 w-[180px]">Importes (MXN)</th>
                                <th className="p-3 w-[180px]">Fechas y Plazos</th>
                                <th className="p-3 w-[200px]">Auditoría / Usuarios</th>
                                <th className="p-3 w-[100px] text-center">Acciones</th>
                                
                            </tr>
                        </thead>
                        <tbody className="divide-y divide-slate-200">
                            {pedidos.map((p) => (
                                <tr key={`${p.serie}-${p.pedidoFolio}`} className="even:bg-slate-100/100 hover:bg-blue-100/100 transition-colors text-[11px]">
                                    
                                    {/* 1. Serie y Folio */}
                                    <td className="p-4 align-top">
                                        <div className="flex flex-col">
                                            <span className="flex items-center gap-1 font-bold text-slate-900 text-base">
                                                <Hash size={14} className="text-blue-500"/>{p.idPedido} 
                                            </span>
                                            <span
                                                className={`flex items-center justify-center px-4 py-1.5 mt-2 rounded-full text-[10px] font-black tracking-widest border ${
                                                    p.estado === 'AUTORIZADO'
                                                    ? 'bg-green-100 text-green-700 border-green-200'
                                                    : p.estado === 'RECHAZADO'
                                                    ? 'bg-red-100 text-red-700 border-red-200'
                                                    : 'bg-blue-100 text-blue-700 border-blue-200'
                                                }`}
                                                >
                                                {p.estado}
                                            </span>

                                        </div>
                                    </td>

                                    {/* 2. Cliente Completo */}
                                    <td className="p-4 align-top">
                                        <div className="flex flex-col gap-0.5">
                                            <div className="flex items-center gap-1 font-bold text-slate-800">
                                                <User size={14} className="text-slate-400"/> {p.clienteNombre}
                                            </div>
                                            <div className="text-xs font-mono text-blue-600">{p.clienteRFC}</div>
                                            <div className="text-xs text-slate-500 leading-tight">"{p.clienteRazonSocial}"</div>
                                            <div className="flex gap-2 mt-1">
                                                <span className="text-[10px] bg-slate-100 px-1.5 py-0.5 rounded text-slate-600 font-bold">ID: {p.clienteId}</span>
                                                <span className="text-[10px] bg-orange-50 px-1.5 py-0.5 rounded text-orange-700 font-bold">Plazo: {p.plazoCliente} días</span>
                                            </div>
                                        </div>
                                    </td>

                                    {/* 3. Importes */}
                                    <td className="p-4 align-top">
                                        <div className="space-y-1">
                                            <div className="flex justify-between text-xs">
                                                <span className="text-slate-400">Solicitado:</span>
                                                <span className="font-bold text-slate-700">${p.totalSolicitado?.toLocaleString()}</span>
                                            </div>
                                            <div className="flex justify-between text-xs">
                                                <span className="text-slate-400">Autorizado:</span>
                                                <span className="font-bold text-green-600">${p.totalAutorizado?.toLocaleString()}</span>
                                            </div>
                                            <div className="flex justify-between text-xs border-t pt-1 mt-1">
                                                <span className="text-slate-400">Facturado:</span>
                                                <span className="font-bold text-blue-600">${p.totalFacturado?.toLocaleString()}</span>
                                            </div>
                                        </div>
                                    </td>

                                    {/* 4. Fechas y Plazos */}
                                    <td className="p-4 align-top">
                                        <div className="text-[11px] space-y-1.5">
                                            <div className="flex items-center gap-2">
                                                <Calendar size={12} className="text-slate-400"/>
                                                <span className="text-slate-600">Creación: <b>{p.fechaCreacion}</b></span>
                                            </div>
                                            <div className="flex items-center gap-2">
                                                <ShieldCheck size={12} className="text-slate-400"/>
                                                <span className="text-slate-600">Autorización: <b>{p.fechaAutorizacion || '---'}</b></span>
                                            </div>
                                            
                                            <div className="bg-amber-50 text-amber-800 p-1 rounded border border-amber-100 text-center font-bold">
                                                {p.plazoPedido === 0 
                                                    ? "Plazo Pedido: Al contado" 
                                                    : `Plazo Pedido: ${p.plazoPedido} días (A crédito)`
                                                }
                                            </div>
                                        </div>
                                    </td>

                                    {/* 5. Usuarios y Agente */}
                                    <td className="p-4 align-top">
                                        <div className="text-[11px] space-y-1">
                                            <div className="flex items-center gap-1.5 text-slate-700">
                                                <ShoppingCart size={12} className="text-blue-400"/>
                                                <b>Agente:</b> {p.agenteVentas} <span className="text-slate-300">#{p.agenteVentasId}</span>
                                            </div>
                                            <div className="flex items-center gap-1.5 text-slate-500">
                                                <FileText size={12}/>
                                                <b>Creó:</b> {p.usuarioCreo} <span className="text-slate-300">#{p.usuarioCreoId}</span>
                                            </div>
                                            <div className="flex items-center gap-1.5 text-slate-500">
                                                <UserCheck size={12}/>
                                                <b>Autorizó:</b> {p.usuarioAutorizo || 'N/A'} {p.usuarioAutorizoId && <span className="text-slate-300">#{p.usuarioAutorizoId}</span>}
                                            </div>
                                            <div className="flex items-center gap-1.5 text-slate-500 border-t pt-1">
                                                <DollarSign size={12} className="text-green-500"/>
                                                <b>Facturó:</b> {p.usuarioFacturo || 'Pendiente'}
                                            </div>
                                        </div>
                                    </td>

                                    {/* 6. Acciones */}
                                    <td className="p-4 align-middle text-center">
                                        <div className="flex flex-col items-center gap-2">
                                            {/* SI EL PEDIDO ESTÁ PENDIENTE: Muestra botones de decisión */}
                                            {p.estado == 'RECIBIDO' ? (
                                                <div className="flex gap-2 mt-2">
                                                    <button 
                                                        onClick={() => abrirModal(p, 'AUTORIZADO')}
                                                        className="p-1.5 bg-green-50 text-green-600 rounded-lg hover:bg-green-600 hover:text-white transition-colors border border-green-200"
                                                        title="Autorizar"
                                                    >
                                                        <CheckCircle size={18} />
                                                    </button>
                                                    <button 
                                                        onClick={() => abrirModal(p, 'RECHAZADO')}
                                                        className="p-1.5 bg-red-50 text-red-600 rounded-lg hover:bg-red-600 hover:text-white transition-colors border border-red-200"
                                                        title="Rechazar"
                                                    >
                                                        <XCircle size={18} />
                                                    </button>
                                                </div>
                                            ) : (
                                                /* SI YA ESTÁ FINALIZADO: Muestra botón de solo consulta */
                                                <button 
                                                    onClick={() => abrirModal(p, 'VER')}
                                                    className="flex items-center gap-1 px-3 py-1.5 bg-slate-100 text-slate-600 rounded-lg hover:bg-slate-200 transition-colors border border-slate-300 font-bold text-[10px]"
                                                >
                                                    <FileText size={14} />
                                                    VER DETALLE
                                                </button>
                                            )}
                                        </div>
                                    </td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                </div>
                {pedidos.length === 0 && !loading && (
                    <div className="flex flex-col items-center justify-center p-16 text-slate-400">
                        <div className="bg-slate-100 p-4 rounded-full mb-4"><Search size={40} /></div>
                        <p className="text-lg font-medium">No se encontraron pedidos</p>
                        <p className="text-sm">Intenta ajustar los criterios de búsqueda</p>
                    </div>
                )}
            </div>
            {/* MODAL DE DECISIÓN */}
            {modalOpen && (
                <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/80 backdrop-blur-sm p-4">
                    {/* MODAL CONTAINER: Aumentado a max-w-5xl para que quepa la tabla */}
                    <div className="bg-white rounded-2xl shadow-2xl w-full max-w-5xl max-h-[90vh] flex flex-col overflow-hidden border border-slate-200">
                        
                        {/* 1. HEADER */}
                            <div className={`p-4 flex justify-between items-center text-white ${  accion === 'AUTORIZADO' ? 'bg-slate-800' :  accion === 'RECHAZADO' ? 'bg-red-900' : 
                                'bg-blue-900' // Color para 'VER'
                                }`}>                            
                                <div className="flex flex-col">
                                <h3 className="font-bold flex items-center gap-2 text-lg uppercase tracking-wider">
                                    {accion === 'AUTORIZADO' ? (   <ShieldCheck size={24} className="text-green-400"/>) : accion === 'RECHAZADO' ? (<XCircle size={24} className="text-red-400"/> ) : (<FileText size={24} className="text-blue-400"/>
)}                                  {accion === 'AUTORIZADO' ? 'Autorizar Pedido' 
                                                : accion === 'RECHAZADO' 
                                                    ? 'Rechazar Pedido' 
                                                    : 'Detalle del Pedido'} <span className="font-mono bg-white/20 px-2 rounded">{pedidoSeleccionado?.pedidoFolio}</span>
                                </h3>
                                <span className="text-xs text-slate-300 ml-8">Cliente: {pedidoSeleccionado?.clienteNombre} ({pedidoSeleccionado?.clienteId})</span>
                            </div>
                            <button onClick={() => setModalOpen(false)} className="hover:bg-white/20 p-1 rounded-full transition-colors">
                                <X size={24} />
                            </button>
                        </div>
                        
                        {/* 2. CONTENIDO SCROLLABLE */}
                        <div className="flex-1 overflow-y-auto p-6 bg-slate-50">
                            
                            {/* A. DASHBOARD DE RIESGO (Crédito y Facturas Vencidas) */}
                           

                            {/* B. TABLA DE PRODUCTOS EDITABLE */}
                            <div className="bg-white rounded-lg border border-slate-200 overflow-hidden mb-6 shadow-sm">
                                <div className="bg-slate-100 px-4 py-3 border-b border-slate-200 flex justify-between items-center">
                                    <h4 className="font-bold text-sm text-slate-700 flex items-center gap-2">
                                        <ShoppingCart size={16} className="text-slate-500"/> 
                                        Detalle y Ajuste de Cantidades
                                    </h4>
                                    {loadingDetalles && <span className="text-xs text-blue-600 animate-pulse font-semibold flex items-center gap-1">Cargando...</span>}
                                </div>
                                
                                <table className="w-full text-left text-sm">
                                    <thead className="bg-slate-50 text-xs text-slate-500 uppercase font-bold tracking-wider">
                                        <tr>
                                            <th className="p-3 pl-4">Producto</th>
                                            <th className="p-3 text-right">Precio U.</th>
                                            <th className="p-3 text-center bg-slate-100/50 w-[100px]">Solicitado</th>
                                            <th className="p-3 text-center bg-yellow-50 text-yellow-700 border-x border-yellow-100 w-[130px]">Autorizar</th>
                                            <th className="p-3 text-right w-[140px] pr-4">Total Línea</th>
                                        </tr>
                                    </thead>
                                    <tbody className="divide-y divide-slate-100">
                                        {detallesPedido.map((det) => (
                                            <tr key={det.idDetalle} className="hover:bg-slate-50 transition-colors">
                                                <td className="p-3 pl-4">
                                                    <div className="font-bold text-slate-700 text-sm">{det.nombreProducto}</div>
                                                    <div className="text-xs text-slate-400 font-mono">{det.sku} ({det.productoId})</div>
                                                </td>
                                                <td className="p-3 text-right text-slate-600 font-mono">
                                                    ${det.precioUnitario.toFixed(2)}
                                                </td>
                                                
                                                {/* COLUMNA 1: CANTIDAD ORIGINAL (SOLO LECTURA) */}
                                                <td className="p-3 text-center">
                                                    <span className="bg-slate-100 text-slate-500 px-2 py-1 rounded font-bold text-xs">
                                                        {det.cantidadSolicitada}
                                                    </span>
                                                </td>

                                                {/* COLUMNA 2: CANTIDAD A AUTORIZAR (EDITABLE) */}
                                                <td className="p-2 text-center bg-yellow-50/30 border-x border-yellow-100">
                                                    <input 
                                                        type="number"
                                                        min="0"
                                                        disabled={pedidoSeleccionado?.estado === 'AUTORIZADO' || pedidoSeleccionado?.estado === 'RECHAZADO'}
                                                        max={det.cantidadSolicitada} // No permitir subir más de lo pedido
                                                    className={`w-full text-center font-bold rounded outline-none py-1.5 shadow-sm 
                                                            ${(pedidoSeleccionado?.estado === 'AUTORIZADO' || pedidoSeleccionado?.estado === 'RECHAZADO') 
                                                                ? 'bg-slate-100 text-slate-500 border-slate-200 cursor-not-allowed' 
                                                                : 'bg-white text-slate-800 border-yellow-300 focus:ring-2 focus:ring-yellow-400'}`}                                                        value={det.cantidadAutorizada}
                                                        // 1. Actualiza estado local visualmente (rápido)
                                                        onChange={(e) => handleCantidadChange(det.idDetalle, e.target.value)}
                                                        // 2. Guarda en base de datos al salir del input (automático)
                                                        onBlur={(e) => actualizarCantidadLinea(det.idDetalle, parseFloat(e.target.value))}
                                                    />
                                                </td>

                                                <td className="p-3 text-right font-bold text-slate-800 pr-4 font-mono">
                                                    ${(det.cantidadAutorizada * det.precioUnitario).toLocaleString(undefined, {minimumFractionDigits:2})}
                                                </td>
                                            </tr>
                                        ))}
                                    </tbody>
                                </table>
    
                                {detallesPedido.length === 0 && !loadingDetalles && (
                                    <div className="p-8 text-center text-slate-400 text-sm italic">
                                        No hay detalles disponibles para este pedido.
                                    </div>
                                )}
                            </div>

                            {/* BOTÓN AUTORIZAR TODO */}
                            {pedidoSeleccionado?.estado !== 'AUTORIZADO' && pedidoSeleccionado?.estado !== 'RECHAZADO'&& pedidoSeleccionado?.estado === 'RECIBIDO'  && (

                                <div className="flex w-full items-center justify-center gap-3">
                                    <button
                                        onClick={autorizarTodo}
                                        type="button"
                                        className="flex items-center gap-1.5 px-3 py-1.5 bg-green-50 text-green-700 border border-green-200 rounded-lg text-xs font-bold hover:bg-green-600 hover:text-white transition-all shadow-sm"
                                    >
                                        <CheckCheck size={14} />
                                        Autorizar todas las cantidades al Máximo
                                    </button>

                                    {loadingDetalles && (
                                        <span className="text-xs text-blue-600 animate-pulse font-semibold">
                                            Cargando...
                                        </span>
                                    )}
                                </div>
                            )}
                            {/* C. CAMPO DE COMENTARIOS */}
                            <div className="mt-6">
                                <label className="block text-xs font-bold text-slate-500 mb-2 uppercase tracking-wide">
                                    Comentario de {accion} {accion === 'RECHAZADO' && <span className="text-red-500">*</span>}
                                </label>

                               <textarea 
                                    value={comentario}
                                    onChange={(e) => setComentario(e.target.value)}
                                    // Ahora solo es lectura si el pedido YA fue procesado (Autorizado o Rechazado anteriormente)
                                       readOnly={(pedidoSeleccionado?.estado !== 'RECIBIDO')} 
                                        placeholder={
                                        accion === 'RECHAZADO'
                                        ? "Explica por qué se rechaza (Ej. Límite de crédito excedido)..."
                                        : "Notas para almacén o facturación..."
                                    }
                                    className={`w-full border-slate-300 rounded-xl p-3 text-sm h-24 outline-none resize-none shadow-sm
                                    ${(pedidoSeleccionado?.estado === 'AUTORIZADO' || pedidoSeleccionado?.estado === 'RECHAZADO'||pedidoSeleccionado?.estado !== 'RECIBIDO') 
                                        ? "bg-slate-100 cursor-not-allowed text-slate-500" 
                                        : "bg-white focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
                                    }`}
                                />
                            </div>
                        </div>

                        {/* 3. FOOTER BOTONES */}
                        <div className="p-4 bg-slate-50 border-t border-slate-200 flex justify-end gap-3 z-10">
                            <button 
                                onClick={() => setModalOpen(false)}
                                className="px-6 py-2.5 rounded-xl font-bold text-slate-600 hover:bg-slate-200 transition-colors border border-transparent"
                            >
                                {pedidoSeleccionado?.estado === 'AUTORIZADO' || pedidoSeleccionado?.estado === 'RECHAZADO'|| pedidoSeleccionado?.estado !== 'RECIBIDO' ? 'Cerrar' : 'Cancelar'}
                            </button>

                            {pedidoSeleccionado?.estado !== 'AUTORIZADO' && pedidoSeleccionado?.estado !== 'RECHAZADO' && pedidoSeleccionado?.estado =='RECIBIDO' &&(
                                <button 
                                    onClick={confirmarAccion}
                                    disabled={
                                        isProcessing || (accion === 'AUTORIZADO' && !hayAutorizados)
                                    }
                                    className={`px-8 py-2.5 rounded-xl font-bold text-white shadow-lg transition-all active:scale-95 flex items-center gap-2
                                        ${
                                            accion === 'AUTORIZADO'
                                            ? hayAutorizados
                                                ? 'bg-green-600 hover:bg-green-700 shadow-green-200'
                                                : 'bg-gray-300 text-gray-500 cursor-not-allowed shadow-none'
                                            : 'bg-red-600 hover:bg-red-700 shadow-red-200'
                                        }
                                    `}
                                >



                                    {isProcessing ? <div className="animate-spin rounded-full h-5 w-5 border-2 border-white border-t-transparent" /> : (
                                        <>
                                            {accion === 'AUTORIZADO' ? <CheckCircle size={18}/> : <XCircle size={18}/>}
                                            Confirmar {accion}
                                        </>
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