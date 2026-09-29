import ReactMarkdown, { type Components } from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { cn } from '@/lib/utils';

/**
 * 管理后台的 AI 回复 Markdown 渲染（对话测试 / 会话详情 / 人工工作台 / 卡片里的文字片段）。
 * widget 那边是自写的 DOM 渲染器（widget/src/markdown.ts），语法子集与安全规矩对齐：
 *
 * - 不渲染原文里的 HTML：react-markdown 不接 rehype-raw 时 HTML 不会变成元素，`Markdown.test.tsx` 钉着；
 * - 链接经默认的 urlTransform，`javascript:` 之类被清掉，只剩 http(s) / mailto 等安全协议；
 * - 只给 AI 回复用：访客与真人坐席的消息保持纯文本，坐席打字时可能用到 `*`，被当成格式吃掉反而是错的。
 *
 * 样式全部跟随 currentColor（半透明底色、边框），因为它会出现在浅色气泡与深色气泡（会话详情、工作台的
 * AI 消息是主色底白字）两种背景里。
 *
 * @author ruan
 */
const components: Components = {
    p: ({ children }) => <p className="my-1.5 first:mt-0 last:mb-0">{children}</p>,
    h1: ({ children }) => <p className="font-semibold mt-3 mb-1 first:mt-0">{children}</p>,
    h2: ({ children }) => <p className="font-semibold mt-3 mb-1 first:mt-0">{children}</p>,
    h3: ({ children }) => <p className="font-semibold mt-2 mb-1 first:mt-0">{children}</p>,
    h4: ({ children }) => <p className="font-semibold mt-2 mb-1 first:mt-0">{children}</p>,
    ul: ({ children }) => <ul className="list-disc pl-5 my-1.5 space-y-0.5">{children}</ul>,
    ol: ({ children, start }) => <ol start={start} className="list-decimal pl-5 my-1.5 space-y-0.5">{children}</ol>,
    a: ({ children, href }) => (
        <a href={href} target="_blank" rel="noopener noreferrer" className="underline underline-offset-2 break-all">
            {children}
        </a>
    ),
    code: ({ children, className }) => (
        // 块级代码由 pre 包着，行内代码没有 language-* 类名
        <code className={cn('rounded px-1 py-0.5 font-mono text-[0.85em] bg-current/10', className && 'bg-transparent p-0')}>
            {children}
        </code>
    ),
    pre: ({ children }) => <pre className="my-2 overflow-x-auto rounded-md bg-current/10 p-2.5 text-xs">{children}</pre>,
    blockquote: ({ children }) => <blockquote className="my-2 border-l-2 border-current/30 pl-3 opacity-80">{children}</blockquote>,
    hr: () => <hr className="my-3 border-current/20" />,
    table: ({ children }) => (
        <div className="my-2 overflow-x-auto">
            <table className="text-xs border-collapse">{children}</table>
        </div>
    ),
    th: ({ children }) => <th className="border border-current/20 px-2 py-1 text-left font-semibold whitespace-nowrap">{children}</th>,
    td: ({ children }) => <td className="border border-current/20 px-2 py-1 whitespace-nowrap">{children}</td>,
};

export function Markdown({ text, className }: { text: string; className?: string }) {
    return (
        <div className={cn('break-words', className)}>
            <ReactMarkdown remarkPlugins={[remarkGfm]} components={components}>
                {text}
            </ReactMarkdown>
        </div>
    );
}
