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

export interface ChatReply {
  sessionId: string;
  reply: string;
  handoverSuggested: boolean;
  /** AI 消息 ID（提交赞/踩时使用；后端旧版本可能缺省） */
  messageId?: number;
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
  };
}

export type WidgetApi = ReturnType<typeof createApi>;
