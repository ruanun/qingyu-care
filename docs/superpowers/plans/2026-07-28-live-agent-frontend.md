# 真人坐席 Plan 2（前端 + widget）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把已合入 master 的真人坐席后端接通到前端——坐席台 `AgentPage` 去 mock、实时化（消息/未读/typing/presence），widget 建 inbox 实时渲染坐席消息/状态/typing，并重建提交 `widget.js`。

**Architecture:** 两条 SSE 均用 **fetch + ReadableStream**（原生 EventSource 不能带鉴权 header）：前端共享 helper `lib/sse.ts` 带 `satoken`+`X-App-Id` header；widget 独立 `src/sse.ts` 带 `X-App-Id` header。事件 `message`/`session`/`typing`/`presence`（JSON payload）。**零后端改动**——后端 Plan 1 端点/契约已就绪。

**Tech Stack:** React 19 + TS + Vite + Zustand + shadcn(`components/ui`)（前端，pnpm，无 vitest：验证靠 `pnpm lint` + `pnpm build`/tsc）；widget 独立子项目（pnpm，vitest + jsdom，纯手写 DOM 零运行时依赖，IIFE 单文件产出 `widget.js`）。

## Global Constraints

- 全部简体中文：代码注释、commit、文档。作者署名 `ruan`。
- 本项目允许 `git commit`，仅在特性分支 `feat/live-agent-frontend` 上；未获授权**不 push**。
- **零后端改动**（用户选定 fetch+ReadableStream header 鉴权路线）。
- 前端：`pnpm`；HTTP 走 `api/client.ts` 单例 axios（请求拦截器从 `localStorage['qingyu-auth']` 注入 `satoken` header、从 `localStorage['qingyu-current-app']` 注入 `X-App-Id`；响应拦截器已拆成 `ApiResponse`，调用处直接拿 `response.code/response.data`）；别名 `@/`→`src/`；UI 用 `components/ui/*` + `cn()`。**SSE 不走 axios**，用 `lib/sse.ts` 的 fetch-stream。
- 后端契约（Plan 1 已就绪，勿改）：坐席端点在 `/api/v1/chat/**`（前端 axios baseURL `/api/v1`；SSE 用完整路径 `/api/v1/chat/agent/stream`，经 vite `/api`→:8080 代理）需登录 + 坐席角色（`TENANT_ADMIN`/`TENANT_MEMBER`）；`acceptSession`/`agentReply` **忽略入参 agentId**（取登录态），前端应去掉传参；`returnToAI`=`POST /v1/chat/session/{id}/return-to-ai`；`getOnlineAgents`=`GET /v1/chat/agents/online`→`Long[]`；`getSessionMessages`=`GET /v1/chat/session/{id}/messages`（前端 `chatService.getSessionMessages` 已存在）。用户 inbox=`GET /open/v1/sessions/{id}/inbox`（X-App-Id header）；用户 typing=`POST /open/v1/sessions/{id}/typing`；断线补拉=`GET /open/v1/sessions/{id}`（`OpenSessionResponse` 含消息历史）。SSE 事件名 `message`/`session`/`typing`/`presence`。
- **widget.js 是提交进仓库的构建产物**：改 `widget/src/` 后必须 `pnpm -C widget build` 重生成 `backend/src/main/resources/static/widget.js` 并提交；`.gitattributes` 强制其 LF；CI 校验 `pnpm -C widget build && git diff --exit-code`。
- 坐席台需补 **role 门禁**（现 `/agent` 路由与菜单均无 `roles`，后端 `requireAgent` 会 403 非坐席）。

---

## 文件结构

**前端（`frontend/`）**
- 新增 `src/lib/sse.ts`：fetch-stream SSE 客户端（`connectSse` + 纯函数 `parseSseChunk`）+ 鉴权头读取。
- 改 `src/api/agent.ts`：`acceptSession`/`agentReply` 去 agentId；新增 `returnToAI`/`getOnlineAgents`。
- 改 `src/pages/AgentPage.tsx`：去 mock、真实消息、坐席 SSE、未读、returnToAI/结束、在线指示。
- 改 `src/layouts/MainLayout.tsx`（+ 可选 `src/App.tsx`）：客服中心 section 加 `roles:['TENANT_ADMIN','TENANT_MEMBER']`。

