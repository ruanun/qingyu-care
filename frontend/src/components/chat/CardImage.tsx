import { useAppStore } from '@/stores/appStore';
import { isImageHostAllowed, parseImageDomains } from '@/lib/imagePolicy';

/**
 * 卡片图片：只有命中当前应用配置的图片域名才自动加载，否则显示为需要主动点击的链接（2026-09-30 安全审查 M7）。
 * 组件化是因为要读应用设置，而 renderNode 是普通函数、用不了 hook。
 *
 * @author ruan
 */
export default function CardImage({ src, alt }: { src: string; alt: string }) {
  // 只选原始字符串：选择器每次返回新数组会让 useSyncExternalStore 判定快照一直在变、无限重渲染
  const raw = useAppStore((s) => s.apps.find((a) => a.id === s.currentApp?.id)?.imageAllowedDomains);
  if (!isImageHostAllowed(src, parseImageDomains(raw))) {
    return (
      <a href={src} target="_blank" rel="noopener noreferrer" className="text-sm underline underline-offset-2 break-all">
        查看图片{alt ? `：${alt}` : ''}
      </a>
    );
  }
  return <img src={src} alt={alt} referrerPolicy="no-referrer" className="max-w-full rounded" />;
}
