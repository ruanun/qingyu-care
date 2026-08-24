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
