# 前端测试基建 + CrawlSiteList 覆盖 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 `frontend/` 建立 vitest 测试基建，并用五条测试把站点爬取前端三轮返工的缺陷锁住，每条都用变异实验证明它抓得住。

**Architecture:** vitest + jsdom + `@testing-library/react`，桩到 api service 模块层（`vi.mock('@/api/knowledge')`）而非 HTTP 层。测试文件与被测组件同目录并放。所有异步推进走 `await act(() => vi.advanceTimersByTimeAsync(ms))`。

**Tech Stack:** vitest 4 · jsdom 29 · @testing-library/react 16 · @testing-library/jest-dom 6 · React 19 · Vite 7

**设计文档：** `docs/superpowers/specs/2026-08-10-frontend-test-infra-design.md`

## Global Constraints

- **不是 TDD 而是变异测试**：被测代码 `CrawlSiteList.tsx` 已存在且已上线，**一行都不改**。每条测试的验收是两段：① 在正确实现上跑绿；② 按指定方式把实现改坏后跑红。红不了的测试当场重写。
- **变异实验前必须先 `git commit`。** 上一批栽过一次：未提交就变异，`git checkout --` 把自己未提交的改动一起还原了。变异后一律用 `git checkout -- <file>` 还原。
- **不开 `globals: true`**（与 `widget/` 不同，故意）：测试文件显式 `import { describe, it, expect, vi } from 'vitest'`。目的是 eslint 不必加 vitest globals、`lint` 的 69 个 error 基线零新增。
- **时间推进一律用 `await vi.advanceTimersByTimeAsync(ms)`**，禁止同步 `advanceTimersByTime`——`poll` 的 `tick` 内部有 `await`，同步推进推不动 promise 链，测试会「通过」但什么都没验证。
- **断言调用次数一律用 `toHaveBeenCalledTimes(n)`**，禁止 `toHaveBeenCalled()`——后者对「多续期了一次」完全是瞎的，而重复续期正是本组件的核心风险。
- 中文注释、`@author ruan`（本批不新增带 author 头的产品文件，测试文件不要求）。
- 分支：`test/frontend-vitest-infra`（已建）。**不 push。**
- 全部命令在 `D:/Code/m/qingyu-care/frontend` 下执行（除 git）。
- 收尾验收：`pnpm test` 全绿 · `pnpm build` 绿 · `pnpm lint` 的 error 数 ≤ 69。

## 相对 spec 的两处修正

实现时按本计划走，spec 那两句已过时：

1. **不改 `tsconfig.app.json` 的 `types` 数组。** spec §3.2 要求加 `"@testing-library/jest-dom"`，但那个包的**根入口**是给 jest 做全局类型增强的；vitest 要用的是子路径 `@testing-library/jest-dom/vitest`，它在被 import 时对 `vitest` 的 `Assertion` 接口做模块增强。`src/test/setup.ts` 已在 `tsconfig.app.json` 的 `include: ["src"]` 覆盖范围内，import 一次即全程序生效。加到 `types` 数组是多余的，还可能把 jest 的全局声明拖进来。
2. **StrictMode 探针留在仓库里**，不像 spec §4 说的那样跑完删掉。理由：它守的是 T3 的**前提**。T3 那条测试只有在「StrictMode 真的双调用」时才有判别力——`isMountedRef` 初值本来就是 `true`，不双调用就永远测不出「缺少 setup 复位」。哪天升级 React / vitest / Vite 让双调用不再发生，T3 会**静默退化成永真**而不是转红。把前提本身钉成一条测试，T3 才可信。

---

## File Structure

| 文件 | 职责 | 动作 |
|:---|:---|:---|
| `frontend/package.json` | 加 4 个 devDeps + `test` / `test:watch` 脚本 | 修改 |
| `frontend/vite.config.ts` | `defineConfig` 改从 `vitest/config` 导入，加 `test` 段 | 修改 |
| `frontend/src/test/setup.ts` | jest-dom matcher 注册 + 显式 `afterEach(cleanup)` | 新建 |
| `frontend/src/test/strictModeDoubleInvoke.test.tsx` | 工具链前提哨兵：断言 StrictMode 双调用真的发生 | 新建 |
| `frontend/src/components/knowledge/CrawlSiteList.test.tsx` | 五条回归测试（T1/T4/T2/T3/T5） | 新建 |
| `CLAUDE.md` | 前端命令补 `pnpm test`；测试约定加前端小节；修正响应拦截器表述 | 修改 |
| `SaaS平台规划.md` | 技术债 6 标注进展（不折叠）；新增静默失败一条 | 修改 |

`CrawlSiteList.tsx` **不修改**（除变异实验期间的临时改动，用完必须还原）。

---

## Task 1: 基建 + 工具链前提哨兵

**Files:**
- Modify: `frontend/package.json`
- Modify: `frontend/vite.config.ts`
- Create: `frontend/src/test/setup.ts`
- Create: `frontend/src/test/strictModeDoubleInvoke.test.tsx`

**Interfaces:**
- Consumes: 无（首个任务）
- Produces: 可用的 `pnpm test`；`src/test/setup.ts` 里已注册 jest-dom matcher 与 `afterEach(cleanup)`，后续测试文件**不需要**自己 cleanup。以及一个明确结论：**vitest 下 `<StrictMode>` 是否双调用 effect**——Task 5 按这个结论选写法。

- [ ] **Step 1: 装依赖**

```bash
cd D:/Code/m/qingyu-care/frontend
pnpm add -D vitest@^4 jsdom@^29 @testing-library/react@^16 @testing-library/jest-dom@^6
```

不装 `@testing-library/user-event`：本批只用 `fireEvent`，能避开 user-event 与 fake timers 之间需要 `advanceTimers` 桥接的配置坑。

- [ ] **Step 2: 改 `frontend/vite.config.ts`**

只改 import 来源、加 `test` 段，其余原样（保持现有双引号风格）：

