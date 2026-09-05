export interface LoginRequest {
  // Coincide con el Map<String, String> esperado en LoginController.java
  email: string;
  password: string;
}

export interface AuthResponse {
  // Coincide exactamente con el Map retornado en LoginService.java
  usuarioId: number;
  nombre: string;
  roles: any; // Puedes definir una interfaz de Roles si la tienes
  accessToken: string;
  refreshToken: string;
  mensaje: string;
}

export interface RefreshRequest {
  refreshToken: string;
}

export interface UserProfile {
  id: number;
  nombre: string;
  roles: any; // O el tipo específico que devuelva tu backend
}
