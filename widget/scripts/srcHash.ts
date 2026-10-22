// widget 源码指纹：build 时写进产物 banner，测试重算后比对，
// 守护「改了 widget/src/ 却忘了重新 build 并提交 widget.js」这条约定。
//
// 计算方（vite.config.ts）与校验方（buildFreshness.test.ts）共用本模块，
// 两份实现漂移的可能性从设计上排除——这正是卡片协议白名单三处分裂踩过的坑。
//
// 刻意放在 scripts/ 而非 src/：tsconfig 的 include 只有 src，放这里既不需要给
// 这个刻意保持精简的子项目加 @types/node，也不会被打进产物。

import { createHash } from 'node:crypto';
import { readdirSync, readFileSync } from 'node:fs';
import { join, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = fileURLToPath(new URL('.', import.meta.url));

/** widget 源码目录。 */
export const WIDGET_SRC_DIR = resolve(HERE, '../src');

/** 构建产物——直接落在后端 static/ 下，由后端作为静态资源对外提供。 */
export const WIDGET_BUNDLE_PATH = resolve(
  HERE,
  '../../backend/src/main/resources/static/widget.js'
);

// ⚠️ banner 必须写成 `/*!` 开头的 legal comment。vite build 默认用 esbuild 压缩，
// 普通 `/* */` 注释会被整个剥掉——实测产物里一个字都不剩，且 build 全程无任何提示。
// 另注意 esbuild 可能把 legal comment 挪到文件末尾，所以取指纹要全文搜、不能只看首行。
const MARKER = 'qingyu-widget src-hash:';
const BANNER_PATTERN = /\/\*!\s*qingyu-widget src-hash: ([0-9a-f]{64})\s*\*\//;

/** 递归收集计入指纹的源文件，返回相对 srcDir 的 posix 风格路径。 */
function collectSourceFiles(dir: string, srcDir: string): string[] {
  const found: string[] = [];
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name);
    if (entry.isDirectory()) {
      found.push(...collectSourceFiles(full, srcDir));
      continue;
    }
    // 测试文件不进产物，改测试不该要求重新 build
    if (!entry.name.endsWith('.ts') || entry.name.endsWith('.test.ts')) continue;
    found.push(full.slice(srcDir.length + 1).split(sep).join('/'));
  }
  return found;
}

/**
 * 计算 src 指纹。
 *
 * 两处归一化都不能省：
 * - **路径分隔符**统一成 `/`，否则 Windows 与 Linux 算出的指纹不同。
 * - **行尾**统一成 LF。仓库里存 LF、Windows 工作区 checkout 成 CRLF，
 *   不归一会让同一份源码在两个平台算出不同指纹，变成谁跑谁红的假警报。
 */
export function computeSrcHash(srcDir: string = WIDGET_SRC_DIR): string {
  const files = collectSourceFiles(srcDir, srcDir).sort();
  const hash = createHash('sha256');
  for (const relativePath of files) {
    hash.update(relativePath);
    hash.update('\0');
    hash.update(readFileSync(join(srcDir, relativePath), 'utf8').replace(/\r\n/g, '\n'));
    hash.update('\0');
  }
  return hash.digest('hex');
}

/** 计入指纹的文件清单——测试用它断言「指纹真的覆盖了源码」，防止空目录下算出空 hash。 */
export function listHashedFiles(srcDir: string = WIDGET_SRC_DIR): string[] {
  return collectSourceFiles(srcDir, srcDir).sort();
}

export function srcHashBanner(hash: string): string {
  return `/*! ${MARKER} ${hash} */`;
}

/** 从产物里取回指纹；取不到返回 null（产物是加 banner 之前构建的）。 */
export function extractSrcHash(bundle: string): string | null {
  return bundle.match(BANNER_PATTERN)?.[1] ?? null;
}
