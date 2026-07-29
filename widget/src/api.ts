import type { WidgetConfig } from './config';

/** 服务端判定会话失效（404）时抛出，供上层清本地会话并自动重开。 */
export class SessionExpiredError extends Error {
  constructor() {
    super('会话不存在或已过期');
    this.name = 'SessionExpiredError';
  }
}

export interface WidgetBrand {
  appName: string;
  welcomeMessage: string;
  avatar: string;
  themeColor: string;
}

/** 结构化卡片 UI 节点（镜像后端 UiNode）。 */
export interface UiNode {
  type: string;
  props?: Record<string, unknown>;
  children?: UiNode[];
}

/** 有序回复片段：text（纯文本）或 ui（卡片节点）（镜像后端 UiPart）。 */
export interface UiPart {
  kind: string;
  text?: string | null;
  node?: UiNode | null;
}

export interface ChatReply {
  sessionId: string;
  reply: string;
  handoverSuggested: boolean;
  /** AI 消息 ID（提交赞/踩时使用；后端旧版本可能缺省） */
  messageId?: number;
  /** 结构化卡片片段（后端旧版本可能缺省）；有 ui 片段时按序渲染，否则回退 reply 纯文本 */
  uiParts?: UiPart[];
}

/** 断线补拉会话响应：会话状态 + 消息历史（对应后端 OpenSessionResponse） */
export interface OpenSessionResponse {
  sessionId: string;
  /** AI_PROCESSING / WAITING_AGENT / AGENT_PROCESSING / ENDED / TIMEOUT */
  status: string;
  messages: Array<{
    /** USER / ASSISTANT / SYSTEM / AGENT */
    role: string;
    content: string;
    createdAt: string;
    /** ASSISTANT 历史消息的结构化卡片片段（后端反序列化 ui_spec 而来，可空） */
    uiParts?: UiPart[];
  }>;
}

export function createApi(cfg: WidgetConfig) {
  const headers = (): Record<string, string> => ({
    'Content-Type': 'application/json',
    'X-App-Id': cfg.appKey, // 仅公开 appKey，无任何密钥；浏览器自动带 Origin 供服务端校验
  });

  return {
    async getConfig(): Promise<WidgetBrand> {
      const r = await fetch(`${cfg.apiBase}/open/v1/widget/config`, { headers: headers() });
      if (!r.ok) throw new Error(`widget config 请求失败: ${r.status}`);
      return (await r.json()) as WidgetBrand;
    },

    async send(message: string, sessionId: string | null): Promise<ChatReply> {
      const body = sessionId ? { message, sessionId } : { message };
      const r = await fetch(`${cfg.apiBase}/open/v1/chat/completions`, {
        method: 'POST',
        headers: headers(),
        body: JSON.stringify(body),
      });
      if (r.status === 404) throw new SessionExpiredError();
      if (!r.ok) throw new Error(`对话请求失败: ${r.status}`);
      return (await r.json()) as ChatReply;
    },

    async handover(sessionId: string): Promise<void> {
      await fetch(`${cfg.apiBase}/open/v1/sessions/${sessionId}/handover`, {
        method: 'POST',
        headers: headers(),
      });
    },

    /** 消息反馈（1=赞 / -1=踩）；失败静默（不干扰对话） */
    async feedback(sessionId: string, messageId: number, value: 1 | -1): Promise<void> {
      try {
        await fetch(`${cfg.apiBase}/open/v1/chat/feedback`, {
          method: 'POST',
          headers: headers(),
          body: JSON.stringify({ sessionId, messageId, feedback: value }),
        });
      } catch {
        // 反馈失败静默：不打断对话、不产生未捕获 rejection
      }
    },

    /** inbox SSE 完整 URL（供 sse.ts 连接，推送坐席消息/会话状态/typing） */
    inboxUrl(sessionId: string): string {
      return `${cfg.apiBase}/open/v1/sessions/${sessionId}/inbox`;
    },

    /** 用户正在输入：通知当前接管坐席（节流由调用方控制）；失败静默 */
    sendTyping(sessionId: string): Promise<void> {
      return fetch(`${cfg.apiBase}/open/v1/sessions/${sessionId}/typing`, {
        method: 'POST',
        headers: headers(),
      })
        .then(() => undefined)
        .catch(() => {
          // 通知失败静默：不打断对话
        });
    },

    /** 断线补拉会话（含消息历史），用于 inbox 重连后同步状态；失败返回 null */
    async getSession(sessionId: string): Promise<OpenSessionResponse | null> {
      try {
        const r = await fetch(`${cfg.apiBase}/open/v1/sessions/${sessionId}`, { headers: headers() });
        if (!r.ok) return null;
        return (await r.json()) as OpenSessionResponse;
      } catch {
        return null;
      }
    },
  };
}

export type WidgetApi = ReturnType<typeof createApi>;
