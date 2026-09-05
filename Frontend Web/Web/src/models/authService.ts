import api from '../api/axiosConfig';
import { LoginRequest, AuthResponse } from '../types/auth.types';

export const authService = {
  // Conecta con @PostMapping("/login") en el backend
  async login(credentials: LoginRequest): Promise<AuthResponse> {
    const response = await api.post('/auth/login', credentials);
    return response.data;
  },

  // Conecta con @PostMapping("/refresh") en el backend
  async refresh(token: string): Promise<AuthResponse> {
    const response = await api.post('/auth/refresh', { refreshToken: token });
    return response.data;
  }
};
