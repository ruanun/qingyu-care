import { render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import SessionMessages from './SessionMessages';

vi.mock('@/hooks/use-toast', () => ({ useToast: () => ({ toast: vi.fn() }) }));
vi.mock('@/api/chat', () => ({
    chatService: {
        submitFeedback: vi.fn(),
        getSessionMessages: vi.fn().mockResolvedValue({
            code: 200,
            message: 'ok',
            data: {
                messages: [
                    { id: 1, role: 'USER', content: '我要**退货**', createdAt: '2026-09-29T10:00:00' },
                    { id: 2, role: 'ASSISTANT', content: '退货流程：\n\n1. **申请**\n2. 寄回', createdAt: '2026-09-29T10:00:05' },
                    { id: 3, role: 'AGENT', content: '单价 3*4 元，**请确认**', createdAt: '2026-09-29T10:01:00' },
                ],
                visitorReadUpToMessageId: null,
            },
        }),
    },
}));

/**
 * 会话详情里 AI 与坐席的消息同在右侧：只有 AI 回复渲染 Markdown，访客与坐席的消息保持纯文本。
 * 此前全部纯文本，AI 回复里的 `**` 与列表符号原样显示。
 */
describe('SessionMessages Markdown', () => {
    it('AI 回复渲染 Markdown，访客与坐席消息保持原文', async () => {
        render(<SessionMessages sessionId="s1" />);
        // Markdown 渲染器是懒加载的：首次加载要现场转换 react-markdown 及其语法解析依赖，
        // 整套测试并行时会超过默认 1 秒，单独跑则很快——放宽等待，否则这条随机器负载时红时绿
        await waitFor(() => expect(screen.getByText('申请')).toBeInTheDocument(), { timeout: 10000 });

        expect(screen.getByText('申请').tagName).toBe('STRONG');
        expect(document.querySelectorAll('ol > li')).toHaveLength(2);
        expect(screen.getByText('我要**退货**')).toBeInTheDocument();
        expect(screen.getByText('单价 3*4 元，**请确认**')).toBeInTheDocument();
        expect(document.querySelectorAll('strong')).toHaveLength(1);
    });
});
