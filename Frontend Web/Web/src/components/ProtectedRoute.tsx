import { Navigate, Outlet } from 'react-router-dom';
import React from 'react';

export const ProtectedRoute = () => {
  // Obtenemos los datos directamente del storage
  const token = localStorage.getItem('accessToken');
  const user = localStorage.getItem('userProfile');

  // Depuración: Esto te ayudará a ver en la consola qué falta si te rebota
  console.log("Chequeo de Ruta Protegida:", { tieneToken: !!token, tieneUser: !!user });

  // Si falta cualquiera de los dos, redirigir al login
  if (!token || !user) {
    return <Navigate to="/" replace />;
  }

  // Si todo está correcto, renderiza el Dashboard
  return <Outlet />;
};
