export interface WidgetConfig {
  appKey: string;
  /** 开放 API 基地址，如 https://host/api */
  apiBase: string;
}

/**
 * 从加载本脚本的 <script> 标签解析 appKey，并推导开放 API 基地址。
 * 优先 document.currentScript；兜底按 src 含 "widget.js" 查找（应对 async/defer）。
 * 缺 appKey 时告警并返回 null（fail silently，不渲染）。
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
  return { appKey, apiBase: url.origin + '/api' };
}
