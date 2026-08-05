import { describe, it, expect, vi } from 'vitest';
import { renderAttachments } from './attachments';

describe('renderAttachments', () => {
  it('文件名里的 HTML 不被执行', () => {
    const el = renderAttachments([
      { id: 1, fileName: '<img onerror=alert(1)>.png', contentType: 'application/pdf', fileSize: 100 },
    ]);
    // 文件名原样作为文本出现，且没有真的创建出 img 元素
    expect(el!.textContent).toContain('<img onerror=alert(1)>.png');
    expect(el!.querySelectorAll('img').length).toBe(0);
  });

  it('图片带宽高属性用于预留占位', () => {
    const el = renderAttachments([
      { id: 2, fileName: 'a.png', contentType: 'image/png', fileSize: 100, width: 800, height: 600 },
    ]);
    const img = el!.querySelector('img')!;
    expect(img.getAttribute('width')).toBe('800');
    expect(img.getAttribute('height')).toBe('600');
  });

  it('空列表返回 null', () => {
    expect(renderAttachments([])).toBeNull();
    expect(renderAttachments(undefined)).toBeNull();
  });

  it('webp 的 null 宽高不设置 width/height 属性（无解码器场景）', () => {
    const el = renderAttachments([
      { id: 3, fileName: 'b.webp', contentType: 'image/webp', fileSize: 100, width: null, height: null },
    ]);
    const img = el!.querySelector('img')!;
    expect(img.hasAttribute('width')).toBe(false);
    expect(img.hasAttribute('height')).toBe(false);
  });

  it('不传 resolveUrl 时只搭骨架，不发起任何网络请求', () => {
    const el = renderAttachments([
      { id: 4, fileName: 'c.png', contentType: 'image/png', fileSize: 100 },
    ]);
    const img = el!.querySelector('img')!;
    expect(img.getAttribute('src')).toBeNull();
  });

  it('图片 resolveUrl 解析成功后异步换成 blob URL', async () => {
    const resolveUrl = vi.fn().mockResolvedValue('blob:abc');
    const el = renderAttachments(
      [{ id: 5, fileName: 'd.png', contentType: 'image/png', fileSize: 100 }],
      resolveUrl,
    )!;
    await Promise.resolve();
    await Promise.resolve();
    expect(resolveUrl).toHaveBeenCalledWith(5);
    const img = el.querySelector('img')!;
    expect(img.src).toBe('blob:abc');
    const link = el.querySelector('a')!;
    expect(link.href).toBe('blob:abc');
  });

  it('图片 resolveUrl 失败时不抛、alt 标记加载失败', async () => {
    const resolveUrl = vi.fn().mockRejectedValue(new Error('401'));
    const el = renderAttachments(
      [{ id: 6, fileName: 'e.png', contentType: 'image/png', fileSize: 100 }],
      resolveUrl,
    )!;
    await Promise.resolve();
    await Promise.resolve();
    const img = el.querySelector('img')!;
    expect(img.alt).toContain('加载失败');
  });

  it('图片换源前点击被拦下（不跳去占位 #），换源后点击放行', async () => {
    let resolveFn!: (url: string) => void;
    const resolveUrl = vi.fn(() => new Promise<string>((r) => { resolveFn = r; }));
    const el = renderAttachments(
      [{ id: 13, fileName: 'h.png', contentType: 'image/png', fileSize: 100 }],
      resolveUrl,
    )!;
    const link = el.querySelector('a.qy-attach-img-link') as HTMLAnchorElement;

    const early = new MouseEvent('click', { cancelable: true });
    link.dispatchEvent(early);
    expect(early.defaultPrevented).toBe(true); // 换源前点击：拦下，不跳去占位 '#'

    resolveFn('blob:img2');
    await Promise.resolve();
    await Promise.resolve();

    const late = new MouseEvent('click', { cancelable: true });
    link.dispatchEvent(late);
    expect(late.defaultPrevented).toBe(false); // 换源后点击：放行，走原生 <a target=_blank>
  });

  it('文件条点击阻止默认导航并触发 resolveUrl 下载', async () => {
    const resolveUrl = vi.fn().mockResolvedValue('blob:file');
    const el = renderAttachments(
      [{ id: 7, fileName: 'd.pdf', contentType: 'application/pdf', fileSize: 100 }],
      resolveUrl,
    )!;
    const link = el.querySelector('a.qy-attach-file') as HTMLAnchorElement;
    const evt = new MouseEvent('click', { cancelable: true });
    link.dispatchEvent(evt);
    expect(evt.defaultPrevented).toBe(true);
    await Promise.resolve();
    expect(resolveUrl).toHaveBeenCalledWith(7);
  });

  it('文件下载触发后立即释放 blob URL，不泄漏', async () => {
    const revokeSpy = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {});
    const resolveUrl = vi.fn().mockResolvedValue('blob:file2');
    const el = renderAttachments(
      [{ id: 12, fileName: 'g.pdf', contentType: 'application/pdf', fileSize: 100 }],
      resolveUrl,
    )!;
    const link = el.querySelector('a.qy-attach-file') as HTMLAnchorElement;
    link.dispatchEvent(new MouseEvent('click', { cancelable: true }));
    await Promise.resolve();
    await Promise.resolve();
    expect(revokeSpy).toHaveBeenCalledWith('blob:file2');
    revokeSpy.mockRestore();
  });

  it('文件条无 resolveUrl 时点击不抛（无副作用）', () => {
    const el = renderAttachments([
      { id: 8, fileName: 'f.pdf', contentType: 'application/pdf', fileSize: 100 },
    ]);
    const link = el!.querySelector('a.qy-attach-file') as HTMLAnchorElement;
    expect(() => link.dispatchEvent(new MouseEvent('click'))).not.toThrow();
  });

  it('文件体积按 B/KB/MB 分档展示', () => {
    const el = renderAttachments([
      { id: 9, fileName: 'g.zip', contentType: 'application/zip', fileSize: 500 },
      { id: 10, fileName: 'h.zip', contentType: 'application/zip', fileSize: 2048 },
      { id: 11, fileName: 'i.zip', contentType: 'application/zip', fileSize: 3 * 1024 * 1024 },
    ]);
    const sizes = Array.from(el!.querySelectorAll('.qy-attach-size')).map((n) => n.textContent);
    expect(sizes).toEqual(['500 B', '2.0 KB', '3.0 MB']);
  });
});