```ts
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
      }
    }
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    // 故意不开 globals：测试文件显式 import vitest API，换来 eslint 与 tsconfig 零改动
  },
})
```

- [ ] **Step 3: 建 `frontend/src/test/setup.ts`**

```ts
import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';

// ⚠️ 必须显式 cleanup。RTL 只在 `afterEach` 是全局函数时才自动注册清理，
// 而本项目故意关着 vitest 的 globals——漏掉这行的症状是「单个测试跑绿、
// 整套一起跑红」，因为上一个测试的 DOM 还挂在 document 上，
// screen 的查询会撞到重复元素。定位这个症状很费时间，所以在基建里钉死。
afterEach(cleanup);
```

- [ ] **Step 4: 加 `frontend/package.json` 脚本**

在 `scripts` 里加两条（`test` 的语义与 widget 一致：跑完就退）：

```json
"test": "vitest run",
"test:watch": "vitest"
```

- [ ] **Step 5: 写工具链前提哨兵**

创建 `frontend/src/test/strictModeDoubleInvoke.test.tsx`：

```tsx
import { StrictMode, useEffect } from 'react';
import { render } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

/**
 * 工具链前提哨兵——不测任何产品代码。
 *
 * `CrawlSiteList.test.tsx` 里那条「StrictMode 下轮询照常工作」的回归测试，
 * 成立的前提是：vitest 下 react-dom 走 development 构建，<StrictMode> 会把
 * effect 跑成 setup → cleanup → setup。这个前提由工具链决定、不由我们的代码决定。
 *
 * 一旦升级 React / vitest / Vite 让双调用不再发生，那条回归测试会**静默退化成
 * 永真**而不是转红：isMountedRef 的初值本来就是 true，不双调用就永远测不出
 * 「effect 的 setup 阶段缺少复位」这个缺陷。所以把前提本身钉成一条测试——
 * 它先红，那条才可信。
 */
describe('工具链前提：StrictMode 双调用', () => {
    it('挂载时 effect 跑成 setup → cleanup → setup', () => {
        const calls: string[] = [];

        function Probe() {
            useEffect(() => {
                calls.push('setup');
                return () => calls.push('cleanup');
            }, []);
            return null;
        }

        render(<StrictMode><Probe /></StrictMode>);

        expect(calls).toEqual(['setup', 'cleanup', 'setup']);
    });
});
```

- [ ] **Step 6: 跑起来，记结论**

```bash
cd D:/Code/m/qingyu-care/frontend && pnpm test
```

期望：1 个测试文件、1 条测试通过。

**如果这条测试红了**（`calls` 只有 `['setup']`，说明 vitest 下走的是 production 构建或 React 行为已变）：
1. 不要为了让它绿而改断言。把它改成 `expect(calls).toEqual(['setup'])` 并在 doc 注释里写明「实测本工具链**不**双调用」；
2. 在计划里把 Task 5 切到「结构性断言」写法（Task 5 Step 1 的 B 方案）；
3. 在 spec §4 里把探针结论回填成「不通过」。

- [ ] **Step 7: 确认 build 与 lint 没被基建带坏**

```bash
cd D:/Code/m/qingyu-care/frontend && pnpm build && pnpm lint 2>&1 | tail -5
```

期望：build 成功；lint 的 error 数仍是 69（`✖ N problems (69 errors, M warnings)`）。若 error 变多，先修到 69 再继续。

- [ ] **Step 8: 提交**

```bash
cd D:/Code/m/qingyu-care
git add frontend/package.json frontend/pnpm-lock.yaml frontend/vite.config.ts frontend/src/test/
git commit -m "test(frontend): 建 vitest 基建 + StrictMode 双调用前提哨兵

故意不开 globals，测试文件显式 import vitest API——换来 eslint 与 tsconfig
零改动、lint 的 69 个 error 基线零新增。代价是 RTL 不再自动注册清理，
setup.ts 里必须显式 afterEach(cleanup)（漏了的症状是「单跑绿、全跑红」）。

哨兵守的是后面那条 isMountedRef 回归测试的前提：不双调用时那条测试会静默
退化成永真（isMountedRef 初值就是 true），而不是转红。"
```

---

## Task 2: T1 轮询链——RUNNING 续期与终态收尾

**Files:**
- Create: `frontend/src/components/knowledge/CrawlSiteList.test.tsx`
- 变异目标（临时改，用完还原）：`frontend/src/components/knowledge/CrawlSiteList.tsx:118`

**Interfaces:**
- Consumes: Task 1 的 `pnpm test` 与 `src/test/setup.ts`
- Produces: 本任务建立整个测试文件的脚手架，后续 Task 3-6 只往里追加 `it(...)`：
  - `ok<T>(data: T): ApiResponse<T>` —— 造 `{code: 200, message: 'success', data, timestamp}`
  - `progress(over: Partial<CrawlProgress>): CrawlProgress` —— 造进度，默认 RUNNING / `siteId: 1` / `jobId: 'job-1'` / `crawled: 3` / `maxPages: 50`
  - `flush(ms?: number): Promise<void>` —— `act` 包裹的 `advanceTimersByTimeAsync`
  - `renderList(opts?: { strict?: boolean })` —— 渲染并放掉首次 `loadSites`，返回 RTL 的 `RenderResult`
  - `site1: CrawlSite` —— `id: 1`、`maxPages: 50`
  - `toastSpy` —— `useToast().toast` 的桩
  - `onCrawlFinished` —— 每条测试前重建的 `vi.fn()`

- [ ] **Step 1: 写测试文件（脚手架 + T1）**

创建 `frontend/src/components/knowledge/CrawlSiteList.test.tsx`：

