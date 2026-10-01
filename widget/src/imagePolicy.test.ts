import { describe, it, expect } from 'vitest';
import { isImageHostAllowed } from './imagePolicy';

describe('isImageHostAllowed（安全审查 M7）', () => {
  it('精确匹配主机名，大小写不敏感', () => {
    expect(isImageHostAllowed('https://IMG.shop.com/a.png', ['img.shop.com'])).toBe(true);
    expect(isImageHostAllowed('https://img.shop.com.evil.com/a.png', ['img.shop.com'])).toBe(false);
  });
  it('*.example.com 只匹配子域，不匹配本身与仿冒后缀', () => {
    expect(isImageHostAllowed('https://cdn.shop.com/a.png', ['*.shop.com'])).toBe(true);
    expect(isImageHostAllowed('https://a.b.shop.com/a.png', ['*.shop.com'])).toBe(true);
    expect(isImageHostAllowed('https://shop.com/a.png', ['*.shop.com'])).toBe(false);
    expect(isImageHostAllowed('https://evilshop.com/a.png', ['*.shop.com'])).toBe(false);
  });
  it('解析失败或白名单为空一律不放行', () => {
    expect(isImageHostAllowed('not a url', ['shop.com'])).toBe(false);
    expect(isImageHostAllowed('https://shop.com/a.png', [])).toBe(false);
  });
});
