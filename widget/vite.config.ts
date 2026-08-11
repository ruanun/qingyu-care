import type { Plugin } from 'vite';
import { defineConfig } from 'vitest/config';
import { resolve } from 'node:path';

import { computeSrcHash, srcHashBanner } from './scripts/srcHash';

/**
 * 把 src 指纹写进产物，供 scripts/buildFreshness.test.ts 校验产物跟得上源码。
 * widget.js 是提交进仓库的构建产物，改了 src 忘了 build 不会有任何报错。
 *
 * ⚠️ 刻意用插件的 generateBundle 而不是 `rollupOptions.output.banner`：
 * lib 模式下那个 banner **实测完全不生效**（产物一个字节都不变，build 无任何提示）。
 * generateBundle 跑在 minify 之后，注进去的内容不会再被 esbuild 剥掉。
 */
function injectSrcHash(): Plugin {
  return {
    name: 'qingyu-inject-src-hash',
    enforce: 'post',
    generateBundle(_options, bundle) {
      for (const file of Object.values(bundle)) {
        if (file.type === 'chunk' && file.isEntry) {
          file.code = `${srcHashBanner(computeSrcHash())}\n${file.code}`;
        }
      }
    },
  };
}

// 库模式：产出单文件 IIFE，直接输出到后端 static/。
// emptyOutDir=false 至关重要——避免清空后端 static 目录里的其它文件。
export default defineConfig({
  plugins: [injectSrcHash()],
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
