# 前端测试基建 + CrawlSiteList 覆盖 设计

> 技术债清单第 6 项「管理后台前端零测试」的第一批。
> 日期：2026-08-10 · 作者：ruan

## 1. 背景与目标

`frontend/` 有 11.4k 行代码、零测试、无 CI，唯一验收是 `pnpm build` + `pnpm lint`；反倒是 `widget/`
有 vitest 与 10 个测试文件。代价已经实际发生过：站点爬取的前端三轮返工（轮询生命周期、双并发任务
互踩、`<StrictMode>` 下 `isMountedRef` 永久卡死）全靠人工代码走查发现，有测试的话第一轮就该红。

**本批的成功标准**：把那三轮返工真正锁住，并用变异实验证明测试改得出来。

选这个标准而不是「先铺基建、测试从纯函数开始」，是因为技术债第 6 项的论据就是那三轮返工——第一批
若只测纯函数，论据没被回答；而 `CrawlSiteList` 同时是本项目最难测的一类（异步 + fake timers +
StrictMode），基建撑得住它才说明它对后续特性也有用。

### 范围

**做**：vitest 基建 + `CrawlSiteList` 五条测试 + 文档。

**不做**：`api/client.ts` 拦截器、其它页面、覆盖率门槛、CI、重构 `CrawlSiteList`。

**顺带发现、另记技术债不在本批处理**：管理后台大量写操作是 `if (res.code === 200) { 成功 }`
**没有 else 分支**的形态（`ApplicationListPage` / `HandoverRulePage` / `KnowledgePage` /
`AbTestPage` 等）——不是没判 code，是判了却没有失败分支，业务错误时按钮静默无反应。这正是
CLAUDE.md 点名的「HTTP 200 + `body.code`」约定被违反的准确形态，涉及 20+ 处调用点，自己就是一批。

## 2. 桩策略：桩到 api service 模块层

`vi.mock('@/api/knowledge')` 直接返回 `{ code, message, data }`。

选这一层的理由：`api/client.ts` 的响应拦截器是 `response => response.data`，即拆掉 axios 包装后
**把后端的整个 `ApiResponse` 交给调用方**，所以调用方读 `res.code` 是对的。桩在 api service 模块层
= 桩点与「HTTP 200 + `body.code`」约定的发生位置正好重合，构造 `{code: 400}` 这种关键形态最直接。

> ⚠️ CLAUDE.md 原文写的是响应拦截器「拆 `ApiResponse.data`」，这句表述不准确——拆的是 axios 的
> `response.data`（它恰好等于整个 `ApiResponse`），不是再往里取一层 `.data`。这句写错会把下一个人
> 的桩直接写歪，本批一并修正。

**未采纳的两个方案**：

- **MSW 拦真实 HTTP**：能顺带覆盖拦截器（token 注入 / 401 跳转），但为本批目标额外引入两处不确定性
  （node server 生命周期 + axios adapter）。拦截器那层更适合日后单独用一条小测试直接测函数。
- **先把轮询抽成 `useCrawlPolling` hook 只测 hook**：测试最稳，还能顺手收编 `AgentPage` 的
  `setInterval` 与 `KnowledgePage:180` 那个无清理的裸 `setTimeout`；但要重构刚经三轮返工验证过的
  代码，且绕开了「基建能不能撑住组件测试」这个问题——下个特性照样没法测。

## 3. 基建

### 3.1 依赖

新增 devDependencies，版本与 `widget/` 对齐：

| 包 | 版本 | 用途 |
|:---|:---|:---|
| `vitest` | `^4` | 测试运行器（widget 同版本） |
| `jsdom` | `^29` | DOM 环境（widget 同版本） |
| `@testing-library/react` | `^16` | React 19 对应的 RTL 主版本 |
| `@testing-library/jest-dom` | `^6` | 断言可读性（`toBeInTheDocument` 等） |

不装 `@testing-library/user-event`：本批用 `fireEvent` 足够，且能避开 user-event × fake timers
需要 `advanceTimers` 桥接的配置坑。

### 3.2 配置

**`frontend/vite.config.ts`**：`defineConfig` 改从 `vitest/config` 导入（同 `widget/vite.config.ts`），
加：

```ts
test: {
  environment: 'jsdom',
  setupFiles: ['./src/test/setup.ts'],
}
```

**故意不开 `globals: true`**（这点与 widget 不同）：测试文件显式
`import { describe, it, expect, vi } from 'vitest'`。收益是 eslint 不必加 vitest globals、
`tsconfig` 不必加 vitest 全局类型，**lint 的 69 个 error 基线零新增**。

**`src/test/setup.ts`**：

```ts
import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';

afterEach(cleanup);
```

⚠️ 非 globals 模式下 RTL **不会自动 cleanup**。漏掉 `afterEach(cleanup)` 的症状是「单个测试跑绿、
整套跑红」，定位很费时间，所以在基建这一步就固定住。

**`tsconfig.app.json`**：**刻意不加** `"@testing-library/jest-dom"` 到 `types` 数组——
`@testing-library/jest-dom/vitest` 子路径本身就是给 vitest 用的模块增强入口（根入口
`@testing-library/jest-dom` 才是给 jest 的），`src/test/setup.ts` 里
`import '@testing-library/jest-dom/vitest'` 这一行已经把 `toBeInTheDocument` 等断言类型接上，
不需要再显式声明全局类型。测试文件仍留在 `src/` 下受 `tsc -b` 检查——`pnpm build` 顺带给测试做
类型检查，这是想要的。