**widget（`widget/`，独立子项目）**
- 新增 `src/sse.ts`：widget 版 fetch-stream SSE 读取（X-App-Id header）+ 纯函数 `parseSseChunk` + vitest 测试。
- 改 `src/api.ts`：新增 `inboxUrl(sessionId)` 常量/拼接 + `sendTyping(sessionId)` + `getSession(sessionId)`（断线补拉）。
- 改 `src/main.ts`：拿到 sessionId 后 `ensureInbox`；会话重开时重连；卸载清理。
- 改 `src/ui.ts`：渲染坐席 message（bot 气泡标「客服」）、`session` 状态条、坐席 typing（复用 `setTyping`）；用户输入节流触发 typing 上报。
- 产物 `backend/src/main/resources/static/widget.js`：`pnpm -C widget build` 重生成并提交。

---

### Task 1: 前端 SSE 客户端 helper（`lib/sse.ts`）

**Files:**
- Create: `frontend/src/lib/sse.ts`

**Interfaces:**
- Produces:
  - `parseSseChunk(raw: string): { event: string; data: string | null }`（纯函数，`\n\n` 分隔的一个事件块 → 事件名默认 `message` + 拼接 data；无 data 返回 null）
  - `connectSse(url: string, handlers: { onEvent:(event:string,data:string)=>void; onOpen?:()=>void; onError?:(e:unknown)=>void }): { close: () => void }`

- [ ] **Step 1：写 `lib/sse.ts`**

```ts
// frontend/src/lib/sse.ts
// 用 fetch + ReadableStream 消费 SSE：原生 EventSource 不能带鉴权 header，故手写。
// 鉴权头与 api/client.ts 同源（satoken + X-App-Id），自动重连（指数退避，上限 15s）。

export interface SseHandlers {
  onEvent: (event: string, data: string) => void;
  onOpen?: () => void;
  onError?: (err: unknown) => void;
}

export interface SseConnection {
  close: () => void;
}

/** 从 localStorage 读鉴权头（与 api/client.ts 请求拦截器同源）。 */
function authHeaders(): Record<string, string> {
  const headers: Record<string, string> = { Accept: 'text/event-stream' };
  try {
    const auth = localStorage.getItem('qingyu-auth');
    const token = auth ? JSON.parse(auth)?.state?.token : null;
    if (token) headers['satoken'] = token;
    const app = localStorage.getItem('qingyu-current-app');
    const id = app ? JSON.parse(app)?.state?.currentApp?.id : null;
    if (id != null) headers['X-App-Id'] = String(id);
  } catch {
    // localStorage 损坏时静默降级为无鉴权头（后端会 401，交由上层处理）
  }
  return headers;
}

/**
 * 纯函数：解析一个 SSE 事件块（调用方已按 \n\n 切分）。
 * 返回事件名（默认 message）与拼接后的 data；只有注释/心跳（无 data 行）返回 data=null。
 */
export function parseSseChunk(raw: string): { event: string; data: string | null } {
  let event = 'message';
  const dataLines: string[] = [];
  for (const line of raw.split('\n')) {
    if (line === '' || line.startsWith(':')) continue; // 空行/注释(心跳)
    if (line.startsWith('event:')) event = line.slice(6).trim();
    else if (line.startsWith('data:')) dataLines.push(line.slice(5).replace(/^ /, ''));
  }
  return { event, data: dataLines.length ? dataLines.join('\n') : null };
}

/** fetch-stream 消费 SSE，返回可 close 的连接句柄。close 后中止请求并停止重连。 */
export function connectSse(url: string, handlers: SseHandlers): SseConnection {
  let closed = false;
  let controller: AbortController | null = null;
  let retry = 0;

  const loop = async () => {
    while (!closed) {
      controller = new AbortController();
      try {
        const resp = await fetch(url, { headers: authHeaders(), signal: controller.signal });
        if (!resp.ok || !resp.body) throw new Error(`SSE HTTP ${resp.status}`);
        retry = 0;
        handlers.onOpen?.();
        const reader = resp.body.getReader();
        const decoder = new TextDecoder();
        let buf = '';
        for (;;) {
          const { done, value } = await reader.read();
          if (done || closed) break;
          buf += decoder.decode(value, { stream: true });
          let idx: number;
          while ((idx = buf.indexOf('\n\n')) >= 0) {
            const chunk = buf.slice(0, idx);
            buf = buf.slice(idx + 2);
            const { event, data } = parseSseChunk(chunk);
            if (data !== null) handlers.onEvent(event, data);
          }
        }
      } catch (err) {
        if (closed) return;
        handlers.onError?.(err);
      }
      if (closed) return;
      retry += 1;
      const delay = Math.min(15000, 500 * 2 ** Math.min(retry, 5));
      await new Promise((r) => setTimeout(r, delay));
    }
  };
  void loop();

  return {
    close: () => {
      closed = true;
      controller?.abort();
    },
  };
}
```