```tsx
import { StrictMode } from 'react';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ApiResponse, CrawlProgress, CrawlSite } from '@/types';
import { knowledgeService } from '@/api/knowledge';
import { CrawlSiteList } from './CrawlSiteList';

/**
 * CrawlSiteList 的轮询生命周期回归测试。
 *
 * 这个组件在站点爬取上线前返工了三轮，三个缺陷（终态不收尾 / 双任务互踩 /
 * StrictMode 下 isMountedRef 卡死）全靠人工代码走查发现。这里每条测试都配了
 * 一次变异实验（把实现改坏必须转红），实验方式记在各条测试的注释里。
 *
 * 桩在 api service 模块层而不是 HTTP 层：api/client.ts 的响应拦截器是
 * `response => response.data`，交给调用方的就是后端那整个 ApiResponse，
 * 所以这一层的返回值形态 `{code, message, data}` 正是「HTTP 200 + body.code」
 * 这条全局约定发生的位置。
 */

// vi.mock 的工厂会被提到所有 import 之前执行，工厂里直接引用模块顶层的 const
// 会撞 TDZ（ReferenceError: Cannot access before initialization），
// 必须用 vi.hoisted 把桩函数一起提上去。
const { toastSpy } = vi.hoisted(() => ({ toastSpy: vi.fn() }));

vi.mock('@/hooks/use-toast', () => ({
    useToast: () => ({ toast: toastSpy }),
}));

vi.mock('@/api/knowledge', () => ({
    knowledgeService: {
        listCrawlSites: vi.fn(),
        getCrawlProgress: vi.fn(),
        addCrawlSite: vi.fn(),
        recrawlSite: vi.fn(),
        updateCrawlSite: vi.fn(),
        deleteCrawlSite: vi.fn(),
    },
}));

// Radix Dialog 的 portal 与 focus trap 在 jsdom 下的行为跟轮询链毫无关系，
// 把表单对话框桩成空组件，让测试只面对轮询逻辑。
vi.mock('./CrawlSiteDialog', () => ({
    CrawlSiteDialog: () => null,
}));

const ok = <T,>(data: T): ApiResponse<T> => ({
    code: 200,
    message: 'success',
    data,
    timestamp: '2026-08-10T00:00:00',
});

const site1: CrawlSite = {
    id: 1,
    entryUrl: 'https://example.com/help/',
    scopePrefix: 'https://example.com/help/',
    maxPages: 50,
    maxDepth: 3,
    contentSelector: null,
    syncInterval: 'DAILY',
    lastCrawledAt: null,
    createdAt: '2026-08-10T00:00:00',
};

const progress = (over: Partial<CrawlProgress> = {}): CrawlProgress => ({
    jobId: 'job-1',
    siteId: 1,
    status: 'RUNNING',
    crawled: 3,
    created: 3,
    updated: 0,
    failed: 0,
    maxPages: 50,
    errorMessage: null,
    startedAt: '2026-08-10T00:00:00',
    finishedAt: null,
    ...over,
});

// ⚠️ 时间推进必须走 advanceTimersByTimeAsync：poll 的 tick 内部有 await，
// 同步的 advanceTimersByTime 只会把定时器回调排出去、推不动后面的 promise 链，
// 测试会「通过」但实际什么都没验证。外面再包一层 act 是为了让 React 19 不再
// 抱怨状态更新没被 act 包裹。
const flush = async (ms = 0) => {
    await act(async () => {
        await vi.advanceTimersByTimeAsync(ms);
    });
};

let onCrawlFinished: ReturnType<typeof vi.fn>;

const renderList = async (opts: { strict?: boolean } = {}) => {
    const ui = <CrawlSiteList knowledgeBaseId={7} onCrawlFinished={onCrawlFinished} />;
    const result = render(opts.strict ? <StrictMode>{ui}</StrictMode> : ui);
    await flush(); // 放掉挂载时那次 loadSites
    return result;
};

const clickRecrawl = () => fireEvent.click(screen.getByRole('button', { name: '重新爬取' }));

beforeEach(() => {
    vi.useFakeTimers();
    toastSpy.mockReset();
    onCrawlFinished = vi.fn();
    vi.mocked(knowledgeService.listCrawlSites).mockReset().mockResolvedValue(ok([site1]));
    vi.mocked(knowledgeService.getCrawlProgress).mockReset();
    vi.mocked(knowledgeService.recrawlSite).mockReset();
});

afterEach(() => {
    vi.useRealTimers();
});

describe('CrawlSiteList 轮询生命周期', () => {
    /**
     * T1｜变异实验：删掉 CrawlSiteList.tsx:118 那行
     * `timersRef.current[jobId] = window.setTimeout(tick, 2000);`（RUNNING 分支的续期），
     * 本条必须转红——第二次 tick 永远不会发生，收尾的三个断言全部落空。
     */
    it('RUNNING 时按 2 秒续期，终态时收尾且只通知父组件一次', async () => {
        vi.mocked(knowledgeService.recrawlSite).mockResolvedValue(ok('job-1'));
        vi.mocked(knowledgeService.getCrawlProgress)
            .mockResolvedValueOnce(ok(progress({ crawled: 3 })))
            .mockResolvedValueOnce(ok(progress({
                status: 'COMPLETED',
                crawled: 9,
                finishedAt: '2026-08-10T00:01:00',
            })));

        await renderList();
        clickRecrawl();
        await flush(); // 放掉 recrawlSite 那一跳，poll 挂上第一个定时器

        await flush(2000); // tick 1：RUNNING
        expect(knowledgeService.getCrawlProgress).toHaveBeenCalledTimes(1);
        expect(screen.getByText(/已爬 3/)).toBeInTheDocument();

        await flush(2000); // tick 2：COMPLETED → 收尾
        expect(knowledgeService.getCrawlProgress).toHaveBeenCalledTimes(2);
        expect(screen.queryByText(/正在爬取/)).not.toBeInTheDocument();
        expect(onCrawlFinished).toHaveBeenCalledTimes(1);
        // 初次挂载那次 + 收尾时的重查
        expect(knowledgeService.listCrawlSites).toHaveBeenCalledTimes(2);

        await flush(4000); // 终态之后不该再有任何续期
        expect(knowledgeService.getCrawlProgress).toHaveBeenCalledTimes(2);
    });
});
```