**`package.json`**：

```json
"test": "vitest run",
"test:watch": "vitest"
```

`test` 的语义与 widget 保持一致（一次性跑完退出）。

### 3.3 测试文件位置

与 widget 同款就地并放：`src/**/*.test.tsx`，本批即
`src/components/knowledge/CrawlSiteList.test.tsx`。

## 4. 前置探针：StrictMode 双调用在 vitest 下是否发生

`isMountedRef` 卡死那条 bug **只在 `<StrictMode>` 的 `setup → cleanup → setup` 下发作**，而双调用
只在 development 构建生效。vitest 下 react-dom 走哪个构建、双 effect 到底跑不跑，属于**可行性未知**，
必须先实测，不能假设。

探针：约 15 行，`<StrictMode>` 下渲染一个记录 effect setup 次数的组件，断言 setup 跑了 2 次。

- **通过** → 第 5 节 T3 按真实复现写（强守护）。
- **不通过** → T3 退化为「effect 的 setup 分支里必须存在 `isMountedRef.current = true`」的结构性
  断言，并在测试注释与 CLAUDE.md 里写明「这条只有结构性守护、真复现靠手工验证项兜底」——与后端
  技术债 8（站点爬取 C-1 只有结构性哨兵）同款处理，**不假装它是强守护**。

探针留不留在仓库里取决于实测结论——它验证的是工具链行为、不是产品行为，本身没有留存的必然性；
结论写进 spec 与测试注释。

**实测结论（2026-08-10）**：探针通过——vitest 下 `<StrictMode>` 确实把 effect 跑成
`setup → cleanup → setup`，T3 按真实复现写（强守护）。探针留在
`src/test/strictModeDoubleInvoke.test.tsx` 守护这个前提，未按原计划删除，理由见实现计划。

## 5. CrawlSiteList 测试清单

五条，每条都必须配一次变异实验：**把实现改坏，测试必须转红；红不了的测试当场重写**。

| # | 测什么 | 变异（把实现改坏） |
|:--|:---|:---|
| T1 | 轮询链：RUNNING → 2s 后续查 → 终态收尾（进度消失 + 重查列表 + `onCrawlFinished` 恰好一次） | 删掉 RUNNING 分支的 `setTimeout` 续期 |
| T2 | 双 jobId 并发不互踩：两条 RUNNING，其中一条转终态，另一条的进度与定时器仍在 | `progressMap` / `timersRef` 索引从 `jobId` 改回 `siteId` |
| T3 | StrictMode 下挂载后轮询照常工作（`isMountedRef` 没卡在 false） | 删掉 setup 阶段的 `isMountedRef.current = true` |
| T4 | 卸载后零副作用：unmount 后推进 timers，不再发请求、`onCrawlFinished` 不被调用 | 删掉 `if (!isMountedRef.current) return` |
| T5 | 写操作业务异常不静默：`recrawlSite` 返 `{code: 400}` → destructive toast，且不启动轮询 | 删掉 `if (res.code !== 200) throw new Error(res.message)` |

T1/T2/T4 对应站点爬取前端三轮返工的三个缺陷，T3 对应其中最隐蔽的那个（只在 `pnpm dev` 下发作、
`vite build` 不受影响、React 18 起也没有 console 警告）。T5 守的是本仓库的全局约定。

### 5.1 四个容易写出假绿测试的地方

基建阶段就把写法定死：

1. **fake timers 必须用 `await vi.advanceTimersByTimeAsync(2000)`**。`tick` 内部有 `await`，同步的
   `advanceTimersByTime` 推不动 promise 链——测试会「通过」，但实际什么都没验证。
2. **`vi.mock('./CrawlSiteDialog')` 桩成极简 stub**，把 Radix Dialog 从 jsdom 里摘出去，让测试聚焦
   轮询链而不是 portal / focus trap 在 jsdom 下的行为。
3. **`vi.mock('@/hooks/use-toast')`**，`toast` 为 `vi.fn()`，断言调用实参含 `variant: 'destructive'`。
4. **一律断言精确次数 `toHaveBeenCalledTimes(n)`**，不用 `toHaveBeenCalled()`。后者对「多续期了
   一次」完全是瞎的，而重复续期正是本组件的核心风险（T1/T2/T4 都指着它）。

### 5.2 执行纪律

**做变异实验前必须先提交。** 上一批栽过一次：未提交就变异，`git checkout --` 把自己未提交的改动
一起还原了，编译直接不过。

## 6. 文档改动

- CLAUDE.md「前端（frontend/，pnpm）」命令段补 `pnpm test`
- CLAUDE.md「测试约定」新增一小节：前端测试的位置与命名、**不开 `globals` 的原因**、
  **RTL 非 globals 模式必须显式 `afterEach(cleanup)`** 这个坑
- CLAUDE.md 修正响应拦截器「拆 `ApiResponse.data`」的表述（见 §2）
- `SaaS平台规划.md` 技术债清单：
  - 第 6 项标注「基建已建 + `CrawlSiteList` 已覆盖」，但**不折叠**——全站仍近乎零覆盖，债没清完
  - 新增一条：写操作 `if (code === 200)` 无 else 分支导致业务错误静默（20+ 处，见 §1 范围）

## 7. 验收

- `pnpm test` 全绿
- 五条变异实验各自转红，**逐条记录**（写进提交信息）
- `pnpm build` 绿（含对测试文件的类型检查）
- `pnpm lint` 的 error 数 **≤ 69**（既有基线不新增）
- 探针结论落进 spec §4 与测试注释