- [ ] **Step 2：类型检查 + lint**

Run: `cd frontend && pnpm build && pnpm lint`
Expected: `tsc -b` 无类型错误、ESLint 无新增告警（本文件无 `any`、无未用变量）。

- [ ] **Step 3：提交**

```bash
cd frontend && git add src/lib/sse.ts
git commit -m "feat(前端): SSE 客户端 helper（fetch+ReadableStream，header 鉴权+重连）"
```

---

### Task 2: agent API 收口（`api/agent.ts`）

**Files:**
- Modify: `frontend/src/api/agent.ts`

**Interfaces:**
- Consumes: `apiClient`（`api/client.ts` 单例）。
- Produces（供 AgentPage）：
  - `agentService.getWaitingAgentSessions(page?, size?)`（不变）
  - `agentService.acceptSession(sessionId: string)`（**去 agentId**）
  - `agentService.agentReply(req: { sessionId: string; message: string })`（**去 agentId**）
  - `agentService.returnToAI(sessionId: string)`（新增）
  - `agentService.getOnlineAgents(): Promise<ApiResponse<number[]>>`（新增）

- [ ] **Step 1：改写 `api/agent.ts`**

先 Read 现有 `frontend/src/api/agent.ts` 确认 `apiClient` 引入方式与 `ApiResponse`/`ChatSession` 类型来源，然后改为（去掉 `AgentReplyRequest` 里的 `agentId`、`acceptSession` 的 `params.agentId`，新增两个方法）：

```ts
// 关键改动（保留原有 import 与 getWaitingAgentSessions）：

// AgentReplyRequest 去 agentId
export interface AgentReplyRequest {
  sessionId: string;
  message: string;
}

export const agentService = {
  getWaitingAgentSessions: (page = 0, size = 20) =>
    apiClient.get(`/chat/sessions/waiting`, { params: { page, size } }),

  // accept 去 agentId：后端从登录态取
  acceptSession: (sessionId: string) =>
    apiClient.post(`/chat/session/${sessionId}/accept`),

  // reply 去 agentId
  agentReply: (req: AgentReplyRequest) =>
    apiClient.post(`/chat/agent/reply`, req),

  // 坐席把会话交还 AI
  returnToAI: (sessionId: string) =>
    apiClient.post(`/chat/session/${sessionId}/return-to-ai`),

  // 本租户在线坐席 ID 列表
  getOnlineAgents: () => apiClient.get(`/chat/agents/online`),
};
```
> 注：`apiClient` 的响应拦截器已把 axios 外壳拆成 `ApiResponse`，故上面各方法返回 `Promise<ApiResponse<...>>`；调用处用 `res.code === 200` + `res.data`。若原文件用具体返回类型注解，按原风格补 `ApiResponse<ChatSession>` 等泛型（`acceptSession`→`ApiResponse<ChatSession>`、`returnToAI`→`ApiResponse<void>`、`getOnlineAgents`→`ApiResponse<number[]>`）。

