import type { ReactNode } from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { knowledgeService } from '@/api/knowledge';
import KnowledgePage from './KnowledgePage';

/**
 * 新建 / 编辑知识库对话框。它此前是个空壳：两个输入框没绑 state、「创建」按钮没有 onClick，
 * 点了毫无反应也没有任何报错——正是「写操作静默失效」那类缺陷，所以补上接线时一并钉住。
 *
 * @author ruan
 */
const { toastSpy } = vi.hoisted(() => ({ toastSpy: vi.fn() }));

vi.mock('@/hooks/use-toast', () => ({ useToast: () => ({ toast: toastSpy }) }));

vi.mock('@/api/knowledge', () => ({
    knowledgeService: {
        getKnowledgeBases: vi.fn(),
        getDocuments: vi.fn(),
        createKnowledgeBase: vi.fn(),
        updateKnowledgeBase: vi.fn(),
    },
}));

// 与本文件无关的子区块：卡片自己有测试，爬取列表与问答面板各自有测试或无写操作
vi.mock('@/components/knowledge/EmbeddingModelCard', () => ({ EmbeddingModelCard: () => null }));
vi.mock('@/components/knowledge/CrawlSiteList', () => ({ CrawlSiteList: () => null }));
vi.mock('@/components/QaPairPanel', () => ({ default: () => null }));

vi.mock('@/components/ui/dialog', () => ({
    Dialog: ({ open, children }: { open?: boolean; children: ReactNode }) => (open ? <div>{children}</div> : null),
    DialogTrigger: ({ children }: { children: ReactNode }) => <>{children}</>,
    DialogContent: ({ children }: { children: ReactNode }) => <div>{children}</div>,
    DialogHeader: ({ children }: { children: ReactNode }) => <div>{children}</div>,
    DialogFooter: ({ children }: { children: ReactNode }) => <div>{children}</div>,
    DialogTitle: ({ children }: { children: ReactNode }) => <div>{children}</div>,
    DialogDescription: ({ children }: { children: ReactNode }) => <div>{children}</div>,
}));

const ok = <T,>(data: T) => ({ code: 200, message: 'ok', data, timestamp: '' });
const KB = { id: 1, name: '售前', description: '', enabled: true, createdAt: '', updatedAt: '' };
const NEW_KB = { id: 2, name: '售后政策', description: '退换货说明', enabled: true, createdAt: '', updatedAt: '' };

describe('KnowledgePage 新建知识库', () => {
    beforeEach(() => {
        toastSpy.mockClear();
        vi.mocked(knowledgeService.getKnowledgeBases).mockReset();
        vi.mocked(knowledgeService.getDocuments).mockResolvedValue(ok([]));
        vi.mocked(knowledgeService.createKnowledgeBase).mockReset();
    });

    const fillAndSubmit = async () => {
        fireEvent.click(await screen.findByRole('button', { name: /创建知识库/ }));
        fireEvent.change(screen.getByLabelText('名称'), { target: { value: ' 售后政策 ' } });
        fireEvent.change(screen.getByLabelText('描述'), { target: { value: '退换货说明' } });
        fireEvent.click(screen.getByRole('button', { name: '创建' }));
    };

    it('成功：按表单内容提交、刷新列表并选中新库', async () => {
        vi.mocked(knowledgeService.getKnowledgeBases)
            .mockResolvedValueOnce(ok([KB]))
            .mockResolvedValueOnce(ok([KB, NEW_KB]));
        vi.mocked(knowledgeService.createKnowledgeBase).mockResolvedValue(ok(NEW_KB));
        render(<KnowledgePage />);

        await fillAndSubmit();

        await waitFor(() => expect(knowledgeService.createKnowledgeBase)
            .toHaveBeenCalledWith({ name: '售后政策', description: '退换货说明' }));
        await waitFor(() => expect(knowledgeService.getDocuments).toHaveBeenCalledWith(2));
        expect(toastSpy).toHaveBeenCalledWith({ title: '知识库已创建' });
    });

    it('业务错误：弹 destructive toast，不刷新列表', async () => {
        vi.mocked(knowledgeService.getKnowledgeBases).mockResolvedValue(ok([KB]));
        vi.mocked(knowledgeService.createKnowledgeBase).mockResolvedValue({ code: 400, message: '名称不能为空', data: null as never, timestamp: '' });
        render(<KnowledgePage />);

        await fillAndSubmit();

        await waitFor(() => expect(toastSpy).toHaveBeenCalledWith(expect.objectContaining({
            variant: 'destructive', description: '名称不能为空',
        })));
        expect(knowledgeService.getKnowledgeBases).toHaveBeenCalledTimes(1);
    });
});
