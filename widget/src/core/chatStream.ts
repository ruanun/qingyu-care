// widget/src/chatStream.ts
// 对话流专用的一次性 POST SSE：与 sse.ts（GET + 自动重连，inbox 用）刻意分开——
// 对话流绝不能自动重连，重连等于重复发消息、重复计费。

import { parseSseChunk } from './sse';
import type { UiNode } from '../api';

export interface ChatStreamStart {
  sessionId: string;
  suggestHandover: boolean;
  knowledgeHits: Array<{ content: string; score: number; source: string }>;
  /**
   * 访客自己那条消息的 id。⚠️ 主问答路径（检索命中/允许无知识）此刻恒为 null——用户消息与
   * AI 回复整轮延迟到流结束才落库（「停止生成不落库」契约），构造 START 时尚未落库、
   * ChatMessage 用 IDENTITY 生成策略取不到提前 id；人工接管静默/关键词触发转人工/知识库
   * 未命中兜底三条早退路径落库在前，带真实值。widget 侧策略：START 有就先用，没有就等 DONE。
   */
  userMessageId?: number | null;
}

export interface ChatStreamHandlers {
  onStart?: (d: ChatStreamStart) => void;
  onDelta?: (text: string) => void;
  onCard?: (node: UiNode) => void;
  /**
   * @param messageId AI 回复的落库 id，未落库时为 null。
   * @param userMessageId 访客自己那条消息的落库 id；DONE 排在落库之后构造，只要本轮确实
   *   落了库就必然一并拿到真实值，是 START 那个「主问答路径恒为 null」的兜底来源。
   */
  onDone?: (messageId: number | null, userMessageId: number | null) => void;
  /** 后端 error 事件，或流未以 done 结束（中断）。文案已可直接展示。 */
  onError?: (message: string) => void;
}

/** 状态码 → 可直接展示给终端用户的文案（不暴露内部细节）。 */
function httpErrorText(status: number): string {
  if (status === 429) return '请求过于频繁，请稍后再试。';
  return '抱歉，服务暂时不可用，请稍后再试。';
}

export async function postSse(
  url: string,
  headers: Record<string, string>,
  body: unknown,
  handlers: ChatStreamHandlers,
  signal?: AbortSignal,
): Promise<void> {
  const resp = await fetch(url, {
    method: 'POST',
    headers: { ...headers, Accept: 'text/event-stream' },
    body: JSON.stringify(body),
    signal,
  });

  if (resp.status === 404) {
    const err = new Error('会话不存在或已过期');
    err.name = 'SessionExpiredError';
    throw err;
  }
  if (!resp.ok || !resp.body) {
    handlers.onError?.(httpErrorText(resp.status));
    return;
  }

  let done = false;
  let errored = false;
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
      if (data === null) continue;
      let payload: unknown;
      try {
        payload = JSON.parse(data);
      } catch {
        continue; // 非法负载忽略，不打断整条流
      }
      if (event === 'start') handlers.onStart?.(payload as ChatStreamStart);
      else if (event === 'delta') handlers.onDelta?.((payload as { text?: string }).text ?? '');
      else if (event === 'card') { const node = (payload as { node?: UiNode }).node; if (node) handlers.onCard?.(node); }
      else if (event === 'done') {
        done = true;
        const d = payload as { messageId?: number | null; userMessageId?: number | null };
        handlers.onDone?.(d.messageId ?? null, d.userMessageId ?? null);
      }
      else if (event === 'error') { errored = true; handlers.onError?.((payload as { message?: string }).message || '回复生成失败。'); }
    }
  }

  // 流断了但既没 done 也没 error → 中断（半截文本保留在屏上，不重发）
  if (!done && !errored) handlers.onError?.('回复中断，请重试。');
}
