# 卡片 Action URI 协议 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让卡片按钮除「发送合成消息」外，还能打开链接与复制到剪贴板——通过新增 `button.props.action` 承载 `{protocol}://{content}` 格式的 Action URI。

**Architecture:** 两端各新增一个 `actionUri` 模块（纯函数 `parseActionUri` + 执行器 `executeActionUri`），执行器返回布尔值表示「是否已作为动作处理」，未处理时调用方回退到原有的 `value` 发消息逻辑。`url` 协议复用现有 `safeUrl` 白名单，因此需先把 `safeUrl` 从 `render.ts` / `registry.tsx` 抽成独立模块以避免循环依赖。

**Tech Stack:** TypeScript / React 19 + shadcn Button / widget 纯 DOM + Shadow DOM / vitest（仅 widget）/ 后端仅改 Java 字符串常量

设计依据：`docs/superpowers/specs/2026-08-04-card-action-uri-design.md`

## Global Constraints

- 全部使用简体中文：代码注释、commit 信息；新建 Java 文件 javadoc 作者写 `@author ruan`。
- 包管理器一律 **pnpm**。
- **widget 有 vitest，`pnpm test` 必须全绿**（当前基线 59 个用例）；**前端仓库没有测试框架，不要为此引入**，前端验证靠 `pnpm build` + `pnpm lint`。
- 前端 lint 基线是 **69 errors / 0 warnings**（`@typescript-eslint/no-explicit-any` 在本仓库配置为 error）。不要去修既有 error，但**新代码不得新增任何一条**——需要时用 `unknown` + 按分支 cast。
- `widget/` 改动后必须 `pnpm build` 重新产出 `backend/src/main/resources/static/widget.js` 并提交（`.gitattributes` 强制 LF）。
- **XSS 三防原样延续**：全 `textContent` 无 `innerHTML`；`url` 必须过 `safeUrl`；畸形节点守卫返回 null 不崩。
- **fail-closed 边界（spec §3.2，必须按此实现）**：「协议有效但内容被 `safeUrl` 拒绝」**不回退 `value`**，点击无反应。回退只在**协议本身无效**（格式不匹配 / 不在白名单）时发生。`content` 为空时视为无动作、同样不回退。
- 在当前分支提交；**绝对不要 git push**。

---

### Task 1: widget —— 抽出 `safeUrl` + 新增 `actionUri` 模块

**Files:**
- Create: `widget/src/safeUrl.ts`
- Create: `widget/src/actionUri.ts`
- Create: `widget/src/actionUri.test.ts`
- Modify: `widget/src/render.ts`（删除本地 `safeUrl` 实现，改为从 `./safeUrl` 导入并 re-export）

**Interfaces:**
- Consumes: 无（首个任务）
- Produces:
  - `safeUrl(u: unknown): string`（从 `widget/src/safeUrl.ts` 导出，行为与原实现逐字一致）
  - `parseActionUri(raw: unknown): { protocol: string; content: string; isValid: boolean }`
  - `executeActionUri(raw: unknown, handlers: ActionHandlers): boolean` —— 返回 `true` 表示已作为动作处理（调用方**不要**回退 value），`false` 表示协议无效（调用方回退 value）
  - `interface ActionHandlers { onMessage: (text: string) => void; onCopied?: () => void; onCopyFailed?: () => void }`
  - Task 2、3 依赖这些签名。

**为什么先抽 `safeUrl`**：`actionUri.ts` 的 `url` 分支要调 `safeUrl`，而 `render.ts` 又要调 `actionUri.ts`。若 `safeUrl` 继续留在 `render.ts`，就成了 `render.ts → actionUri.ts → render.ts` 的循环依赖。`render.ts` 保留 re-export 是为了不破坏 `render.test.ts` 里既有的 `import { renderNode, safeUrl } from './render'`。

- [ ] **Step 1: 写失败的测试**

`widget/src/actionUri.test.ts`：

