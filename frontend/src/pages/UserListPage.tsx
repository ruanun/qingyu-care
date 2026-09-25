import React, { useState, useEffect } from 'react';
import { UserPlus, Search, UserCircle, Mail, MoreVertical, Edit2, Trash2, Power, Loader2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Card, CardHeader, CardContent } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { Separator } from '@/components/ui/separator';
import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import {
    Dialog,
    DialogContent,
    DialogDescription,
    DialogFooter,
    DialogHeader,
    DialogTitle,
} from "@/components/ui/dialog";
import {
    Select,
    SelectContent,
    SelectItem,
    SelectTrigger,
    SelectValue,
} from "@/components/ui/select";
import { userService } from '@/api/user';
import { tenantService } from '@/api/tenant';
import type { User, UserRole, Tenant } from '@/types';
import { useToast } from '@/hooks/use-toast';
import { useConfirm } from '@/hooks/use-confirm';
import { useAuthStore } from '@/stores/authStore';
import { cn } from '@/lib/utils';
import { getErrorMessage, throwIfFailed } from '@/lib/apiResult';

const UserListPage: React.FC = () => {
    const [users, setUsers] = useState<User[]>([]);
    const [isLoading, setIsLoading] = useState(false);
    const [searchQuery, setSearchQuery] = useState('');
    const [isDialogOpen, setIsDialogOpen] = useState(false);
    const [editingUser, setEditingUser] = useState<User | null>(null);
    const [formData, setFormData] = useState({
        username: '',
        password: '',
        nickname: '',
        email: '',
        phone: '',
        role: 'TENANT_MEMBER' as UserRole,
        tenantId: '',
    });
    const [isSubmitting, setIsSubmitting] = useState(false);
    const [tenants, setTenants] = useState<Tenant[]>([]);
    const { toast } = useToast();
    const { confirm, confirmDialog } = useConfirm();
    const { user: currentUser } = useAuthStore();
    // 平台管理员无自身租户，创建租户成员时需显式指定所属租户
    const isPlatformAdmin = currentUser?.role === 'PLATFORM_ADMIN';

    useEffect(() => {
        fetchUsers();
        if (isPlatformAdmin) fetchTenants();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    async function fetchUsers() {
        setIsLoading(true);
        try {
            const response = await userService.list();
            throwIfFailed(response);
            setUsers(response.data);
        } catch (error: unknown) {
            toast({ variant: "destructive", title: "加载失败", description: getErrorMessage(error) });
        } finally {
            setIsLoading(false);
        }
    };

    async function fetchTenants() {
        try {
            const response = await tenantService.list();
            if (response.code === 200) setTenants(response.data);
        } catch {
            // 拉取租户失败不阻断页面：平台管理员的租户下拉为空时仍可按当前租户建用户
        }
    };

    const handleOpenDialog = (user?: User) => {
        if (user) {
            setEditingUser(user);
            setFormData({
                username: user.username,
                password: '', // 编辑时不显示/修改密码除非输入
                nickname: user.nickname || '',
                email: user.email || '',
                phone: user.phone || '',
                role: user.role,
                tenantId: '',
            });
        } else {
            setEditingUser(null);
            setFormData({
                username: '',
                password: '',
                nickname: '',
                email: '',
                phone: '',
                role: 'TENANT_MEMBER',
                tenantId: '',
            });
        }
        setIsDialogOpen(true);
    };

    const handleSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        // 平台管理员创建时必须选择所属租户
        if (!editingUser && isPlatformAdmin && !formData.tenantId) {
            toast({ variant: "destructive", title: "请选择所属租户", description: "平台管理员创建成员时必须指定所属租户" });
            return;
        }
        setIsSubmitting(true);
        try {
            const { tenantId, ...rest } = formData;
            if (editingUser) {
                // 编辑时不提交空密码：这里本就无意修改密码（见 handleOpenDialog 把
                // password 初始化为空串的注释），不该把这个字段发出去。
                // ⚠️ 后端 UserController 已用 `!= null && !isEmpty()` 兜底，这道前端过滤
                // 不能替代它——两道互为兜底，后端要防的是任何调用方（第三方 API 调用、
                // 其他前端页面），不只是这一个页面，去掉后端那道会让别的入口失守。
                const { password, ...withoutPassword } = rest;
                const payload = password ? rest : withoutPassword;
                const response = await userService.update(editingUser.id, payload);
                throwIfFailed(response);
                toast({ title: "更新成功" });
                fetchUsers();
                setIsDialogOpen(false);
            } else {
                // 平台管理员携带 tenantId；租户管理员由后端按当前租户自动归属
                const payload = isPlatformAdmin
                    ? { ...rest, tenantId: tenantId ? Number(tenantId) : undefined }
                    : rest;
                const response = await userService.create(payload);
                throwIfFailed(response);
                toast({ title: "创建成功", description: `用户 ${formData.username} 已创建` });
                fetchUsers();
                setIsDialogOpen(false);
            }
        } catch (error: unknown) {
            toast({ variant: "destructive", title: "保存失败", description: getErrorMessage(error) });
        } finally {
            setIsSubmitting(false);
        }
    };

    const toggleUserStatus = async (user: User) => {
        try {
            const response = await userService.update(user.id, { enabled: !user.enabled });
            throwIfFailed(response);
            toast({ title: user.enabled ? "已禁用" : "已启用" });
            fetchUsers();
        } catch (error: unknown) {
            toast({ variant: "destructive", title: "操作失败", description: getErrorMessage(error) });
        }
    };

    const handleDelete = async (id: number) => {
        if (!(await confirm({ title: '删除该用户？', description: '此操作不可撤销。', confirmText: '删除', destructive: true }))) return;
        try {
            const response = await userService.delete(id);
            throwIfFailed(response);
            toast({ title: "删除成功" });
            fetchUsers();
        } catch (error: unknown) {
            toast({ variant: "destructive", title: "删除失败", description: getErrorMessage(error) });
        }
    };

    const filteredUsers = users.filter(u =>
        u.username.toLowerCase().includes(searchQuery.toLowerCase()) ||
        u.nickname?.toLowerCase().includes(searchQuery.toLowerCase()) ||
        u.email?.toLowerCase().includes(searchQuery.toLowerCase())
    );

    const getRoleBadge = (role: UserRole) => {
        switch (role) {
            case 'PLATFORM_ADMIN': return <Badge variant="destructive">平台管理员</Badge>;
            case 'TENANT_ADMIN': return <Badge variant="default" className="bg-blue-500">租户管理员</Badge>;
            default: return <Badge variant="secondary">成员</Badge>;
        }
    };

    return (
        <div className="p-8 max-w-7xl mx-auto space-y-8">
            <div className="flex flex-col md:flex-row justify-between items-start md:items-center gap-4">
                <div>
                    <h1 className="text-3xl font-bold tracking-tight">成员管理</h1>
                    <p className="text-muted-foreground mt-1">管理您的租户成员及其访问权限。</p>
                </div>
                <Button onClick={() => handleOpenDialog()} className="gap-2">
                    <UserPlus className="w-4 h-4" /> 邀请成员
                </Button>
            </div>

            <Card>
                <CardHeader className="pb-3">
                    <div className="flex items-center gap-3">
                        <div className="relative flex-1 max-w-md">
                            <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-muted-foreground" />
                            <Input
                                placeholder="搜索用户名、昵称或邮箱..."
                                className="pl-9 bg-muted/30"
                                value={searchQuery}
                                onChange={(e) => setSearchQuery(e.target.value)}
                            />
                        </div>
                    </div>
                </CardHeader>
                <CardContent>
                    <div className="relative overflow-x-auto rounded-lg border">
                        <table className="w-full text-sm text-left">
                            <thead className="text-xs uppercase bg-muted/50 text-muted-foreground">
                                <tr>
                                    <th className="px-6 py-4 font-medium">基本信息</th>
                                    <th className="px-6 py-4 font-medium">角色</th>
                                    <th className="px-6 py-4 font-medium">状态</th>
                                    <th className="px-6 py-4 font-medium">最后登录</th>
                                    <th className="px-6 py-4 font-medium text-right">操作</th>
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-border">
                                {isLoading ? (
                                    <tr>
                                        <td colSpan={5} className="px-6 py-12 text-center">
                                            <Loader2 className="w-8 h-8 animate-spin mx-auto text-muted-foreground" />
                                        </td>
                                    </tr>
                                ) : filteredUsers.length === 0 ? (
                                    <tr>
                                        <td colSpan={5} className="px-6 py-12 text-center text-muted-foreground">
                                            未找到符合条件的成员
                                        </td>
                                    </tr>
                                ) : (
                                    filteredUsers.map((user) => (
                                        <tr key={user.id} className="hover:bg-muted/30 transition-colors">
                                            <td className="px-6 py-4">
                                                <div className="flex items-center gap-3">
                                                    <Avatar className="h-10 w-10 border">
                                                        <AvatarImage src={user.avatar} />
                                                        <AvatarFallback className="bg-primary/5 text-primary text-xs">
                                                            {(user.nickname || user.username).substring(0, 2).toUpperCase()}
                                                        </AvatarFallback>
                                                    </Avatar>
                                                    <div>
                                                        <div className="font-semibold text-foreground flex items-center gap-2">
                                                            {user.nickname || user.username}
                                                            {!user.enabled && <Badge variant="outline" className="text-[10px] h-4 px-1 text-red-500 border-red-200 bg-red-50">已禁用</Badge>}
                                                        </div>
                                                        <div className="text-xs text-muted-foreground flex items-center gap-3 mt-0.5">
                                                            <span className="flex items-center gap-1"><UserCircle className="w-3 h-3" /> {user.username}</span>
                                                            {user.email && <span className="flex items-center gap-1"><Mail className="w-3 h-3" /> {user.email}</span>}
                                                        </div>
                                                    </div>
                                                </div>
                                            </td>
                                            <td className="px-6 py-4">
                                                {getRoleBadge(user.role)}
                                            </td>
                                            <td className="px-6 py-4">
                                                <div className="flex items-center gap-2 text-xs">
                                                    <div className={cn("w-2 h-2 rounded-full", user.enabled ? "bg-green-500" : "bg-gray-300")} />
                                                    {user.enabled ? "激活中" : "冻结"}
                                                </div>
                                            </td>
                                            <td className="px-6 py-4 text-muted-foreground text-xs font-mono">
                                                {user.lastLoginTime ? new Date(user.lastLoginTime).toLocaleString() : '从未登录'}
                                            </td>
                                            <td className="px-6 py-4 text-right">
                                                <DropdownMenu>
                                                    <DropdownMenuTrigger asChild>
                                                        <Button variant="ghost" size="icon" className="h-8 w-8 hover:bg-muted">
                                                            <MoreVertical className="w-4 h-4" />
                                                        </Button>
                                                    </DropdownMenuTrigger>
                                                    <DropdownMenuContent align="end" className="w-40">
                                                        <DropdownMenuItem onClick={() => handleOpenDialog(user)} className="gap-2">
                                                            <Edit2 className="w-4 h-4" /> 编辑资料
                                                        </DropdownMenuItem>
                                                        <DropdownMenuItem onClick={() => toggleUserStatus(user)} className="gap-2">
                                                            <Power className="w-4 h-4" /> {user.enabled ? "禁用账号" : "再次发布"}
                                                        </DropdownMenuItem>
                                                        <Separator className="my-1" />
                                                        <DropdownMenuItem onClick={() => handleDelete(user.id)} className="gap-2 text-destructive focus:text-destructive">
                                                            <Trash2 className="w-4 h-4" /> 删除成员
                                                        </DropdownMenuItem>
                                                    </DropdownMenuContent>
                                                </DropdownMenu>
                                            </td>
                                        </tr>
                                    ))
                                )}
                            </tbody>
                        </table>
                    </div>
                </CardContent>
            </Card>

            <Dialog open={isDialogOpen} onOpenChange={setIsDialogOpen}>
                <DialogContent className="sm:max-w-[450px]">
                    <DialogHeader>
                        <DialogTitle>{editingUser ? '编辑成员' : '邀请新成员'}</DialogTitle>
                        <DialogDescription>
                            {editingUser ? '修改成员的基本资料和访问权限。' : '填写成员基本信息，新成员登录密码默认为下方设置。'}
                        </DialogDescription>
                    </DialogHeader>
                    <form onSubmit={handleSubmit} className="space-y-4 py-3">
                        <div className="grid grid-cols-2 gap-4">
                            <div className="space-y-2">
                                <label className="text-sm font-medium">用户名</label>
                                <Input
                                    name="username"
                                    required
                                    disabled={!!editingUser}
                                    value={formData.username}
                                    onChange={(e) => setFormData({ ...formData, username: e.target.value })}
                                    placeholder="登录账号"
                                />
                            </div>
                            <div className="space-y-2">
                                <label className="text-sm font-medium">密码</label>
                                <Input
                                    name="password"
                                    type="password"
                                    required={!editingUser}
                                    value={formData.password}
                                    onChange={(e) => setFormData({ ...formData, password: e.target.value })}
                                    placeholder={editingUser ? "留空则不修改" : "登录密码"}
                                />
                            </div>
                        </div>
                        <div className="space-y-2">
                            <label className="text-sm font-medium">昵称</label>
                            <Input
                                name="nickname"
                                value={formData.nickname}
                                onChange={(e) => setFormData({ ...formData, nickname: e.target.value })}
                                placeholder="显示的名称"
                            />
                        </div>
                        <div className="grid grid-cols-2 gap-4">
                            <div className="space-y-2">
                                <label className="text-sm font-medium">邮箱</label>
                                <Input
                                    name="email"
                                    type="email"
                                    value={formData.email}
                                    onChange={(e) => setFormData({ ...formData, email: e.target.value })}
                                    placeholder="email@example.com"
                                />
                            </div>
                            <div className="space-y-2">
                                <label className="text-sm font-medium">手机号</label>
                                <Input
                                    name="phone"
                                    value={formData.phone}
                                    onChange={(e) => setFormData({ ...formData, phone: e.target.value })}
                                    placeholder="联系电话"
                                />
                            </div>
                        </div>
                        {!editingUser && isPlatformAdmin && (
                            <div className="space-y-2">
                                <label className="text-sm font-medium">所属租户</label>
                                <Select
                                    value={formData.tenantId}
                                    onValueChange={(val) => setFormData({ ...formData, tenantId: val })}
                                >
                                    <SelectTrigger>
                                        <SelectValue placeholder="选择所属租户" />
                                    </SelectTrigger>
                                    <SelectContent>
                                        {tenants.map((t) => (
                                            <SelectItem key={t.id} value={String(t.id)}>
                                                {t.name || t.code || `租户#${t.id}`}
                                            </SelectItem>
                                        ))}
                                    </SelectContent>
                                </Select>
                            </div>
                        )}
                        <div className="space-y-2">
                            <label className="text-sm font-medium">系统角色</label>
                            <Select
                                value={formData.role}
                                onValueChange={(val: UserRole) => setFormData({ ...formData, role: val })}
                            >
                                <SelectTrigger>
                                    <SelectValue placeholder="选择角色" />
                                </SelectTrigger>
                                <SelectContent>
                                    <SelectItem value="TENANT_ADMIN">租户管理员 (全量权限)</SelectItem>
                                    <SelectItem value="TENANT_MEMBER">租户成员 (部分权限)</SelectItem>
                                </SelectContent>
                            </Select>
                        </div>
                        <DialogFooter className="pt-4">
                            <Button type="button" variant="ghost" onClick={() => setIsDialogOpen(false)}>取消</Button>
                            <Button type="submit" disabled={isSubmitting}>
                                {isSubmitting && <Loader2 className="w-4 h-4 mr-2 animate-spin" />}
                                {editingUser ? '确认修改' : '立即创建'}
                            </Button>
                        </DialogFooter>
                    </form>
                </DialogContent>
            </Dialog>
            {confirmDialog}
        </div>
    );
};

export default UserListPage;