- [ ] **Step 2: 跑，确认在正确实现上是绿的**

```bash
cd D:/Code/m/qingyu-care/frontend && pnpm test
```

期望：2 个测试文件、2 条测试全绿。

如果红了，先排查这三个最可能的原因，不要改断言迁就它：
1. `getByText(/已爬 3/)` 找不到 → 进度块没渲染出来，说明 `flush` 的时序不对（漏了 `clickRecrawl()` 后那次 `await flush()`）；
2. `TDZ / Cannot access 'toastSpy' before initialization` → `vi.hoisted` 写漏了；
3. `listCrawlSites` 次数不对 → 检查是不是不小心用了 `renderList({ strict: true })`（StrictMode 下挂载 effect 跑两次，首次 `loadSites` 也是两次）。

- [ ] **Step 3: 提交（变异前必须先提交）**

```bash
cd D:/Code/m/qingyu-care
git add frontend/src/components/knowledge/CrawlSiteList.test.tsx
git commit -m "test(frontend): T1 轮询链——RUNNING 续期与终态收尾

顺带落下整个测试文件的脚手架：vi.hoisted 桩 toast、桩 api service 模块层、
桩掉 Radix 表单对话框、flush 助手（advanceTimersByTimeAsync + act）。"
```

- [ ] **Step 4: 变异实验——删掉 RUNNING 分支的续期**

把 `CrawlSiteList.tsx` 第 116-120 行的 RUNNING 分支改成（只删续期那一行）：

```tsx
            if (p && p.status === 'RUNNING') {
                setProgressMap((prev) => ({ ...prev, [jobId]: p }));
                return;
            }
```

跑 `pnpm test`，**期望 T1 转红**，失败信息应指向 `getCrawlProgress` 被调用次数是 1 而不是 2。

- [ ] **Step 5: 还原变异**

```bash
cd D:/Code/m/qingyu-care
git checkout -- frontend/src/components/knowledge/CrawlSiteList.tsx
cd frontend && pnpm test
```

期望：回到全绿。把 Step 4 观察到的失败信息记下来，Task 7 要汇总进提交信息。

---

## Task 3: T4 卸载后零副作用

**Files:**
- Modify: `frontend/src/components/knowledge/CrawlSiteList.test.tsx`（追加一条 `it`）
- 变异目标：`frontend/src/components/knowledge/CrawlSiteList.tsx:114`

**Interfaces:**
- Consumes: Task 2 的 `ok` / `progress` / `flush` / `renderList` / `clickRecrawl` / `onCrawlFinished`
- Produces: 无新公共物件

- [ ] **Step 1: 追加 T4**

在 `describe` 块内、T1 之后追加：

```tsx
    /**
     * T4｜变异实验：删掉 CrawlSiteList.tsx:114 那行 `if (!isMountedRef.current) return;`，
     * 本条必须转红。
     *
     * ⚠️ 卸载 effect 里的 clearTimeout **拦不住这条**——它只能取消「还没触发的」
     * 下一次调度。这里刻意让请求在卸载那一刻正挂在半空（deferred promise），
     * 唯一能挡住后续副作用的就是那行 isMountedRef 判断。
     */
    it('卸载后，在飞的请求返回终态也不再刷新列表或通知父组件', async () => {
        vi.mocked(knowledgeService.recrawlSite).mockResolvedValue(ok('job-1'));

        let settle!: (r: ApiResponse<CrawlProgress>) => void;
        vi.mocked(knowledgeService.getCrawlProgress).mockReturnValueOnce(
            new Promise<ApiResponse<CrawlProgress>>((resolve) => {
                settle = resolve;
            }),
        );

        const { unmount } = await renderList();
        clickRecrawl();
        await flush();

        await flush(2000); // tick 触发，请求挂在半空
        expect(knowledgeService.getCrawlProgress).toHaveBeenCalledTimes(1);

        unmount();

        settle(ok(progress({ status: 'COMPLETED', finishedAt: '2026-08-10T00:01:00' })));
        await flush();

        expect(onCrawlFinished).not.toHaveBeenCalled();
        // 只该有挂载时那一次；收尾分支里的 loadSites 不该被执行
        expect(knowledgeService.listCrawlSites).toHaveBeenCalledTimes(1);
    });
```

- [ ] **Step 2: 跑，确认绿**

```bash
cd D:/Code/m/qingyu-care/frontend && pnpm test
```

期望：3 条测试全绿。

- [ ] **Step 3: 提交**

```bash
cd D:/Code/m/qingyu-care
git add frontend/src/components/knowledge/CrawlSiteList.test.tsx
git commit -m "test(frontend): T4 卸载后零副作用——用 deferred 响应把请求卡在半空

卸载 effect 的 clearTimeout 拦不住「卸载那一刻已在途的请求」，唯一挡住它的是
isMountedRef 判断。所以这条必须让响应在 unmount 之后才落地，否则测的是
clearTimeout 而不是那行守卫。"
```

- [ ] **Step 4: 变异实验——删掉 isMountedRef 守卫**

把 `CrawlSiteList.tsx` 第 114 行 `if (!isMountedRef.current) return;` 整行删掉，跑 `pnpm test`。

**期望 T4 转红**：`onCrawlFinished` 被调用了 1 次、`listCrawlSites` 变成 2 次。

- [ ] **Step 5: 还原变异**

```bash
cd D:/Code/m/qingyu-care
git checkout -- frontend/src/components/knowledge/CrawlSiteList.tsx
cd frontend && pnpm test
```

---

## Task 4: T2 同站点双任务并发不互踩

**Files:**
- Modify: `frontend/src/components/knowledge/CrawlSiteList.test.tsx`（追加一条 `it`）
- 变异目标：`frontend/src/components/knowledge/CrawlSiteList.tsx` 的 `progressMap` / `timersRef` 索引键

**Interfaces:**
- Consumes: Task 2 的脚手架
- Produces: 无新公共物件

