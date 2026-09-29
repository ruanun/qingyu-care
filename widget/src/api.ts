// widget/src/api.ts
// 协议部分（鉴权头构造、getConfig/stream/handover/feedback/markRead/getSession 等）已抽到
// core/api.ts，供 CLI（Task 8）复用；这里只留浏览器专属的附件上传/下载——它们带 File/Blob
// 浏览器类型，抽象成跨端接口只会做出各端都不合用的形状，见 spec §2.4。

import type { WidgetConfig } from './config';
import type { WidgetAttachment } from './attachments';
import { createCoreApi } from './core/api';

export type { WidgetBrand, UiNode, UiPart, OpenSessionResponse } from './core/api';
export { SessionExpiredError } from './core/api';
import { SessionExpiredError } from './core/api';

export function createApi(cfg: WidgetConfig) {
  const core = createCoreApi({ apiBase: cfg.apiBase, appKey: cfg.appKey });

  return {
    ...core,

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
      // 会话已结束 / 已超时：后端拒收附件（否则附件挂在旧会话上、新会话重发时必丢）
      if (r.status === 404) throw new SessionExpiredError();
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
  };
}

export type WidgetApi = ReturnType<typeof createApi>;
