import { lazy, Suspense } from 'react';
import { cn } from '@/lib/utils';

/**
 * AI 回复的 Markdown 渲染入口（懒加载包装）。真正的渲染器在 MarkdownRenderer.tsx。
 *
 * react-markdown 连同 micromark / remark-gfm 约 160KB（gzip 50KB），只有对话测试、会话详情、人工工作台用得到，
 * 拆成独立代码块、第一次渲染 AI 回复时才下载，不压在首屏包里。
 * 下载完成前按纯文本显示（保留换行），而不是留白——回复内容始终可读，只是晚一瞬间换成渲染后的样式。
 *
 * @author ruan
 */
const MarkdownRenderer = lazy(() => import('./MarkdownRenderer'));

export function Markdown({ text, className }: { text: string; className?: string }) {
    return (
        <Suspense fallback={<div className={cn('whitespace-pre-wrap break-words', className)}>{text}</div>}>
            <MarkdownRenderer text={text} className={className} />
        </Suspense>
    );
}
