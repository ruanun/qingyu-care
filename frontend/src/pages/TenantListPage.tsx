import React, { useState, useEffect } from 'react';
import { Building2, Search, Plus, Calendar, Shield, MoreVertical, Edit2, Ban, Loader2, CreditCard, ExternalLink } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Card, CardHeader, CardContent } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
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
import { tenantService } from '@/api/tenant';
import type { Tenant, TenantStatus, TenantPlan } from '@/types';
import { useToast } from '@/hooks/use-toast';
import { getErrorMessage } from '@/lib/apiResult';

const TenantListPage: React.FC = () => {
    const [tenants, setTenants] = useState<Tenant[]>([]);
    const [isLoading, setIsLoading] = useState(false);
    const [searchQuery, setSearchQuery] = useState('');
    const [isDialogOpen, setIsDialogOpen] = useState(false);
    const [editingTenant, setEditingTenant] = useState<Tenant | null>(null);
    const [formData, setFormData] = useState({
        name: '',
        code: '',
        status: 'ACTIVE' as TenantStatus,
        plan: 'FREE' as TenantPlan,
        contactName: '',
        contactEmail: '',
        contactPhone: '',
        expireAt: '',
    });
    const [isSubmitting, setIsSubmitting] = useState(false);
    const { toast } = useToast();

    useEffect(() => {
        fetchTenants();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    async function fetchTenants() {
        setIsLoading(true);
        try {
            const response = await tenantService.list();
            if (response.code === 200) {
                setTenants(response.data);
            }
        } catch (error: unknown) {
            toast({ variant: "destructive", title: "加载失败", description: getErrorMessage(error) });
        } finally {
            setIsLoading(false);
        }
    };

    const handleOpenDialog = (tenant?: Tenant) => {
        if (tenant) {
            setEditingTenant(tenant);
            setFormData({
                name: tenant.name,
                code: tenant.code,
                status: tenant.status,
                plan: tenant.plan,
                contactName: tenant.contactName || '',
                contactEmail: tenant.contactEmail || '',
                contactPhone: tenant.contactPhone || '',
                expireAt: tenant.expireAt ? new Date(tenant.expireAt).toISOString().split('T')[0] : '',
            });
        } else {
            setEditingTenant(null);
            setFormData({
                name: '',
                code: '',
                status: 'ACTIVE',
                plan: 'FREE',
                contactName: '',
                contactEmail: '',
                contactPhone: '',
                expireAt: '',
            });
        }
        setIsDialogOpen(true);
    };

    const handleSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        setIsSubmitting(true);
        try {
            if (editingTenant) {
                const response = await tenantService.update(editingTenant.id, formData);
                if (response.code === 200) {
                    toast({ title: "更新成功" });
                    fetchTenants();
                    setIsDialogOpen(false);
                }
            } else {
                const response = await tenantService.create(formData);
                if (response.code === 200) {
                    toast({ title: "创建成功", description: `租户 ${formData.name} 已创建` });
                    fetchTenants();
                    setIsDialogOpen(false);
                }
            }
        } catch (error: unknown) {
            toast({ variant: "destructive", title: "保存失败", description: getErrorMessage(error) });
        } finally {
            setIsSubmitting(false);
        }
    };

    const toggleTenantStatus = async (tenant: Tenant) => {
        const newStatus = tenant.status === 'ACTIVE' ? 'FROZEN' : 'ACTIVE';
        try {
            const response = await tenantService.update(tenant.id, { status: newStatus as TenantStatus });
            if (response.code === 200) {
                toast({ title: newStatus === 'ACTIVE' ? "已解冻" : "已冻结" });
                fetchTenants();
            }
        } catch (error: unknown) {
            toast({ variant: "destructive", title: "操作失败", description: getErrorMessage(error) });
        }
    };

    const filteredTenants = tenants.filter(t =>
        t.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
        t.code.toLowerCase().includes(searchQuery.toLowerCase())
    );

    const getStatusBadge = (status: TenantStatus) => {
        switch (status) {
            case 'ACTIVE': return <Badge variant="default" className="bg-green-500">运行中</Badge>;
            case 'FROZEN': return <Badge variant="destructive">已冻结</Badge>;
            case 'EXPIRED': return <Badge variant="outline">已过期</Badge>;
            default: return <Badge variant="secondary">{status}</Badge>;
        }
    };

    const getPlanBadge = (plan: TenantPlan) => {
        switch (plan) {
            case 'PRO': return <Badge variant="default" className="bg-amber-500">专业版</Badge>;
            case 'ENTERPRISE': return <Badge variant="default" className="bg-purple-600">企业版</Badge>;
            case 'BASIC': return <Badge variant="default" className="bg-blue-500">基础版</Badge>;
            default: return <Badge variant="secondary">免费版</Badge>;
        }
    };

    return (
        <div className="p-8 max-w-7xl mx-auto space-y-8">
            <div className="flex flex-col md:flex-row justify-between items-start md:items-center gap-4">
                <div>
                    <h1 className="text-3xl font-bold tracking-tight">租户管理</h1>
                    <p className="text-muted-foreground mt-1">管理平台入驻租户，监控其状态、套餐及有效期。</p>
                </div>
                <Button onClick={() => handleOpenDialog()} className="gap-2">
                    <Plus className="w-4 h-4" /> 新增租户
                </Button>
            </div>

            <Card>
                <CardHeader className="pb-3 border-b mb-6">
                    <div className="flex items-center gap-3">
                        <div className="relative flex-1 max-w-md">
                            <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-muted-foreground" />
                            <Input
                                placeholder="搜索租户名称或代码..."
                                className="pl-9 bg-muted/30"
                                value={searchQuery}
                                onChange={(e) => setSearchQuery(e.target.value)}
                            />
                        </div>
                    </div>
                </CardHeader>
                <CardContent>
                    <div className="relative overflow-x-auto rounded-lg border bg-card">
                        <table className="w-full text-sm text-left">
                            <thead className="text-xs uppercase bg-muted/50 text-muted-foreground">
                                <tr>
                                    <th className="px-6 py-4 font-medium">租户名称 / 代码</th>
                                    <th className="px-6 py-4 font-medium">套餐方案</th>
                                    <th className="px-6 py-4 font-medium">状态</th>
                                    <th className="px-6 py-4 font-medium">过期时间</th>
                                    <th className="px-6 py-4 font-medium">联系人</th>
                                    <th className="px-6 py-4 font-medium text-right">操作</th>
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-border">
                                {isLoading ? (
                                    <tr>
                                        <td colSpan={6} className="px-6 py-12 text-center">
                                            <Loader2 className="w-8 h-8 animate-spin mx-auto text-muted-foreground" />
                                        </td>
                                    </tr>
                                ) : filteredTenants.length === 0 ? (
                                    <tr>
                                        <td colSpan={6} className="px-6 py-12 text-center text-muted-foreground">
                                            暂无符合条件的租户
                                        </td>
                                    </tr>
                                ) : (
                                    filteredTenants.map((tenant) => (
                                        <tr key={tenant.id} className="hover:bg-muted/30 transition-colors">
                                            <td className="px-6 py-4">
                                                <div className="flex items-center gap-3">
                                                    <div className="w-10 h-10 rounded-lg bg-primary/10 flex items-center justify-center border border-primary/20">
                                                        <Building2 className="w-5 h-5 text-primary" />
                                                    </div>
                                                    <div>
                                                        <div className="font-semibold text-foreground flex items-center gap-2">
                                                            {tenant.name}
                                                        </div>
                                                        <div className="text-xs text-muted-foreground flex items-center gap-1 mt-0.5">
                                                            <Shield className="w-3 h-3" /> {tenant.code}
                                                        </div>
                                                    </div>
                                                </div>
                                            </td>
                                            <td className="px-6 py-4">
                                                {getPlanBadge(tenant.plan)}
                                            </td>
                                            <td className="px-6 py-4">
                                                {getStatusBadge(tenant.status)}
                                            </td>
                                            <td className="px-6 py-4 text-muted-foreground text-xs">
                                                <span className="flex items-center gap-1.5">
                                                    <Calendar className="w-3 h-3" />
                                                    {tenant.expireAt ? new Date(tenant.expireAt).toLocaleDateString() : '永久有效'}
                                                </span>
                                            </td>
                                            <td className="px-6 py-4">
                                                <div className="text-xs space-y-0.5">
                                                    <div className="font-medium">{tenant.contactName || '未设置'}</div>
                                                    <div className="text-muted-foreground truncate max-w-[150px]">{tenant.contactEmail}</div>
                                                </div>
                                            </td>
                                            <td className="px-6 py-4 text-right">
                                                <DropdownMenu>
                                                    <DropdownMenuTrigger asChild>
                                                        <Button variant="ghost" size="icon" className="h-8 w-8">
                                                            <MoreVertical className="w-4 h-4" />
                                                        </Button>
                                                    </DropdownMenuTrigger>
                                                    <DropdownMenuContent align="end" className="w-48">
                                                        <DropdownMenuItem onClick={() => handleOpenDialog(tenant)} className="gap-2">
                                                            <Edit2 className="w-4 h-4" /> 编辑信息
                                                        </DropdownMenuItem>
                                                        <DropdownMenuItem className="gap-2">
                                                            <CreditCard className="w-4 h-4" /> 升级套餐
                                                        </DropdownMenuItem>
                                                        <DropdownMenuItem onClick={() => toggleTenantStatus(tenant)} className="gap-2">
                                                            <Ban className="w-4 h-4" /> {tenant.status === 'ACTIVE' ? '冻结租户' : '恢复激活'}
                                                        </DropdownMenuItem>
                                                        <Separator className="my-1" />
                                                        <DropdownMenuItem className="gap-2">
                                                            <ExternalLink className="w-4 h-4" /> 进入工作台
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
                <DialogContent className="sm:max-w-[550px]">
                    <DialogHeader>
                        <DialogTitle>{editingTenant ? '编辑租户' : '新增租户入驻'}</DialogTitle>
                        <DialogDescription>
                            设置租户的基础信息、联系人及服务套餐。
                        </DialogDescription>
                    </DialogHeader>
                    <form onSubmit={handleSubmit} className="space-y-4 py-3">
                        <div className="grid grid-cols-2 gap-4">
                            <div className="space-y-2">
                                <label className="text-sm font-medium">租户名称</label>
                                <Input
                                    required
                                    value={formData.name}
                                    onChange={(e) => setFormData({ ...formData, name: e.target.value })}
                                    placeholder="企业名称"
                                />
                            </div>
                            <div className="space-y-2">
                                <label className="text-sm font-medium">唯一编码</label>
                                <Input
                                    required
                                    disabled={!!editingTenant}
                                    value={formData.code}
                                    onChange={(e) => setFormData({ ...formData, code: e.target.value })}
                                    placeholder="例如：qingyu"
                                />
                            </div>
                        </div>

                        <div className="grid grid-cols-2 gap-4">
                            <div className="space-y-2">
                                <label className="text-sm font-medium">联系人姓名</label>
                                <Input
                                    value={formData.contactName}
                                    onChange={(e) => setFormData({ ...formData, contactName: e.target.value })}
                                    placeholder="联系人"
                                />
                            </div>
                            <div className="space-y-2">
                                <label className="text-sm font-medium">联系电话</label>
                                <Input
                                    value={formData.contactPhone}
                                    onChange={(e) => setFormData({ ...formData, contactPhone: e.target.value })}
                                    placeholder="手机号"
                                />
                            </div>
                        </div>

                        <div className="space-y-2">
                            <label className="text-sm font-medium">联系邮箱</label>
                            <Input
                                type="email"
                                value={formData.contactEmail}
                                onChange={(e) => setFormData({ ...formData, contactEmail: e.target.value })}
                                placeholder="email@example.com"
                            />
                        </div>

                        <div className="grid grid-cols-2 gap-4">
                            <div className="space-y-2">
                                <label className="text-sm font-medium">套餐方案</label>
                                <Select
                                    value={formData.plan}
                                    onValueChange={(val: TenantPlan) => setFormData({ ...formData, plan: val })}
                                >
                                    <SelectTrigger>
                                        <SelectValue placeholder="选择套餐" />
                                    </SelectTrigger>
                                    <SelectContent>
                                        <SelectItem value="FREE">免费版 (所有限制)</SelectItem>
                                        <SelectItem value="BASIC">基础版 (初创企业)</SelectItem>
                                        <SelectItem value="PRO">专业版 (主流推荐)</SelectItem>
                                        <SelectItem value="ENTERPRISE">企业版 (定制支持)</SelectItem>
                                    </SelectContent>
                                </Select>
                            </div>
                            <div className="space-y-2">
                                <label className="text-sm font-medium">到期日期</label>
                                <Input
                                    type="date"
                                    value={formData.expireAt}
                                    onChange={(e) => setFormData({ ...formData, expireAt: e.target.value })}
                                />
                            </div>
                        </div>

                        <DialogFooter className="pt-4">
                            <Button type="button" variant="ghost" onClick={() => setIsDialogOpen(false)}>取消</Button>
                            <Button type="submit" disabled={isSubmitting}>
                                {isSubmitting && <Loader2 className="w-4 h-4 mr-2 animate-spin" />}
                                {editingTenant ? '确认修改' : '立即创建'}
                            </Button>
                        </DialogFooter>
                    </form>
                </DialogContent>
            </Dialog>
        </div>
    );
};

export default TenantListPage;
