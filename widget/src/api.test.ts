import { describe, it, expect, vi, afterEach } from 'vitest';
import { createApi } from './api';

const cfg = { appKey: 'app_123', apiBase: 'https://host/api' };

function mockFetch(status: number, body: unknown) {
  return vi.fn().mockResolvedValue({
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  });
}

afterEach(() => vi.unstubAllGlobals());

describe('api', () => {
  it('getConfig 请求 widget/config 端点', async () => {
    const f = mockFetch(200, { appName: 'A', welcomeMessage: 'hi', avatar: '', themeColor: '#000' });
    vi.stubGlobal('fetch', f);

    const brand = await createApi(cfg).getConfig();

    expect(brand.appName).toBe('A');
    expect(f.mock.calls[0][0]).toBe('https://host/api/open/v1/widget/config');
  });

  it('feedback 提交到开放反馈端点', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true });
    vi.stubGlobal('fetch', fetchMock);
    const api = createApi(cfg);
    await api.feedback('s-1', 42, 1);
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/open/v1/chat/feedback'),
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ sessionId: 's-1', messageId: 42, feedback: 1 }),
      }),
    );
  });

  it('feedback 网络失败静默不抛', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('network error')));

    await expect(createApi(cfg).feedback('s-1', 42, -1)).resolves.toBeUndefined();
  });

  it('inboxUrl 拼出 inbox SSE 端点', () => {
    const url = createApi(cfg).inboxUrl('s-1');
    expect(url).toBe('https://host/api/open/v1/sessions/s-1/inbox');
  });

  it('sendTyping 带 X-App-Id 头 POST 到 typing 端点', async () => {
    const f = mockFetch(200, {});
    vi.stubGlobal('fetch', f);

    await createApi(cfg).sendTyping('s-1');

    const [url, init] = f.mock.calls[0];
    expect(url).toBe('https://host/api/open/v1/sessions/s-1/typing');
    expect((init as RequestInit).method).toBe('POST');
    expect((init as any).headers['X-App-Id']).toBe('app_123');
  });

  it('sendTyping 网络失败静默不抛', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('network error')));

    await expect(createApi(cfg).sendTyping('s-1')).resolves.toBeUndefined();
  });

  it('getSession 非 2xx 返回 null', async () => {
    vi.stubGlobal('fetch', mockFetch(404, { error: { code: 404, message: 'not found' } }));

    await expect(createApi(cfg).getSession('s-1')).resolves.toBeNull();
  });

  it('getSession 网络失败返回 null（不抛）', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('network error')));

    await expect(createApi(cfg).getSession('s-1')).resolves.toBeNull();
  });

  it('getSession 成功返回解析后的会话数据', async () => {
    const body = {
      sessionId: 's-1',
      status: 'WAITING_AGENT',
      messages: [{ role: 'USER', content: 'hi', createdAt: '2026-07-28T00:00:00Z' }],
    };
    vi.stubGlobal('fetch', mockFetch(200, body));

    await expect(createApi(cfg).getSession('s-1')).resolves.toEqual(body);
  });
});
