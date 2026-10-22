export interface WidgetConfig {
  appKey: string;
  /** 开放 API 基地址，如 https://host/api */
  apiBase: string;
}

/**
 * 从加载本脚本的 <script> 标签解析 appKey，并推导开放 API 基地址。
 * 优先 document.currentScript；兜底按 src 含 "widget.js" 查找（应对 async/defer）。
 * 缺 appKey 时告警并返回 null（fail silently，不渲染）。
 *
 * apiBase 的推导顺序：
 * 1. 显式参数 `?apiBase=`：脚本放在 CDN 等与后端不同源的地方时用；
 * 2. 脚本 URL 去掉末尾的 `/widget.js`：脚本本就由后端在 `{apiBase}/widget.js` 提供，
 *    反代挂在子路径下（如 `https://a.com/qingyu/api/widget.js`）时自动得到正确的前缀；
 * 3. 兜底 `origin + '/api'`。
 * 此前一律写死第 3 种，挂在子路径下时所有请求都打到域名根的 /api，全部 404 且界面无任何提示。
 */
export function resolveConfig(): WidgetConfig | null {
  const script =
    (document.currentScript as HTMLScriptElement | null) ??
    document.querySelector<HTMLScriptElement>('script[src*="widget.js"]');
  if (!script || !script.src) {
    console.warn('[Qingyu Widget] 未找到加载脚本，无法初始化');
    return null;
  }
  const url = new URL(script.src);
  const appKey = url.searchParams.get('appKey');
  if (!appKey) {
    console.warn('[Qingyu Widget] 缺少 appKey，无法初始化');
    return null;
  }
  return { appKey, apiBase: resolveApiBase(url) };
}

function resolveApiBase(scriptUrl: URL): string {
  const explicit = scriptUrl.searchParams.get('apiBase');
  if (explicit) {
    try {
      const u = new URL(explicit, scriptUrl);
      if (u.protocol === 'https:' || u.protocol === 'http:') {
        return (u.origin + u.pathname).replace(/\/+$/, '');
      }
    } catch {
      // 非法值落到下面的推导，并提示一次
    }
    console.warn('[Qingyu Widget] apiBase 参数不是合法的 http(s) 地址，已忽略');
  }
  const path = scriptUrl.pathname;
  if (path.endsWith('/widget.js')) {
    return scriptUrl.origin + path.slice(0, -'/widget.js'.length);
  }
  return scriptUrl.origin + '/api';
}