- [ ] **Step 1: 追加 T2**

⚠️ 这条测试的构造很讲究，**两个前提缺一不可**，写错就抓不到东西：

- 必须是**同一个站点**的两条任务。按 `siteId` 索引的缺陷只在「两条任务的 siteId 相同」时才现形；用两个不同站点写这条测试，按 `siteId` 索引照样绿。
- 必须让**旧任务的响应压在新任务之后才落地**。定时器按注册顺序触发，job-1 的 tick 排在 job-2 前面；若两者都立即 resolve，则 job-1 先收尾、job-2 后写入，被覆盖的方向正好反过来，变异实验依然绿。用 deferred promise 把 job-1 的响应按住，才制造出「旧任务收尾」与「新任务写入」的真实交叠窗口。

```tsx
    /**
     * T2｜变异实验：把 poll 里 progressMap 与 timersRef 的索引键从 jobId 改回 siteId
     * （三处：第 117 行的 setProgressMap、第 118 行的 timersRef、第 128-134 行的收尾清理，
     * 以及第 138 行首次挂表；连带把渲染处第 216 行的反查改成直接取 progressMap[site.id]），
     * 本条必须转红——job-1 的收尾会把 job-2 刚写进去的那条进度抹掉。
     */
    it('同一站点两条任务并发时，旧任务收尾不会抹掉新任务的进度', async () => {
        vi.mocked(knowledgeService.recrawlSite)
            .mockResolvedValueOnce(ok('job-1'))
            .mockResolvedValueOnce(ok('job-2'));

        // job-1 的响应按住，等 job-2 写完进度之后再放——这才是「旧任务收尾」
        // 与「新任务写入」交叠的那个窗口。若让 job-1 立刻 resolve，
        // 定时器注册顺序会让它先收尾、job-2 后写入，覆盖方向正好反过来，抓不到缺陷。
        let settleJob1!: (r: ApiResponse<CrawlProgress>) => void;
        vi.mocked(knowledgeService.getCrawlProgress).mockImplementation((jobId: string) => {
            if (jobId === 'job-1') {
                return new Promise<ApiResponse<CrawlProgress>>((resolve) => {
                    settleJob1 = resolve;
                });
            }
            return Promise.resolve(ok(progress({ jobId: 'job-2', crawled: 7 })));
        });

        await renderList();
        clickRecrawl();
        await flush();
        clickRecrawl();
        await flush();

        await flush(2000); // 两条 tick 都触发；job-2 立刻落地，job-1 仍被按住
        expect(screen.getByText(/已爬 7/)).toBeInTheDocument();

        settleJob1(ok(progress({ jobId: 'job-1', status: 'COMPLETED', crawled: 40 })));
        await flush();

        // 判别性断言：job-1 收尾之后，job-2 的进度必须还在
        expect(screen.getByText(/已爬 7/)).toBeInTheDocument();
        expect(onCrawlFinished).toHaveBeenCalledTimes(1);
    });
```

- [ ] **Step 2: 跑，确认绿**

```bash
cd D:/Code/m/qingyu-care/frontend && pnpm test
```

期望：4 条测试全绿。

若 `已爬 7` 在第一次断言处就找不到，检查是不是两次 `clickRecrawl()` 之间漏了 `await flush()`——`handleRecrawl` 要先 await `recrawlSite` 才会挂定时器。

- [ ] **Step 3: 提交**

```bash
cd D:/Code/m/qingyu-care
git add frontend/src/components/knowledge/CrawlSiteList.test.tsx
git commit -m "test(frontend): T2 同站点双任务并发不互踩

两个前提缺一不可：必须同一站点（按 siteId 索引的缺陷只在 siteId 相同时现形）、
必须让旧任务响应压在新任务之后落地（否则覆盖方向反过来，变异照样绿）。
写歪任一条，这测试就是摆设。"
```

- [ ] **Step 4: 变异实验——索引键改回 siteId**

在 `CrawlSiteList.tsx` 里把 `poll` 的索引从 `jobId` 换成 `p?.siteId ?? site 的 id`。最省事的等价变异：把 `poll` 的入参从 `jobId` 扩成 `(jobId, siteId)` 太啰嗦，直接改成用一个固定键即可——同一站点的两条任务本来就会落到同一个键：

```tsx
    const poll = useCallback((jobId: string) => {
        const key = 'site-1'; // 变异：模拟按 siteId 索引，同站点两条任务撞同一个键
        const tick = async () => {
            // ...把函数体里所有 progressMap / timersRef 的 [jobId] 换成 [key]
        };
        timersRef.current[key] = window.setTimeout(tick, 2000);
    }, [loadSites, onCrawlFinished, toast]);
```

同时把渲染处第 216 行的反查保持原样即可（它按 `siteId` 匹配 progress 的字段，不受键名影响）。

跑 `pnpm test`，**期望 T2 转红**：`getByText(/已爬 7/)` 在第二次断言处抛 `Unable to find an element`。

- [ ] **Step 5: 还原变异**

```bash
cd D:/Code/m/qingyu-care
git checkout -- frontend/src/components/knowledge/CrawlSiteList.tsx
cd frontend && pnpm test
```

---

## Task 5: T3 StrictMode 下 isMountedRef 不卡死

**Files:**
- Modify: `frontend/src/components/knowledge/CrawlSiteList.test.tsx`（追加一条 `it`）
- 变异目标：`frontend/src/components/knowledge/CrawlSiteList.tsx:84`

**Interfaces:**
- Consumes: Task 2 的脚手架（含 `renderList({ strict: true })`）；Task 1 Step 6 的探针结论
- Produces: 无新公共物件

- [ ] **Step 1: 按 Task 1 的探针结论选写法**

**A 方案（探针通过 → 用这个）**：真复现。追加：

