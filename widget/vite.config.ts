import { defineConfig } from 'vitest/config';
import { resolve } from 'node:path';

// 库模式：产出单文件 IIFE，直接输出到后端 static/。
// emptyOutDir=false 至关重要——避免清空后端 static 目录里的其它文件。
export default defineConfig({
  build: {
    outDir: resolve(__dirname, '../backend/src/main/resources/static'),
    emptyOutDir: false,
    lib: {
      entry: resolve(__dirname, 'src/main.ts'),
      name: 'QingyuWidget',
      formats: ['iife'],
      fileName: () => 'widget.js',
    },
    rollupOptions: {
      output: { inlineDynamicImports: true },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
  },
});
