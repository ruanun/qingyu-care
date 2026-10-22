// 卡片按钮的 Action URI 协议：{protocol}://{content}
// 与 frontend/src/lib/actionUri.ts 是同一套规则的两份实现，白名单必须逐字一致

import { safeUrl } from './safeUrl.js';

export interface ParsedActionUri {
  protocol: string;
  content: string;
  isValid: boolean;
}

/**
 * 白名单：只有这三个协议才当动作处理，其余一律回退为纯文本发消息。
 *
 * ⚠️ 这份白名单与 `ChatServiceImpl.CARD_PROMPT`、`frontend/src/lib/actionUri.ts` 三处必须
 * 逐字相等，由后端的 CardActionProtocolSyncTest 强制。改这里必须同步另两处。
 */
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

/**
 * 解析结果的平台无关表示。
 *
 * ⚠️ `none` 与 `blocked` 是两种**不同**的结果，不能合并：
 * - `none`    协议无效 → 调用方**应当**回退成发送原始 value
 * - `blocked` 协议有效但内容被安全策略拦下（或为空）→ 调用方**不得**回退
 *
 * 这是 CLAUDE.md 点名过的 fail-closed 语义：被 safeUrl 拒绝的按钮点下去没反应是**正确**的，
 * 改去做别的事（比如发一条奇怪的消息）才是错的。
 */
export type ResolvedAction =
  | { kind: 'none' }
  | { kind: 'blocked' }
  | { kind: 'openUrl'; url: string }
  | { kind: 'message'; text: string }
  | { kind: 'copy'; text: string };

export function resolveActionUri(raw: unknown): ResolvedAction {
  const parsed = parseActionUri(raw);
  if (!parsed.isValid) return { kind: 'none' };
  if (!parsed.content) return { kind: 'blocked' };

  switch (parsed.protocol) {
    case 'url': {
      const url = safeUrl(parsed.content);
      return url ? { kind: 'openUrl', url } : { kind: 'blocked' };
    }
    case 'msg':
      return { kind: 'message', text: parsed.content };
    case 'copy':
      return { kind: 'copy', text: parsed.content };
    default:
      return { kind: 'none' };
  }
}