```tsx
    /**
     * T3｜变异实验：删掉 CrawlSiteList.tsx:84 那行 `isMountedRef.current = true;`
     * （卸载 effect 的 setup 阶段），本条必须转红。
     *
     * 缺陷机理：isMountedRef 初值是 true，<StrictMode> 会把 effect 跑成
     * setup → cleanup → setup。setup 里不复位的话，中间那次 cleanup 置的 false
     * 就永久生效——组件挂载完标志位直接卡死在 false，poll 的第一次响应回来就被
     * 挡返回：进度不更新、定时器不续期、FAILED 不 toast、爬完不通知父组件。
     * 只在 `pnpm dev` 下发作，`vite build` 的生产包不受影响，React 18 起也没有
     * console 警告——所以极难靠人工发现，正是这条测试存在的理由。
     *
     * ⚠️ 判别性只在 DOM 断言上：请求本身在守卫**之前**就发出去了，
     * 所以 getCrawlProgress 的调用次数在变异前后都是 1，拿它断言抓不到东西。
     */
    it('StrictMode 双调用后轮询照常工作（isMountedRef 在 setup 阶段被复位）', async () => {
        vi.mocked(knowledgeService.recrawlSite).mockResolvedValue(ok('job-1'));
        vi.mocked(knowledgeService.getCrawlProgress).mockResolvedValue(ok(progress({ crawled: 5 })));

        await renderList({ strict: true });
        clickRecrawl();
        await flush();

        await flush(2000);

        expect(screen.getByText(/已爬 5/)).toBeInTheDocument();
    });
```

**B 方案（探针不通过 → 用这个，并在注释里写明它是弱守护）**：结构性断言。追加（需在文件顶部加 `import { readFile } from 'node:fs/promises';`）：

```tsx
    /**
     * T3（降级为结构性守护）｜本工具链下 <StrictMode> 不双调用 effect
     * （见 src/test/strictModeDoubleInvoke.test.tsx 的实测结论），
     * 那条缺陷无法在测试里真复现——isMountedRef 初值就是 true，不双调用永远测不出
     * 缺少复位。这里退化成「setup 分支里必须存在这行赋值」的源码断言：
     * 它只能挡住「有人把这行删掉」这一种回归方式，挡不住等价的写歪。
     * 真复现靠 CLAUDE.md 前端手工验证项兜底——与后端技术债 8（站点爬取 C-1
     * 只有结构性哨兵）同款处理，不假装它是强守护。
     */
    it('effect 的 setup 阶段必须复位 isMountedRef（结构性守护）', async () => {
        const source = await readFile(new URL('./CrawlSiteList.tsx', import.meta.url), 'utf8');
        expect(source).toMatch(/isMountedRef\.current = true;/);
    });
```

- [ ] **Step 2: 跑，确认绿**

```bash
cd D:/Code/m/qingyu-care/frontend && pnpm test
```

期望：5 条测试全绿。

- [ ] **Step 3: 提交**

```bash
cd D:/Code/m/qingyu-care
git add frontend/src/components/knowledge/CrawlSiteList.test.tsx
git commit -m "test(frontend): T3 StrictMode 下 isMountedRef 不卡死

三轮返工里最隐蔽的一个：只在 pnpm dev 下发作、vite build 不受影响、
React 18 起也没有 console 警告。判别性只在 DOM 断言上——请求在守卫之前就
发出去了，拿 getCrawlProgress 的调用次数断言抓不到任何东西。"
```

- [ ] **Step 4: 变异实验——删掉 setup 阶段的复位**

把 `CrawlSiteList.tsx` 第 84 行 `isMountedRef.current = true;` 删掉，跑 `pnpm test`。

- A 方案期望：T3 转红（`Unable to find an element with the text: /已爬 5/`）。
- B 方案期望：T3 转红（`toMatch` 失败）。

⚠️ 若用 A 方案而它**没有**转红，说明 StrictMode 双调用实际没发生（与 Task 1 的探针结论矛盾）——此时不要接受这条测试，回到 Step 1 改用 B 方案，并回填 Task 1 Step 6 的结论与 spec §4。

- [ ] **Step 5: 还原变异**

```bash
cd D:/Code/m/qingyu-care
git checkout -- frontend/src/components/knowledge/CrawlSiteList.tsx
cd frontend && pnpm test
```

---

## Task 6: T5 写操作业务异常不静默

**Files:**
- Modify: `frontend/src/components/knowledge/CrawlSiteList.test.tsx`（加 `fail` 助手 + 一条 `it`）
- 变异目标：`frontend/src/components/knowledge/CrawlSiteList.tsx:171`

**Interfaces:**
- Consumes: Task 2 的脚手架
- Produces: `fail<T>(message: string): ApiResponse<T>` —— 造 `{code: 400, message, data: null}`，供后续测试复用

- [ ] **Step 1: 加 `fail` 助手**

放在 `ok` 定义之后：

```tsx
// 业务异常的形态：HTTP 200 + body.code != 200，data 为 null。
// axios 不会 reject 这种响应，调用方必须自己判 code——这正是 T5 守的那条约定。
const fail = <T,>(message: string): ApiResponse<T> => ({
    code: 400,
    message,
    data: null as unknown as T,
    timestamp: '2026-08-10T00:00:00',
});
```

- [ ] **Step 2: 追加 T5**

```tsx
    /**
     * T5｜变异实验：删掉 CrawlSiteList.tsx:171 那行
     * `if (res.code !== 200) throw new Error(res.message);`，本条必须转红。
     *
     * 守的是本仓库的全局约定：业务异常走「HTTP 200 + body.code」，axios 不会 reject，
     * 不显式判 code 就会把后端的业务错误静默吞掉——用户点按钮毫无反应且没有任何报错。
     * 变异后 res.data 是 null，`if (res.data) poll(...)` 自然不进，于是既不轮询也不报错，
     * 正是「静默」那个形态。
     */
    it('重新爬取返回业务错误时报错而非静默，且不启动轮询', async () => {
        vi.mocked(knowledgeService.recrawlSite).mockResolvedValue(fail<string>('站点不存在'));

        await renderList();
        clickRecrawl();
        await flush();

        expect(toastSpy).toHaveBeenCalledWith(expect.objectContaining({
            variant: 'destructive',
            title: '重新爬取失败',
            description: '站点不存在',
        }));

        await flush(4000);
        expect(knowledgeService.getCrawlProgress).not.toHaveBeenCalled();
    });
```

