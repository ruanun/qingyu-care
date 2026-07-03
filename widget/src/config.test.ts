import { describe, it, expect, afterEach } from 'vitest';
import { resolveConfig } from './config';

function addScript(src: string): HTMLScriptElement {
  const s = document.createElement('script');
  s.src = src;
  document.body.appendChild(s);
  return s;
}

afterEach(() => {
  document.querySelectorAll('script').forEach((s) => s.remove());
});

describe('resolveConfig', () => {
  it('从 script src 的 ?appKey 解析出配置', () => {
    addScript('https://host.example.com/api/widget.js?appKey=app_123&v=1');
    const cfg = resolveConfig();
    expect(cfg).not.toBeNull();
    expect(cfg!.appKey).toBe('app_123');
    expect(cfg!.apiBase).toBe('https://host.example.com/api');
  });

  it('缺少 appKey 时返回 null', () => {
    addScript('https://host.example.com/api/widget.js');
    expect(resolveConfig()).toBeNull();
  });

  it('找不到脚本时返回 null', () => {
    expect(resolveConfig()).toBeNull();
  });
});
