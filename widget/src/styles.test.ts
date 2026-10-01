import { describe, it, expect } from 'vitest';
import { buildStyles } from './styles';

describe('buildStyles 主题色（2026-09-30 安全审查）', () => {
  it('合法十六进制颜色原样使用', () => {
    expect(buildStyles('#123abc')).toContain('#123abc');
  });

  // themeColor 原样拼进 <style>：不校验时可注入任意 CSS，在宿主页铺覆盖层或用 url() 追踪访客
  it('非十六进制颜色回退默认色，注入的 CSS 不出现在样式里', () => {
    const css = buildStyles('red} .qy-panel{position:fixed;inset:0;background:url(https://evil.example/t)');
    expect(css).not.toContain('evil.example');
    expect(css).not.toContain('position:fixed;inset:0');
    expect(css).toContain('#0d9488');
  });

  it('空值回退默认色', () => {
    expect(buildStyles(undefined as unknown as string)).toContain('#0d9488');
  });
});
