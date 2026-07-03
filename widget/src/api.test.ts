import { describe, it, expect, vi, afterEach } from 'vitest';
import { createApi, SessionExpiredError } from './api';

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
  it('send 带 X-App-Id 头与消息体，新会话不带 sessionId', async () => {
    const f = mockFetch(200, { sessionId: 's-1', reply: '你好', handoverSuggested: false });
    vi.stubGlobal('fetch', f);

    const reply = await createApi(cfg).send('hi', null);

    expect(reply.sessionId).toBe('s-1');
    const [url, init] = f.mock.calls[0];
    expect(url).toBe('https://host/api/open/v1/chat/completions');
    expect((init as RequestInit).method).toBe('POST');
    expect((init as any).headers['X-App-Id']).toBe('app_123');
    expect(JSON.parse((init as any).body)).toEqual({ message: 'hi' });
  });

  it('send 带 sessionId 时请求体包含它', async () => {
    const f = mockFetch(200, { sessionId: 's-1', reply: 'ok', handoverSuggested: false });
    vi.stubGlobal('fetch', f);

    await createApi(cfg).send('hi', 's-1');

    expect(JSON.parse((f.mock.calls[0][1] as any).body)).toEqual({ message: 'hi', sessionId: 's-1' });
  });

  it('send 收到 404 抛 SessionExpiredError', async () => {
    vi.stubGlobal('fetch', mockFetch(404, { error: { code: 404, message: '会话不存在或已过期' } }));

    await expect(createApi(cfg).send('hi', 's-x')).rejects.toBeInstanceOf(SessionExpiredError);
  });

  it('getConfig 请求 widget/config 端点', async () => {
    const f = mockFetch(200, { appName: 'A', welcomeMessage: 'hi', avatar: '', themeColor: '#000' });
    vi.stubGlobal('fetch', f);

    const brand = await createApi(cfg).getConfig();

    expect(brand.appName).toBe('A');
    expect(f.mock.calls[0][0]).toBe('https://host/api/open/v1/widget/config');
  });
});