- [ ] **Step 2：类型检查 + lint**

Run: `cd frontend && pnpm build && pnpm lint`
Expected: 通过（AgentPage 尚未改，若因 `acceptSession(sessionId, MOCK_AGENT_ID)` 旧调用报参数错，属预期——Task 3 会一并改；本步若 tsc 因此失败，可先只 `pnpm lint` 该文件，Task 3 后再整体 build。为避免中间态构建失败，**建议本 Task 与 Task 3 连续实现、在 Task 3 末尾统一 build**）。

- [ ] **Step 3：提交**

```bash
cd frontend && git add src/api/agent.ts
git commit -m "feat(前端): agent API 收口（accept/reply 去 agentId + returnToAI/getOnlineAgents）"
```

---

### Task 3: AgentPage 实时化（去 mock + 坐席 SSE + 未读 + 回归AI）

**Files:**
- Modify: `frontend/src/pages/AgentPage.tsx`
- Consumes: `agentService`（Task 2）、`connectSse`（Task 1）、`chatService.getSessionMessages`（已存在）。

**Interfaces:**
- Produces：无对外接口（页面组件）。

先 Read 现有 `frontend/src/pages/AgentPage.tsx`（约 225 行）与 `frontend/src/api/chat.ts`（确认 `getSessionMessages` 签名/返回）。以下为**要落地的改动**，逐项应用到现有结构（保留既有布局/toast/样式类）：

- [ ] **Step 1：删 mock + 接管拉真实消息 + accept 去 agentId**

- 删除 `const MOCK_AGENT_ID = 1;`。
- `handleAcceptSession`：`agentService.acceptSession(session.sessionId)`（去 agentId）；成功后**用真实消息替换假消息**：
```ts
  const handleAcceptSession = async (session: ChatSession) => {
    try {
      const res = await agentService.acceptSession(session.sessionId);
      if (res.code === 200) {
        setSelectedSession(res.data);
        const msgs = await chatService.getSessionMessages(session.sessionId);
        setMessages(msgs.code === 200 ? msgs.data : []);
        clearUnread(session.sessionId);      // 见 Step 3
        fetchWaitingSessions();
      }
    } catch {
      toast({ title: '接管失败', variant: 'destructive' });
    }
  };
```
（确认 `chatService.getSessionMessages` 的返回形状——若已是 `ApiResponse<ChatMessage[]>` 用 `.code/.data`；若直接返回数组则相应调整。以真实签名为准。）

- [ ] **Step 2：发送回复去 agentId + 用服务端 messageId**

`handleSendReply` 改 `agentService.agentReply({ sessionId, message })`（去 agentId）；optimistic 本地 append 仍保留，但用后端返回/后续 SSE 无关（坐席不订阅 session 流），本地 append 用临时 id 即可；发送失败回滚输入并 toast。

- [ ] **Step 3：坐席 SSE 连接 + 未读状态**

新增 state：`const [unread, setUnread] = useState<Record<string, number>>({});` 与在线坐席数 `const [onlineCount, setOnlineCount] = useState(0);`。加一个 `clearUnread(sessionId)` 置 0。

