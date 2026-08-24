// @vitest-environment node
import { afterEach, describe, expect, it, vi } from 'vitest';
import { createCoreApi } from './api';

/**
 * 只测**鉴权头构造**——这是本次抽取新增的能力（widget 只有浏览器一条路径，CLI 需要服务端路径）。
 * 各端点的行为由既有的 widget api 测试覆盖，此处不重复。
 *
 * ⚠️ 两条路径看到的知识库不一样（后端 OpenApiAuthInterceptor）：带 X-Api-Key 的服务端身份
 * 可见 INTERNAL 文档，仅带 X-App-Id 的浏览器身份只见 PUBLIC。头发错会让调试结论完全失真。
 */

afterEach(() => {
  vi.unstubAllGlobals();
});

function captureHeaders() {
  const calls: Array<{ url: string; headers: Record<string, string> }> = [];
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    calls.push({ url, headers: (init?.headers ?? {}) as Record<string, string> });
    return new Response(JSON.stringify({}), { status: 200, headers: { 'Content-Type': 'application/json' } });
  }));
  return calls;
}

describe('鉴权头构造', () => {
  it('给了 apiSecret 时发服务端身份的两个头', async () => {
    const calls = captureHeaders();
    await createCoreApi({ apiBase: 'https://x.com/api', appKey: 'k1', apiSecret: 's1' }).getConfig();

    expect(calls[0].headers['X-App-Id']).toBe('k1');
    expect(calls[0].headers['X-Api-Key']).toBe('s1');
  });

  it('没给 apiSecret 时只发 X-App-Id，不带空的 X-Api-Key', async () => {
    const calls = captureHeaders();
    await createCoreApi({ apiBase: 'https://x.com/api', appKey: 'k1' }).getConfig();

    expect(calls[0].headers['X-App-Id']).toBe('k1');
    expect('X-Api-Key' in calls[0].headers).toBe(false);
  });

  /** CLI 的 --as-visitor：显式设置 Origin 模拟浏览器那条鉴权路径 */
  it('给了 origin 时带上 Origin 头', async () => {
    const calls = captureHeaders();
    await createCoreApi({ apiBase: 'https://x.com/api', appKey: 'k1', origin: 'https://shop.com' }).getConfig();

    expect(calls[0].headers['Origin']).toBe('https://shop.com');
  });

  it('浏览器场景（无 origin 无 secret）不带 Origin——由浏览器自动附加', async () => {
    const calls = captureHeaders();
    await createCoreApi({ apiBase: 'https://x.com/api', appKey: 'k1' }).getConfig();

    expect('Origin' in calls[0].headers).toBe(false);
  });
});
