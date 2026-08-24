// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { safeUrl } from '../../widget/src/core/safeUrl';

/**
 * 这不是在测 safeUrl（它有自己的测试），而是在测**构建配置**：
 * CLI 作为独立子项目，能否 import 位于 widget 目录下的 core 源码。
 *
 * 这是本次抽取唯一一条没有先例的构建配置。它一旦不成立，core 的位置就要重新定，
 * 所以把验证放在最前面，而不是等 CLI 写完才发现解析不了。
 *
 * @author ruan
 */
describe('CLI 能消费 widget/src/core 的源码', () => {
  it('import 得到的是真实实现而不是 undefined', () => {
    expect(typeof safeUrl).toBe('function');
    expect(safeUrl('https://example.com')).toBe('https://example.com');
    // 实测 widget/src/core/safeUrl.ts 对非法协议返回空串而非 null，按实现调整断言。
    expect(safeUrl('javascript:alert(1)')).toBe('');
  });
});
