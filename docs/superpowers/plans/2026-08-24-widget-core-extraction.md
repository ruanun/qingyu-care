# Widget 协议层抽取 + CLI 调试客户端 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `widget/src` 里与浏览器无关的协议逻辑抽成 `widget/src/core/`，并写一个面向运维的 CLI 调试客户端作为第一个非浏览器消费者。

**Architecture:** core 留在 widget 的 build root 内（不建 monorepo、不独立子项目），平台差异由调用方注入一个 `WidgetStorage` 实现（widget→localStorage，CLI→本地文件）。⚠️ **动作执行本期不抽象**——CLI 只打印卡片的原始 `ui_spec`、不渲染按钮，执行方只有 widget 一个，凭空造接口没有第二个实现来验证它对不对（这是对 spec §3.3 的一处收窄，Task 9 会同步勘误）。CLI 是独立子项目，用相对路径 import core 的 TS 源码、由 `tsc` 编译。**core 的测试必须在 vitest 的 `node` 环境跑通**——这是本次抽取唯一一条机器可判定的「抽干净了」证据。

**Tech Stack:** TypeScript 5.9 / vite 7 + vitest 4（widget）/ Node 内置 `util.parseArgs` + `readline` + 原生 `fetch`（CLI，零运行时第三方依赖）。

**Spec:** `docs/superpowers/specs/2026-08-24-widget-core-extraction-design.md`

## Global Constraints

- **语言**：代码注释、commit message 一律简体中文。新建文件若写作者写 `ruan`。
- **测试与被测文件同目录并放**（本仓库既有约定，widget 与 frontend 都如此）。
- **core 的每个测试文件首行必须是 `// @vitest-environment node`**。widget 整体配置是
  `environment: 'jsdom'`，core 的测试要用文件级注释覆盖成 node。**这不是形式**：协议层里若
  还粘着 `document` / `localStorage`，只有在 node 环境下才会炸出来。
- ⚠️ **`widget.js` 是提交进仓库的构建产物**，由后端作为静态资源下发。`scripts/buildFreshness.test.ts`
  把 `widget/src/**/*.ts`（排除 `*.test.ts`）的 sha256 写进产物并校验——**动了 widget/src 就必须
  `pnpm build` 并提交 `widget.js`**，否则该测试转红。
- **不改后端任何契约**：CLI 只消费现有开放 API 与现有 SSE 事件。
- **零运行时第三方依赖（CLI）**：参数解析用 `util.parseArgs`、交互用 `readline`、HTTP 用原生
  `fetch`。⚠️ 构建仍需 `typescript`（devDependency），不要理解成「devDependency 也不能有」。
- 分支已是 `feat/widget-core-extraction`，**不要切分支、不要合并、不要 push**。
- 每个任务至少一次提交，commit message 说明「为什么」而不只是「做了什么」。

---

## 文件结构

**新建（core，全部在 `widget/src/core/`）**
| 文件 | 职责 |
|---|---|
| `sse.ts` / `chatStream.ts` / `history.ts` / `safeUrl.ts` | 从 `widget/src/` 原样移入，零改造 |
| `session.ts` | 会话持久化，**改造：注入 storage** |
| `actionUri.ts` | Action URI **解析与白名单**（执行留平台层） |
| `api.ts` | 各端点调用 + **双路径鉴权头构造**（不含附件） |
| `platform.ts` | `WidgetStorage` 接口定义（**只此一个**，动作执行本期不抽象） |

**修改（widget）**：`main.ts`（提供 localStorage 实现 + session 三处调用点）、`actionUri.ts`（收缩为执行层）、`api.ts`（收缩为附件两方法 + 重导出 core）

**新建（CLI，`cli/`）**
| 文件 | 职责 |
|---|---|
| `package.json` / `tsconfig.json` | 子项目配置，`tsc` 编译 |
| `src/args.ts` | 参数解析与互斥校验 |
| `src/fileStorage.ts` | `Storage` 的文件实现 |
| `src/render.ts` | 终端输出（纯函数） |
| `src/index.ts` | 入口 + 交互循环 |

**修改（后端）**：`CardActionProtocolSyncTest.java` 的路径常量

---

## Task 1: 建 core 并移入四个零改造模块

**Files:**
- Create: `widget/src/core/{sse,chatStream,history,safeUrl}.ts`（从 `widget/src/` 移动）
- Create: `widget/src/core/{sse,chatStream,history,safeUrl}.test.ts`（同步移动，`safeUrl` 若无测试则不必建）
- Modify: 所有 import 这四个模块的 widget 文件

**Interfaces:**
- Produces: `widget/src/core/` 目录成立；四个模块的导出签名**完全不变**，只是路径变了。

- [ ] **Step 1: 移动文件并改 import**

```bash
cd widget/src && mkdir -p core
git mv sse.ts chatStream.ts history.ts safeUrl.ts core/
git mv sse.test.ts chatStream.test.ts history.test.ts core/ 2>/dev/null || true
ls core/
```

然后把仓库内所有指向这四个模块的 import 改成新路径。用这条命令找出全部调用点：

```bash
cd widget/src && grep -rn "from './sse'\|from './chatStream'\|from './history'\|from './safeUrl'" . | grep -v '^./core/'
```

- `widget/src/*.ts` 里的 `from './sse'` → `from './core/sse'`（其余三个同理）
- `widget/src/core/*.ts` 内部互相引用保持 `./xxx` 不变（它们现在同目录）

- [ ] **Step 2: 给 core 的测试文件加 node 环境注释**

在 `widget/src/core/` 下**每个** `*.test.ts` 的**第一行**加：

```ts
// @vitest-environment node
```

⚠️ 必须是文件第一行（vitest 用 docblock 识别）。这一步是本次抽取的核心验证手段——若某个模块
其实还依赖 `document`/`localStorage`，切成 node 环境后会立刻失败。

- [ ] **Step 3: 跑测试**

```bash
cd widget && pnpm test
```

预期：全绿。**若 core 的某条测试在 node 环境下失败**，说明该模块并非平台无关——停下来报告，
不要通过加 jsdom 环境来"修复"它（那等于把问题盖回去）。

- [ ] **Step 4: 重建产物并提交**

```bash
cd widget && pnpm build
cd .. && git add widget/src backend/src/main/resources/static/widget.js
git commit -m "refactor(widget): 建 core 目录并移入四个零改造协议模块

sse/chatStream/history/safeUrl 本来就零浏览器依赖（grep 确认无 document/window/
localStorage/navigator 引用），原样移入 widget/src/core/。

core 的测试全部加 @vitest-environment node：widget 整体跑在 jsdom 下，协议层里若还粘着
浏览器 API 是看不出来的，切成 node 环境才能把它炸出来。这是本次抽取唯一一条机器可判定的
「抽干净了」证据。

widget.js 已重建——文件移动会改变 buildFreshness 的源码指纹。"
```

---

## Task 2: CLI 骨架与跨目录 import 打通（风险前置）

**Files:**
- Create: `cli/package.json`、`cli/tsconfig.json`、`cli/.gitignore`
- Create: `cli/src/index.ts`（本任务只做最小可运行版本）
- Test: `cli/src/smoke.test.ts`

**Interfaces:**
- Consumes: Task 1 的 `widget/src/core/safeUrl.ts`
- Produces: 可编译可运行的 `cli/` 子项目；证明「CLI 能 import core 的 TS 源码」这条唯一没有先例的构建配置成立。

**为什么排在第 2 位**：这是本次唯一没有先例的构建配置。若它走不通，后面所有抽取的形态都要重定——
必须在投入更多之前把它证伪或证实。本仓库有过「纸面推理翻车」的教训（Boot 4 迁移时靠实测才确认
引入 webflux 不会把 MVC 应用切成 reactive）。

- [ ] **Step 1: 建子项目配置**

`cli/package.json`：

