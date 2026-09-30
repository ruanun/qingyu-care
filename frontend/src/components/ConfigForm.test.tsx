import { act, fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import ConfigForm from './ConfigForm';
import type { ConfigField } from './ConfigForm';

/**
 * 凭据类字段（type: secret）只写不读：后端读到的是掩码，输入框不回显；留空保存即「不修改」。
 */

const { toastSpy } = vi.hoisted(() => ({ toastSpy: vi.fn() }));
vi.mock('@/hooks/use-toast', () => ({ useToast: () => ({ toast: toastSpy }) }));

const FIELDS: ConfigField[] = [
    { key: 'plain', label: '普通项', type: 'text', default: '' },
    { key: 'sec', label: '密钥项', type: 'secret', default: '' },
];

const flush = async () => {
    await act(async () => {
        await Promise.resolve();
    });
};

const save = vi.fn();

beforeEach(() => {
    toastSpy.mockReset();
    save.mockReset().mockResolvedValue({ code: 200, message: '已保存', timestamp: '', data: null });
});

const renderForm = async (data: Record<string, string>) => {
    const load = vi.fn().mockResolvedValue({ code: 200, message: 'ok', timestamp: '', data });
    render(<ConfigForm title="t" fields={FIELDS} load={load} save={save} />);
    await flush();
};

describe('ConfigForm secret 字段', () => {
    it('已设置时不回显掩码，提示留空不修改；原样保存时提交空串', async () => {
        await renderForm({ plain: 'p', sec: '******' });

        const input = screen.getByLabelText('密钥项');
        expect(input).toHaveValue('');
        expect(input).toHaveAttribute('type', 'password');
        expect(input).toHaveAttribute('placeholder', '已设置，留空则不修改');

        fireEvent.click(screen.getByRole('button', { name: '保存' }));
        await flush();
        expect(save).toHaveBeenCalledWith({ plain: 'p', sec: '' });
    });

    it('未设置时提示未设置', async () => {
        await renderForm({ plain: 'p', sec: '' });
        expect(screen.getByLabelText('密钥项')).toHaveAttribute('placeholder', '未设置');
    });

    /** 保存后与重新打开页面看到的一致：输入框清空、提示变成「已设置」，否则再点一次保存会把明文又提交一遍 */
    it('填了新值保存成功后清空输入框并标记为已设置', async () => {
        await renderForm({ plain: 'p', sec: '' });
        const input = screen.getByLabelText('密钥项');
        fireEvent.change(input, { target: { value: 'new-secret' } });

        fireEvent.click(screen.getByRole('button', { name: '保存' }));
        await flush();
        await flush();

        expect(save).toHaveBeenCalledWith({ plain: 'p', sec: 'new-secret' });
        expect(input).toHaveValue('');
        expect(input).toHaveAttribute('placeholder', '已设置，留空则不修改');
    });

    it('renderAfter 拿到的是表单当前值（含未保存的修改）', async () => {
        const fields: ConfigField[] = [
            { key: 'plain', label: '普通项', type: 'text', default: '', renderAfter: (v) => <span data-testid="after">{v.plain}</span> },
        ];
        const load = vi.fn().mockResolvedValue({ code: 200, message: 'ok', timestamp: '', data: { plain: 'a' } });
        render(<ConfigForm title="t" fields={fields} load={load} save={save} />);
        await flush();

        fireEvent.change(screen.getByLabelText('普通项'), { target: { value: 'b' } });
        expect(screen.getByTestId('after')).toHaveTextContent('b');
    });
});
