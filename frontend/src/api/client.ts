import axios from 'axios';

const api = axios.create({
    baseURL: import.meta.env.VITE_API_BASE_URL || '/api/v1',
    headers: {
        'Content-Type': 'application/json',
    },
});

// Request interceptor for adding token + current app
api.interceptors.request.use(
    (config) => {
        // 从 localStorage 读取持久化的 auth state
        const authState = localStorage.getItem('qingyu-auth');
        if (authState) {
            try {
                const { state } = JSON.parse(authState);
                if (state?.token) {
                    config.headers['satoken'] = state.token;
                }
            } catch (e) {
                console.error('Failed to parse auth state:', e);
            }
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
            localStorage.removeItem('qingyu-auth');
            window.location.href = '/login';
        }

        console.error('API Error:', message);
        return Promise.reject(new Error(message));
    }
);

export default api;