新增 `useEffect` 建立坐席 SSE（组件挂载即连，卸载 close；依赖当前选中会话用 ref 避免频繁重连）：
```ts
  const selectedRef = useRef<ChatSession | null>(null);
  useEffect(() => { selectedRef.current = selectedSession; }, [selectedSession]);

  useEffect(() => {
    const conn = connectSse('/api/v1/chat/agent/stream', {
      onEvent: (event, data) => {
        let payload: any;
        try { payload = JSON.parse(data); } catch { return; }
        if (event === 'message') {
          const sid = payload.sessionId;
          if (selectedRef.current?.sessionId === sid) {
            setMessages((prev) => [...prev, {
              id: payload.messageId ?? Date.now(),
              role: payload.role, content: payload.content, createdAt: payload.createdAt,
            } as ChatMessage]);
          } else if (sid) {
            setUnread((prev) => ({ ...prev, [sid]: (prev[sid] ?? 0) + 1 }));
          }
        } else if (event === 'session') {
          // 新待接/结束等 → 刷新待接队列
          fetchWaitingSessions();
        } else if (event === 'presence') {
          agentService.getOnlineAgents().then((r) => {
            if (r.code === 200) setOnlineCount(r.data.length);
          }).catch(() => {});
        }
        // typing 事件：可选，见 Step 5
      },
    });
    // 首次拉一次在线坐席数
    agentService.getOnlineAgents().then((r) => { if (r.code === 200) setOnlineCount(r.data.length); }).catch(() => {});
    return () => conn.close();
  }, []);   // 只在挂载时建一次；用 ref 读当前会话
```
> 注意：`MainLayout` 用 `key={currentApp?.id}` 强制重挂载页面，切应用会触发本 `useEffect` cleanup→close→重建，符合预期（SSE 随应用切换重置）。

- [ ] **Step 4：JSX——未读红点 + 回归AI 按钮 + 在线指示**

- 待接列表项：若 `unread[session.sessionId] > 0`，在项上加 `Badge variant="destructive"` 显示计数（复用现有 `animate-pulse` 红点范式）。
- 右侧对话区顶栏：在「结束会话」按钮旁加「回归 AI」按钮：
```tsx
  <Button variant="outline" size="sm" onClick={handleReturnToAI}>回归 AI</Button>
```
- `handleReturnToAI`：`agentService.returnToAI(selectedSession.sessionId)` → 成功 toast「已交还 AI」+ 清 selected/messages + 刷队列。
- 顶部或队列标题旁加在线坐席指示：`<span className="text-xs text-muted-foreground">在线坐席 {onlineCount}</span>`。

- [ ] **Step 5：（可选）坐席端 typing 展示 + 上报**

若纳入：用户 `typing` 事件（`payload.from === 'user'` 且 `sessionId===当前`）时短暂显示「用户正在输入…」；坐席输入框 `onChange` 节流（≥3s 一次）调 `apiClient.post('/chat/session/{id}/typing')`。**MVP 若从简可跳过 typing 展示，仅保留后端已推的能力**——本步标注可选，实现者按简报既定范围决定（若跳过需在报告说明，不算缺陷）。

- [ ] **Step 6：整体类型检查 + lint + build**

Run: `cd frontend && pnpm lint && pnpm build`
Expected: ESLint 无新增告警、`tsc -b && vite build` 成功（此时 Task 2 的 API 改动 + Task 3 的调用方一致，无参数错）。

- [ ] **Step 7：提交**

```bash
cd frontend && git add src/pages/AgentPage.tsx
git commit -m "feat(前端): 坐席台实时化（去mock+真实消息+坐席SSE+未读红点+回归AI+在线指示）"
```

---

### Task 4: 坐席台 role 门禁

**Files:**
- Modify: `frontend/src/layouts/MainLayout.tsx`

- [ ] **Step 1：客服中心菜单加 role 限定**

先 Read `MainLayout.tsx` 找到「客服中心」`NavSection`（含「人工工作台 /agent」）。给该 section（或其中 `/agent` item）加 `roles: ['TENANT_ADMIN', 'TENANT_MEMBER']`（与后端 `SecurityUtil.requireAgent` 对齐；沿用文件里既有 `roles` + `canSee` 过滤机制，参考「平台管理」section 的 `roles:['PLATFORM_ADMIN']` 写法）。这样平台管理员/无角色用户不再看到坐席台入口（直接访问 URL 仍会被后端端点 403，属既有 API 层兜底）。

