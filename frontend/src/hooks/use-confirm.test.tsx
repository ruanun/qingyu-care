import { act, fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { useConfirm, type ConfirmOptions } from './use-confirm';

/**
 * useConfirm 替代了全站 11 处 `window.confirm()`。它的调用方全是
 * `if (!(await confirm(...))) return;` 这一种写法，所以要钉住的只有 Promise 语义：
 * 点确定 → true、点取消 → false、弹窗被新的一次覆盖 → 旧的按 false 收场。
 * 任何一条错了，删除 / 重置密钥要么「点取消照样执行」，要么「点确定没反应」。
 */

let results: boolean[] = [];

function Harness({ opts }: { opts: ConfirmOptions }) {
    const { confirm, confirmDialog } = useConfirm();
    return (
        <>
            <button onClick={async () => results.push(await confirm(opts))}>触发</button>
            {confirmDialog}
        </>
    );
}

const flush = async () => {
    await act(async () => {
        await Promise.resolve();
    });
};

describe('useConfirm', () => {
    it('点确认按钮 → resolve(true)，且文案来自 options', async () => {
        results = [];
        render(<Harness opts={{ title: '删除该工具？', description: '不可撤销', confirmText: '删除', destructive: true }} />);

        fireEvent.click(screen.getByText('触发'));
        expect(screen.getByRole('alertdialog')).toHaveTextContent('删除该工具？');
        expect(screen.getByRole('alertdialog')).toHaveTextContent('不可撤销');

        fireEvent.click(screen.getByRole('button', { name: '删除' }));
        await flush();

        expect(results).toEqual([true]);
        expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
    });

    it('点取消 → resolve(false)', async () => {
        results = [];
        render(<Harness opts={{ title: '删除？' }} />);

        fireEvent.click(screen.getByText('触发'));
        fireEvent.click(screen.getByRole('button', { name: '取消' }));
        await flush();

        expect(results).toEqual([false]);
    });

    /**
     * 每一轮 confirm 必须拿到自己的 resolve。若第二轮沿用了第一轮已用过的 resolve，
     * 第二轮的 await 永远不返回——用户点了按钮没反应，控制台也没有任何报错。
     */
    it('连续两轮确认互不串味', async () => {
        results = [];
        render(<Harness opts={{ title: '删除？', confirmText: '确定删除' }} />);

        fireEvent.click(screen.getByText('触发'));
        fireEvent.click(screen.getByRole('button', { name: '确定删除' }));
        await flush();
        fireEvent.click(screen.getByText('触发'));
        fireEvent.click(screen.getByRole('button', { name: '取消' }));
        await flush();

        expect(results).toEqual([true, false]);
    });
});
