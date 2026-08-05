// frontend/src/lib/chatStream.ts
// 对话流专用的一次性 POST SSE：与 sse.ts（GET + 自动重连，inbox 长连接用）刻意分开——
// 对话流绝不能自动重连，重连等于重复发消息、重复计费。
// 事件协议见 docs/superpowers/specs/2026-08-04-streaming-chat-design.md §3。

import { authHeaders, parseSseChunk } from './sse';
import type { KnowledgeHit, UiNode } from '@/types';

export interface ChatStreamStart {
  sessionId: string;
  suggestHandover: boolean;
  knowledgeHits: KnowledgeHit[];
}

export interface ChatStreamHandlers {
  onStart?: (d: ChatStreamStart) => void;
  onDelta?: (text: string) => void;
  onCard?: (node: UiNode) => void;
  onDone?: (messageId: number | null) => void;
  /** 后端 error 事件，或流未以 done 结束（中断）。文案已可直接展示。 */
  onError?: (message: string) => void;
}

export interface ChatStreamRequest {
  userId: string;
  sessionId?: string;
  message: string;
  // 本条消息携带的附件 id（先经 chatService.uploadChatFile 上传拿到）。可空。
  attachmentIds?: number[];
}

/**
 * 发起一次流式对话并按事件分派。正常结束或出错都会 resolve（错误经 onError 回调），
 * 只有 abort 会以 AbortError reject —— 调用方据此区分「用户主动停止」与「服务端出错」。
 */
export async function streamChat(
  body: ChatStreamRequest,
  handlers: ChatStreamHandlers,
  signal?: AbortSignal,
): Promise<void> {
  let done = false;
  let errored = false;

  const resp = await fetch('/api/v1/chat/stream', {
    method: 'POST',
    headers: { ...authHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
    signal,
  });

  if (!resp.ok || !resp.body) {
    handlers.onError?.(resp.status === 429 ? '请求过于频繁，请稍后再试。' : '回复生成失败，请稍后重试。');
    return;
  }

  const reader = resp.body.getReader();
  const decoder = new TextDecoder();
  let buf = '';
  for (;;) {
    const { done: finished, value } = await reader.read();
    if (finished) break;
    buf += decoder.decode(value, { stream: true });
    let idx: number;
    while ((idx = buf.indexOf('\n\n')) >= 0) {
      const chunk = buf.slice(0, idx);
      buf = buf.slice(idx + 2);
      const { event, data } = parseSseChunk(chunk);
      if (data === null) continue; // 心跳/注释
      let payload: unknown;
      try {
        payload = JSON.parse(data);
      } catch {
        continue; // 非法负载忽略，不打断整条流
      }
      if (event === 'start') handlers.onStart?.(payload as ChatStreamStart);
      else if (event === 'delta') handlers.onDelta?.((payload as { text?: string }).text ?? '');
      else if (event === 'card') { const node = (payload as { node?: UiNode }).node; if (node) handlers.onCard?.(node); }
      else if (event === 'done') { done = true; handlers.onDone?.((payload as { messageId?: number }).messageId ?? null); }
      else if (event === 'error') { errored = true; handlers.onError?.((payload as { message?: string }).message || '回复生成失败。'); }
    }
  }

  // 流断了但既没收到 done 也没收到 error → 视为中断（半截文本保留在屏上，不重发）
  if (!done && !errored) handlers.onError?.('回复中断，请重试。');
}