- [ ] **Step 2：lint + build**

Run: `cd frontend && pnpm lint && pnpm build`
Expected: 通过。

- [ ] **Step 3：提交**

```bash
cd frontend && git add src/layouts/MainLayout.tsx
git commit -m "feat(前端): 坐席台菜单加坐席角色门禁（对齐后端 requireAgent）"
```

---

### Task 5: widget SSE 读取器（`widget/src/sse.ts` + vitest）

**Files:**
- Create: `widget/src/sse.ts`
- Test: `widget/src/sse.test.ts`

**Interfaces:**
- Produces:
  - `parseSseChunk(raw: string): { event: string; data: string | null }`（纯函数，同前端逻辑）
  - `connectSse(url: string, appKey: string, onEvent: (event: string, data: string) => void): { close: () => void }`（widget 版：X-App-Id header = appKey；自动重连）

- [ ] **Step 1：写 `sse.test.ts`（先失败）**

```ts
// widget/src/sse.test.ts
import { describe, it, expect } from 'vitest';
import { parseSseChunk } from './sse';

describe('parseSseChunk', () => {
  it('解析命名事件 + data', () => {
    expect(parseSseChunk('event: message\ndata: {"a":1}')).toEqual({ event: 'message', data: '{"a":1}' });
  });
  it('无 event 行默认 message', () => {
    expect(parseSseChunk('data: hi')).toEqual({ event: 'message', data: 'hi' });
  });
  it('注释/心跳行(无 data)返回 data=null', () => {
    expect(parseSseChunk(': ping')).toEqual({ event: 'message', data: null });
  });
  it('多行 data 拼接', () => {
    expect(parseSseChunk('data: a\ndata: b')).toEqual({ event: 'message', data: 'a\nb' });
  });
});
```

- [ ] **Step 2：运行确认失败**

Run: `cd widget && pnpm test`
Expected: FAIL（`sse.ts` 不存在）。

- [ ] **Step 3：写 `widget/src/sse.ts`**

```ts
// widget/src/sse.ts
// widget 版 SSE（fetch+ReadableStream）：X-App-Id header 鉴权（EventSource 不能带 header），自动重连。

export function parseSseChunk(raw: string): { event: string; data: string | null } {
  let event = 'message';
  const dataLines: string[] = [];
  for (const line of raw.split('\n')) {
    if (line === '' || line.startsWith(':')) continue;
    if (line.startsWith('event:')) event = line.slice(6).trim();
    else if (line.startsWith('data:')) dataLines.push(line.slice(5).replace(/^ /, ''));
  }
  return { event, data: dataLines.length ? dataLines.join('\n') : null };
}

export function connectSse(
  url: string,
  appKey: string,
  onEvent: (event: string, data: string) => void,
): { close: () => void } {
  let closed = false;
  let controller: AbortController | null = null;
  let retry = 0;

  const loop = async () => {
    while (!closed) {
      controller = new AbortController();
      try {
        const resp = await fetch(url, {
          headers: { 'X-App-Id': appKey, Accept: 'text/event-stream' },
          signal: controller.signal,
        });
        if (!resp.ok || !resp.body) throw new Error('SSE ' + resp.status);
        retry = 0;
        const reader = resp.body.getReader();
        const decoder = new TextDecoder();
        let buf = '';
        for (;;) {
          const { done, value } = await reader.read();
          if (done || closed) break;
          buf += decoder.decode(value, { stream: true });
          let idx: number;
          while ((idx = buf.indexOf('\n\n')) >= 0) {
            const chunk = buf.slice(0, idx);
            buf = buf.slice(idx + 2);
            const { event, data } = parseSseChunk(chunk);
            if (data !== null) onEvent(event, data);
          }
        }
      } catch {
        if (closed) return;
      }
      if (closed) return;
      retry += 1;
      await new Promise((r) => setTimeout(r, Math.min(15000, 500 * 2 ** Math.min(retry, 5))));
    }
  };
  void loop();
  return { close: () => { closed = true; controller?.abort(); } };
}
```

