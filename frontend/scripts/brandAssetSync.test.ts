// 品牌资产同源哨兵：守护 logo 的五处副本不漂移。
//
// 图形的路径数据在仓库里存了五份（见 brand/README.md）。frontend 与 widget 各自是
// 独立的 pnpm workspace，没有共享模块的通道，副本消不掉——所以改用测试把「改一处
// 忘了其它几处」从「靠人记得」变成「跑测试即红」。
//
// 刻意放在 scripts/ 而非 src/：tsconfig.app.json 的 types 只有 vite/client，
// src 下 import node:fs 会让 tsc -b 失败；tsconfig.node.json 的 include 又只有
// vite.config.ts。放这里 vitest 照样收（默认 include 覆盖全仓 *.test.ts），且不必动
// 任何 tsconfig。widget/scripts/buildFreshness.test.ts 是同样的处置。

import { describe, expect, it } from 'vitest';
import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';

// ⚠️ 不能用 fileURLToPath(import.meta.url) 定位自身：本项目 vitest 跑在 jsdom 环境下，
// import.meta.url 不是 file:// scheme，转换会直接抛「The URL must be of scheme file」。
// vitest 会把 cwd 设成项目根（frontend/），由它上溯一级即仓库根。
const REPO_ROOT = resolve(process.cwd(), '..');

/** 五处同源副本，键是报错时给人看的名字。 */
const COPIES: Record<string, string> = {
  '基准 brand/logo-mark.svg': 'brand/logo-mark.svg',
  'favicon frontend/public/logo.svg': 'frontend/public/logo.svg',
  '管理端 components/Logo.tsx': 'frontend/src/components/Logo.tsx',
  'widget src/logo.ts': 'widget/src/logo.ts',
  '字标 brand/logo-lockup.svg': 'brand/logo-lockup.svg',
};

/** 带渐变的四处；widget 是单色 currentColor，不参与色值比对。 */
const GRADIENT_COPIES = Object.entries(COPIES).filter(([, p]) => !p.startsWith('widget/'));

/** 图形共 7 条路径：气泡外形 1 + 「轻」字骨架 6（车字旁 3 + 「工」3）。 */
const EXPECTED_PATH_COUNT = 7;

/** 渐变两个色标：靛蓝 → 紫。 */
const EXPECTED_STOP_COUNT = 2;

function read(relativePath: string): string {
  const full = resolve(REPO_ROOT, relativePath);
  // 路径推错时给出能直接照着排查的报错，而不是一句 ENOENT
  if (!existsSync(full)) {
    throw new Error(`找不到 ${relativePath}（解析为 ${full}）。仓库根推断错了或文件被移动了`);
  }
  // 归一行尾：仓库存 LF，Windows 工作区 checkout 成 CRLF，不归一会变成谁跑谁红
  return readFileSync(full, 'utf8').replace(/\r\n/g, '\n');
}

/** 抽路径数据。SVG 与 TSX 的写法相同，一条正则通吃。 */
function pathData(source: string): string[] {
  return [...source.matchAll(/\sd="([^"]+)"/g)].map((m) => m[1]);
}

/** 抽渐变色标。同时匹配 SVG 的 stop-color 与 JSX 的 stopColor。 */
function stopColors(source: string): string[] {
  return [...source.matchAll(/stop-?color="(#[0-9a-f]{6})"/gi)].map((m) => m[1].toLowerCase());
}

/** 抽笔画线宽。同时匹配 stroke-width 与 strokeWidth。 */
function strokeWidths(source: string): string[] {
  return [...source.matchAll(/stroke-?width="([\d.]+)"/gi)].map((m) => m[1]);
}

describe('品牌资产同源', () => {
  // ⚠️ 这一条不是凑数的：正则一旦失配（有人把 d= 换成别的写法、或文件被移走），
  // 抽出来的集合会静默变空，后面的"两两相等"就退化成「空 == 空」永真——测试全绿而
  // 保护为零。卡片协议白名单哨兵踩过同一个坑，所以先把"真的抽到了东西"钉死。
  it('每处都抽到完整的路径数据（防正则失配退化成永真）', () => {
    for (const [name, relativePath] of Object.entries(COPIES)) {
      const source = read(relativePath);
      expect(source.length, `${name} 读到的内容为空`).toBeGreaterThan(0);
      expect(pathData(source), `${name} 抽到的路径条数不对`).toHaveLength(EXPECTED_PATH_COUNT);
    }
  });

  it('五处的路径数据完全一致', () => {
    const [baselineName, baselinePath] = Object.entries(COPIES)[0];
    const baseline = pathData(read(baselinePath));

    for (const [name, relativePath] of Object.entries(COPIES).slice(1)) {
      expect(
        pathData(read(relativePath)),
        `${name} 与${baselineName}的图形不一致——改了一处没同步其它。步骤见 brand/README.md`
      ).toEqual(baseline);
    }
  });

  it('带渐变的四处色值一致', () => {
    const [baselineName, baselinePath] = GRADIENT_COPIES[0];
    const baseline = stopColors(read(baselinePath));
    expect(baseline, `${baselineName} 没抽到渐变色标`).toHaveLength(EXPECTED_STOP_COUNT);

    for (const [name, relativePath] of GRADIENT_COPIES.slice(1)) {
      const colors = stopColors(read(relativePath));
      expect(colors, `${name} 抽到的色标数不对`).toHaveLength(EXPECTED_STOP_COUNT);
      expect(colors, `${name} 的品牌色与${baselineName}不一致`).toEqual(baseline);
    }
  });

  it('五处的笔画线宽一致', () => {
    const [baselineName, baselinePath] = Object.entries(COPIES)[0];
    const baseline = strokeWidths(read(baselinePath));
    expect(baseline, `${baselineName} 没抽到线宽`).toHaveLength(1);

    for (const [name, relativePath] of Object.entries(COPIES).slice(1)) {
      expect(
        strokeWidths(read(relativePath)),
        `${name} 的线宽与${baselineName}不一致——形状一样但粗细不同，肉眼很难发现`
      ).toEqual(baseline);
    }
  });

  // favicon 那份没有任何格式上的理由与基准不同（两者都是独立 SVG 文件），
  // 之所以要两份，只是因为 Vite 要求 favicon 物理落在 publicDir 内。
  // 既然如此就用最强的约束：逐字节相同，连注释都不许分叉。
  it('基准与 favicon 副本逐字节相同', () => {
    expect(
      read('frontend/public/logo.svg'),
      'frontend/public/logo.svg 与 brand/logo-mark.svg 不一致，'
        + '直接 cp brand/logo-mark.svg frontend/public/logo.svg'
    ).toBe(read('brand/logo-mark.svg'));
  });
});