- [ ] **Step 3: 跑，确认绿**

```bash
cd D:/Code/m/qingyu-care/frontend && pnpm test
```

期望：6 条测试全绿。

- [ ] **Step 4: 提交**

```bash
cd D:/Code/m/qingyu-care
git add frontend/src/components/knowledge/CrawlSiteList.test.tsx
git commit -m "test(frontend): T5 写操作业务异常不静默

守的是全局约定：业务异常走 HTTP 200 + body.code，axios 不 reject，
不显式判 code 就把后端错误静默吞掉。"
```

- [ ] **Step 5: 变异实验——删掉 code 判断**

把 `CrawlSiteList.tsx` 第 171 行 `if (res.code !== 200) throw new Error(res.message);` 删掉，跑 `pnpm test`。

**期望 T5 转红**：`toastSpy` 一次都没被调用。

- [ ] **Step 6: 还原变异**

```bash
cd D:/Code/m/qingyu-care
git checkout -- frontend/src/components/knowledge/CrawlSiteList.tsx
cd frontend && pnpm test
```

---

## Task 7: 文档、技术债清单与终验

**Files:**
- Modify: `CLAUDE.md:82-87`（前端命令段）
- Modify: `CLAUDE.md:98-106`（测试约定段）
- Modify: `CLAUDE.md:205`（响应拦截器表述）
- Modify: `SaaS平台规划.md:269-271`（技术债 6）与 `SaaS平台规划.md:279` 之后（新增一条）
- Modify: `docs/superpowers/specs/2026-08-10-frontend-test-infra-design.md`（回填探针结论）

**Interfaces:**
- Consumes: Task 1-6 的全部变异实验观测结果
- Produces: 收官提交

- [ ] **Step 1: CLAUDE.md 前端命令段加 `pnpm test`**

把第 82-87 行改成：

```markdown
### 前端（frontend/，pnpm）
```bash
pnpm install && pnpm dev   # Vite 开发服务器（:5173，/api 代理到 :8080）
pnpm build                 # tsc -b && vite build
pnpm lint                  # ESLint（flat config: eslint.config.js）
pnpm test                  # vitest（jsdom + @testing-library/react）
```
```

- [ ] **Step 2: CLAUDE.md 测试约定段加前端小节**

在第 106 行（`mvn verify` 那条 ⚠️）之后、`## 架构要点` 之前插入：

