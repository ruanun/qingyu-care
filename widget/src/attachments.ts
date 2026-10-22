/**
 * @author ruan
 * 消息附件的纯 DOM 渲染。widget 跑在客户网站页面里，文件名是用户输入，
 * 全程 textContent，不用 innerHTML——这是本仓库 widget 侧的铁律。
 *
 * 后端 AttachmentDTO 不带 url（两条下载端点 GET /open/v1/chat/files/{id} 都是请求头
 * （X-App-Id）+ sessionId 查询参数鉴权，浏览器原生 <img src>/<a href> 不会带自定义头）。
 * 真正的图片预览 / 文件下载都要经调用方注入的 resolveUrl 回调发起带头 fetch 换成
 * blob URL，这里只负责同步搭好 DOM 骨架（含图片宽高占位，避免加载完成前的布局跳动）
 * + 异步换源；resolveUrl 缺省时只搭骨架，不发起任何网络请求。
 *
 * WidgetAttachment（纯数据，无 DOM 依赖）定义已下沉到 core/attachments.ts——
 * core/api.ts 的 OpenSessionResponse 与 core/history.ts 都要用它，此处重导出保持既有导入路径可用。
 */

import type { WidgetAttachment } from './core/attachments';
export type { WidgetAttachment } from './core/attachments';

/** 按附件 id 取二进制并返回可用于 src/下载的 blob URL；失败时 reject。 */
export type ResolveAttachmentUrl = (id: number) => Promise<string>;

function humanSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

/**
 * 渲染一组附件：图片内嵌预览（a 包 img，点开新标签查看原图），其余渲染为文件条
 * （点击触发鉴权下载）。空列表/undefined 返回 null（调用方据此决定是否插入容器）。
 */
export function renderAttachments(
  items?: WidgetAttachment[],
  resolveUrl?: ResolveAttachmentUrl,
): HTMLElement | null {
  if (!items || items.length === 0) return null;

  const box = document.createElement('div');
  box.className = 'qy-attachments';

  for (const a of items) {
    box.appendChild(
      a.contentType.startsWith('image/') ? renderImage(a, resolveUrl) : renderFile(a, resolveUrl),
    );
  }
  return box;
}

function renderImage(a: WidgetAttachment, resolveUrl?: ResolveAttachmentUrl): HTMLElement {
  const link = document.createElement('a');
  link.className = 'qy-attach-img-link';
  link.target = '_blank';
  link.rel = 'noopener noreferrer';
  link.href = '#'; // 换源前的占位；resolveUrl 成功后改指向 blob URL

  const img = document.createElement('img');
  img.className = 'qy-attach-img';
  img.alt = a.fileName; // 用户输入，走属性赋值不拼 HTML
  if (a.width) img.setAttribute('width', String(a.width));
  if (a.height) img.setAttribute('height', String(a.height));
  link.appendChild(img);

  if (resolveUrl) {
    // 换源前 href 还是占位的 '#'；用户在 fetch 完成前点"新标签查看原图"会拦下这次点击
    // （不让它跳去 '#'），换源后 href 已是真 blob URL，后续点击走原生 <a target=_blank>
    // 行为放行，不用 window.open 补触发——那是从 fetch 回调里发起的非直接用户手势，
    // 大概率被浏览器弹窗拦截器挡掉，体验反而更差；影响面很小（早点一次不生效，再点一次即可）。
    let resolved = false;
    link.addEventListener('click', (e) => {
      if (!resolved) e.preventDefault();
    });
    resolveUrl(a.id)
      .then((blobUrl) => {
        resolved = true;
        img.src = blobUrl;
        link.href = blobUrl;
      })
      .catch(() => {
        img.alt = `${a.fileName}（加载失败）`;
      });
  }
  return link;
}

function renderFile(a: WidgetAttachment, resolveUrl?: ResolveAttachmentUrl): HTMLElement {
  const link = document.createElement('a');
  link.className = 'qy-attach-file';
  link.href = '#';

  const name = document.createElement('span');
  name.className = 'qy-attach-name';
  name.textContent = a.fileName; // 用户输入，必须 textContent
  const size = document.createElement('span');
  size.className = 'qy-attach-size';
  size.textContent = humanSize(a.fileSize);
  link.appendChild(name);
  link.appendChild(size);

  if (resolveUrl) {
    link.addEventListener('click', (e) => {
      e.preventDefault(); // href="#" 只是占位，真正下载走鉴权 fetch
      resolveUrl(a.id)
        .then((blobUrl) => {
          const tmp = document.createElement('a');
          tmp.href = blobUrl;
          tmp.download = a.fileName;
          tmp.click();
          // 临时锚点用完即弃，立即释放——不像图片预览的 blob 还要继续挂在 <img>/新标签链接上
          // 显示，这份 blob 触发下载后再无用途，不释放就是纯泄漏（反复点同一份文件下载会
          // 累积到标签页关闭才回收）。
          URL.revokeObjectURL(blobUrl);
        })
        .catch(() => {
          // 下载失败静默：不打断对话，用户可重新点击重试
        });
    });
  }
  return link;
}