- [ ] **Step 4：运行确认通过**

Run: `cd widget && pnpm test`
Expected: PASS（4/4）。

- [ ] **Step 5：提交**

```bash
cd widget && git add src/sse.ts src/sse.test.ts
git commit -m "feat(widget): SSE 读取器（fetch+ReadableStream，X-App-Id 鉴权+重连）"
```

---

### Task 6: widget inbox 接入（渲染坐席消息/状态/typing）

**Files:**
- Modify: `widget/src/api.ts`（加 `inboxUrl`/`sendTyping`/`getSession`）
- Modify: `widget/src/main.ts`（`ensureInbox`）
- Modify: `widget/src/ui.ts`（渲染坐席 message/状态条/typing）

先 Read `widget/src/api.ts`、`main.ts`、`ui.ts`、`session.ts`、`config.ts` 现状（掌握 `cfg.appKey`/`apiBase`/`sessionId` 流转、`appendMsg`/`setTyping`/Shadow DOM 结构）。

- [ ] **Step 1：`api.ts` 加 inbox 相关**

在 `widget/src/api.ts` 加（沿用其 `cfg.apiBase`/`X-App-Id` header 手法）：
```ts
  // inbox SSE 完整 URL（供 sse.ts 连接）
  inboxUrl: (sessionId: string) => `${cfg.apiBase}/v1/sessions/${sessionId}/inbox`,
  // 用户正在输入（节流由调用方控制）
  sendTyping: (sessionId: string) =>
    fetch(`${cfg.apiBase}/v1/sessions/${sessionId}/typing`, {
      method: 'POST', headers: { 'X-App-Id': cfg.appKey },
    }).catch(() => {}),
  // 断线补拉会话（含消息历史），返回 OpenSessionResponse
  getSession: (sessionId: string) =>
    fetch(`${cfg.apiBase}/v1/sessions/${sessionId}`, { headers: { 'X-App-Id': cfg.appKey } })
      .then((r) => (r.ok ? r.json() : null)).catch(() => null),
```
> 确认 `cfg.apiBase` 的值（勘查：`url.origin + '/api'`）→ inbox 完整路径 `{origin}/api/open/v1/sessions/{id}/inbox`。核对现有 `send()` 用的正是 `/open/v1/...` 前缀拼法，保持一致（若现有拼的是 `${apiBase}/open/v1/...`，则 inboxUrl 也用 `/open/v1/`——**以现有 api.ts 的实际拼法为准**，别引入不一致）。

- [ ] **Step 2：`ui.ts` 加坐席消息/状态/typing 渲染句柄**

复用现有 `appendMsg(text, 'bot')` 渲染坐席消息（可加「客服」前缀或标记区分 AI）；加一个 `showSessionState(text: string)` 在消息区插一条居中系统提示（如「客服已接入」）；坐席 `typing` 复用现有 `setTyping(true/false)`。导出这些句柄供 main.ts 调用。

- [ ] **Step 3：`main.ts` 接 inbox**

拿到 `sessionId` 后（`sendWithRetry` 成功赋值处）调 `ensureInbox(sessionId)`：
```ts
  let inbox: { close: () => void } | null = null;
  let inboxSid: string | null = null;
  function ensureInbox(sid: string) {
    if (inboxSid === sid && inbox) return;
    inbox?.close();
    inboxSid = sid;
    inbox = connectSse(api.inboxUrl(sid), cfg.appKey, (event, data) => {
      let p: any; try { p = JSON.parse(data); } catch { return; }
      if (event === 'message' && p.role === 'ASSISTANT') ui.appendMsg(p.content, 'bot');
      else if (event === 'session') ui.showSessionState(p.text || sessionStateText(p.kind));
      else if (event === 'typing' && p.from === 'agent') ui.setTyping(true);   // 短暂显示后由下一条消息/超时清除
    });
  }
```
`sessionStateText(kind)`：`accepted→'客服已接入'`/`returned_to_ai→'已切回智能助手'`/`ended→'会话已结束'`/`waiting→'正在为您转接人工…'`。会话重开（sessionId 变）时 `ensureInbox` 自动 close 旧连开新连。widget 卸载/销毁处（若有）`inbox?.close()`。

