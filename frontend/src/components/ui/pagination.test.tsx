import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { Pagination } from './pagination';

describe('Pagination', () => {
    it('首页禁用上一页，点下一页回调 page+1', () => {
        const onPageChange = vi.fn();
        render(<Pagination page={0} totalPages={3} onPageChange={onPageChange} />);

        expect(screen.getByText('1 / 3')).toBeInTheDocument();
        expect(screen.getByRole('button', { name: /上一页/ })).toBeDisabled();
        fireEvent.click(screen.getByRole('button', { name: /下一页/ }));
        expect(onPageChange).toHaveBeenCalledWith(1);
    });

    it('末页禁用下一页', () => {
        render(<Pagination page={2} totalPages={3} onPageChange={vi.fn()} />);
        expect(screen.getByRole('button', { name: /下一页/ })).toBeDisabled();
        expect(screen.getByRole('button', { name: /上一页/ })).toBeEnabled();
    });

    // 后端空结果返回 totalPages=0：显示成 1 / 1 且两个按钮都禁用，而不是「1 / 0」外加一个能点的下一页
    it('totalPages 为 0 时按 1 页处理', () => {
        render(<Pagination page={0} totalPages={0} onPageChange={vi.fn()} />);
        expect(screen.getByText('1 / 1')).toBeInTheDocument();
        expect(screen.getByRole('button', { name: /下一页/ })).toBeDisabled();
    });
});
