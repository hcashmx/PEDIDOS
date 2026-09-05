import { BrowserRouter as Router } from 'react-router-dom';
import { Toaster } from 'sonner';
import { AppRouter } from './router/AppRouter';
import React from 'react';

function App() {
  return (
    <Router>
      {/* Toaster permite mostrar los mensajes de "Inicio de sesión exitoso" */}
      <Toaster position="top-right" richColors />

      {/* Aquí cargamos toda la lógica de rutas y protección */}
      <AppRouter />
    </Router>
  );
}

export default App;