- [ ] **Step 4：用户 typing 节流上报（可选，随范围）**

widget 输入框 `input` 事件节流（≥3s）调 `api.sendTyping(sessionId)`。若从简可跳过，报告说明。

- [ ] **Step 5：widget 单元/构建验证（不含 widget.js 提交，留 Task 7）**

Run: `cd widget && pnpm test && pnpm build`
Expected: vitest 绿、`tsc && vite build` 成功产出（此步会更新 `backend/.../widget.js`，但**先不提交产物**——Task 7 统一重建+提交，避免中间态产物）。

- [ ] **Step 6：提交源码（不含产物）**

```bash
cd "$(git rev-parse --show-toplevel)" && git add widget/src/api.ts widget/src/main.ts widget/src/ui.ts
git commit -m "feat(widget): 接 inbox SSE 渲染坐席消息/会话状态/typing + 用户 typing 上报"
```

---

### Task 7: 重建并提交 widget.js + 全量验证

**Files:**
- Modify: `backend/src/main/resources/static/widget.js`（构建产物）

- [ ] **Step 1：干净重建 widget.js**

Run: `cd widget && pnpm build`
Expected: 产出 `backend/src/main/resources/static/widget.js`（IIFE 单文件）。

- [ ] **Step 2：确认产物为 LF、diff 稳定**

Run: `cd "$(git rev-parse --show-toplevel)" && git diff --stat backend/src/main/resources/static/widget.js`
Expected: 有改动（新增 inbox 逻辑）；`.gitattributes` 已强制 LF，`git diff` 无整文件 CRLF 翻转。

- [ ] **Step 3：前端 + widget 全量验证**

Run: `cd frontend && pnpm lint && pnpm build` 且 `cd widget && pnpm test && pnpm build`
Expected: 全绿。

- [ ] **Step 4：提交产物**

```bash
cd "$(git rev-parse --show-toplevel)" && git add backend/src/main/resources/static/widget.js
git commit -m "build(widget): 重建 widget.js（含坐席 inbox 实时渲染）"
```

- [ ] **Step 5：留债登记**
真机端到端联调（起全栈 + 坐席登录 + widget 对话 + 真人接管收发）作为收尾 smoke（受 LLM 依赖/浏览器环境，沿用既定取舍，可留真机验证）。

## Self-Review

**1. Spec coverage（对 design §8）：** 坐席台去 mock/真实消息/坐席 SSE/未读/回复去 agentId/回归AI/结束/在线指示 → T2+T3；role 门禁 → T4；widget inbox SSE 渲染坐席 message/状态/typing + 用户 typing + 重建 widget.js → T5+T6+T7。SSE 鉴权（fetch-stream header）→ T1（前端）+T5（widget）。无遗漏。
**2. Placeholder scan：** 无 TBD；Step 里「可选 typing」显式标注为范围内可裁剪项并要求报告说明，非模糊要求；每个改码步给了完整/具体代码或精确改点。
**3. Type consistency：** `parseSseChunk`/`connectSse` 前端与 widget 各自定义但签名一致（widget 版多 appKey 参数）；`agentService.acceptSession(sessionId)`/`agentReply({sessionId,message})`/`returnToAI`/`getOnlineAgents`、事件名 `message`/`session`/`typing`/`presence`、payload 字段 `sessionId`/`messageId`/`role`/`content`/`kind`/`text`/`from`/`online` 与后端 `RealtimeNotifier` 产出对齐。
**4. 已知局限：** 前端无 vitest，SSE 逻辑单测仅在 widget 侧（同款 `parseSseChunk`）；前端验证靠 lint+build+真机 smoke。
