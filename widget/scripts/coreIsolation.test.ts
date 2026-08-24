// @vitest-environment node
// 本项目默认 jsdom 环境，这条测试只读文件系统、不碰 DOM，跑在 node 环境即可
// （同 buildFreshness.test.ts 的理由）。
//
// 刻意放在 scripts/ 而非 src/core/：tsconfig 的 include 只有 src，而本文件需要
// node:fs/node:path/node:url 的类型声明——这个子项目刻意不装 @types/node（同
// srcHash.ts 顶部注释点名的理由：@types/node 会往全局注入 Buffer/URL/fetch 等声明，
// 与 widget 依赖的 DOM lib 撞车，风险不值得为一条测试引入）。放 scripts/ 下即不进
// tsc 的 include、也不影响 vitest 收集（vitest 默认按 `**/*.test.ts` 全仓收集，
// buildFreshness.test.ts 已验证过这条路径可行）。

import { readFileSync, readdirSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

const HERE = fileURLToPath(new URL('.', import.meta.url));
const CORE_DIR = resolve(HERE, '../src/core');

/**
 * 守护 widget/src/core/platform.ts 注释里写的那条核心不变量：
 * 「core 内任何文件都不得 import widget/src/ 下 core 之外的东西」+
 * 「core 内运行时相对 import 必须带 .js 后缀」。
 *
 * 这两条在合入前的终审里都是**零自动化守护**、只靠注释与 code review 肉眼维持：
 *
 * 1. **反向依赖**：R3 清理时五处违规里四处是 `import type`——`import type` 在 emit 时被
 *    tsc 整句擦除，widget 的 `pnpm test`（vite 解析器不关心目录结构）、`tsc`（类型检查
 *    通过不代表没有反向依赖）、cli 的 `pnpm build`/`smoke.test.ts`（同样看不出纯类型引用）
 *    全部对此失明。哪天有人顺手 `import type { WidgetUi } from '../ui'`，四道检查照样全绿，
 *    直到真的从 core 引用了一个 DOM 相关类型才会在别处报错，归因困难。
 * 2. **`.js` 后缀**：`moduleResolution: "bundler"` 允许省略后缀，tsc/vitest 都不会因为漏写
 *    `.js` 报错——`actionUri.ts` 漏了 `./safeUrl.js` 就是这样潜伏下来的：编译过、单测过，
 *    只有 CLI 真的用 Node 原生 ESM `import()` 加载编译产物时才炸 `ERR_MODULE_NOT_FOUND`，
 *    而这正是 core 存在的理由——它要能被非浏览器消费者直接跑。
 *
 * 两条失效方式都是**完全静默**的，所以按本仓库对这类不变量的一贯做法（`AsyncExecutorRoutingTest`
 * / `buildFreshness.test.ts` / `CardActionProtocolSyncTest`）补一条扫描式哨兵。
 */
describe('core 层隔离哨兵', () => {
  interface ImportInfo {
    /** 整条 import/export 语句原文，用于失败信息定位。 */
    raw: string;
    /** `from '...'` 里的模块说明符。 */
    specifier: string;
    /** 是否是纯 `import type { ... } from '...'`（emit 时被整句擦除，不产生运行时依赖）。 */
    typeOnly: boolean;
  }

  /** core 下参与运行时产物的源文件——测试文件走 vitest 自己的解析器，不受这两条约束。 */
  function coreSourceFiles(): string[] {
    return readdirSync(CORE_DIR)
      .filter((f) => f.endsWith('.ts') && !f.endsWith('.test.ts'))
      .sort();
  }

  // 按整篇源码匹配而非逐行匹配：import/export 语句可能跨行书写（如多标识符换行的
  // `import type {\n  X,\n} from '...'`），逐行正则要求语句起始行本身就带 `from`，
  // 续行天然匹配不到、会静默漏检。[\s\S]*? 允许语句体内跨行，非贪婪匹配到最近一个
  // `from '...'` 为止；`m` 让 `^` 匹配任意一行行首，从而只认语句起始处的 import/export。
  const IMPORT_RE = /^[ \t]*(?:import|export)\s+(type\s+)?[\s\S]*?\bfrom\s+['"]([^'"]+)['"]/gm;

  function extractImports(source: string): ImportInfo[] {
    return Array.from(source.matchAll(IMPORT_RE)).map((m) => ({
      // 多行匹配时 m[0] 本身带换行，折叠成单行便于失败信息定位。
      raw: m[0].replace(/\s+/g, ' ').trim(),
      specifier: m[2],
      typeOnly: Boolean(m[1]),
    }));
  }

  const files = coreSourceFiles();

  it('样本非空（否则下面几条断言会对空文件列表恒真）', () => {
    expect(files.length, 'core 目录下扫不到任何非测试 .ts 文件，收集逻辑失配').toBeGreaterThan(0);
  });

  // 同 CardActionProtocolSyncTest.java 防过的病：抽取正则一旦因上游改动（如 import 风格调整、
  // TS 语法变化）失配，抽出来的集合会静默变空，下面两条违规断言就退化成「空数组==空数组」永真——
  // 测试全绿但完全没在守护任何东西。下界钉在 5：以当前 core 源码实测抽取到 8 条 import 语句，
  // 留一点冗余空间给后续正常增删，但抽取归零这种彻底失配必然被这条抓住。
  it('抽取到的 import 语句总数达到下界（否则上面/下面的违规断言可能正在空转）', () => {
    const total = files.reduce(
      (sum, file) => sum + extractImports(readFileSync(join(CORE_DIR, file), 'utf8')).length,
      0
    );
    expect(
      total,
      `core 全部源文件仅抽取到 ${total} 条 import 语句，抽取正则疑似已失配——`
        + '违规检测断言可能正在对空集合空转而非真的没有违规'
    ).toBeGreaterThanOrEqual(5);
  });

  it('core 内不得反向依赖 widget 层（不得出现 `../` 相对 import，含 import type）', () => {
    const violations: string[] = [];
    for (const file of files) {
      const source = readFileSync(join(CORE_DIR, file), 'utf8');
      for (const imp of extractImports(source)) {
        if (imp.specifier.startsWith('../')) {
          violations.push(`${file}: ${imp.raw}`);
        }
      }
    }
    expect(
      violations,
      '发现反向依赖——core 内文件只能 `./xxx` 引用同目录，不能 `../` 引用 widget 非 core 层。'
        + '详见 src/core/platform.ts 顶部注释'
    ).toEqual([]);
  });

  it('core 内运行时相对 import 必须带 .js 后缀（import type 可豁免）', () => {
    const violations: string[] = [];
    for (const file of files) {
      const source = readFileSync(join(CORE_DIR, file), 'utf8');
      for (const imp of extractImports(source)) {
        if (imp.typeOnly) continue;
        if (imp.specifier.startsWith('./') && !imp.specifier.endsWith('.js')) {
          violations.push(`${file}: ${imp.raw}`);
        }
      }
    }
    expect(
      violations,
      '运行时相对 import 缺 .js 后缀——tsc/vitest 不会报错，但 Node 原生 ESM 加载编译产物时会炸'
        + ' ERR_MODULE_NOT_FOUND（CLI 就是这样的消费者）'
    ).toEqual([]);
  });
});
