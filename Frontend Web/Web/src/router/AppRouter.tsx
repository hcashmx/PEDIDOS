import { Routes, Route, Navigate } from 'react-router-dom';
import { LoginView } from '../views/LoginView';
import { DashboardView } from '../views/DashboardView';
import { ProtectedRoute } from '../components/ProtectedRoute';
import React from 'react';



export const AppRouter = () => {
  return (
    <Routes>
      {/* RUTAS PÚBLICAS */}
      <Route path="/" element={<LoginView />} />

      {/* RUTAS PROTEGIDAS */}
      <Route element={<ProtectedRoute />}>
        <Route path="/dashboard" element={<DashboardView />} />
        {/* Agrega aquí más rutas como /pedidos, /usuarios, etc. */}
      </Route>

      {/* REDIRECCIÓN SI LA RUTA NO EXISTE */}
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
};
