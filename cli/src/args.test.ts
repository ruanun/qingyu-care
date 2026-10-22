// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { parseCliArgs } from './args';

/**
 * ⚠️ 两种身份的参数互斥必须**显式报错**而不是静默忽略其一：
 * 运维会以为自己在用某个视角、实际却是另一个，而这正是调试工具最该避免的误导
 * （两条鉴权路径看到的知识库不一样）。
 */
describe('parseCliArgs', () => {
  const base = ['--api-base', 'https://x.com/api', '--app-key', 'k1'];

  it('服务端身份：解析出 apiSecret', () => {
    const o = parseCliArgs([...base, '--api-secret', 's1']);
    expect(o).toEqual({ apiBase: 'https://x.com/api', appKey: 'k1', apiSecret: 's1' });
  });

  it('访客身份：解析出 origin 且不含 apiSecret', () => {
    const o = parseCliArgs([...base, '--as-visitor', '--origin', 'https://shop.com']);
    expect(o).toEqual({ apiBase: 'https://x.com/api', appKey: 'k1', origin: 'https://shop.com' });
  });

  it('同时给 --as-visitor 与 --api-secret 时报错', () => {
    expect(() => parseCliArgs([...base, '--as-visitor', '--origin', 'https://s.com', '--api-secret', 's1']))
      .toThrow(/不能同时/);
  });

  it('--as-visitor 缺 --origin 时报错', () => {
    expect(() => parseCliArgs([...base, '--as-visitor'])).toThrow(/origin/);
  });

  it('缺 --api-base 或 --app-key 时报错', () => {
    expect(() => parseCliArgs(['--app-key', 'k1'])).toThrow(/api-base/);
    expect(() => parseCliArgs(['--api-base', 'https://x.com/api'])).toThrow(/app-key/);
  });

  it('两种身份都没给时报错（不要静默退化成匿名）', () => {
    expect(() => parseCliArgs(base)).toThrow();
  });

  // 2026-09-30 安全审查：密钥放命令行参数会进 shell 历史与进程列表，支持从环境变量读
  it('未给 --api-secret 时从环境变量 QINGYU_API_SECRET 读取', () => {
    const o = parseCliArgs([...base], { QINGYU_API_SECRET: 'env-secret' });
    expect(o).toMatchObject({ apiSecret: 'env-secret' });
  });

  it('--api-secret 优先于环境变量；--as-visitor 时忽略环境变量而不报互斥', () => {
    expect(parseCliArgs([...base, '--api-secret', 'arg'], { QINGYU_API_SECRET: 'env' })).toMatchObject({ apiSecret: 'arg' });
    const v = parseCliArgs([...base, '--as-visitor', '--origin', 'https://shop.com'], { QINGYU_API_SECRET: 'env' });
    expect(v).not.toHaveProperty('apiSecret');
  });
});
