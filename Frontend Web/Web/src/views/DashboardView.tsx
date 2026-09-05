import React, { useState } from 'react'; // 1. Agregamos useState
import { useDashboardController } from '../controllers/useDashboardController';
import { PedidosView } from './PedidosCobranzaView'; // 2. Importamos tu nuevo componente
import { SurtidoView } from './SurtidoView'; // <--- IMPORTANTE: Importar la nueva vista
import {
  LayoutDashboard,
  Users,
  Settings,
  LogOut,
  Package,
  Bell,
  Search,
  Box,
  ClipboardList // Nuevo icono para Pedidos
} from 'lucide-react';

export const DashboardView = () => {
  const { user, handleLogout } = useDashboardController();
  // 3. Estado para controlar qué vista se muestra
  const [currentView, setCurrentView] = useState<'resumen' | 'pedidos' | 'almacen'>('resumen');

  if (!user) return (
    <div className="flex h-screen items-center justify-center">
      <div className="animate-spin rounded-full h-12 w-12 border-b-2 border-blue-600"></div>
    </div>
  );

  return (
    <div className="flex h-screen bg-slate-100 font-sans">
      {/* SIDEBAR */}
      <aside className="w-64 bg-slate-900 text-slate-300 flex flex-col">
        <div className="p-6 text-white font-bold text-xl flex items-center gap-2">
          <div className="bg-blue-600 p-1.5 rounded-lg">
            <Package size={20} />
          </div>
          ERP System
        </div>

        <nav className="flex-1 px-4 space-y-2">
          <NavItem 
            icon={<LayoutDashboard size={20} />} 
            label="Dashboard" 
            active={currentView === 'resumen'} 
            onClick={() => setCurrentView('resumen')}
          />
          {/* 4. Nuevo ítem de navegación para Pedidos */}
          <NavItem 
            icon={<ClipboardList size={20} />} 
            label="Pedidos" 
            active={currentView === 'pedidos'} 
            onClick={() => setCurrentView('pedidos')}
          />

          <NavItem 
            icon={<Box size={20} />} 
            label="Almacen" 
            active={currentView === 'almacen'} 
            onClick={() => setCurrentView('almacen')}
  />
          <NavItem icon={<Users size={20} />} label="Usuarios" />
          <NavItem icon={<Package size={20} />} label="Productos" />
          <NavItem icon={<Settings size={20} />} label="Configuración" />
        </nav>

        <div className="p-4 border-t border-slate-800">
          <button
            onClick={handleLogout}
            className="flex items-center gap-3 px-4 py-3 w-full text-left hover:bg-red-500/10 hover:text-red-500 rounded-xl transition-colors"
          >
            <LogOut size={20} />
            <span className="font-medium">Cerrar Sesión</span>
          </button>
        </div>
      </aside>

      {/* CONTENIDO PRINCIPAL */}
      <main className="flex-1 flex flex-col overflow-hidden">
        {/* ... (TOPBAR se mantiene igual) ... */}
        <header className="h-16 bg-white border-b border-slate-200 flex items-center justify-between px-8">
            <div className="relative w-96">
                <Search className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" size={18} />
                <input
                    type="text"
                    placeholder="Buscar..."
                    className="w-full bg-slate-50 border-none rounded-lg pl-10 pr-4 py-2 focus:ring-2 focus:ring-blue-500 transition-all"
                />
            </div>
            {/* Información del usuario usando los datos del userProfile */}
            <div className="flex items-center gap-4">
                <div className="text-right">
                    <p className="text-sm font-semibold text-slate-700 leading-none">{user.nombre}</p>
                    <p className="text-xs text-slate-500 mt-1 capitalize">{user.roles}</p>
                </div>
                <div className="w-10 h-10 bg-blue-100 text-blue-600 rounded-full flex items-center justify-center font-bold">
                    {user.nombre.charAt(0)}
                </div>
            </div>
        </header>

        {/* ÁREA DE TRABAJO DINÁMICA */}
        {/* ÁREA DE TRABAJO DINÁMICA */}
        <section className="flex-1 overflow-y-auto p-8">
          {currentView === 'resumen' && (
            <div className="space-y-8">
              <div>
                <h2 className="text-2xl font-bold text-slate-800">Bienvenido de nuevo, {user.nombre}</h2>
                <p className="text-slate-500">Aquí tienes un resumen de la actividad de hoy.</p>
              </div>

              {/* Grid de Estadísticas */}
              <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
                <StatCard title="Ventas Totales" value="$124,500" change="+12.5%" />
                <StatCard title="Pedidos Pendientes" value="18" change="-2" />
                <StatCard title="En Surtido" value="5" change="+1" />
                <StatCard title="Usuarios Activos" value="12" change="+0" />
              </div>

              {/* Aquí podrías poner una gráfica o tabla rápida */}
              <div className="bg-white p-6 rounded-xl border border-slate-200 shadow-sm h-64 flex items-center justify-center text-slate-400">
                  Espacio para Gráficas de Rendimiento
              </div>
            </div>
          )}

          {currentView === 'pedidos' && <PedidosView />}

          {currentView === 'almacen' && <SurtidoView />}
        </section>
      </main>
    </div>
  );
};


// Componente para las tarjetas de estadísticas
const StatCard = ({ title, value, change }: { title: string, value: string, change: string }) => (
  <div className="bg-white p-6 rounded-xl border border-slate-200 shadow-sm">
    <p className="text-sm text-slate-500 font-medium">{title}</p>
    <div className="flex items-end justify-between mt-2">
      <h4 className="text-2xl font-bold text-slate-800">{value}</h4>
      <span className={`text-xs font-bold px-2 py-1 rounded-full ${
        change.startsWith('+') ? 'bg-green-100 text-green-600' : 'bg-red-100 text-red-600'
      }`}>
        {change}
      </span>
    </div>
  </div>
);
// Actualizamos el NavItem para que acepte el evento onClick
const NavItem = ({ icon, label, active = false, onClick }: { icon: React.ReactNode, label: string, active?: boolean, onClick?: () => void }) => (
  <button 
    onClick={onClick}
    className={`flex items-center gap-3 px-4 py-3 rounded-xl transition-all w-full text-left ${
      active ? 'bg-blue-600 text-white shadow-lg shadow-blue-600/20' : 'hover:bg-slate-800 hover:text-white text-slate-400'
    }`}
  >
    {icon}
    <span className="font-medium">{label}</span>
  </button>
);