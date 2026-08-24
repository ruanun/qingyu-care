// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { parseActionUri, resolveActionUri } from './actionUri';

/**
 * Action URI 的解析与白名单。执行（开链接/写剪贴板）留在平台层。
 *
 * ⚠️ 核心不变量：`blocked` 与 `none` 必须是两种不同结果。
 * - none    = 协议无效 → 调用方**应当**回退成发送原始 value
 * - blocked = 协议有效但内容被安全策略拦下 → 调用方**不得**回退（fail-closed）
 * 把它们混成一种，就等于把 CLAUDE.md 那条 fail-closed 语义弄丢了。
 */

describe('parseActionUri', () => {
  it('识别三个白名单协议', () => {
    expect(parseActionUri('url://https://a.com').isValid).toBe(true);
    expect(parseActionUri('msg://你好').isValid).toBe(true);
    expect(parseActionUri('copy://ABC').isValid).toBe(true);
  });

  it('白名单外的协议不算有效', () => {
    expect(parseActionUri('javascript://alert(1)').isValid).toBe(false);
    expect(parseActionUri('file:///etc/passwd').isValid).toBe(false);
  });

  it('非字符串与格式不符一律无效', () => {
    expect(parseActionUri(null).isValid).toBe(false);
    expect(parseActionUri(123).isValid).toBe(false);
    expect(parseActionUri('').isValid).toBe(false);
    expect(parseActionUri('没有协议头').isValid).toBe(false);
  });

  it('内容做 URL 解码，解码失败时用原文而不抛', () => {
    expect(parseActionUri('msg://%E4%BD%A0%E5%A5%BD').content).toBe('你好');
    expect(parseActionUri('msg://100%').content).toBe('100%');
  });

  it('协议名大小写不敏感', () => {
    expect(parseActionUri('URL://https://a.com').protocol).toBe('url');
  });
});

describe('resolveActionUri', () => {
  it('合法 http/https 链接解析成 openUrl', () => {
    expect(resolveActionUri('url://https://a.com')).toEqual({ kind: 'openUrl', url: 'https://a.com' });
  });

  /** fail-closed：协议有效但 URL 被白名单拒绝 → blocked，调用方不回退 */
  it('非 http/https 的链接被拦下且不是 none', () => {
    const r = resolveActionUri('url://javascript:alert(1)');
    expect(r.kind).toBe('blocked');
    expect(r.kind).not.toBe('none');
  });

  it('msg 解析成 message', () => {
    expect(resolveActionUri('msg://你好')).toEqual({ kind: 'message', text: '你好' });
  });

  it('copy 解析成 copy', () => {
    expect(resolveActionUri('copy://ABC')).toEqual({ kind: 'copy', text: 'ABC' });
  });

  it('协议无效时是 none（调用方应回退）', () => {
    expect(resolveActionUri('javascript://x').kind).toBe('none');
    expect(resolveActionUri(null).kind).toBe('none');
  });

  /** 协议有效但内容为空：无动作可做，但也**不该**回退成发消息 */
  it('内容为空时是 blocked 而不是 none', () => {
    expect(resolveActionUri('msg://').kind).toBe('blocked');
  });
});