```ts
import { describe, it, expect, vi, afterEach } from 'vitest';
import { parseActionUri, executeActionUri } from './actionUri';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('parseActionUri', () => {
  it('识别三种白名单协议', () => {
    expect(parseActionUri('url://https://a.com')).toMatchObject({ protocol: 'url', content: 'https://a.com', isValid: true });
    expect(parseActionUri('msg://查询物流')).toMatchObject({ protocol: 'msg', content: '查询物流', isValid: true });
    expect(parseActionUri('copy://SF123')).toMatchObject({ protocol: 'copy', content: 'SF123', isValid: true });
  });

  it('协议名大小写归一', () => {
    expect(parseActionUri('URL://https://a.com')).toMatchObject({ protocol: 'url', isValid: true });
  });

  it('未知协议判无效', () => {
    expect(parseActionUri('tel://10086').isValid).toBe(false);
    // http:// 是常见文本前缀，绝不能被当成动作
    expect(parseActionUri('http://a.com').isValid).toBe(false);
  });

  it('格式不匹配判无效', () => {
    expect(parseActionUri('查询物流').isValid).toBe(false);
    expect(parseActionUri('').isValid).toBe(false);
    expect(parseActionUri(null).isValid).toBe(false);
    expect(parseActionUri(123).isValid).toBe(false);
  });

  it('content 走 decodeURIComponent', () => {
    expect(parseActionUri('msg://%E6%9F%A5%E8%AF%A2').content).toBe('查询');
  });

  it('解码失败时回退原文，不抛', () => {
    expect(parseActionUri('msg://100%').content).toBe('100%');
  });
});

describe('executeActionUri', () => {
  const handlers = () => ({ onMessage: vi.fn(), onCopied: vi.fn(), onCopyFailed: vi.fn() });

  it('msg 协议调 onMessage 并返回已处理', () => {
    const h = handlers();
    expect(executeActionUri('msg://查询物流', h)).toBe(true);
    expect(h.onMessage).toHaveBeenCalledWith('查询物流');
  });

  it('url 协议新标签打开且带 noopener,noreferrer', () => {
    const open = vi.fn();
    vi.stubGlobal('open', open);
    const h = handlers();

    expect(executeActionUri('url://https://a.com/x', h)).toBe(true);
    expect(open).toHaveBeenCalledWith('https://a.com/x', '_blank', 'noopener,noreferrer');
  });

  it('url 协议遇 javascript: 被 safeUrl 拒掉——不打开、也不回退', () => {
    const open = vi.fn();
    vi.stubGlobal('open', open);
    const h = handlers();

    // 返回 true 表示"已作为动作处理"，调用方不得回退去发 value
    expect(executeActionUri('url://javascript:alert(1)', h)).toBe(true);
    expect(open).not.toHaveBeenCalled();
    expect(h.onMessage).not.toHaveBeenCalled();
  });

  it('copy 协议写剪贴板并回调 onCopied', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    vi.stubGlobal('navigator', { clipboard: { writeText } });
    const h = handlers();

    expect(executeActionUri('copy://SF123', h)).toBe(true);
    expect(writeText).toHaveBeenCalledWith('SF123');
    await Promise.resolve();
    await Promise.resolve();
    expect(h.onCopied).toHaveBeenCalled();
  });

  it('copy 在无 clipboard 的非安全上下文回调 onCopyFailed', () => {
    vi.stubGlobal('navigator', {});
    const h = handlers();

    expect(executeActionUri('copy://SF123', h)).toBe(true);
    expect(h.onCopyFailed).toHaveBeenCalled();
  });

  it('协议无效返回 false，交由调用方回退', () => {
    const h = handlers();
    expect(executeActionUri('查询物流', h)).toBe(false);
    expect(executeActionUri(undefined, h)).toBe(false);
    expect(h.onMessage).not.toHaveBeenCalled();
  });

  it('协议有效但内容为空——不动作也不回退', () => {
    const h = handlers();
    expect(executeActionUri('msg://', h)).toBe(true);
    expect(h.onMessage).not.toHaveBeenCalled();
  });
});
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd D:\Code\m\qingyu-care\widget && pnpm test actionUri`
Expected: 失败，`./actionUri` 模块不存在。

- [ ] **Step 3: 抽出 `safeUrl`**

