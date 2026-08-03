import { describe, it, expect, vi, afterEach } from 'vitest';
import { postSse } from './chatStream';
import { createApi, SessionExpiredError } from './api';

/** 用可控的 ReadableStream 伪造一个 SSE 响应 */
function mockSseResponse(chunks: string[], status = 200) {
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      const enc = new TextEncoder();
      for (const c of chunks) controller.enqueue(enc.encode(c));
      controller.close();
    },
  });
  return new Response(status === 200 ? body : null, { status });
}

afterEach(() => vi.unstubAllGlobals());

describe('postSse', () => {
  it('按事件类型分派 start/delta/card/done', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(mockSseResponse([
      'event:start\ndata: {"sessionId":"s1","suggestHandover":false,"knowledgeHits":[]}\n\n',
      'event:delta\ndata: {"text":"你好"}\n\n',
      'event:card\ndata: {"node":{"type":"card"}}\n\n',
      'event:done\ndata: {"messageId":9}\n\n',
    ])));
    const seen: string[] = [];
    let sid = '';
    let mid: number | null = null;

    await postSse('/x', {}, {}, {
      onStart: (d) => { sid = d.sessionId; seen.push('start'); },
      onDelta: (t) => seen.push('delta:' + t),
      onCard: () => seen.push('card'),
      onDone: (m) => { mid = m; seen.push('done'); },
    });

    expect(seen).toEqual(['start', 'delta:你好', 'card', 'done']);
    expect(sid).toBe('s1');
    expect(mid).toBe(9);
  });

  it('跨 chunk 切断的事件块能正确拼回', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(mockSseResponse([
      'event:del', 'ta\ndata: {"te', 'xt":"拼回来了"}\n\n',
      'event:done\ndata: {"messageId":1}\n\n',
    ])));
    const texts: string[] = [];

    await postSse('/x', {}, {}, { onDelta: (t) => texts.push(t) });

    expect(texts).toEqual(['拼回来了']);
  });

  it('流未以 done 结束时报告中断', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(mockSseResponse([
      'event:delta\ndata: {"text":"半截"}\n\n',
    ])));
    const errors: string[] = [];

    await postSse('/x', {}, {}, { onDelta: () => {}, onError: (m) => errors.push(m) });

    expect(errors).toHaveLength(1);
  });

  it('后端 error 事件透传文案且不再报中断', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(mockSseResponse([
      'event:error\ndata: {"message":"回复生成失败，请稍后重试"}\n\n',
    ])));
    const errors: string[] = [];

    await postSse('/x', {}, {}, { onError: (m) => errors.push(m) });

    expect(errors).toEqual(['回复生成失败，请稍后重试']);
  });

  it('非法 JSON 负载被忽略且不中断整条流', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(mockSseResponse([
      'event:delta\ndata: 这不是JSON\n\n',
      'event:delta\ndata: {"text":"正常"}\n\n',
      'event:done\ndata: {"messageId":2}\n\n',
    ])));
    const texts: string[] = [];

    await postSse('/x', {}, {}, { onDelta: (t) => texts.push(t) });

    expect(texts).toEqual(['正常']);
  });

  it('404 时经 api.stream() 抛出完整链路上的 SessionExpiredError', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(mockSseResponse([], 404)));
    const api = createApi({ appKey: 'app_123', apiBase: 'https://host/api' });

    await expect(api.stream('hi', 's-1', {})).rejects.toBeInstanceOf(SessionExpiredError);
  });

  it('非 404 的 HTTP 失败（如 500）走 onError 且文案不泄露状态码/响应体', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(mockSseResponse([], 500)));
    const errors: string[] = [];

    await postSse('/x', {}, {}, { onError: (m) => errors.push(m) });

    expect(errors).toHaveLength(1);
    expect(errors[0]).not.toMatch(/500/);
    expect(errors[0]).toBe('抱歉，服务暂时不可用，请稍后再试。');
  });
});
