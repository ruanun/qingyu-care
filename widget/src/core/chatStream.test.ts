// @vitest-environment node
import { describe, it, expect, vi, afterEach } from 'vitest';
import { postSse } from './chatStream';
import { createCoreApi, SessionExpiredError } from './api';

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
    const api = createCoreApi({ appKey: 'app_123', apiBase: 'https://host/api' });

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

  /**
   * 已读回执依赖 userMessageId 事后回填访客自己那条消息的气泡。裁定：START 有就先用，
   * 没有就等 DONE——主问答路径 START.userMessageId 恒为 null，DONE 排在落库之后必带真实值。
   */
  it('START 携带 userMessageId 时透传给 onStart', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(mockSseResponse([
      'event:start\ndata: {"sessionId":"s1","suggestHandover":false,"knowledgeHits":[],"userMessageId":7}\n\n',
      'event:done\ndata: {"messageId":9,"userMessageId":7}\n\n',
    ])));
    let uid: number | null | undefined;

    await postSse('/x', {}, {}, { onStart: (d) => { uid = d.userMessageId; } });

    expect(uid).toBe(7);
  });

  it('START 的 userMessageId 缺省时 onStart 拿到 undefined（主问答路径已知限制）', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(mockSseResponse([
      'event:start\ndata: {"sessionId":"s1","suggestHandover":false,"knowledgeHits":[]}\n\n',
      'event:done\ndata: {"messageId":9,"userMessageId":8}\n\n',
    ])));
    let uid: number | null | undefined = 'unset' as unknown as number;

    await postSse('/x', {}, {}, { onStart: (d) => { uid = d.userMessageId; } });

    expect(uid).toBeUndefined();
  });

  it('DONE 同时携带 messageId 与 userMessageId 时都透传给 onDone', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(mockSseResponse([
      'event:done\ndata: {"messageId":9,"userMessageId":8}\n\n',
    ])));
    let mid: number | null = null;
    let uid: number | null = null;

    await postSse('/x', {}, {}, { onDone: (m, u) => { mid = m; uid = u; } });

    expect(mid).toBe(9);
    expect(uid).toBe(8);
  });

  it('DONE 未落库（半截回复/取消）时 messageId 与 userMessageId 均为 null', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(mockSseResponse([
      'event:done\ndata: {"messageId":null,"userMessageId":null}\n\n',
    ])));
    let mid: number | null = 0 as unknown as number;
    let uid: number | null = 0 as unknown as number;

    await postSse('/x', {}, {}, { onDone: (m, u) => { mid = m; uid = u; } });

    expect(mid).toBeNull();
    expect(uid).toBeNull();
  });
});
