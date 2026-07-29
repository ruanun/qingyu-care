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
        if (resp.status === 401 || resp.status === 403) {
          closed = true;                 // 鉴权失败不可恢复,停止重连避免空转
          handlers.onError?.(new Error(`SSE 鉴权失败: ${resp.status}`));
          return;                        // 直接退出,不进入下方 retry 退避
        }
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
