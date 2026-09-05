import React from 'react';
import { useForm } from 'react-hook-form';
import { useLoginController } from '../controllers/useLoginController';
import { LoginRequest } from '../types/auth.types';
import { Mail, Lock, LogIn, Loader2 } from 'lucide-react'; // Iconos de tu package.json

export const LoginView = () => {
  const { register, handleSubmit } = useForm<LoginRequest>({
  defaultValues: {
    email: 'cobranza@ferreteria.com',
    password: 'vendedor'
  }
});
  const { handleLogin, loading } = useLoginController();

  return (
    <div className="flex min-h-screen items-center justify-center bg-gradient-to-br from-slate-100 to-slate-200 p-4">
      <div className="w-full max-w-md">
        {/* Card Contenedora */}
        <form
          onSubmit={handleSubmit(handleLogin)}
          className="bg-white rounded-2xl shadow-xl border border-slate-200 overflow-hidden"
        >
          {/* Encabezado con gradiente */}
          <div className="bg-blue-600 p-8 text-center">
            <div className="inline-flex items-center justify-center w-16 h-16 bg-white/10 rounded-full mb-4">
              <LogIn className="w-8 h-8 text-white" />
            </div>
            <h1 className="text-2xl font-bold text-white">Admin Panel</h1>
            <p className="text-blue-100 text-sm mt-1">Ingresa tus credenciales para continuar</p>
          </div>

          {/* Cuerpo del Formulario */}
          <div className="p-8 space-y-5">
            {/* Campo Email */}
            <div className="space-y-1.5">
              <label className="text-sm font-semibold text-slate-700 flex items-center gap-2">
                <Mail className="w-4 h-4" />
                Email Corporativo
              </label>
              <div className="relative">
                <input
                  {...register('email')}
                  type="email"
                  placeholder="ejemplo@admin.com"
                  required
                  className="w-full rounded-lg border border-slate-300 bg-slate-50 px-4 py-2.5 text-slate-900 transition-all focus:border-blue-500 focus:bg-white focus:ring-4 focus:ring-blue-500/10 outline-none"
                />
              </div>
            </div>

            {/* Campo Password */}
            <div className="space-y-1.5">
              <label className="text-sm font-semibold text-slate-700 flex items-center gap-2">
                <Lock className="w-4 h-4" />
                Contraseña
              </label>
              <div className="relative">
                <input
                  {...register('password')}
                  type="password"
                  placeholder="••••••••"
                  required
                  className="w-full rounded-lg border border-slate-300 bg-slate-50 px-4 py-2.5 text-slate-900 transition-all focus:border-blue-500 focus:bg-white focus:ring-4 focus:ring-blue-500/10 outline-none"
                />
              </div>
            </div>

            {/* Botón de Acción */}
            <button
              type="submit"
              disabled={loading}
              className="w-full flex items-center justify-center gap-2 py-3 px-4 bg-blue-600 hover:bg-blue-700 active:bg-blue-800 text-white font-bold rounded-xl shadow-lg shadow-blue-500/30 transition-all duration-200 disabled:opacity-70 disabled:cursor-not-allowed mt-2"
            >
              {loading ? (
                <>
                  <Loader2 className="w-5 h-5 animate-spin" />
                  <span>Autenticando...</span>
                </>
              ) : (
                <span>Acceder al Sistema</span>
              )}
            </button>
          </div>

          {/* Pie de página decorativo */}
          <div className="bg-slate-50 border-t border-slate-100 p-4 text-center">
            <p className="text-xs text-slate-500">
              © 2026 Admin System. Todos los derechos reservados.
            </p>
          </div>
        </form>
      </div>
    </div>
  );
};
