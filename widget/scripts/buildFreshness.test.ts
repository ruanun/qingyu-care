// @vitest-environment node
// 本项目默认 jsdom 环境，而 jsdom 下 import.meta.url 不是 file: scheme，
// srcHash.ts 里的 fileURLToPath 会抛 "The URL must be of scheme file"。
// 这条测试只读文件系统、不碰 DOM，跑在 node 环境即可。

import { existsSync, readFileSync } from 'node:fs';

import { describe, expect, it } from 'vitest';

import {
  WIDGET_BUNDLE_PATH,
  computeSrcHash,
  extractSrcHash,
  listHashedFiles,
} from './srcHash';

/**
 * 守护「改了 widget/src/ 必须重新 build 并提交 widget.js」这条约定。
 *
 * widget.js 是**提交进仓库的构建产物**，由后端作为静态资源对外提供
 * （`<script src=".../api/widget.js?appKey=...">`）。忘了重新 build 不会有任何报错：
 * 后端照常启动、页面照常加载 widget，只是线上跑的是旧代码——改动看起来「没生效」，
 * 而真正的原因（产物没更新）在任何日志里都看不到。
 */
describe('widget 构建产物新鲜度', () => {
  it('指纹覆盖了真实源码文件（否则下面那条断言会退化成空 hash 恒等）', () => {
    const files = listHashedFiles();

    // 收集逻辑一旦失配（比如目录结构调整、扩展名改了），文件清单会静默变空，
    // 此时 computeSrcHash 对空清单照样返回一个稳定的 sha256，
    // 「产物指纹 == 源码指纹」就变成一条永真断言。
    expect(files.length, '计入指纹的源文件为空——收集逻辑失配，本测试正在空转').toBeGreaterThan(0);
    expect(files, '入口文件 main.ts 不在指纹里，收集逻辑有问题').toContain('main.ts');
    expect(
      files.filter((f) => f.endsWith('.test.ts')),
      '测试文件不该计入指纹——改测试不需要重新 build'
    ).toEqual([]);
  });

  it('widget.js 的 src 指纹与当前 widget/src 一致', () => {
    expect(
      existsSync(WIDGET_BUNDLE_PATH),
      `找不到构建产物 ${WIDGET_BUNDLE_PATH}——在 widget/ 下跑 pnpm build`
    ).toBe(true);

    const bundle = readFileSync(WIDGET_BUNDLE_PATH, 'utf8');
    const embedded = extractSrcHash(bundle);

    expect(
      embedded,
      'widget.js 里没有 src 指纹 banner——产物是加入本守护之前构建的，'
        + '在 widget/ 下跑 pnpm build 重新生成并提交'
    ).not.toBeNull();

    expect(
      embedded,
      'widget.js 与 widget/src 不同步：改了源码但没有重新 build。'
        + '在 widget/ 下跑 pnpm build，并把 backend/src/main/resources/static/widget.js 一起提交'
    ).toBe(computeSrcHash());
  });
});
