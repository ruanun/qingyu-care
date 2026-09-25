import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';
import type { LoginResponse } from '../api/auth';
import { AUTH_KEY, authStateStorage } from '@/lib/authStorage';

interface AuthState {
    // 状态
    token: string | null;
    user: LoginResponse | null;
    isAuthenticated: boolean;

    // 操作
    setAuth: (response: LoginResponse) => void;
    logout: () => void;
    updateUser: (user: Partial<LoginResponse>) => void;
}

export const useAuthStore = create<AuthState>()(
    persist(
        (set) => ({
            token: null,
            user: null,
            isAuthenticated: false,

            setAuth: (response: LoginResponse) => {
                set({
                    token: response.token,
                    user: response,
                    isAuthenticated: true,
                });
            },

            logout: () => {
                set({
                    token: null,
                    user: null,
                    isAuthenticated: false,
                });
            },

            updateUser: (userData: Partial<LoginResponse>) => {
                set((state) => ({
                    user: state.user ? { ...state.user, ...userData } : null,
                }));
            },
        }),
        {
            name: AUTH_KEY,
            // 按「记住我」落到 localStorage 或 sessionStorage，见 lib/authStorage
            storage: createJSONStorage(() => authStateStorage),
        }
    )
);
