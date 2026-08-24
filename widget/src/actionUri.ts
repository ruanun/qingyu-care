// 卡片按钮的 Action URI 协议：{protocol}://{content}
// 协议见 docs/superpowers/specs/2026-08-04-card-action-uri-design.md §3

import { safeUrl } from './core/safeUrl';

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
