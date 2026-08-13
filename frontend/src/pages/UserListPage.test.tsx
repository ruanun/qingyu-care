import type { ReactNode } from 'react';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { ApiResponse, User } from '@/types';
import { userService } from '@/api/user';
import UserListPage from './UserListPage';

/**
 * 编辑用户时的密码提交回归测试。
 *
 * 守的是本次修复加的「第二道防线」：`handleSubmit` 编辑分支里
 * `const payload = password ? rest : withoutPassword;` 这个三元——它决定编辑弹窗
 * 留空密码时，`password` 字段究竟发不发出去。后端 `UserController` 已经用
 * `!= null && !isEmpty()` 兜住了空串，但前端这道拦截写反方向此前没有任何测试能拦。
 *
 * ⚠️ 这条只守前端「要不要带上这个字段」，不重复验证后端的空串判断——那条由
 * `PartialUpdateSemanticsTest.编辑用户时传空串密码不改动密码哈希` 守着。
 */

// vi.mock 的工厂被提到所有 import 之前执行，工厂里引用模块顶层 const 会撞 TDZ，
// 桩函数必须用 vi.hoisted 一起提上去。
const { toastSpy } = vi.hoisted(() => ({ toastSpy: vi.fn() }));

vi.mock('@/hooks/use-toast', () => ({
    useToast: () => ({ toast: toastSpy }),
}));

vi.mock('@/api/user', () => ({
    userService: {
        list: vi.fn(),
        getById: vi.fn(),
        create: vi.fn(),
        update: vi.fn(),
        delete: vi.fn(),
    },
}));

// Dialog/DropdownMenu/Select 是 Radix 组件，portal 与 focus trap 在 jsdom 下行为特殊，
// 与本文件要验的密码字段逻辑无关，硬测只会引入噪声——桩掉它们。
//
// 但不能桩成 `() => null`：编辑弹窗是通过 DropdownMenuItem 的 onClick 打开的，
// 表单提交是通过 form 的 onSubmit（由 Dialog 内真实的 <form> 触发，Dialog 本身只是
// 外层容器，不拦截 onSubmit）。桩成透传+按 open 门控的极简 stub，既避开 Radix 在
// jsdom 下的行为噪声，又保留「点击编辑资料 → 弹窗打开 → 改字段 → 提交」这条真实链路。
vi.mock('@/components/ui/dropdown-menu', () => ({
    DropdownMenu: ({ children }: { children: ReactNode }) => <>{children}</>,
    DropdownMenuTrigger: ({ children }: { children: ReactNode }) => <>{children}</>,
    DropdownMenuContent: ({ children }: { children: ReactNode }) => <div>{children}</div>,
    DropdownMenuItem: (
        { children, onClick }: { children: ReactNode; onClick?: () => void },
    ) => (
        <button type="button" onClick={onClick}>{children}</button>
    ),
}));

vi.mock('@/components/ui/dialog', () => ({
    Dialog: ({ open, children }: { open: boolean; children: ReactNode }) => (open ? <div>{children}</div> : null),
    DialogContent: ({ children }: { children: ReactNode }) => <div>{children}</div>,
    DialogHeader: ({ children }: { children: ReactNode }) => <div>{children}</div>,
    DialogFooter: ({ children }: { children: ReactNode }) => <div>{children}</div>,
    DialogTitle: ({ children }: { children: ReactNode }) => <div>{children}</div>,
    DialogDescription: ({ children }: { children: ReactNode }) => <div>{children}</div>,
}));

// 本文件不触碰角色/租户下拉，Select 只作装饰性渲染，桩成不带 Radix 行为的透传即可。
vi.mock('@/components/ui/select', () => ({
    Select: ({ children }: { children: ReactNode }) => <div>{children}</div>,
    SelectTrigger: ({ children }: { children: ReactNode }) => <div>{children}</div>,
    SelectValue: () => null,
    SelectContent: ({ children }: { children: ReactNode }) => <div>{children}</div>,
    SelectItem: ({ children }: { children: ReactNode }) => <div>{children}</div>,
}));

const ok = <T,>(data: T): ApiResponse<T> => ({
    code: 200,
    message: 'success',
    data,
    timestamp: '2026-08-13T00:00:00',
});

const user1: User = {
    id: 1,
    username: 'zhangsan',
    nickname: '张三',
    email: 'zhangsan@example.com',
    phone: '',
    role: 'TENANT_MEMBER',
    enabled: true,
    createdAt: '2026-08-10T00:00:00',
};

const flush = async () => {
    await act(async () => {
        await Promise.resolve();
    });
};

const renderPage = async () => {
    const result = render(<UserListPage />);
    await flush();
    return result;
};

const openEditDialog = async () => {
    fireEvent.click(screen.getByRole('button', { name: '编辑资料' }));
    await flush();
};

const submitEdit = async () => {
    fireEvent.click(screen.getByRole('button', { name: '确认修改' }));
    await flush();
};

beforeEach(() => {
    toastSpy.mockReset();
    vi.mocked(userService.list).mockReset().mockResolvedValue(ok([user1]));
    vi.mocked(userService.update).mockReset().mockResolvedValue(ok(user1));
});

describe('UserListPage 编辑用户时的密码提交', () => {
    /**
     * 变异实验：把 handleSubmit 里的
     * `const payload = password ? rest : withoutPassword;` 改回 `rest`
     * （即撤掉本次加的第二道防线），本条必须转红——payload 会带上
     * `password: ''`。
     */
    it('密码留空 → 提交的 payload 不含 password 字段', async () => {
        await renderPage();
        await openEditDialog();
        expect(screen.getByText('编辑成员')).toBeInTheDocument();

        // 不触碰密码输入框，保持 handleOpenDialog 初始化的空串
        await submitEdit();

        expect(userService.update).toHaveBeenCalledTimes(1);
        const payload = vi.mocked(userService.update).mock.calls[0][1];
        expect(payload).not.toHaveProperty('password');
    });

    /**
     * 变异实验：把三元反过来写成 `password ? withoutPassword : rest`，
     * 本条必须转红——填了密码时反而被剔掉，改密码功能失效。
     */
    it('密码填了值 → 提交的 payload 带上 password 字段', async () => {
        await renderPage();
        await openEditDialog();

        fireEvent.change(screen.getByPlaceholderText('留空则不修改'), {
            target: { value: 'new-secret-123' },
        });
        await submitEdit();

        expect(userService.update).toHaveBeenCalledTimes(1);
        const payload = vi.mocked(userService.update).mock.calls[0][1];
        expect(payload).toHaveProperty('password', 'new-secret-123');
    });
});
