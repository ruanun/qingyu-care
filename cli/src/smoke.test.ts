// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { safeUrl } from '../../widget/src/core/safeUrl';
import { createCoreApi } from '../../widget/src/core/api';

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

  /**
   * 只 import safeUrl 挡不住 core 里其它文件反向依赖 widget 层——safeUrl.ts 本身零依赖，
   * 编译得过不代表 core 整体干净。core/api.ts 是本次修复的对象（曾反向 import
   * widget/src/attachments.ts），换它做冒烟对象才真正覆盖到「core 不得 import widget 层」
   * 这条约束：只要有一处反向依赖没清干净，`cd cli && pnpm build` 就会把整个 widget UI 层
   * （document/HTMLElement 等 DOM API）一并纳入编译图——见 widget/src/core/platform.ts
   * 顶部注释里 `tsc --listFilesOnly` 的实测记录。
   */
  it('import core/api 得到真实的 createCoreApi 工厂函数', () => {
    expect(typeof createCoreApi).toBe('function');
    const api = createCoreApi({ apiBase: 'https://x.com/api', appKey: 'k1' });
    expect(typeof api.getConfig).toBe('function');
    expect(typeof api.stream).toBe('function');
  });
});
