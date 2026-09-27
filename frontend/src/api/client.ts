import axios from 'axios';
import { clearAuth, readAuthToken } from '@/lib/authStorage';

const api = axios.create({
    baseURL: import.meta.env.VITE_API_BASE_URL || '/api/v1',
    headers: {
        'Content-Type': 'application/json',
    },
});

// Request interceptor for adding token + current app
api.interceptors.request.use(
    (config) => {
        // 登录态可能在 localStorage 或 sessionStorage（取决于「记住我」），必须经 authStorage 读
        const token = readAuthToken();
        if (token) {
            config.headers['satoken'] = token;
        }

        // 从 localStorage 读取当前应用，注入 X-App-Id（后端据此按应用隔离）
        const appState = localStorage.getItem('qingyu-current-app');
        if (appState) {
            try {
                const { state } = JSON.parse(appState);
                if (state?.currentApp?.id != null) {
                    config.headers['X-App-Id'] = String(state.currentApp.id);
                }
            } catch (e) {
                console.error('Failed to parse app state:', e);
            }
        }

        return config;
    },
    (error) => Promise.reject(error)
);

// Response interceptor for handling common errors
api.interceptors.response.use(
    (response) => response.data,
    (error) => {
        const message = error.response?.data?.message || '网络错误，请稍后再试';

        // 如果是 401 未认证，清除 token 并跳转登录页
        if (error.response?.status === 401) {
            clearAuth();
            // 当前应用一并清：残留会让下一个登录者带着上一个租户的 X-App-Id 发请求
            localStorage.removeItem('qingyu-current-app');
            window.location.href = '/login';
        }

        console.error('API Error:', message);
        return Promise.reject(new Error(message));
    }
);

export default api;
