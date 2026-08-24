import { describe, it, expect, vi, afterEach } from 'vitest';
import { executeActionUri } from './actionUri';

/**
 * 纯解析（协议识别/解码/大小写）已移进 core/actionUri.test.ts。这里只留
 * **执行层**：开链接、写剪贴板，以及 fail-closed 的「不回退」边界——都依赖
 * 浏览器全局（window.open / navigator.clipboard），必须留在 jsdom。
 */

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
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
