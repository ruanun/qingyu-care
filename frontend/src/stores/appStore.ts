import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import type { Application } from '../types';

/** 当前选中应用的精简信息 */
export interface CurrentApp {
    id: number;
    name: string;
}

interface AppState {
    // 当前操作的应用（持久化到 localStorage）
    currentApp: CurrentApp | null;
    // 当前用户可见的应用列表（不持久化，登录后重新拉取）
    apps: Application[];

    setCurrentApp: (app: CurrentApp | null) => void;
    setApps: (apps: Application[]) => void;
    clearCurrentApp: () => void;
}

export const useAppStore = create<AppState>()(
    persist(
        (set) => ({
            currentApp: null,
            apps: [],
            setCurrentApp: (app) => set({ currentApp: app }),
            setApps: (apps) => set({ apps }),
            clearCurrentApp: () => set({ currentApp: null }),
        }),
        {
            name: 'qingyu-current-app',
            // 仅持久化 currentApp；apps 每次会话重新拉取，避免陈旧
            partialize: (state) => ({ currentApp: state.currentApp }),
        }
    )
);
