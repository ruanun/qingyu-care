import { describe, it, expect, vi, afterEach } from 'vitest';
import { parseActionUri, executeActionUri } from './actionUri';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('parseActionUri', () => {
  it('识别三种白名单协议', () => {
    expect(parseActionUri('url://https://a.com')).toMatchObject({ protocol: 'url', content: 'https://a.com', isValid: true });
    expect(parseActionUri('msg://查询物流')).toMatchObject({ protocol: 'msg', content: '查询物流', isValid: true });
    expect(parseActionUri('copy://SF123')).toMatchObject({ protocol: 'copy', content: 'SF123', isValid: true });
  });

  it('协议名大小写归一', () => {
    expect(parseActionUri('URL://https://a.com')).toMatchObject({ protocol: 'url', isValid: true });
  });

  it('未知协议判无效', () => {
    expect(parseActionUri('tel://10086').isValid).toBe(false);
    // http:// 是常见文本前缀，绝不能被当成动作
    expect(parseActionUri('http://a.com').isValid).toBe(false);
  });

  it('格式不匹配判无效', () => {
    expect(parseActionUri('查询物流').isValid).toBe(false);
    expect(parseActionUri('').isValid).toBe(false);
    expect(parseActionUri(null).isValid).toBe(false);
    expect(parseActionUri(123).isValid).toBe(false);
  });

  it('content 走 decodeURIComponent', () => {
    expect(parseActionUri('msg://%E6%9F%A5%E8%AF%A2').content).toBe('查询');
  });

  it('解码失败时回退原文，不抛', () => {
    expect(parseActionUri('msg://100%').content).toBe('100%');
  });
});

describe('executeActionUri', () => {
  const handlers = () => ({ onMessage: vi.fn(), onCopied: vi.fn(), onCopyFailed: vi.fn() });

  it('msg 协议调 onMessage 并返回已处理', () => {
    const h = handlers();
    expect(executeActionUri('msg://查询物流', h)).toBe(true);
    expect(h.onMessage).toHaveBeenCalledWith('查询物流');
  });

  it('url 协议新标签打开且带 noopener,noreferrer', () => {
    const open = vi.fn();
    vi.stubGlobal('open', open);
    const h = handlers();

    expect(executeActionUri('url://https://a.com/x', h)).toBe(true);
    expect(open).toHaveBeenCalledWith('https://a.com/x', '_blank', 'noopener,noreferrer');
  });

  it('url 协议遇 javascript: 被 safeUrl 拒掉——不打开、也不回退', () => {
    const open = vi.fn();
    vi.stubGlobal('open', open);
    const h = handlers();

    // 返回 true 表示"已作为动作处理"，调用方不得回退去发 value
    expect(executeActionUri('url://javascript:alert(1)', h)).toBe(true);
    expect(open).not.toHaveBeenCalled();
    expect(h.onMessage).not.toHaveBeenCalled();
  });

  it('copy 协议写剪贴板并回调 onCopied', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    vi.stubGlobal('navigator', { clipboard: { writeText } });
    const h = handlers();

    expect(executeActionUri('copy://SF123', h)).toBe(true);
    expect(writeText).toHaveBeenCalledWith('SF123');
    await Promise.resolve();
    await Promise.resolve();
    expect(h.onCopied).toHaveBeenCalled();
  });

  it('copy 在无 clipboard 的非安全上下文回调 onCopyFailed', () => {
    vi.stubGlobal('navigator', {});
    const h = handlers();

    expect(executeActionUri('copy://SF123', h)).toBe(true);
    expect(h.onCopyFailed).toHaveBeenCalled();
  });

  it('协议无效返回 false，交由调用方回退', () => {
    const h = handlers();
    expect(executeActionUri('查询物流', h)).toBe(false);
    expect(executeActionUri(undefined, h)).toBe(false);
    expect(h.onMessage).not.toHaveBeenCalled();
  });

  it('协议有效但内容为空——不动作也不回退', () => {
    const h = handlers();
    expect(executeActionUri('msg://', h)).toBe(true);
    expect(h.onMessage).not.toHaveBeenCalled();
  });
});
