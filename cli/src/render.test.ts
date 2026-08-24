// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { formatCard, formatDone, formatHits } from './render';

describe('formatHits', () => {
  it('按分数顺序列出命中条数与分数', () => {
    const out = formatHits([
      { content: 'a', score: 0.7234, source: '退货政策.md' },
      { content: 'b', score: 0.6812, source: 'FAQ.md' },
    ]);
    expect(out).toContain('命中 2 条');
    expect(out).toContain('0.72');
    expect(out).toContain('0.68');
  });

  it('没有命中时明确说没有，而不是返回空字符串', () => {
    // 「没检索到」本身就是排查时最要紧的信号，不能让它看起来像没输出
    expect(formatHits([])).toContain('命中 0 条');
  });

  it('分数缺失时不显示 NaN', () => {
    const out = formatHits([{ content: 'a', score: undefined as unknown as number, source: 'x.md' }]);
    expect(out).not.toContain('NaN');
  });
});

describe('formatDone', () => {
  it('带出 messageId 与转人工建议', () => {
    const out = formatDone({ messageId: 123, suggestHandover: true });
    expect(out).toContain('123');
    expect(out).toContain('是');
  });
});

describe('formatCard', () => {
  it('原样打印 ui_spec 的 JSON（运维要的就是它长什么样）', () => {
    const out = formatCard({ type: 'card', title: 'x' });
    expect(out).toContain('"type"');
    expect(out).toContain('card');
  });
});