新建 `widget/src/safeUrl.ts`（内容从 `render.ts` 原样搬过来，一字不改）：

```ts
/** 仅允 http/https 且长度受限，其余返回空串（防 javascript:/data: 注入）。 */
export function safeUrl(u: unknown): string {
  if (typeof u !== 'string' || u.length > 2048) return '';
  try {
    const p = new URL(u);
    return p.protocol === 'http:' || p.protocol === 'https:' ? u : '';
  } catch {
    return '';
  }
}
```

`widget/src/render.ts`：删除本地的 `safeUrl` 函数定义，在文件顶部改为：

```ts
import { safeUrl } from './safeUrl';

// 保持既有导出路径不变（render.test.ts 从 './render' 导入 safeUrl）
export { safeUrl };
```

- [ ] **Step 4: 实现 `actionUri.ts`**

```ts
// widget/src/actionUri.ts
// 卡片按钮的 Action URI 协议：{protocol}://{content}
// 协议见 docs/superpowers/specs/2026-08-04-card-action-uri-design.md §3

import { safeUrl } from './safeUrl';

export interface ParsedActionUri {
  protocol: string;
  content: string;
  isValid: boolean;
}

/** 白名单：只有这三个协议才当动作处理，其余一律回退为纯文本发消息。 */
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
 * 注意 fail-closed 边界：协议有效但 URL 被 safeUrl 拒绝时仍返回 true——
 * 被安全策略拦下的按钮不该改去做别的事。
 */
export function executeActionUri(raw: unknown, handlers: ActionHandlers): boolean {
  const parsed = parseActionUri(raw);
  if (!parsed.isValid) return false;
  if (!parsed.content) return true; // 协议有效但内容为空：无动作，也不回退

  switch (parsed.protocol) {
    case 'url': {
      const url = safeUrl(parsed.content);
      if (url) window.open(url, '_blank', 'noopener,noreferrer');
      return true;
    }
    case 'msg':
      handlers.onMessage(parsed.content);
      return true;
    case 'copy': {
      // navigator.clipboard 仅在安全上下文（HTTPS/localhost）可用；
      // widget 嵌在客户站点上，HTTP 站点下会缺失，此时直接回调失败
      if (!navigator.clipboard?.writeText) {
        handlers.onCopyFailed?.();
        return true;
      }
      void navigator.clipboard
        .writeText(parsed.content)
        .then(() => handlers.onCopied?.())
        .catch((e) => {
          console.warn('[Qingyu Widget] 复制失败', e);
          handlers.onCopyFailed?.();
        });
      return true;
    }
    default:
      return false;
  }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `cd D:\Code\m\qingyu-care\widget && pnpm test`
Expected: 全绿（既有 59 + 本任务新增 13）。

- [ ] **Step 6: 提交**

```bash
git add widget/src/safeUrl.ts widget/src/actionUri.ts widget/src/actionUri.test.ts widget/src/render.ts
git commit -m "feat(widget): 卡片 Action URI 协议解析与执行器"
```

---

### Task 2: widget —— button 接线 + 按钮样式 + 重建产物

**Files:**
- Modify: `widget/src/render.ts`（`case 'button'` 分支）
- Modify: `widget/src/styles.ts`（新增两个按钮样式类）
- Modify: `widget/src/render.test.ts`（新增用例）
- Modify: `backend/src/main/resources/static/widget.js`（构建产物，重新生成）

**Interfaces:**
- Consumes: `executeActionUri(raw, handlers): boolean`、`ActionHandlers`（Task 1）
- Produces: 无新导出（`renderNode` 签名不变，仍是 `renderNode(node, onAction)`）

**关键**：`renderNode` 的 `onAction: (value: string) => void` 签名**保持不变**。`url`/`copy` 是客户端本地行为，在 `render.ts` 内部消化；只有 `msg` 与回退的 `value` 才调 `onAction`。这样上层（`ui.ts` / `main.ts`）零改动。

- [ ] **Step 1: 写失败的测试**

在 `widget/src/render.test.ts` 追加：

```ts
describe('button 的 Action URI', () => {
  it('action=msg:// 时把内容当消息发出，不发 value', () => {
    const onAction = vi.fn();
    const el = renderNode(
      { type: 'button', props: { label: '查物流', value: '旧文本', action: 'msg://新文本' } } as UiNode,
      onAction,
    )!;
    el.dispatchEvent(new MouseEvent('click'));
    expect(onAction).toHaveBeenCalledWith('新文本');
    expect(onAction).not.toHaveBeenCalledWith('旧文本');
  });

  it('action 无效时回退 value', () => {
    const onAction = vi.fn();
    const el = renderNode(
      { type: 'button', props: { label: '查物流', value: '旧文本', action: '没有协议前缀' } } as UiNode,
      onAction,
    )!;
    el.dispatchEvent(new MouseEvent('click'));
    expect(onAction).toHaveBeenCalledWith('旧文本');
  });

  it('无 action 时行为与改造前一致', () => {
    const onAction = vi.fn();
    const el = renderNode(
      { type: 'button', props: { label: '查物流', value: '查询订单' } } as UiNode,
      onAction,
    )!;
    el.dispatchEvent(new MouseEvent('click'));
    expect(onAction).toHaveBeenCalledWith('查询订单');
  });

  it('action=url:// 被 safeUrl 拒绝时不打开也不回退 value', () => {
    const open = vi.fn();
    vi.stubGlobal('open', open);
    const onAction = vi.fn();
    const el = renderNode(
      { type: 'button', props: { label: '详情', value: '回退文本', action: 'url://javascript:alert(1)' } } as UiNode,
      onAction,
    )!;
    el.dispatchEvent(new MouseEvent('click'));
    expect(open).not.toHaveBeenCalled();
    expect(onAction).not.toHaveBeenCalled();
    vi.unstubAllGlobals();
  });

  it('copy 成功后按钮文案短暂变「已复制」再恢复', async () => {
    vi.useFakeTimers();
    const writeText = vi.fn().mockResolvedValue(undefined);
    vi.stubGlobal('navigator', { clipboard: { writeText } });
    const el = renderNode(
      { type: 'button', props: { label: '复制单号', action: 'copy://SF123' } } as UiNode,
      () => {},
    )!;

    el.dispatchEvent(new MouseEvent('click'));
    await Promise.resolve();
    await Promise.resolve();
    expect(el.textContent).toBe('已复制');

    vi.advanceTimersByTime(2000);
    expect(el.textContent).toBe('复制单号');

    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('style 映射到 class，非法值落 default', () => {
    const noop = () => {};
    const primary = renderNode({ type: 'button', props: { label: 'A', style: 'primary' } } as UiNode, noop)!;
    const danger = renderNode({ type: 'button', props: { label: 'B', style: 'danger' } } as UiNode, noop)!;
    const weird = renderNode({ type: 'button', props: { label: 'C', style: '不存在' } } as UiNode, noop)!;

    expect(primary.className).toContain('qy-card-btn-primary');
    expect(danger.className).toContain('qy-card-btn-danger');
    expect(weird.className).toBe('qy-card-btn');
  });
});
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd D:\Code\m\qingyu-care\widget && pnpm test render`
Expected: 新增用例失败（action 未接线、无 style class）。

- [ ] **Step 3: 改 `render.ts` 的 button 分支**

在文件内新增两个辅助函数（放在 `renderNode` 之前）：

```ts
/** 按钮样式 → class 后缀；非法/缺失落到默认样式。 */
function btnStyleClass(style: unknown): string {
  if (style === 'primary') return ' qy-card-btn-primary';
  if (style === 'danger') return ' qy-card-btn-danger';
  return '';
}

/** 按钮文案短暂切换为提示语，2 秒后恢复（widget 没有 toast 系统）。 */
function flashLabel(btn: HTMLElement, temp: string, original: string): void {
  btn.textContent = temp;
  setTimeout(() => {
    btn.textContent = original;
  }, 2000);
}
```

把 `case 'button'` 分支整体替换为：

```ts
    case 'button': {
      const btn = el('button', 'qy-card-btn' + btnStyleClass(p.style));
      (btn as HTMLButtonElement).type = 'button';
      const label = str(p.label);
      btn.textContent = label;
      btn.addEventListener('click', () => {
        // action 优先；协议无效时回退 value（详见 actionUri.ts 的 fail-closed 说明）
        const handled = executeActionUri(p.action, {
          onMessage: onAction,
          onCopied: () => flashLabel(btn, '已复制', label),
          onCopyFailed: () => flashLabel(btn, '复制失败', label),
        });
        if (!handled) {
          const fallback = str(p.value ?? p.label);
          if (fallback) onAction(fallback);
        }
      });
      return btn;
    }
```

顶部补 import：`import { executeActionUri } from './actionUri';`

- [ ] **Step 4: 加按钮样式**

`widget/src/styles.ts`，在 `.qy-card-btn:hover` 那行之后插入：

```ts
    .qy-card-btn-primary { background:${themeColor}; color:#fff; }
    .qy-card-btn-danger { border-color:#dc2626; color:#dc2626; }
    .qy-card-btn-danger:hover { background:#dc2626; color:#fff; }
```

- [ ] **Step 5: 运行测试确认通过**

Run: `cd D:\Code\m\qingyu-care\widget && pnpm test`
Expected: 全绿（59 + Task 1 的 13 + 本任务 6 = 78）。

- [ ] **Step 6: 重建构建产物**

Run: `cd D:\Code\m\qingyu-care\widget && pnpm build`
Expected: `backend/src/main/resources/static/widget.js` 更新。提交前用 `file backend/src/main/resources/static/widget.js` 确认输出**不含** `CRLF line terminators`。

- [ ] **Step 7: 提交**

```bash
git add widget/src/render.ts widget/src/styles.ts widget/src/render.test.ts backend/src/main/resources/static/widget.js
git commit -m "feat(widget): 卡片按钮接入 Action URI 与样式变体"
```

---

### Task 3: 前端 —— 抽出 `safeUrl` + `actionUri` + `ActionButton`

**Files:**
- Create: `frontend/src/lib/safeUrl.ts`
- Create: `frontend/src/lib/actionUri.ts`
- Modify: `frontend/src/components/chat/registry.tsx`（删本地 `safeUrl`、改 `case 'button'`、新增 `ActionButton` 组件）

**Interfaces:**
- Consumes: 无（前端独立实现一份，与 widget 逻辑等价但各自维护——两端依赖不同：widget 无 React、前端要用 `useToast`）
- Produces: `ActionButton` 为 `registry.tsx` 内部组件，不对外导出；`safeUrl` 从 `@/lib/safeUrl` 导出，`registry.tsx` 保留 re-export（避免破坏既有导入路径）

> 前端仓库**没有测试框架**（既有现状，不为此引入）。本任务靠 `pnpm build` + `pnpm lint` + Task 5 的真机冒烟验证。逻辑与 widget 侧完全对应，widget 的 13 个 `actionUri` 单测已覆盖同一套判定规则。

- [ ] **Step 1: 抽出 `safeUrl`**

新建 `frontend/src/lib/safeUrl.ts`（从 `registry.tsx` 原样搬，一字不改）：

```ts
/** 仅允 http/https 且长度受限，其余返回空串（防 javascript:/data: 注入）。 */
export function safeUrl(u: unknown): string {
  if (typeof u !== 'string' || u.length > 2048) return '';
  try {
    const p = new URL(u);
    return (p.protocol === 'http:' || p.protocol === 'https:') ? u : '';
  } catch {
    return '';
  }
}
```

`registry.tsx`：删除本地 `safeUrl` 定义，顶部加：

```tsx
import { safeUrl } from '@/lib/safeUrl';

// 保持既有导出路径不变
export { safeUrl };
```

- [ ] **Step 2: 新建 `actionUri.ts`**

```ts
// frontend/src/lib/actionUri.ts
// 卡片按钮的 Action URI 协议：{protocol}://{content}
// 协议见 docs/superpowers/specs/2026-08-04-card-action-uri-design.md §3
// 与 widget/src/actionUri.ts 是同一套规则的两份实现（widget 不依赖 React，无法共享）

import { safeUrl } from './safeUrl';

export interface ParsedActionUri {
  protocol: string;
  content: string;
  isValid: boolean;
}

/** 白名单：只有这三个协议才当动作处理，其余一律回退为纯文本发消息。 */
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

export interface ActionHandlers {
  /** msg 协议：把内容当用户消息发出 */
  onMessage: (text: string) => void;
  onCopied?: () => void;
  onCopyFailed?: () => void;
}

/**
 * 执行 Action URI。
 * 返回 true=已作为动作处理（调用方不要回退 value）；false=协议无效（调用方回退 value）。
 * fail-closed：协议有效但 URL 被 safeUrl 拒绝时仍返回 true——被安全策略拦下的按钮不该改去做别的事。
 */
export function executeActionUri(raw: unknown, handlers: ActionHandlers): boolean {
  const parsed = parseActionUri(raw);
  if (!parsed.isValid) return false;
  if (!parsed.content) return true; // 协议有效但内容为空：无动作，也不回退

  switch (parsed.protocol) {
    case 'url': {
      const url = safeUrl(parsed.content);
      if (url) window.open(url, '_blank', 'noopener,noreferrer');
      return true;
    }
    case 'msg':
      handlers.onMessage(parsed.content);
      return true;
    case 'copy': {
      if (!navigator.clipboard?.writeText) {
        handlers.onCopyFailed?.();
        return true;
      }
      void navigator.clipboard
        .writeText(parsed.content)
        .then(() => handlers.onCopied?.())
        .catch(() => handlers.onCopyFailed?.());
      return true;
    }
    default:
      return false;
  }
}
```

- [ ] **Step 3: 新增 `ActionButton` 组件并接线**

`registry.tsx` 顶部补 import：

```tsx
import { executeActionUri } from '@/lib/actionUri';
import { useToast } from '@/hooks/use-toast';
```

在 `FormNode` 组件之后新增（`renderNode` 是普通函数、不能用 hook，所以按钮必须是组件）：

```tsx
/** 按钮样式 → shadcn variant；非法/缺失落到默认。 */
function btnVariant(style: unknown): 'outline' | 'default' | 'destructive' {
  if (style === 'primary') return 'default';
  if (style === 'danger') return 'destructive';
  return 'outline';
}

/**
 * 卡片按钮：props.action 走 Action URI（url/msg/copy），协议无效时回退 props.value 发消息。
 * 单独抽成组件是因为复制反馈要用 useToast，而 renderNode 是普通函数不能用 hook。
 */
function ActionButton({ node, onAction }: { node: UiNode; onAction: (v: string) => void }) {
  const { toast } = useToast();
  const p = node.props ?? {};
  const label = str(p.label);

  const handleClick = () => {
    const handled = executeActionUri(p.action, {
      onMessage: onAction,
      onCopied: () => toast({ title: '已复制' }),
      onCopyFailed: () =>
        toast({ variant: 'destructive', title: '复制失败', description: '当前页面不允许访问剪贴板' }),
    });
    if (!handled) {
      const fallback = str(p.value ?? p.label);
      if (fallback) onAction(fallback);
    }
  };

  return (
    <Button size="sm" variant={btnVariant(p.style)} className="mr-2 mt-1" onClick={handleClick}>
      {label}
    </Button>
  );
}
```

把 `renderNode` 里的 `case 'button'` 整体替换为：

```tsx
    case 'button':
      return <ActionButton key={key} node={node} onAction={onAction} />;
```

- [ ] **Step 4: 构建与 lint 校验**

Run: `cd D:\Code\m\qingyu-care\frontend && pnpm build && pnpm lint`
Expected: build 通过；lint 输出 **69 problems (69 errors, 0 warnings)**，与基线一致、零新增。（`pnpm lint` 因存在 error 会以非零码退出，这是既有基线状态，看计数而非退出码。）

- [ ] **Step 5: 提交**

```bash
git add frontend/src/lib/safeUrl.ts frontend/src/lib/actionUri.ts frontend/src/components/chat/registry.tsx
git commit -m "feat(frontend): 卡片按钮接入 Action URI 与样式变体"
```

---

### Task 4: 后端 —— CARD_PROMPT 教 LLM 用 action

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/service/impl/ChatServiceImpl.java`（`CARD_PROMPT` 常量）
- Modify: `backend/src/test/java/com/qingyu/care/service/impl/ChatCardWiringTest.java`（新增断言）

**Interfaces:**
- Consumes: 无
- Produces: 无新签名（仅改字符串常量）

- [ ] **Step 1: 写失败的测试**

在 `ChatCardWiringTest` 追加：

```java
    /**
     * CARD_PROMPT 必须教会 LLM 三种 Action URI 协议——否则模型不会产出 action 字段，
     * 两端刚接的按钮动作就成了摆设。协议白名单同时存在于
     * frontend/src/lib/actionUri.ts 与 widget/src/actionUri.ts（跨语言，无法自动比对）。
     */
    @Test
    void CARD_PROMPT_含三种ActionURI协议说明() {
        String prompt = ChatServiceImpl.appendCardPrompt("系统提示", true, true);

        assertThat(prompt).contains("url://").contains("msg://").contains("copy://");
        assertThat(prompt).contains("action");
        assertThat(prompt).contains("style");
    }
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd D:\Code\m\qingyu-care\backend && mvn test -Dtest=ChatCardWiringTest`
Expected: FAIL —— 当前 CARD_PROMPT 里没有 `url://` 等内容。

- [ ] **Step 3: 更新 CARD_PROMPT**

把 button 那一行（当前是 `button（按钮，props.label/props.value，点击会把 value 作为用户消息发出）`）替换为：

```
            button（按钮，props.label 必填；点击行为二选一——props.action 用 Action URI 执行动作：url://链接（新标签打开）、
            msg://文本（作为用户消息发出）、copy://文本（复制到剪贴板）；或 props.value 纯文本（点击后作为用户消息发出，等价于 msg://）。
            可选 props.style：primary（主要）/danger（危险），缺省为普通样式）、
```

并把示例里的 `actions` 那行替换为（三种动作各出现一次）：

```
              {"type":"actions","children":[
                {"type":"button","props":{"label":"查看物流","value":"查询订单10086物流"}},
                {"type":"button","props":{"label":"订单详情","action":"url://https://shop.example.com/orders/10086"}},
                {"type":"button","props":{"label":"复制单号","action":"copy://SF13142026","style":"primary"}}
              ]}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd D:\Code\m\qingyu-care\backend && mvn test -Dtest=ChatCardWiringTest`
Expected: PASS。

- [ ] **Step 5: 跑全量单测确认无回归**

Run: `cd D:\Code\m\qingyu-care\backend && mvn test`
Expected: 全绿（基线 435 + 本任务 1）。

- [ ] **Step 6: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/service/impl/ChatServiceImpl.java \
        backend/src/test/java/com/qingyu/care/service/impl/ChatCardWiringTest.java
git commit -m "feat(backend): CARD_PROMPT 教 LLM 使用 Action URI 与按钮样式"
```

---

### Task 5: 全量验证 + 真机冒烟 + 漂移检查项沉淀

**Files:**
- Modify: `CLAUDE.md`（在「改动流式对话链路后必须手工验证」小节后追加一条）

**Interfaces:**
- Consumes: 全部前序任务
- Produces: 无（验收任务）

- [ ] **Step 1: 三端全量验证**

```bash
cd D:\Code\m\qingyu-care\backend && mvn verify          # 需 Docker Desktop + 本地 redis 容器
cd D:\Code\m\qingyu-care\widget   && pnpm test          # 78 个（既有 59 + 新增 19）
cd D:\Code\m\qingyu-care\frontend && pnpm build && pnpm lint
```
Expected: 后端 BUILD SUCCESS；widget 全绿；前端 build 通过、lint 69 errors 与基线一致。`git status` 确认 `widget.js` 已提交、工作树干净。

- [ ] **Step 2: 起环境**

```bash
cd D:\Code\m\qingyu-care\docker && docker compose up -d
cd D:\Code\m\qingyu-care\backend && mvn spring-boot:run -Dspring-boot.run.profiles=dev   # 需 DASHSCOPE_API_KEY
cd D:\Code\m\qingyu-care\frontend && pnpm dev
```

- [ ] **Step 3: 真机冒烟（管理端）**

用租户账号登录（**不能用平台管理员**，`requireTenantContext` 会拒绝无租户上下文的对话）。让 LLM 生成含三类按钮的卡片，例如提问：「用卡片展示订单 10086，带三个按钮：查看物流、订单详情链接、复制运单号」。逐条验证：

1. **`value` 按钮**（查看物流）→ 发出合成消息，行为与改造前一致
2. **`url://` 按钮** → 新标签打开正确页面；浏览器拦弹窗时**不报错**
3. **`copy://` 按钮** → 剪贴板内容正确，且弹出「已复制」toast
4. **`style`** → primary/danger 按钮视觉可区分
5. **手工构造的恶意链接**：直接在数据库把某条消息的 `ui_spec` 改成含 `"action":"url://javascript:alert(1)"` 的按钮，刷新页面点击 → **不得弹窗、不得回退发消息**（fail-closed）

- [ ] **Step 4: 真机冒烟（widget）**

在页面注入 widget（`<script src="http://localhost:8080/api/widget.js?appKey=...">`，应用需配 `allowedOrigins`），重跑上面 1–5 项。widget 特有的两点：

6. **复制反馈**：按钮文案变「已复制」，2 秒后恢复原文案
7. **Shadow DOM 内**样式正确、`window.open` 被拦时不报错

- [ ] **Step 5: 沉淀跨语言漂移检查项**

`CLAUDE.md` 中「改动流式对话链路后必须手工验证」小节之后，追加：

```markdown
### 改动卡片 Action URI 协议后必须同步三处
协议白名单分散在三个语言里，**没有任何自动化测试能比对它们**：

1. `ChatServiceImpl.CARD_PROMPT`（Java 字符串，教 LLM 生成什么）
2. `frontend/src/lib/actionUri.ts` 的 `VALID_PROTOCOLS`
3. `widget/src/actionUri.ts` 的 `VALID_PROTOCOLS`

漏改任一处的后果：LLM 产出客户端不认的协议 → 按钮点了没反应（回退逻辑会让它退化成发一条奇怪的消息，或彻底静默）。
改协议后务必真机点一遍三类按钮。另注意 `url://` 的 fail-closed 语义：被 `safeUrl` 拒绝时**不回退** `value`，点击无反应是正确行为。
```

- [ ] **Step 6: 提交**

```bash
git add CLAUDE.md
git commit -m "docs: 沉淀卡片 Action URI 的跨语言协议同步检查项"
```

---

## 自查记录

- **spec 覆盖**：§3 协议 → Task 1；§3.2 回退与 fail-closed 边界 → Task 1 的 `executeActionUri` 返回值语义 + Task 1/2 对应用例；§3.3 分派 → Task 1；§4 安全 → Task 1（safeUrl + noopener）；§5 复制反馈 → Task 2（widget flashLabel）/ Task 3（前端 toast）；§6 按钮样式 → Task 2/3；§7 CARD_PROMPT → Task 4；§8 测试 → 各任务；§9 冒烟 → Task 5；§10 漂移风险 → Task 5 Step 5。
- **两端实现刻意重复**：`actionUri.ts` 在前端与 widget 各一份。widget 是独立子项目、不依赖 React 也不共享构建，无法抽公共包；这与既有的 `parseSseChunk`/`safeUrl` 两份实现的处理方式一致。代价已通过 Task 5 Step 5 的检查项显式登记。
- **类型一致性**：`parseActionUri` / `executeActionUri` / `ActionHandlers` / `btnStyleClass` / `btnVariant` / `flashLabel` 在两端命名与签名一致（`btnStyleClass` 为 widget 专有、`btnVariant` 为前端专有，因返回值分别是 CSS class 与 shadcn variant）。
