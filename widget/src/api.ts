import type { WidgetConfig } from './config';
import { postSse, type ChatStreamHandlers } from './chatStream';
import type { WidgetAttachment } from './attachments';

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

/** 断线补拉会话响应：会话状态 + 消息历史（对应后端 OpenSessionResponse） */
export interface OpenSessionResponse {
  sessionId: string;
  /** AI_PROCESSING / WAITING_AGENT / AGENT_PROCESSING / ENDED / TIMEOUT */
  status: string;
  /**
   * 坐席读到哪了（对方水位线），供刷新后恢复已读标记；无坐席接管或坐席未读过时为 null。
   * 访客自己的已读水位线不在这里——那是上报出去的，不需要读回来。
   */
  agentReadUpToMessageId?: number | null;
  messages: Array<{
    /** 消息 id（对应后端 ChatMessage.id），用于定位该消息挂载的附件 */
    id?: number | null;
    /** USER / ASSISTANT / SYSTEM / AGENT */
    role: string;
    content: string;
    createdAt: string;
    /** ASSISTANT 历史消息的结构化卡片片段（后端反序列化 ui_spec 而来，可空） */
    uiParts?: UiPart[];
    /** 该消息挂载的图片/文件（可空，无附件为空数组） */
    attachments?: WidgetAttachment[];
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

    /**
     * 流式对话；404 抛 SessionExpiredError（供上层清会话后重开）。
     * attachmentIds 排在 signal 前——上层重开新会话重试时刻意不透传 attachmentIds
     * （见 main.ts streamWithRetry 的说明：附件绑定在旧会话上，新会话下校验会拒绝，
     * 宁可丢附件保文本也不能让整条消息发不出去），故常见调用不需要给 signal。
     */
    async stream(
      message: string,
      sessionId: string | null,
      handlers: ChatStreamHandlers,
      attachmentIds?: number[],
      signal?: AbortSignal,
    ): Promise<void> {
      const body: { message: string; sessionId?: string; attachmentIds?: number[] } = { message };
      if (sessionId) body.sessionId = sessionId;
      if (attachmentIds && attachmentIds.length) body.attachmentIds = attachmentIds;
      try {
        await postSse(`${cfg.apiBase}/open/v1/chat/stream`, headers(), body, handlers, signal);
      } catch (e) {
        if (e instanceof Error && e.name === 'SessionExpiredError') throw new SessionExpiredError();
        throw e;
      }
    },

    /**
     * 上传消息附件（图片/文件），仅落库拿 id，不关联到具体消息；发消息时把返回的 id
     * 塞进 attachmentIds。sessionId 必填且不能是空串——后端直接拒绝（空串曾是所有租户
     * 共享的命名空间，两端已统一拒绝，见 backend ChatAttachmentService#requireOwnedSession）。
     * 不手工设置 Content-Type，交给浏览器自己带 multipart boundary。
     */
    async uploadAttachment(sessionId: string, file: File): Promise<WidgetAttachment> {
      const form = new FormData();
      form.append('sessionId', sessionId);
      form.append('file', file);
      const r = await fetch(`${cfg.apiBase}/open/v1/chat/files`, {
        method: 'POST',
        headers: { 'X-App-Id': cfg.appKey },
        body: form,
      });
      if (!r.ok) throw new Error(`附件上传失败: ${r.status}`);
      return (await r.json()) as WidgetAttachment;
    },

    /**
     * 下载附件二进制。私有桶 + 请求头鉴权，<img src>/<a href> 拿不到；调用方自行
     * URL.createObjectURL 换成可用于 img.src / 下载链接的 blob URL。
     * sessionId 是必填查询参数——防止公网凭 appKey 枚举 id 拿到其它访客上传的文件。
     */
    async downloadAttachment(id: number, sessionId: string): Promise<Blob> {
      const url = `${cfg.apiBase}/open/v1/chat/files/${id}?sessionId=${encodeURIComponent(sessionId)}`;
      const r = await fetch(url, { headers: { 'X-App-Id': cfg.appKey } });
      if (!r.ok) throw new Error(`附件下载失败: ${r.status}`);
      return await r.blob();
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

    /**
     * 访客上报已读进度（水位线单调递增）。fail-soft：失败只 console.debug，绝不打断对话——
     * 已读回执是锦上添花的功能，不该因网络抖动影响正常聊天。
     */
    async markRead(sessionId: string, upToMessageId: number): Promise<void> {
      try {
        const r = await fetch(`${cfg.apiBase}/open/v1/sessions/${sessionId}/read`, {
          method: 'POST',
          headers: headers(),
          body: JSON.stringify({ upToMessageId }),
        });
        if (!r.ok) console.debug('[Qingyu Widget] 已读上报失败', r.status);
      } catch (e) {
        console.debug('[Qingyu Widget] 已读上报失败', e);
      }
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
