import path from "path"
import react from "@vitejs/plugin-react"
import tailwindcss from "@tailwindcss/vite"
import { defineConfig } from "vitest/config"

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "./src"),
    },
  },
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        // ⚠️ changeOrigin 只改 Host 头，不动 Origin。不摘掉 Origin 的话后端会看到
        // Origin=http://localhost:5175 而 Host=localhost:8080，判成跨域 → 撞管理端 CORS 白名单
        // 403，前端只显示「网络错误，请稍后再试」，完全看不出原因；而 5173 被占用时 Vite 会自动
        // 漂到 5174/5175，白名单永远追不上（真机浏览器验证发现，2026-09-03）。
        // 走反代时前端与后端本就该视为同源，摘掉 Origin 才是正确语义。
        configure: (proxy) => {
          proxy.on('proxyReq', (proxyReq) => proxyReq.removeHeader('origin'))
        },
      }
    }
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    // 故意不开 globals：测试文件显式 import vitest API，换来 eslint 与 tsconfig 零改动
  },
})