```markdown
### 前端测试（frontend/，vitest）

`src/**/*.test.tsx` 与被测文件同目录并放（同 `widget/`）。`pnpm test` 跑一次就退，`pnpm test:watch` 是监听模式。

- ⚠️ **故意不开 `globals: true`**（这点与 `widget/` 不同）：测试文件显式 `import { describe, it, expect, vi } from 'vitest'`。收益是 eslint 不必加 vitest globals、`tsconfig` 不必加全局类型，前端 lint 的既有 error 基线零新增。
- ⚠️ **代价是 RTL 不再自动清理**：`@testing-library/react` 只在 `afterEach` 是全局函数时才自己注册 `cleanup`。`src/test/setup.ts` 里那行 `afterEach(cleanup)` **不能删**——漏掉的症状是「单个测试跑绿、整套一起跑红」，因为上一个测试的 DOM 还挂在 `document` 上。
- ⚠️ **推进时间一律 `await vi.advanceTimersByTimeAsync(ms)`，包在 `act` 里**。轮询类代码的回调内部有 `await`，同步的 `advanceTimersByTime` 只把定时器回调排出去、推不动后面的 promise 链——测试会「通过」但实际什么都没验证。
- 桩 HTTP 一律**桩到 `api/*.ts` 的 service 对象**（`vi.mock('@/api/knowledge')`），返回 `{code, message, data}`。这一层正是「HTTP 200 + `body.code`」约定发生的位置，构造 `{code: 400}` 最直接。不引入 MSW。
- `vi.mock` 的工厂被提到所有 import 之前执行，工厂里引用模块顶层 `const` 会撞 TDZ——桩函数必须用 `vi.hoisted` 一起提上去。
- Radix 组件（Dialog / Select 等）的 portal 与 focus trap 在 jsdom 下行为特殊，测别的逻辑时**直接 `vi.mock` 桩成空组件**，别顺带把它们一起测了。
- `src/test/strictModeDoubleInvoke.test.tsx` 是**工具链前提哨兵**，不测产品代码：它断言 vitest 下 `<StrictMode>` 真的把 effect 跑成 setup→cleanup→setup。`CrawlSiteList.test.tsx` 里那条 `isMountedRef` 回归测试依赖这个前提——前提一旦不成立，那条测试会**静默退化成永真**而不是转红，所以把前提本身钉成一条测试。
- 覆盖面目前只有 `CrawlSiteList`（技术债清单第 6 项的第一批），其余页面仍是零覆盖。
```

- [ ] **Step 3: CLAUDE.md 修正响应拦截器表述**

第 205 行里的 `响应拦截器拆 `ApiResponse.data`` 改成：

```
响应拦截器返回 `response.data`（即后端那整个 `ApiResponse`，**不是**再往里取一层 `.data`——调用方拿到的是 `{code, message, data}`）
```

这句原本的写法会把下一个人的桩直接写歪。

- [ ] **Step 4: `SaaS平台规划.md` 技术债 6 标注进展（不折叠）**

把第 269-271 行改成：

```markdown
6. **管理后台前端测试覆盖仍近乎为零**（基建已建，2026-08-10）
   `frontend/` 已接 vitest + jsdom + `@testing-library/react`（`pnpm test`），并用五条测试把站点爬取前端那三轮返工的缺陷锁住（`CrawlSiteList.test.tsx`：轮询续期 / 终态收尾 / 双任务互踩 / 卸载后在途请求 / 写操作业务异常），每条都配了变异实验。**但也仅此一处**——27 个页面与其余 29 个组件仍是零覆盖，唯一验收还是 `pnpm build` + lint，所以这条债**没清完**、不折叠。
   <details><summary>原问题</summary>
   `frontend/` 无 vitest，唯一验收是 `pnpm build` + lint；反倒是 `widget/` 有 vitest 与 10 个测试文件。
   代价已经实际发生过：站点爬取的前端三轮返工（轮询生命周期、双并发任务互踩、StrictMode 下 `isMountedRef` 永久卡死）全靠人工代码走查发现，**有测试的话第一轮就该红**。
   </details>
```

- [ ] **Step 5: `SaaS平台规划.md` 新增静默失败一条**

在第 279 行（技术债 8 那条）之后追加：

```markdown
9. **管理后台大量写操作把业务错误静默吞掉**
   形态是 `if (res.code === 200) { 成功分支 }` **没有 else**——不是没判 code，是判了却没有失败分支。业务异常走「HTTP 200 + `body.code`」、axios 不 reject，于是后端的业务错误（重复入口、资源被占用、并发上限、额度超限等）到了前端一律无声无息：用户点按钮毫无反应，也没有任何报错。
   已确认的样本：`ApplicationListPage`（创建 / 更新 / 启停）、`HandoverRulePage`（启停 / 保存配置）、`KnowledgePage`（删文档）、`AbTestPage`（创建 / 更新）。全量约 43 处写操作调用点，判了 code 的约 20 处，其中带失败分支的更少。
   正确写法在仓库里现成：`components/knowledge/CrawlSiteList.tsx` 的四个写操作都是 `if (res.code !== 200) throw new Error(res.message)` + catch 里 destructive toast。
   ⚠️ 修的时候顺手给关键页面补回归测试——基建已经有了（见第 6 项），这类缺陷正是它最擅长抓的。
```

- [ ] **Step 6: 回填 spec 的探针结论**

在 `docs/superpowers/specs/2026-08-10-frontend-test-infra-design.md` 的 §4 末尾加一行实测结论，二选一：

```markdown
**实测结论（2026-08-10）**：探针通过——vitest 下 `<StrictMode>` 确实把 effect 跑成
`setup → cleanup → setup`，T3 按真实复现写（强守护）。探针留在
`src/test/strictModeDoubleInvoke.test.tsx` 守护这个前提，未按原计划删除，理由见实现计划。
```

或（探针不通过时）：

```markdown
**实测结论（2026-08-10）**：探针不通过——本工具链下 `<StrictMode>` 不双调用 effect，
T3 已降级为结构性断言（弱守护），真复现靠手工验证项兜底。
```

- [ ] **Step 7: 终验三件套**

```bash
cd D:/Code/m/qingyu-care/frontend && pnpm test && pnpm build && pnpm lint 2>&1 | tail -5
```

期望：6 条测试全绿 · build 成功 · lint 的 error 数 ≤ 69。

- [ ] **Step 8: 收官提交，把六次变异实验的观测结果写进提交信息**

```bash
cd D:/Code/m/qingyu-care
git add CLAUDE.md SaaS平台规划.md docs/superpowers/specs/2026-08-10-frontend-test-infra-design.md
git commit -m "docs: 前端测试基建收官——技术债 6 标注进展（不折叠）+ 新增静默失败一条

变异实验逐条记录（每条都在正确实现上绿、在指定变异下红）：
- T1 删 RUNNING 分支的 setTimeout 续期 → 红（getCrawlProgress 只被调 1 次）
- T4 删 isMountedRef 守卫 → 红（onCrawlFinished 被调 1 次、listCrawlSites 2 次）
- T2 索引键 jobId 改回 siteId → 红（job-2 的进度被 job-1 收尾抹掉）
- T3 删 setup 阶段的 isMountedRef 复位 → 红（进度块整个不出现）
- T5 删 res.code 判断 → 红（toast 一次都没调）
- 工具链哨兵：StrictMode 双调用实测<成立/不成立>

技术债 6 不折叠：只覆盖了 CrawlSiteList 一处，27 个页面与其余 29 个组件仍零覆盖。
顺带修正 CLAUDE.md 里「响应拦截器拆 ApiResponse.data」的错误表述——实际拆的是
axios 的 response.data，调用方拿到的是整个 ApiResponse，这句写错会把桩写歪。"
```

---

## Self-Review 记录

**Spec 覆盖核对**：§3.1 依赖 → Task 1 Step 1；§3.2 配置四项 → Task 1 Step 2-4（`tsconfig` 那项按「相对 spec 的修正 1」不做）；§3.3 位置 → Task 2 Step 1；§4 探针 → Task 1 Step 5-6 + Task 5 Step 1 双方案 + Task 7 Step 6 回填；§5 五条测试 → Task 2-6；§5.1 四个假绿陷阱 → Global Constraints + Task 2 脚手架注释；§5.2 纪律 → Global Constraints；§6 文档四项 → Task 7 Step 1-5；§7 验收 → Task 7 Step 7。无遗漏。

**类型一致性**：`ok<T>` / `fail<T>` 返回 `ApiResponse<T>`（`{code, message, data, timestamp}`，与 `types/index.ts:1-6` 一致）；`progress()` 返回 `CrawlProgress` 全部 11 个字段（`types/index.ts:127-139`）；`site1` 覆盖 `CrawlSite` 全部 9 个字段（`types/index.ts:114-124`）。`knowledgeService` 桩的六个方法名与 `api/knowledge.ts:71-92` 一致。`useToast()` 只被解构出 `toast`，桩只提供这一个键。

**已知的两处判别性陷阱**（写歪就抓不到东西，已在对应任务里标粗）：T2 必须同站点 + 旧任务响应后落地；T3 的判别性只在 DOM 断言上，调用次数断言抓不到。
