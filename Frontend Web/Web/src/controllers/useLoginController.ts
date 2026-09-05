import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { authService } from '../models/authService';
import { LoginRequest } from '../types/auth.types';
import { toast } from 'sonner';

export const useLoginController = () => {
  const [loading, setLoading] = useState(false);
  const navigate = useNavigate();

  const handleLogin = async (data: LoginRequest) => {
    setLoading(true);
    try {
      // Llamada al modelo
      const response = await authService.login(data);

      // Persistencia de los datos retornados por el backend
      localStorage.setItem('accessToken', response.accessToken);
      localStorage.setItem('refreshToken', response.refreshToken);

      const userProfile = {
        id: response.usuarioId,
        nombre: response.nombre,
        roles: response.roles // aquí vendrá "agente"
      };
      localStorage.setItem('userProfile', JSON.stringify(userProfile));
      // console.log("Chequeo de user:",localStorage.getItem('userProfile'));
      toast.success(response.mensaje);
      navigate('/dashboard');
    } catch (error: any) {
      const errorMsg = error.response?.data?.error || 'Error de conexión';
      toast.error(errorMsg);
    } finally {
      setLoading(false);
    }
  };

  return { handleLogin, loading };
};
