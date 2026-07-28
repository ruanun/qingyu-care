import { describe, it, expect } from 'vitest';
import { parseSseChunk } from './sse';

describe('parseSseChunk', () => {
  it('解析命名事件 + data', () => {
    expect(parseSseChunk('event: message\ndata: {"a":1}')).toEqual({ event: 'message', data: '{"a":1}' });
  });
  it('无 event 行默认 message', () => {
    expect(parseSseChunk('data: hi')).toEqual({ event: 'message', data: 'hi' });
  });
  it('注释/心跳行(无 data)返回 data=null', () => {
    expect(parseSseChunk(': ping')).toEqual({ event: 'message', data: null });
  });
  it('多行 data 拼接', () => {
    expect(parseSseChunk('data: a\ndata: b')).toEqual({ event: 'message', data: 'a\nb' });
  });
});