```json
{
  "name": "qingyu-cli",
  "private": true,
  "version": "1.0.0",
  "type": "module",
  "bin": { "qingyu-cli": "./dist/index.js" },
  "scripts": {
    "build": "tsc",
    "test": "vitest run",
    "start": "node dist/index.js"
  },
  "devDependencies": {
    "typescript": "~5.9.3",
    "vitest": "^4.1.10",
    "@types/node": "^22.10.2"
  }
}
```

`cli/tsconfig.json` —— ⚠️ 关键是**不设 `rootDir`**（要编译 `cli/` 之外的 core 源码）：

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "module": "ESNext",
    "moduleResolution": "bundler",
    "outDir": "dist",
    "strict": true,
    "esModuleInterop": true,
    "skipLibCheck": true,
    "types": ["node"]
  },
  "include": ["src/**/*.ts", "../widget/src/core/**/*.ts"],
  "exclude": ["**/*.test.ts"]
}
```

`cli/.gitignore`：

```
node_modules
dist
```

- [ ] **Step 2: 写最小 import 的冒烟测试**

`cli/src/smoke.test.ts`：

```ts
// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { safeUrl } from '../../widget/src/core/safeUrl';

/**
 * 这不是在测 safeUrl（它有自己的测试），而是在测**构建配置**：
 * CLI 作为独立子项目，能否 import 位于 widget 目录下的 core 源码。
 *
 * 这是本次抽取唯一一条没有先例的构建配置。它一旦不成立，core 的位置就要重新定，
 * 所以把验证放在最前面，而不是等 CLI 写完才发现解析不了。
 */
describe('CLI 能消费 widget/src/core 的源码', () => {
  it('import 得到的是真实实现而不是 undefined', () => {
    expect(typeof safeUrl).toBe('function');
    expect(safeUrl('https://example.com')).toBe('https://example.com');
    expect(safeUrl('javascript:alert(1)')).toBeNull();
  });
});
```

⚠️ `safeUrl` 对非法 URL 的实际返回值以 `widget/src/core/safeUrl.ts` 的实现为准——先读它再写断言，
若返回的是 `''` 而非 `null`，按实际改断言，不要改实现。

- [ ] **Step 3: 装依赖并跑测试**

```bash
cd cli && pnpm install && pnpm test
```

预期：通过。**若 vitest 解析不到 `../../widget/src/core/safeUrl`**，先尝试在 `cli/vite.config.ts`
里加 `resolve.preserveSymlinks` 或调整 `include`；仍不通就**停下来报告**——那意味着 spec §2.1
的方案 A 有问题，需要重新决定 core 的位置，不是实现者该独自改架构的事。

- [ ] **Step 4: 写最小入口并验证编译产物真能跑**

`cli/src/index.ts`：

```ts
#!/usr/bin/env node
/**
 * 轻语 CLI 调试客户端入口。本任务只做到「能编译、能运行、能用上 core」，
 * 参数解析与交互循环在后续任务补齐。
 *
 * @author ruan
 */
import { safeUrl } from '../../widget/src/core/safeUrl';

