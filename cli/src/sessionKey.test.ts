// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { computeSessionKey } from './sessionKey';
import type { CliOptions } from './args';

/**
 * ⚠️ 身份必须进键：真机实测复现过用 `--as-visitor` 启动会续上服务端身份那次的会话
 * （两种身份能看到的知识库范围不同，拿错视角的上下文去归因是这个调试工具最该避免的事）。
 */
describe('computeSessionKey', () => {
  const server: CliOptions = { apiBase: 'https://x.com/api', appKey: 'k1', apiSecret: 's1' };
  const visitor: CliOptions = { apiBase: 'https://x.com/api', appKey: 'k1', origin: 'https://shop.com' };

  it('服务端与访客身份得到不同的键（同 apiBase/appKey）', () => {
    expect(computeSessionKey(server)).not.toBe(computeSessionKey(visitor));
  });

  it('不同 origin 的访客键也不同', () => {
    const a: CliOptions = { ...visitor, origin: 'https://shop.com' };
    const b: CliOptions = { ...visitor, origin: 'https://other.com' };
    expect(computeSessionKey(a)).not.toBe(computeSessionKey(b));
  });

  it('相同参数得到的键稳定（多次调用一致）', () => {
    expect(computeSessionKey(server)).toBe(computeSessionKey(server));
    expect(computeSessionKey(visitor)).toBe(computeSessionKey(visitor));
  });

  it('服务端键不受 origin 字段影响（服务端身份忽略 origin）', () => {
    const withOrigin: CliOptions = { ...server, origin: 'https://shop.com' };
    expect(computeSessionKey(server)).toBe(computeSessionKey(withOrigin));
  });
});
