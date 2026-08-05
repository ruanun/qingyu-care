// frontend/src/components/chat/Attachments.tsx
// 消息附件渲染（图片内嵌预览 + 其余文件条）。
//
// ⚠️ 不复用卡片的 image 组件：safeUrl 白名单只放行 http:/https:，而附件走的是
// 请求头鉴权下载端点（GET /v1/chat/files/{id}），客户端要先 fetch 出 blob 再
// URL.createObjectURL，得到的是 blob: 协议——会被 safeUrl fail-closed 拒掉、
// 图片静默不显示。且语义不同：卡片 image 的 URL 是 LLM 生成的不可信输入才需要
// 白名单，附件 id 是我们自己签发的，本就不需要那道防线。
//
// 文件名（fileName）是用户输入，靠 React 默认转义防 XSS——不要改用 dangerouslySetInnerHTML。
//
// @author ruan

import { useEffect, useState } from 'react';
import { Loader2, Paperclip, X } from 'lucide-react';
import type { Attachment } from '@/types';
import { chatService } from '@/api/chat';
import { cn } from '@/lib/utils';

/** 字节数转人类可读 */
function humanSize(bytes: number): string {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

/**
 * 图片附件：鉴权 fetch blob → object URL。组件卸载/附件切换时 revoke，避免内存泄漏。
 * width/height 对 webp 恒为 null（JDK ImageIO 无解码器），加载中占位退化为固定尺寸。
 */
function ImageAttachment({ a, compact }: { a: Attachment; compact?: boolean }) {
    const [state, setState] = useState<'loading' | 'ready' | 'error'>('loading');
    const [src, setSrc] = useState<string | null>(null);

    useEffect(() => {
        // 不在此处同步重置 state（react-hooks/set-state-in-effect 会报错）：
        // 父级用 key={a.id} 包裹每个附件项，a.id 变化时 React 会整个重挂载出全新实例，
        // 初始 state（loading/null）天然就是正确起点，无需手动重置。
        let cancelled = false;
        let objectUrl: string | null = null;
        chatService.downloadAttachment(a.id)
            .then((blob) => {
                if (cancelled) return;
                objectUrl = URL.createObjectURL(blob);
                setSrc(objectUrl);
                setState('ready');
            })
            .catch(() => {
                if (!cancelled) setState('error');
            });
        return () => {
            cancelled = true;
            if (objectUrl) URL.revokeObjectURL(objectUrl);
        };
    }, [a.id]);

    const boxClass = cn('rounded border object-contain bg-muted', compact ? 'w-20 h-20' : 'max-w-xs max-h-64');

    if (state !== 'ready' || !src) {
        return (
            <div
                className={cn(boxClass, 'flex items-center justify-center text-muted-foreground')}
                style={compact ? undefined : { width: a.width ?? 160, height: a.height ?? 120 }}
                title={a.fileName}
            >
                {state === 'error' ? <span className="text-[10px]">加载失败</span> : <Loader2 className="w-4 h-4 animate-spin" />}
            </div>
        );
    }

    return (
        <a href={src} target="_blank" rel="noopener noreferrer" download={a.fileName} title={a.fileName}>
            <img
                src={src}
                alt={a.fileName}
                width={a.width ?? undefined}
                height={a.height ?? undefined}
                className={boxClass}
            />
        </a>
    );
}

/** 非图片附件：点击时才鉴权拉取 blob 并触发浏览器下载，不预取。 */
function FileAttachment({ a, compact }: { a: Attachment; compact?: boolean }) {
    const [busy, setBusy] = useState(false);

    const handleDownload = async () => {
        setBusy(true);
        try {
            const blob = await chatService.downloadAttachment(a.id);
            const url = URL.createObjectURL(blob);
            const link = document.createElement('a');
            link.href = url;
            link.download = a.fileName;
            link.click();
            URL.revokeObjectURL(url);
        } catch {
            // 静默失败：下载失败不阻断页面，用户可重试点击
        } finally {
            setBusy(false);
        }
    };

    return (
        <button
            type="button"
            onClick={handleDownload}
            disabled={busy}
            className={cn(
                'flex items-center gap-2 rounded border text-xs hover:bg-muted disabled:opacity-60 text-left',
                compact ? 'px-2 py-1 max-w-[10rem]' : 'px-3 py-2',
            )}
        >
            {busy ? <Loader2 className="w-3.5 h-3.5 animate-spin shrink-0" /> : <Paperclip className="w-3.5 h-3.5 shrink-0" aria-hidden />}
            <span className={cn('truncate', compact ? 'max-w-[6rem]' : 'max-w-[16rem]')}>{a.fileName}</span>
            <span className="text-muted-foreground shrink-0">{humanSize(a.fileSize)}</span>
        </button>
    );
}

export interface AttachmentsProps {
    items?: Attachment[];
    /** 传入时每个附件右上角加一个移除按钮（用于发送前的待上传预览）。 */
    onRemove?: (id: number) => void;
    /** 紧凑模式：待发送预览用小尺寸缩略图；默认（消息列表）用正常尺寸。 */
    compact?: boolean;
}

export function Attachments({ items, onRemove, compact }: AttachmentsProps) {
    if (!items || items.length === 0) return null;

    return (
        <div className="flex flex-wrap gap-2 mt-2">
            {items.map((a) => (
                <div key={a.id} className="relative">
                    {a.contentType.startsWith('image/')
                        ? <ImageAttachment a={a} compact={compact} />
                        : <FileAttachment a={a} compact={compact} />}
                    {onRemove && (
                        <button
                            type="button"
                            onClick={() => onRemove(a.id)}
                            className="absolute -top-1.5 -right-1.5 w-4 h-4 rounded-full bg-foreground text-background flex items-center justify-center shadow"
                            title="移除"
                            aria-label={`移除附件 ${a.fileName}`}
                        >
                            <X className="w-2.5 h-2.5" />
                        </button>
                    )}
                </div>
            ))}
        </div>
    );
}