const url = process.argv[2] ?? 'https://example.com';
console.log(`safeUrl(${url}) = ${safeUrl(url)}`);
```

```bash
cd cli && pnpm build && node dist/index.js https://example.com
```

预期：打印 `safeUrl(https://example.com) = https://example.com`。

⚠️ **编译产物的目录结构要确认**：不设 `rootDir` 时 tsc 会按公共根推断输出结构，`dist/` 下可能变成
`dist/cli/src/index.js` + `dist/widget/src/core/*.js`。若 `node dist/index.js` 找不到文件，按实际
结构调整 `package.json` 的 `bin`/`start` 路径，并在 README 或注释里记下这个结构。

- [ ] **Step 5: 提交**

```bash
git add cli/
git commit -m "feat(cli): 子项目骨架与跨目录 import 打通

把「CLI 能否 import widget/src/core 的 TS 源码」这条唯一没有先例的构建配置前置验证掉——
它一旦不成立，core 的位置就要重新定，不该等 CLI 写完才发现。

tsconfig 刻意不设 rootDir（要编译 cli/ 之外的 core 源码），include 显式带上
../widget/src/core。冒烟测试断言的是「import 到的是真实实现」而不是 safeUrl 的行为本身。"
```

---

## Task 3: `session` 改造为注入 storage

**Files:**
- Create: `widget/src/core/platform.ts`
- Move + Modify: `widget/src/session.ts` → `widget/src/core/session.ts`
- Move + Modify: `widget/src/session.test.ts` → `widget/src/core/session.test.ts`
- Modify: `widget/src/main.ts`（3 处调用点 + 提供 localStorage 实现）

**Interfaces:**
- Produces:
  - `interface WidgetStorage { get(key: string): string | null; set(key: string, value: string): void; remove(key: string): void }`
  - `loadSession(storage: WidgetStorage, appKey: string): string | null`
  - `saveSession(storage: WidgetStorage, appKey: string, sessionId: string): void`
  - `clearSession(storage: WidgetStorage, appKey: string): void`
  - `SESSION_TTL_MS`（不变）
- Task 7 的 CLI 文件存储实现 `WidgetStorage`。

- [ ] **Step 1: 定义平台接口**

`widget/src/core/platform.ts`：

```ts
/**
 * 平台差异的注入点。core 不直接碰任何平台 API，由各端调用方提供实现。
 *
 * ⚠️ storage 只收发**字符串**：序列化由 core 内部负责，这样各端实现不必关心存的是什么结构，
 * 也避免「某端存对象、某端存字符串」的分叉。
 *
 * @author ruan
 */
export interface WidgetStorage {
  get(key: string): string | null;
  set(key: string, value: string): void;
  remove(key: string): void;
}
```

⚠️ **本期只定义 `WidgetStorage`，不定义 spec §3.3 里那个 `WidgetActions` / `Platform`。**

写计划时核对出来的：CLI **不渲染卡片按钮**，它只打印卡片的原始 `ui_spec` JSON（见 Task 7），
所以「动作执行」在本期**零消费者**——widget 是唯一的执行方，它直接调 `window.open` /
`navigator.clipboard` 即可（见 Task 4）。凭空造一个只有一个实现的接口，正是本仓库反复避免的
过度设计；等真有第二个执行方（小程序端）时再抽，那时才有东西验证它抽得对不对。

`Platform` 这个聚合接口同理不建——它现在只会包着唯一一个字段。Task 9 会把 spec §3.3 同步勘误。

- [ ] **Step 2: 写失败测试**

`widget/src/core/session.test.ts`（**首行是环境注释**）：

```ts
// @vitest-environment node
import { beforeEach, describe, expect, it } from 'vitest';
import type { WidgetStorage } from './platform';
import { SESSION_TTL_MS, clearSession, loadSession, saveSession } from './session';

/**
 * 会话持久化。改造重点：从直接调 localStorage 变成接受注入的 storage。
 *
 * ⚠️ 本文件跑在 node 环境下（首行注释），所以 localStorage 根本不存在——
 * 若实现里还残留对它的引用，这里会直接炸，这正是我们要的。
 */

/** 内存实现，兼作「storage 抛异常」场景的注入点。 */
class MemoryStorage implements WidgetStorage {
  private data = new Map<string, string>();
  throwOnGet = false;
  throwOnSet = false;

  get(key: string): string | null {
    if (this.throwOnGet) throw new Error('storage 不可用');
    return this.data.get(key) ?? null;
  }
  set(key: string, value: string): void {
    if (this.throwOnSet) throw new Error('storage 不可用');
    this.data.set(key, value);
  }
  remove(key: string): void {
    this.data.delete(key);
  }
}

let storage: MemoryStorage;

beforeEach(() => {
  storage = new MemoryStorage();
});

describe('会话持久化', () => {
  it('存进去能读回来', () => {
    saveSession(storage, 'app1', 'sess-1');
    expect(loadSession(storage, 'app1')).toBe('sess-1');
  });

  it('不同 appKey 互不干扰', () => {
    saveSession(storage, 'app1', 'sess-1');
    saveSession(storage, 'app2', 'sess-2');
    expect(loadSession(storage, 'app1')).toBe('sess-1');
    expect(loadSession(storage, 'app2')).toBe('sess-2');
  });

  it('没存过时返回 null', () => {
    expect(loadSession(storage, 'app1')).toBeNull();
  });

  it('超过 TTL 的会话被判为失效并清理', () => {
    const stale = JSON.stringify({ sessionId: 'old', createdAt: Date.now() - SESSION_TTL_MS - 1 });
    storage.set('qingyu_widget_session_app1', stale);

    expect(loadSession(storage, 'app1')).toBeNull();
    expect(storage.get('qingyu_widget_session_app1')).toBeNull();
  });

  it('数据损坏时降级为无会话而不是抛错', () => {
    storage.set('qingyu_widget_session_app1', '这不是 JSON');
    expect(loadSession(storage, 'app1')).toBeNull();
  });

  it('结构不完整时降级为无会话', () => {
    storage.set('qingyu_widget_session_app1', JSON.stringify({ sessionId: 123 }));
    expect(loadSession(storage, 'app1')).toBeNull();
  });

  /**
   * ⚠️ 这条守的是一条有来历的降级：localStorage 在隐私模式下会被禁用、读写直接抛异常。
   * 原实现对此降级为「无会话」而不是让整个 widget 崩掉。搬家时最容易顺手"简化"掉。
   */
  it('storage 读抛异常时降级为无会话', () => {
    storage.throwOnGet = true;
    expect(loadSession(storage, 'app1')).toBeNull();
  });

  it('storage 写抛异常时不影响会话继续', () => {
    storage.throwOnSet = true;
    expect(() => saveSession(storage, 'app1', 'sess-1')).not.toThrow();
  });

  it('清理后读不到', () => {
    saveSession(storage, 'app1', 'sess-1');
    clearSession(storage, 'app1');
    expect(loadSession(storage, 'app1')).toBeNull();
  });
});
```

- [ ] **Step 3: 跑测试确认失败**

```bash
cd widget && pnpm test src/core/session.test.ts
```
预期：失败（`./session` 不存在或签名不符）。

- [ ] **Step 4: 实现**

`widget/src/core/session.ts`：

```ts
import type { WidgetStorage } from './platform';

export const SESSION_TTL_MS = 24 * 60 * 60 * 1000; // 24h

const keyOf = (appKey: string) => `qingyu_widget_session_${appKey}`;

interface Stored {
  sessionId: string;
  createdAt: number;
}

/**
 * 读取未过期的 sessionId；过期/损坏/storage 不可用时返回 null 并清理。
 *
 * ⚠️ catch 里的降级有来历：浏览器隐私模式下 localStorage 读写会直接抛异常，
 * 那时应降级为「无会话」让对话照常开始，而不是让整个 widget 崩掉。
 */
export function loadSession(storage: WidgetStorage, appKey: string): string | null {
  try {
    const raw = storage.get(keyOf(appKey));
    if (!raw) return null;
    const data = JSON.parse(raw) as Partial<Stored>;
    if (
      !data ||
      typeof data.sessionId !== 'string' ||
      typeof data.createdAt !== 'number' ||
      Date.now() - data.createdAt > SESSION_TTL_MS
    ) {
      clearSession(storage, appKey);
      return null;
    }
    return data.sessionId;
  } catch {
    return null;
  }
}

export function saveSession(storage: WidgetStorage, appKey: string, sessionId: string): void {
  try {
    const data: Stored = { sessionId, createdAt: Date.now() };
    storage.set(keyOf(appKey), JSON.stringify(data));
  } catch {
    // 忽略写失败，不影响会话进行
  }
}

export function clearSession(storage: WidgetStorage, appKey: string): void {
  try {
    storage.remove(keyOf(appKey));
  } catch {
    // 忽略
  }
}
```

删除原 `widget/src/session.ts` 与 `widget/src/session.test.ts`（内容已迁移）。

- [ ] **Step 5: widget 侧提供 localStorage 实现并改调用点**

在 `widget/src/main.ts` 顶部附近加（放在 import 之后、逻辑之前）：

```ts
import type { WidgetStorage } from './core/platform';

/** widget 侧的 storage 实现。异常由 core 的 try/catch 兜住（隐私模式下会抛）。 */
const browserStorage: WidgetStorage = {
  get: (k) => localStorage.getItem(k),
  set: (k, v) => localStorage.setItem(k, v),
  remove: (k) => localStorage.removeItem(k),
};
```

把 import 改成 `from './core/session'`，三处调用点改为：

- `loadSession(cfg.appKey)` → `loadSession(browserStorage, cfg.appKey)`
- `saveSession(cfg!.appKey, d.sessionId)` → `saveSession(browserStorage, cfg!.appKey, d.sessionId)`
- `clearSession(cfg!.appKey)` → `clearSession(browserStorage, cfg!.appKey)`

- [ ] **Step 6: 跑全部 widget 测试**

```bash
cd widget && pnpm test
```
预期：全绿（含既有的 `main.test.ts`）。

- [ ] **Step 7: 变异验证（本仓库规矩：绿证明不了守护有效）**

把 `loadSession` 的 `catch { return null; }` 临时改成 `catch (e) { throw e; }`，跑
`pnpm test src/core/session.test.ts`，**确认「storage 读抛异常时降级为无会话」转红**，恢复后再跑一次确认全绿。把实际输出记进 commit message。

- [ ] **Step 8: 重建产物并提交**

```bash
cd widget && pnpm build
cd .. && git add widget/src backend/src/main/resources/static/widget.js
git commit -m "refactor(widget): session 改为注入 storage 并移入 core

从直接调 localStorage 改成接受注入的 WidgetStorage，widget 侧提供 localStorage 实现。
storage 接口只收发字符串，序列化留在 core 内部——避免各端出现「某端存对象、某端存字符串」
的分叉。

测试跑在 node 环境（localStorage 根本不存在），任何残留引用都会直接炸。

⚠️ 保留了一条有来历的降级：隐私模式下 localStorage 读写抛异常时降级为无会话，而不是让
widget 崩掉。已用变异验证钉住（把 catch 改成 rethrow，对应测试转红）。"
```

---

## Task 4: `actionUri` 拆分 + 后端测试路径同步

**Files:**
- Create: `widget/src/core/actionUri.ts`（解析与白名单）
- Create: `widget/src/core/actionUri.test.ts`
- Modify: `widget/src/actionUri.ts`（收缩为执行层）
- Modify: `widget/src/actionUri.test.ts`（只留执行层的测试）
- Modify: `widget/src/render.ts`（调用点）
- Modify: `backend/src/test/java/com/qingyu/care/service/impl/CardActionProtocolSyncTest.java`（路径常量）
- Modify: `backend/src/test/java/com/qingyu/care/service/impl/ChatCardWiringTest.java`（注释里的路径）

**Interfaces:**
- Consumes: `widget/src/core/safeUrl.ts`（Task 1）
- Produces:
  - core：`parseActionUri(raw: unknown): ParsedActionUri`、`resolveActionUri(raw: unknown): ResolvedAction`
  - `type ResolvedAction = { kind: 'none' } | { kind: 'openUrl'; url: string } | { kind: 'message'; text: string } | { kind: 'copy'; text: string } | { kind: 'blocked' }`
  - widget：`executeActionUri(raw, handlers): boolean`（签名不变）

⚠️ **本任务最容易弄丢的东西**：CLAUDE.md 为 `url://` 的 **fail-closed** 语义专门立过一节——
被 `safeUrl` 白名单拒绝时**不回退成发消息**，点击无反应是**正确行为**；`executeActionUri` 的
返回值表示「是否已作为动作处理」而非「是否成功」。拆分后这条要跨两个模块成立，所以 core 的
解析结果必须**显式区分**「协议无效」（应回退）与「协议有效但被安全策略拦下」（不回退），
而不是都返回失败让执行方去猜。上面 `ResolvedAction` 里的 `blocked` 就是为此存在的。

- [ ] **Step 1: 写 core 的失败测试**

`widget/src/core/actionUri.test.ts`：

```ts
// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { parseActionUri, resolveActionUri } from './actionUri';

/**
 * Action URI 的解析与白名单。执行（开链接/写剪贴板）留在平台层。
 *
 * ⚠️ 核心不变量：`blocked` 与 `none` 必须是两种不同结果。
 * - none    = 协议无效 → 调用方**应当**回退成发送原始 value
 * - blocked = 协议有效但内容被安全策略拦下 → 调用方**不得**回退（fail-closed）
 * 把它们混成一种，就等于把 CLAUDE.md 那条 fail-closed 语义弄丢了。
 */

describe('parseActionUri', () => {
  it('识别三个白名单协议', () => {
    expect(parseActionUri('url://https://a.com').isValid).toBe(true);
    expect(parseActionUri('msg://你好').isValid).toBe(true);
    expect(parseActionUri('copy://ABC').isValid).toBe(true);
  });

  it('白名单外的协议不算有效', () => {
    expect(parseActionUri('javascript://alert(1)').isValid).toBe(false);
    expect(parseActionUri('file:///etc/passwd').isValid).toBe(false);
  });

  it('非字符串与格式不符一律无效', () => {
    expect(parseActionUri(null).isValid).toBe(false);
    expect(parseActionUri(123).isValid).toBe(false);
    expect(parseActionUri('').isValid).toBe(false);
    expect(parseActionUri('没有协议头').isValid).toBe(false);
  });

  it('内容做 URL 解码，解码失败时用原文而不抛', () => {
    expect(parseActionUri('msg://%E4%BD%A0%E5%A5%BD').content).toBe('你好');
    expect(parseActionUri('msg://100%').content).toBe('100%');
  });

  it('协议名大小写不敏感', () => {
    expect(parseActionUri('URL://https://a.com').protocol).toBe('url');
  });
});

describe('resolveActionUri', () => {
  it('合法 http/https 链接解析成 openUrl', () => {
    expect(resolveActionUri('url://https://a.com')).toEqual({ kind: 'openUrl', url: 'https://a.com' });
  });

  /** fail-closed：协议有效但 URL 被白名单拒绝 → blocked，调用方不回退 */
  it('非 http/https 的链接被拦下且不是 none', () => {
    const r = resolveActionUri('url://javascript:alert(1)');
    expect(r.kind).toBe('blocked');
    expect(r.kind).not.toBe('none');
  });

  it('msg 解析成 message', () => {
    expect(resolveActionUri('msg://你好')).toEqual({ kind: 'message', text: '你好' });
  });

  it('copy 解析成 copy', () => {
    expect(resolveActionUri('copy://ABC')).toEqual({ kind: 'copy', text: 'ABC' });
  });

  it('协议无效时是 none（调用方应回退）', () => {
    expect(resolveActionUri('javascript://x').kind).toBe('none');
    expect(resolveActionUri(null).kind).toBe('none');
  });

  /** 协议有效但内容为空：无动作可做，但也**不该**回退成发消息 */
  it('内容为空时是 blocked 而不是 none', () => {
    expect(resolveActionUri('msg://').kind).toBe('blocked');
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd widget && pnpm test src/core/actionUri.test.ts
```
预期：失败（模块不存在）。

- [ ] **Step 3: 实现 core 的解析层**

`widget/src/core/actionUri.ts`：

```ts
// 卡片按钮的 Action URI 协议：{protocol}://{content}
// 协议见 docs/superpowers/specs/2026-08-04-card-action-uri-design.md §3

import { safeUrl } from './safeUrl';

export interface ParsedActionUri {
  protocol: string;
  content: string;
  isValid: boolean;
}

/**
 * 白名单：只有这三个协议才当动作处理，其余一律回退为纯文本发消息。
 *
 * ⚠️ 这份白名单与 `ChatServiceImpl.CARD_PROMPT`、`frontend/src/lib/actionUri.ts` 三处必须
 * 逐字相等，由后端的 CardActionProtocolSyncTest 强制。改这里必须同步另两处。
 */
const VALID_PROTOCOLS = ['url', 'msg', 'copy'];
const ACTION_URI_REGEX = /^([a-z]+):\/\/(.*)$/i;

export function parseActionUri(raw: unknown): ParsedActionUri {
  if (typeof raw !== 'string' || !raw) {
    return { protocol: '', content: '', isValid: false };
  }
  const m = raw.match(ACTION_URI_REGEX);
  if (!m) return { protocol: '', content: '', isValid: false };

  const protocol = m[1].toLowerCase();
  let content = m[2] ?? '';
  try {
    content = decodeURIComponent(content);
  } catch {
    // LLM 可能吐未编码的 % 字符，解码失败时用原文，不抛
  }
  return { protocol, content, isValid: VALID_PROTOCOLS.includes(protocol) };
}

/**
 * 解析结果的平台无关表示。
 *
 * ⚠️ `none` 与 `blocked` 是两种**不同**的结果，不能合并：
 * - `none`    协议无效 → 调用方**应当**回退成发送原始 value
 * - `blocked` 协议有效但内容被安全策略拦下（或为空）→ 调用方**不得**回退
 *
 * 这是 CLAUDE.md 点名过的 fail-closed 语义：被 safeUrl 拒绝的按钮点下去没反应是**正确**的，
 * 改去做别的事（比如发一条奇怪的消息）才是错的。
 */
export type ResolvedAction =
  | { kind: 'none' }
  | { kind: 'blocked' }
  | { kind: 'openUrl'; url: string }
  | { kind: 'message'; text: string }
  | { kind: 'copy'; text: string };

export function resolveActionUri(raw: unknown): ResolvedAction {
  const parsed = parseActionUri(raw);
  if (!parsed.isValid) return { kind: 'none' };
  if (!parsed.content) return { kind: 'blocked' };

  switch (parsed.protocol) {
    case 'url': {
      const url = safeUrl(parsed.content);
      return url ? { kind: 'openUrl', url } : { kind: 'blocked' };
    }
    case 'msg':
      return { kind: 'message', text: parsed.content };
    case 'copy':
      return { kind: 'copy', text: parsed.content };
    default:
      return { kind: 'none' };
  }
}
```

⚠️ `safeUrl` 被拒时的返回值以其实现为准（`null` 或 `''`），上面的三元判断对两者都成立。

- [ ] **Step 4: 收缩 widget 侧为执行层**

`widget/src/actionUri.ts` 整体替换为：

```ts
/**
 * Action URI 的**执行**层（解析在 core）。浏览器特有的部分只剩两件事：
 * 开新窗口与写剪贴板。
 */
import { resolveActionUri } from './core/actionUri';

export type { ParsedActionUri } from './core/actionUri';
export { parseActionUri } from './core/actionUri';

export interface ActionHandlers {
  /** msg 协议：把内容当用户消息发出 */
  onMessage: (text: string) => void;
  onCopied?: () => void;
  onCopyFailed?: () => void;
}

/**
 * 执行 Action URI。
 * @returns true=已作为动作处理（调用方**不要**回退 value）；false=协议无效（调用方回退 value）
 *
 * ⚠️ fail-closed 边界：协议有效但 URL 被 safeUrl 拒绝时（core 返回 blocked）仍返回 true——
 * 被安全策略拦下的按钮不该改去做别的事。
 */
export function executeActionUri(raw: unknown, handlers: ActionHandlers): boolean {
  const action = resolveActionUri(raw);
  switch (action.kind) {
    case 'none':
      return false;
    case 'blocked':
      return true;
    case 'openUrl':
      window.open(action.url, '_blank', 'noopener,noreferrer');
      return true;
    case 'message':
      handlers.onMessage(action.text);
      return true;
    case 'copy': {
      // navigator.clipboard 仅在安全上下文（HTTPS/localhost）可用；
      // widget 嵌在客户站点上，HTTP 站点下会缺失，此时直接回调失败
      if (!navigator.clipboard?.writeText) {
        handlers.onCopyFailed?.();
        return true;
      }
      void navigator.clipboard
        .writeText(action.text)
        .then(() => handlers.onCopied?.())
        .catch((e) => {
          console.warn('[Qingyu Widget] 复制失败', e);
          handlers.onCopyFailed?.();
        });
      return true;
    }
  }
}
```

`widget/src/render.ts` 的 import 不变（仍从 `./actionUri` 拿 `executeActionUri`）。

- [ ] **Step 5: 精简 widget 侧的既有测试**

`widget/src/actionUri.test.ts` 里**纯解析**的用例（协议识别、解码、大小写）已被 core 的测试覆盖，
删掉重复部分，**保留并强化执行层的**：`url://` 调用 `window.open`、`javascript:` 被拦下时
**既不 open 也不回退**（返回 true）、`copy://` 在 `navigator.clipboard` 缺失时走 `onCopyFailed`。
这些必须留在 jsdom 环境（不加 node 注释）。

- [ ] **Step 6: 同步后端硬编码的路径**

`CardActionProtocolSyncTest.java`：

```java
    private static final String WIDGET_ACTION_URI = "widget/src/core/actionUri.ts";
```

同文件 javadoc 里的 `{@code widget/src/actionUri.ts}` 一并改成 `widget/src/core/actionUri.ts`。
`ChatCardWiringTest.java` 第 161 行注释里的同款路径一并更新。

- [ ] **Step 7: 三端一起跑**

```bash
cd widget && pnpm test
cd ../backend && mvn test -Dtest=CardActionProtocolSyncTest
```

预期：都绿。⚠️ 后端那条**必须真跑**——它是防「三处白名单漂移」的哨兵，路径改错会让它找不到文件。
若它报「集合为空」，说明正则没抽到东西，检查路径与文件内容。

- [ ] **Step 8: 重建产物并提交**

```bash
cd widget && pnpm build
cd .. && git add widget/src backend/src backend/src/main/resources/static/widget.js
git commit -m "refactor(widget): actionUri 拆成 core 解析 + 平台执行

解析与白名单进 core，开链接/写剪贴板留在 widget。

⚠️ 拆分时最容易弄丢的是 fail-closed 语义：core 的 ResolvedAction 显式区分 none（协议无效，
调用方应回退）与 blocked（协议有效但被 safeUrl 拦下，调用方不得回退）。把两者合并就等于
把 CLAUDE.md 那条「被拦下的按钮点下去没反应是正确行为」弄丢了。

同步了后端 CardActionProtocolSyncTest 硬编码的 widget 文件路径——它是防三处白名单漂移的
哨兵，路径不改会找不到文件。"
```

---

## Task 5: `api` 拆分 + 双路径鉴权

**Files:**
- Create: `widget/src/core/api.ts`、`widget/src/core/api.test.ts`
- Modify: `widget/src/api.ts`（收缩为附件两方法 + 重导出 core）
- Modify: `widget/src/api.test.ts`（拆分对应部分）

**Interfaces:**
- Consumes: `widget/src/core/sse.ts`（Task 1）
- Produces: `createCoreApi(cfg: CoreApiConfig)`，其中
  `interface CoreApiConfig { apiBase: string; appKey: string; apiSecret?: string; origin?: string }`
  返回含 `getConfig` / `stream` / `handover` / `feedback` / `markRead` / `getSession` 的对象。
  Task 8 的 CLI 消费它。

- [ ] **Step 1: 写鉴权头的失败测试**

`widget/src/core/api.test.ts`：

```ts
// @vitest-environment node
import { afterEach, describe, expect, it, vi } from 'vitest';
import { createCoreApi } from './api';

/**
 * 只测**鉴权头构造**——这是本次抽取新增的能力（widget 只有浏览器一条路径，CLI 需要服务端路径）。
 * 各端点的行为由既有的 widget api 测试覆盖，此处不重复。
 *
 * ⚠️ 两条路径看到的知识库不一样（后端 OpenApiAuthInterceptor）：带 X-Api-Key 的服务端身份
 * 可见 INTERNAL 文档，仅带 X-App-Id 的浏览器身份只见 PUBLIC。头发错会让调试结论完全失真。
 */

afterEach(() => {
  vi.unstubAllGlobals();
});

function captureHeaders() {
  const calls: Array<{ url: string; headers: Record<string, string> }> = [];
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    calls.push({ url, headers: (init?.headers ?? {}) as Record<string, string> });
    return new Response(JSON.stringify({}), { status: 200, headers: { 'Content-Type': 'application/json' } });
  }));
  return calls;
}

describe('鉴权头构造', () => {
  it('给了 apiSecret 时发服务端身份的两个头', async () => {
    const calls = captureHeaders();
    await createCoreApi({ apiBase: 'https://x.com/api', appKey: 'k1', apiSecret: 's1' }).getConfig();

    expect(calls[0].headers['X-App-Id']).toBe('k1');
    expect(calls[0].headers['X-Api-Key']).toBe('s1');
  });

  it('没给 apiSecret 时只发 X-App-Id，不带空的 X-Api-Key', async () => {
    const calls = captureHeaders();
    await createCoreApi({ apiBase: 'https://x.com/api', appKey: 'k1' }).getConfig();

    expect(calls[0].headers['X-App-Id']).toBe('k1');
    expect('X-Api-Key' in calls[0].headers).toBe(false);
  });

  /** CLI 的 --as-visitor：显式设置 Origin 模拟浏览器那条鉴权路径 */
  it('给了 origin 时带上 Origin 头', async () => {
    const calls = captureHeaders();
    await createCoreApi({ apiBase: 'https://x.com/api', appKey: 'k1', origin: 'https://shop.com' }).getConfig();

    expect(calls[0].headers['Origin']).toBe('https://shop.com');
  });

  it('浏览器场景（无 origin 无 secret）不带 Origin——由浏览器自动附加', async () => {
    const calls = captureHeaders();
    await createCoreApi({ apiBase: 'https://x.com/api', appKey: 'k1' }).getConfig();

    expect('Origin' in calls[0].headers).toBe(false);
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd widget && pnpm test src/core/api.test.ts
```

- [ ] **Step 3: 实现**

把 `widget/src/api.ts` 里**除 `uploadAttachment` / `downloadAttachment` 之外**的全部内容移入
`widget/src/core/api.ts`，工厂函数改名为 `createCoreApi` 并接受 `CoreApiConfig`；把原来写死的

```ts
const headers = (): Record<string, string> => ({ ...,  'X-App-Id': cfg.appKey });
```

改成：

```ts
/**
 * 双路径鉴权（后端 OpenApiAuthInterceptor）：
 * - 服务端到服务端：X-App-Id + X-Api-Key，可见 INTERNAL 文档
 * - 浏览器 Widget：X-App-Id + Origin 白名单，匿名访客，仅见 PUBLIC 文档
 *
 * ⚠️ 不要在没有 apiSecret 时发一个空的 X-Api-Key——后端会当成服务端路径去校验密钥并 401。
 * ⚠️ 浏览器里不要手工设 Origin（受限请求头，浏览器自动附加）；只有 CLI 的 --as-visitor 才显式设。
 */
const headers = (): Record<string, string> => {
  const h: Record<string, string> = { 'Content-Type': 'application/json', 'X-App-Id': cfg.appKey };
  if (cfg.apiSecret) h['X-Api-Key'] = cfg.apiSecret;
  if (cfg.origin) h['Origin'] = cfg.origin;
  return h;
};
```

⚠️ 原 `headers()` 的其余字段以现有实现为准，逐字保留，不要顺手改动。

`widget/src/api.ts` 收缩为：保留 `uploadAttachment` / `downloadAttachment`（它们带 `File`/`Blob`
浏览器类型，见 spec §2.4），其余从 core 重导出，对 widget 内部调用方保持原有导入路径可用。

- [ ] **Step 4: 跑全部 widget 测试**

```bash
cd widget && pnpm test
```
预期：全绿。既有 `api.test.ts` 若因工厂改名而失败，改调用处而**不要**改断言的业务语义。

- [ ] **Step 5: 重建产物并提交**

```bash
cd widget && pnpm build
cd .. && git add widget/src backend/src/main/resources/static/widget.js
git commit -m "refactor(widget): api 协议部分进 core 并支持双路径鉴权

附件两方法（带 File/Blob 浏览器类型）留在 widget 层——附件的本质是「平台如何表示文件」，
各端差异太大，现在抽只会做出各端都不合用的接口。

新增服务端鉴权路径：有 apiSecret 就发 X-Api-Key，没有就不发（发空值会被后端当成服务端
路径去校验密钥并 401）；origin 仅供 CLI 的 --as-visitor 显式设置，浏览器里由浏览器自动附加。

⚠️ 两条路径可见的文档不同（INTERNAL vs 仅 PUBLIC），头发错会让调试结论完全失真。"
```

---

## Task 6: CLI 参数解析与文件存储

**Files:**
- Create: `cli/src/args.ts`、`cli/src/args.test.ts`
- Create: `cli/src/fileStorage.ts`、`cli/src/fileStorage.test.ts`

**Interfaces:**
- Consumes: `WidgetStorage`（Task 3）
- Produces:
  - `parseCliArgs(argv: string[]): CliOptions`，`interface CliOptions { apiBase: string; appKey: string; apiSecret?: string; origin?: string }`
  - `createFileStorage(filePath: string): WidgetStorage`
  - Task 7 的入口消费两者。

- [ ] **Step 1: 写参数解析的失败测试**

`cli/src/args.test.ts`：

```ts
// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { parseCliArgs } from './args';

/**
 * ⚠️ 两种身份的参数互斥必须**显式报错**而不是静默忽略其一：
 * 运维会以为自己在用某个视角、实际却是另一个，而这正是调试工具最该避免的误导
 * （两条鉴权路径看到的知识库不一样）。
 */
describe('parseCliArgs', () => {
  const base = ['--api-base', 'https://x.com/api', '--app-key', 'k1'];

  it('服务端身份：解析出 apiSecret', () => {
    const o = parseCliArgs([...base, '--api-secret', 's1']);
    expect(o).toEqual({ apiBase: 'https://x.com/api', appKey: 'k1', apiSecret: 's1' });
  });

  it('访客身份：解析出 origin 且不含 apiSecret', () => {
    const o = parseCliArgs([...base, '--as-visitor', '--origin', 'https://shop.com']);
    expect(o).toEqual({ apiBase: 'https://x.com/api', appKey: 'k1', origin: 'https://shop.com' });
  });

  it('同时给 --as-visitor 与 --api-secret 时报错', () => {
    expect(() => parseCliArgs([...base, '--as-visitor', '--origin', 'https://s.com', '--api-secret', 's1']))
      .toThrow(/不能同时/);
  });

  it('--as-visitor 缺 --origin 时报错', () => {
    expect(() => parseCliArgs([...base, '--as-visitor'])).toThrow(/origin/);
  });

  it('缺 --api-base 或 --app-key 时报错', () => {
    expect(() => parseCliArgs(['--app-key', 'k1'])).toThrow(/api-base/);
    expect(() => parseCliArgs(['--api-base', 'https://x.com/api'])).toThrow(/app-key/);
  });

  it('两种身份都没给时报错（不要静默退化成匿名）', () => {
    expect(() => parseCliArgs(base)).toThrow();
  });
});
```

- [ ] **Step 2: 跑测试确认失败，然后实现**

`cli/src/args.ts`：

```ts
import { parseArgs } from 'node:util';

export interface CliOptions {
  apiBase: string;
  appKey: string;
  apiSecret?: string;
  origin?: string;
}

/**
 * 解析命令行参数。
 *
 * ⚠️ 两种身份互斥且必须显式二选一：开放 API 的两条鉴权路径看到的知识库不一样
 * （服务端可见 INTERNAL、浏览器访客仅见 PUBLIC）。静默退化会让运维以为自己在用某个视角、
 * 实际却是另一个——那是这个调试工具最该避免的误导。
 *
 * @author ruan
 */
export function parseCliArgs(argv: string[]): CliOptions {
  const { values } = parseArgs({
    args: argv,
    options: {
      'api-base': { type: 'string' },
      'app-key': { type: 'string' },
      'api-secret': { type: 'string' },
      'as-visitor': { type: 'boolean', default: false },
      origin: { type: 'string' },
    },
    allowPositionals: false,
  });

  const apiBase = values['api-base'];
  const appKey = values['app-key'];
  if (!apiBase) throw new Error('缺少 --api-base');
  if (!appKey) throw new Error('缺少 --app-key');

  const asVisitor = values['as-visitor'] === true;
  const apiSecret = values['api-secret'];
  const origin = values.origin;

  if (asVisitor && apiSecret) {
    throw new Error('--as-visitor 与 --api-secret 不能同时使用：访客路径不校验密钥，同时给出会让你分不清实际用的是哪种身份');
  }
  if (asVisitor) {
    if (!origin) throw new Error('--as-visitor 需要 --origin（且必须是该应用 allowedOrigins 白名单里的域名，否则后端 401）');
    return { apiBase, appKey, origin };
  }
  if (!apiSecret) {
    throw new Error('需要 --api-secret（服务端身份），或用 --as-visitor --origin <白名单域名> 切到访客身份');
  }
  return { apiBase, appKey, apiSecret };
}
```

- [ ] **Step 3: 写文件存储的失败测试**

`cli/src/fileStorage.test.ts`：

```ts
// @vitest-environment node
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { mkdtempSync, rmSync, existsSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { createFileStorage } from './fileStorage';

let dir: string;
let file: string;

beforeEach(() => {
  dir = mkdtempSync(join(tmpdir(), 'qingyu-cli-'));
  file = join(dir, 'sessions.json');
});

afterEach(() => {
  rmSync(dir, { recursive: true, force: true });
});

describe('createFileStorage', () => {
  it('存进去能读回来', () => {
    const s = createFileStorage(file);
    s.set('k1', 'v1');
    expect(s.get('k1')).toBe('v1');
  });

  it('跨实例可读（真的落到了文件）', () => {
    createFileStorage(file).set('k1', 'v1');
    expect(createFileStorage(file).get('k1')).toBe('v1');
  });

  it('文件不存在时读返回 null 而不是抛', () => {
    expect(createFileStorage(join(dir, 'nope.json')).get('k1')).toBeNull();
  });

  it('文件内容损坏时降级为空而不是抛', () => {
    const s = createFileStorage(file);
    s.set('k1', 'v1');
    require('node:fs').writeFileSync(file, '这不是 JSON');
    expect(createFileStorage(file).get('k1')).toBeNull();
  });

  it('remove 后读不到，其它键不受影响', () => {
    const s = createFileStorage(file);
    s.set('k1', 'v1');
    s.set('k2', 'v2');
    s.remove('k1');
    expect(s.get('k1')).toBeNull();
    expect(s.get('k2')).toBe('v2');
  });

  it('目录不存在时会自动创建', () => {
    const nested = join(dir, 'a', 'b', 'sessions.json');
    createFileStorage(nested).set('k1', 'v1');
    expect(existsSync(nested)).toBe(true);
  });
});
```

- [ ] **Step 4: 实现文件存储**

`cli/src/fileStorage.ts`：

```ts
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname } from 'node:path';
import type { WidgetStorage } from '../../widget/src/core/platform';

/**
 * `WidgetStorage` 的文件实现（widget 侧对应的是 localStorage）。
 *
 * 整份文件是一个扁平的 key→value JSON，core 只收发字符串，序列化在 core 内部完成。
 * 读失败一律降级为「没有数据」而不是抛——与 widget 侧「隐私模式下 localStorage 抛异常时
 * 降级为无会话」保持同一姿态。
 *
 * @author ruan
 */
export function createFileStorage(filePath: string): WidgetStorage {
  const readAll = (): Record<string, string> => {
    try {
      return JSON.parse(readFileSync(filePath, 'utf8')) as Record<string, string>;
    } catch {
      return {};
    }
  };
  const writeAll = (data: Record<string, string>): void => {
    mkdirSync(dirname(filePath), { recursive: true });
    writeFileSync(filePath, JSON.stringify(data, null, 2), 'utf8');
  };

  return {
    get: (key) => readAll()[key] ?? null,
    set: (key, value) => {
      const data = readAll();
      data[key] = value;
      writeAll(data);
    },
    remove: (key) => {
      const data = readAll();
      delete data[key];
      writeAll(data);
    },
  };
}
```

- [ ] **Step 5: 跑测试并提交**

```bash
cd cli && pnpm test
git add cli/src
git commit -m "feat(cli): 参数解析与文件存储

两种身份的参数互斥显式报错而非静默忽略其一——两条鉴权路径看到的知识库不一样，静默退化会
让运维以为自己在用某个视角、实际却是另一个。

文件存储实现 WidgetStorage：读失败一律降级为「没有数据」，与 widget 侧「隐私模式下
localStorage 抛异常时降级为无会话」同一姿态。它同时是 storage 抽象的第二个真实消费者
——抽得对不对，到这里就见分晓。"
```

---

## Task 7: CLI 终端渲染

**Files:**
- Create: `cli/src/render.ts`、`cli/src/render.test.ts`

**Interfaces:**
- Produces: `formatHits(hits)`、`formatDone(meta)`、`formatCard(node)`——全部是纯函数（收数据、返回字符串），便于测试且不碰 stdout。

- [ ] **Step 1: 写失败测试**

`cli/src/render.test.ts`：

```ts
// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { formatCard, formatDone, formatHits } from './render';

describe('formatHits', () => {
  it('按分数顺序列出命中条数与分数', () => {
    const out = formatHits([
      { content: 'a', score: 0.7234, source: '退货政策.md' },
      { content: 'b', score: 0.6812, source: 'FAQ.md' },
    ]);
    expect(out).toContain('命中 2 条');
    expect(out).toContain('0.72');
    expect(out).toContain('0.68');
  });

  it('没有命中时明确说没有，而不是返回空字符串', () => {
    // 「没检索到」本身就是排查时最要紧的信号，不能让它看起来像没输出
    expect(formatHits([])).toContain('命中 0 条');
  });

  it('分数缺失时不显示 NaN', () => {
    const out = formatHits([{ content: 'a', score: undefined as unknown as number, source: 'x.md' }]);
    expect(out).not.toContain('NaN');
  });
});

describe('formatDone', () => {
  it('带出 messageId 与转人工建议', () => {
    const out = formatDone({ messageId: 123, suggestHandover: true });
    expect(out).toContain('123');
    expect(out).toContain('是');
  });
});

describe('formatCard', () => {
  it('原样打印 ui_spec 的 JSON（运维要的就是它长什么样）', () => {
    const out = formatCard({ type: 'card', title: 'x' });
    expect(out).toContain('"type"');
    expect(out).toContain('card');
  });
});
```

- [ ] **Step 2: 实现**

`cli/src/render.ts`：

```ts
/**
 * 终端渲染。全部是纯函数（收数据、返回字符串），不碰 stdout——这样能直接断言输出内容。
 *
 * @author ruan
 */

export interface KnowledgeHit {
  content: string;
  score: number;
  source: string;
}

const fmtScore = (s: number): string => (typeof s === 'number' && Number.isFinite(s) ? s.toFixed(2) : '—');

export function formatHits(hits: KnowledgeHit[]): string {
  const scores = hits.map((h) => fmtScore(h.score)).join(' / ');
  return hits.length ? `⋯ 命中 ${hits.length} 条  ${scores}` : '⋯ 命中 0 条';
}

/** `/hits` 展开时用：逐条列出来源与正文。 */
export function formatHitDetails(hits: KnowledgeHit[]): string {
  if (!hits.length) return '（本轮没有检索命中）';
  return hits
    .map((h, i) => `[${i + 1}] ${fmtScore(h.score)}  ${h.source}\n${h.content}`)
    .join('\n\n');
}

export function formatDone(meta: { messageId?: number; suggestHandover?: boolean }): string {
  return `✓ messageId=${meta.messageId ?? '—'}  建议转人工=${meta.suggestHandover ? '是' : '否'}`;
}

export function formatCard(node: unknown): string {
  return `[卡片 ui_spec]\n${JSON.stringify(node, null, 2)}`;
}
```

- [ ] **Step 3: 跑测试并提交**

```bash
cd cli && pnpm test
git add cli/src/render.ts cli/src/render.test.ts
git commit -m "feat(cli): 终端渲染（纯函数）

命中 0 条也要明确打印——「没检索到」本身就是排查时最要紧的信号，不能让它看起来像没输出。
卡片直接打印原始 ui_spec：运维要的就是它到底长什么样，不是好看的渲染。"
```

---

## Task 8: CLI 交互循环与端到端串联

**Files:**
- Modify: `cli/src/index.ts`（替换 Task 2 的最小版本）

**Interfaces:**
- Consumes: `parseCliArgs`、`createFileStorage`（Task 6）、`formatHits`/`formatHitDetails`/`formatDone`/`formatCard`（Task 7）、`createCoreApi`（Task 5）、`loadSession`/`saveSession`/`clearSession`（Task 3）

- [ ] **Step 1: 实现入口**

`cli/src/index.ts`：

```ts
#!/usr/bin/env node
/**
 * 轻语 CLI 调试客户端。面向运维排查「这条回答为什么不对」。
 *
 * 它同时是 widget 协议层的第一个非浏览器消费者——core 里若还粘着浏览器假设，
 * 这里会第一个撞上。
 *
 * @author ruan
 */
import { createInterface } from 'node:readline/promises';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { parseCliArgs } from './args';
import { createFileStorage } from './fileStorage';
import { formatCard, formatDone, formatHitDetails, formatHits, type KnowledgeHit } from './render';
import { createCoreApi } from '../../widget/src/core/api';
import { clearSession, loadSession, saveSession } from '../../widget/src/core/session';

const SESSION_FILE = join(homedir(), '.qingyu-cli', 'sessions.json');

async function main(): Promise<void> {
  let opts;
  try {
    opts = parseCliArgs(process.argv.slice(2));
  } catch (e) {
    console.error(`参数错误：${(e as Error).message}`);
    process.exit(1);
  }

  const storage = createFileStorage(SESSION_FILE);
  // 按 apiBase + appKey 分键：同一台机器调试多个应用/环境时互不覆盖
  const sessionKey = `${opts.apiBase}|${opts.appKey}`;
  const api = createCoreApi(opts);

  let sessionId = loadSession(storage, sessionKey);
  const identity = opts.apiSecret ? '服务端（可见 INTERNAL 文档）' : `访客（Origin=${opts.origin}，仅见 PUBLIC 文档）`;
  console.log(`身份：${identity}`);
  console.log(sessionId ? `续上会话 ${sessionId}` : '新会话');
  console.log('命令：/hits 展开命中详情，/new 开新会话，/exit 退出\n');

  let lastHits: KnowledgeHit[] = [];
  const rl = createInterface({ input: process.stdin, output: process.stdout });

  for (;;) {
    const line = (await rl.question('> ')).trim();
    if (!line) continue;
    if (line === '/exit') break;
    if (line === '/hits') {
      console.log(formatHitDetails(lastHits) + '\n');
      continue;
    }
    if (line === '/new') {
      clearSession(storage, sessionKey);
      sessionId = null;
      console.log('已开新会话\n');
      continue;
    }

    try {
      let meta: { messageId?: number; suggestHandover?: boolean } = {};
      await api.stream(
        { sessionId: sessionId ?? undefined, message: line },
        {
          onStart: (s: { sessionId: string; suggestHandover?: boolean; knowledgeHits?: KnowledgeHit[] }) => {
            sessionId = s.sessionId;
            saveSession(storage, sessionKey, s.sessionId);
            lastHits = s.knowledgeHits ?? [];
            meta.suggestHandover = s.suggestHandover;
            console.log(formatHits(lastHits));
          },
          onDelta: (text: string) => process.stdout.write(text),
          onCard: (node: unknown) => console.log('\n' + formatCard(node)),
          onDone: (d: { messageId?: number }) => {
            meta.messageId = d.messageId;
            console.log('\n' + formatDone(meta) + '\n');
          },
          onError: (msg: string) => console.error(`\n[错误] ${msg}\n`),
        },
      );
    } catch (e) {
      console.error(`\n[请求失败] ${(e as Error).message}\n`);
    }
  }

  rl.close();
}

void main();
```

⚠️ **`api.stream` 的确切签名与回调名以 `widget/src/core/api.ts` 的实现为准**——先读它再写，
上面的 `onStart`/`onDelta`/`onCard`/`onDone`/`onError` 是按 widget 现有用法推断的。若不符，
以实现为准调整**这里**，不要为了迁就本文件去改 core。

- [ ] **Step 2: 编译并冒烟**

```bash
cd cli && pnpm build
node dist/index.js --api-base https://x.com/api --app-key k1
```
预期：因缺 `--api-secret` 报参数错误并退出码 1（此时**不需要**真实服务端）。

- [ ] **Step 3: 跑 CLI 全部测试并提交**

```bash
cd cli && pnpm test
git add cli/src/index.ts
git commit -m "feat(cli): 交互循环与端到端串联

按 apiBase+appKey 分键存会话（同机调试多环境时互不覆盖）。启动时显式打印当前身份与它能
看到的文档范围——两条鉴权路径可见的知识库不同，不打出来运维容易拿错视角的结论去归因。"
```

---

## Task 9: 文档收口与全量验证

**Files:**
- Modify: `CLAUDE.md`、`SaaS平台规划.md`、`scripts/manual-smoke-hints.sh`
- Create: `cli/README.md`

- [ ] **Step 1: CLAUDE.md 加一节**

在「Widget（widget/，pnpm — 独立子项目）」一节之后插入：

```markdown
### CLI 调试客户端（cli/，pnpm — 独立子项目）
```bash
cd cli && pnpm install && pnpm build
node dist/index.js --api-base http://localhost:8080/api --app-key <appKey> --api-secret <secret>
node dist/index.js --api-base ... --app-key <appKey> --as-visitor --origin https://已在白名单的域名
```
面向运维排查「这条回答为什么不对」，同时是 widget 协议层（`widget/src/core/`）的第一个非浏览器消费者。

⚠️ **两种身份看到的知识库不一样**：`--api-secret` 是服务端身份（可见 INTERNAL 文档），`--as-visitor` 是浏览器访客身份（仅见 PUBLIC）。拿错视角排查访客问题会得出「我这儿明明查得到」的误导结论，所以两者互斥且必须显式二选一，CLI 启动时会打印当前身份。

⚠️ **`widget/src/core/` 的测试必须跑在 node 环境**（每个测试文件首行 `// @vitest-environment node`）。widget 整体跑 jsdom，协议层里若还粘着 `document`/`localStorage` 是看不出来的——切成 node 才炸得出来。这是「协议层抽干净了」唯一一条机器可判定的证据，**别为了让测试过而把某个 core 测试改回 jsdom**。
```

同时在「改动卡片 Action URI 协议后必须同步三处」一节中，把 `widget/src/actionUri.ts` 的路径更新为 `widget/src/core/actionUri.ts`，并补一句：解析在 core、执行在 `widget/src/actionUri.ts`，`ResolvedAction` 的 `none` 与 `blocked` 是 fail-closed 语义的承载点，不能合并。

- [ ] **Step 2: 更新规划**

`SaaS平台规划.md` 的 5.3「多端 Widget SDK」条目下补一段：第一步（协议层抽取 + CLI 消费者）已完成，日期与 spec 路径；其余端仍未启动，并写明各自的外部账号前置。

- [ ] **Step 3: 更新提示脚本**

`scripts/manual-smoke-hints.sh` 加映射：`widget/src/core/`、`cli/` → 「改动 widget 协议层或 CLI 后必须手工验证」。

⚠️ 该脚本只存「路径 → 章节标题」映射、不复述内容，且**引用的章节标题在 CLAUDE.md 里找不到时会 exit 1**。所以要先在 CLAUDE.md 里建这一节（标题逐字一致）：

```markdown
### 改动 widget 协议层或 CLI 后必须手工验证
自动化测试覆盖不到的两条：
1. **同一问题用两种身份各问一遍**，确认访客视角命中的文档确实比服务端视角少。这一条同时验证三件事：鉴权抽象接对了、`--as-visitor` 的 Origin 真的走到了浏览器那条鉴权路径、文档可见性分级在开放 API 上确实生效。单测里 fetch 是桩的，验不到真实鉴权。
2. **widget 真机回归**：嵌一次 widget，确认会话恢复、卡片按钮的三类动作（打开链接/发消息/复制）仍然正常。协议层抽取动了 session 与 actionUri 两条运行时路径，而 `url://` 被 `safeUrl` 拒绝时「点击无反应」是正确行为——这条 fail-closed 语义只有真机点得出来。
```

改完跑一次验证：

```bash
./scripts/manual-smoke-hints.sh master...HEAD
```
预期：exit 0 且能看到新章节。

- [ ] **Step 3.5: 同步设计文档 §3.3 的勘误**

`docs/superpowers/specs/2026-08-24-widget-core-extraction-design.md` 的 §3.3 写了
`Platform { storage, actions }` 两个成员，但实施中核对出 **`actions` 在本期零消费者**：
CLI 只打印卡片的原始 `ui_spec`、不渲染按钮，所以动作执行方只有 widget 一个，直接调
`window.open` / `navigator.clipboard` 即可。

把 §3.3 改成只定义 `WidgetStorage`，并写明「动作执行本期不抽象，等小程序端成为第二个执行方
时再做——那时才有东西验证抽象对不对」。不要留着一个没人实现的接口在文档里，那会让下一个人
以为它已经存在。

`cli/README.md`：用途、两种身份的区别与选择、安装与构建、命令与斜杠命令、会话文件位置、以及「它 import 的是 `widget/src/core` 的源码，改了 core 要重新 `pnpm build`」这条。

- [ ] **Step 5: 全量验证**

```bash
cd widget && pnpm test && pnpm build
cd ../cli && pnpm test && pnpm build
cd ../backend && mvn test -Dtest=CardActionProtocolSyncTest
cd .. && ./scripts/manual-smoke-hints.sh master...HEAD
git status --short   # 确认 widget.js 已重建并纳入提交
```

- [ ] **Step 6: 提交**

```bash
git add CLAUDE.md SaaS平台规划.md scripts/manual-smoke-hints.sh cli/README.md
git commit -m "docs(widget): CLI 用法、core 的 node 环境约定与手工验证清单

把「core 的测试必须跑 node 环境」写成明令并说明理由——它是抽取是否干净唯一的机器判据，
后来人最可能的破坏方式就是「测试报错了，加个 jsdom 环境让它过」。

两条手工验证都是自动化覆盖不到的：两种身份的可见文档差异（单测里 fetch 是桩的）、
以及 url:// 被拦下时「点击无反应」这条 fail-closed 语义（只有真机点得出来）。"
```

- [ ] **Step 7: 真机手工验证（按上面那两条走）**

⚠️ 这一步不能跳。本仓库反复记载的教训是「真机验证抓缺陷的密度远高于评审」。
