import { describe, it, expect, afterEach } from 'vitest';
import { resolveConfig } from './config';

function addScript(src: string): HTMLScriptElement {
  const s = document.createElement('script');
  s.src = src;
  document.body.appendChild(s);
  return s;
}

afterEach(() => {
  document.querySelectorAll('script').forEach((s) => s.remove());
});

describe('resolveConfig', () => {
  it('从 script src 的 ?appKey 解析出配置', () => {
    addScript('https://host.example.com/api/widget.js?appKey=app_123&v=1');
    const cfg = resolveConfig();
    expect(cfg).not.toBeNull();
    expect(cfg!.appKey).toBe('app_123');
    expect(cfg!.apiBase).toBe('https://host.example.com/api');
  });

  // 反代挂在子路径下：此前写死 origin + '/api'，请求全部打到域名根而 404，界面无任何提示
  it('脚本挂在子路径下时 apiBase 跟随脚本路径', () => {
    addScript('https://a.example.com/qingyu/api/widget.js?appKey=app_1');
    expect(resolveConfig()!.apiBase).toBe('https://a.example.com/qingyu/api');
  });

  it('显式 apiBase 参数优先（脚本放 CDN 时用），并去掉末尾斜杠', () => {
    addScript('https://cdn.example.net/assets/widget.js?appKey=app_1&apiBase=https%3A%2F%2Fcs.example.com%2Fapi%2F');
    expect(resolveConfig()!.apiBase).toBe('https://cs.example.com/api');
  });

  it('apiBase 不是 http(s) 时忽略并按脚本路径推导', () => {
    addScript('https://host.example.com/api/widget.js?appKey=app_1&apiBase=javascript%3Aalert(1)');
    expect(resolveConfig()!.apiBase).toBe('https://host.example.com/api');
  });

  it('脚本文件名不是 widget.js 时兜底 origin + /api', () => {
    // src 含 "widget.js" 才会被兜底查找命中，这里借查询串让它命中、而路径本身不以 /widget.js 结尾
    addScript('https://host.example.com/static/qingyu.js?widget.js=1&appKey=app_1');
    expect(resolveConfig()!.apiBase).toBe('https://host.example.com/api');
  });

  it('缺少 appKey 时返回 null', () => {
    addScript('https://host.example.com/api/widget.js');
    expect(resolveConfig()).toBeNull();
  });

  it('找不到脚本时返回 null', () => {
    expect(resolveConfig()).toBeNull();
  });
});
