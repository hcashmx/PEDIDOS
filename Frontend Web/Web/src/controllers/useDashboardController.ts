import { useState, useEffect } from 'react';
import { UserProfile } from '../types/auth.types';

export const useDashboardController = () => {
  const [user, setUser] = useState<UserProfile | null>(null);

  useEffect(() => {
    const loadUser = () => {
      const savedUser = localStorage.getItem('userProfile');
      if (savedUser) {
        try {
          setUser(JSON.parse(savedUser));
        } catch (e) {
          console.error("Error al parsear el usuario", e);
        }
      }
    };

    loadUser();
  }, []);

  const handleLogout = () => {
    localStorage.clear();
    window.location.href = '/';
  };

  return { user, handleLogout };
};
