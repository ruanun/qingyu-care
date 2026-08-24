import { describe, it, expect, vi, afterEach } from 'vitest';
import { createApi } from './api';

// 协议部分（getConfig/feedback/inboxUrl/sendTyping/getSession/stream/markRead）的行为覆盖仍在
// 这里——createApi 只是在 core 的 createCoreApi 之上叠了两个附件方法，端点行为未变。
// 新增的鉴权头构造能力（apiSecret/origin）测试见 core/api.test.ts，此处不重复。

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

  it('stream 携带 attachmentIds 时写进请求体', async () => {
    const enc = new TextEncoder();
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(enc.encode('event:done\ndata: {"messageId":1}\n\n'));
        controller.close();
      },
    });
    const f = vi.fn().mockResolvedValue(new Response(stream, { status: 200 }));
    vi.stubGlobal('fetch', f);

    await createApi(cfg).stream('看图', 's-1', {}, [10, 11]);

    const [, init] = f.mock.calls[0];
    const sent = JSON.parse((init as RequestInit).body as string);
    expect(sent).toEqual({ message: '看图', sessionId: 's-1', attachmentIds: [10, 11] });
  });

  it('stream 不带附件时请求体不含 attachmentIds 字段', async () => {
    const enc = new TextEncoder();
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(enc.encode('event:done\ndata: {"messageId":1}\n\n'));
        controller.close();
      },
    });
    const f = vi.fn().mockResolvedValue(new Response(stream, { status: 200 }));
    vi.stubGlobal('fetch', f);

    await createApi(cfg).stream('hi', null, {});

    const [, init] = f.mock.calls[0];
    const sent = JSON.parse((init as RequestInit).body as string);
    expect(sent).toEqual({ message: 'hi' });
  });

  it('uploadAttachment 用 FormData 携带 sessionId+file，且不手工设 Content-Type', async () => {
    const f = mockFetch(200, { id: 1, fileName: 'a.png', contentType: 'image/png', fileSize: 100 });
    vi.stubGlobal('fetch', f);

    const file = new File(['x'], 'a.png', { type: 'image/png' });
    const result = await createApi(cfg).uploadAttachment('s-1', file);

    expect(result.id).toBe(1);
    const [url, init] = f.mock.calls[0];
    expect(url).toBe('https://host/api/open/v1/chat/files');
    expect((init as RequestInit).body).toBeInstanceOf(FormData);
    expect((init as any).headers['Content-Type']).toBeUndefined();
    expect((init as any).headers['X-App-Id']).toBe('app_123');
  });

  it('uploadAttachment 非 2xx 抛错', async () => {
    vi.stubGlobal('fetch', mockFetch(400, { error: 'bad' }));

    await expect(createApi(cfg).uploadAttachment('s-1', new File(['x'], 'a.png'))).rejects.toThrow();
  });

  it('downloadAttachment 带 sessionId 查询参数与 X-App-Id 头，返回 blob', async () => {
    const blob = new Blob(['x']);
    const f = vi.fn().mockResolvedValue({ ok: true, status: 200, blob: async () => blob });
    vi.stubGlobal('fetch', f);

    const result = await createApi(cfg).downloadAttachment(5, 's-1');

    expect(result).toBe(blob);
    const [url, init] = f.mock.calls[0];
    expect(url).toBe('https://host/api/open/v1/chat/files/5?sessionId=s-1');
    expect((init as any).headers['X-App-Id']).toBe('app_123');
  });

  it('downloadAttachment 非 2xx 抛错', async () => {
    vi.stubGlobal('fetch', mockFetch(404, {}));

    await expect(createApi(cfg).downloadAttachment(5, 's-1')).rejects.toThrow();
  });

  it('markRead POST 到 /read 端点，请求体带 upToMessageId', async () => {
    const f = mockFetch(200, { code: 200, message: 'ok', data: 42 });
    vi.stubGlobal('fetch', f);

    await createApi(cfg).markRead('s-1', 42);

    const [url, init] = f.mock.calls[0];
    expect(url).toBe('https://host/api/open/v1/sessions/s-1/read');
    expect((init as RequestInit).method).toBe('POST');
    expect(JSON.parse((init as RequestInit).body as string)).toEqual({ upToMessageId: 42 });
    expect((init as any).headers['X-App-Id']).toBe('app_123');
  });

  it('markRead 非 2xx 时 fail-soft 不抛（已读回执是锦上添花，不该打断对话）', async () => {
    vi.stubGlobal('fetch', mockFetch(500, {}));

    await expect(createApi(cfg).markRead('s-1', 42)).resolves.toBeUndefined();
  });

  it('markRead 网络失败静默不抛（fail-soft）', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('network error')));

    await expect(createApi(cfg).markRead('s-1', 42)).resolves.toBeUndefined();
  });
});
