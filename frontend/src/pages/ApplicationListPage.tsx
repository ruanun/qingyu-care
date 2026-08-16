import React, { useState, useEffect } from 'react';
import { Plus, Search, Layers, Key, Copy, Eye, EyeOff, MoreHorizontal, Edit2, Trash2, Loader2, Palette, MessageSquare, Shield } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Card, CardHeader, CardTitle, CardDescription, CardContent, CardFooter } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { Separator } from '@/components/ui/separator';
import { Switch } from '@/components/ui/switch';
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
import { applicationService } from '@/api/application';
import type { Application } from '@/types';
import { useToast } from '@/hooks/use-toast';
import { getErrorMessage, throwIfFailed } from '@/lib/apiResult';

const ApplicationListPage: React.FC = () => {
    const [apps, setApps] = useState<Application[]>([]);
    const [isLoading, setIsLoading] = useState(false);
    const [searchQuery, setSearchQuery] = useState('');
    const [isDialogOpen, setIsDialogOpen] = useState(false);
    const [editingApp, setEditingApp] = useState<Application | null>(null);
    const [showSecretId, setShowSecretId] = useState<number | null>(null);
    const [formData, setFormData] = useState({
        name: '',
        description: '',
        welcomeMessage: '',
        themeColor: '#0d9488',
    });
    const [isSubmitting, setIsSubmitting] = useState(false);
    const { toast } = useToast();

    useEffect(() => {
        fetchApps();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    async function fetchApps() {
        setIsLoading(true);
        try {
            const response = await applicationService.list();
            throwIfFailed(response);
            setApps(response.data);
        } catch (error: unknown) {
            toast({ variant: "destructive", title: "加载失败", description: getErrorMessage(error) });
        } finally {
            setIsLoading(false);
        }
    };

    const handleOpenDialog = (app?: Application) => {
        if (app) {
            setEditingApp(app);
            setFormData({
                name: app.name,
                description: app.description || '',
                welcomeMessage: app.welcomeMessage || '',
                themeColor: app.themeColor || '#0d9488',
            });
        } else {
            setEditingApp(null);
            setFormData({
                name: '',
                description: '',
                welcomeMessage: '您好！我是您的智能助理，很高兴为您服务。',
                themeColor: '#0d9488',
            });
        }
        setIsDialogOpen(true);
    };

    const handleSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        setIsSubmitting(true);
        try {
            if (editingApp) {
                const response = await applicationService.update(editingApp.id, formData);
                throwIfFailed(response);
                toast({ title: "更新成功" });
                fetchApps();
                setIsDialogOpen(false);
            } else {
                const response = await applicationService.create(formData);
                throwIfFailed(response);
                toast({ title: "创建成功", description: `应用 ${formData.name} 已创建` });
                fetchApps();
                setIsDialogOpen(false);
            }
        } catch (error: unknown) {
            toast({ variant: "destructive", title: "保存失败", description: getErrorMessage(error) });
        } finally {
            setIsSubmitting(false);
        }
    };

    const toggleAppStatus = async (app: Application) => {
        try {
            const response = await applicationService.update(app.id, { enabled: !app.enabled });
            throwIfFailed(response);
            toast({ title: app.enabled ? "应用已禁用" : "应用已启用" });
            fetchApps();
        } catch (error: unknown) {
            toast({ variant: "destructive", title: "操作失败", description: getErrorMessage(error) });
        }
    };

    const copyToClipboard = (text: string, label: string) => {
        navigator.clipboard.writeText(text);
        toast({ title: `${label} 已复制` });
    };

    const filteredApps = apps.filter(a =>
        a.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
        a.appKey.toLowerCase().includes(searchQuery.toLowerCase())
    );

    return (
        <div className="p-8 max-w-7xl mx-auto space-y-8">
            <div className="flex flex-col md:flex-row justify-between items-start md:items-center gap-4">
                <div>
                    <h1 className="text-3xl font-bold tracking-tight">应用管理</h1>
                    <p className="text-muted-foreground mt-1">创建和配置您的 AI 应用，每个应用拥有独立的 Key 和 Secret。</p>
                </div>
                <Button onClick={() => handleOpenDialog()} className="gap-2">
                    <Plus className="w-4 h-4" /> 新建应用
                </Button>
            </div>

            <div className="relative max-w-md">
                <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-muted-foreground" />
                <Input
                    placeholder="搜索应用名称或 AppKey..."
                    className="pl-9 bg-muted/30"
                    value={searchQuery}
                    onChange={(e) => setSearchQuery(e.target.value)}
                />
            </div>

            {isLoading ? (
                <div className="flex flex-col items-center justify-center py-24 gap-4">
                    <Loader2 className="w-10 h-10 animate-spin text-primary" />
                    <p className="text-muted-foreground">正在加载应用列表...</p>
                </div>
            ) : filteredApps.length === 0 ? (
                <Card className="border-dashed py-24 flex flex-col items-center justify-center text-center opacity-60">
                    <Layers className="w-16 h-16 mb-4 text-muted-foreground/30" />
                    <p className="text-xl font-medium">暂无应用</p>
                    <p className="text-muted-foreground max-w-xs mt-2">点击“新建应用”按钮开始创建您的第一个智能助理。</p>
                </Card>
            ) : (
                <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
                    {filteredApps.map((app) => (
                        <Card key={app.id} className="group hover:border-primary/20 transition-all shadow-xs overflow-hidden flex flex-col">
                            <CardHeader className="pb-4">
                                <div className="flex justify-between items-start">
                                    <Avatar className="h-12 w-12 border cursor-pointer hover:scale-105 transition-transform">
                                        <AvatarImage src={app.avatar} />
                                        <AvatarFallback style={{ backgroundColor: app.themeColor + '20', color: app.themeColor }}>
                                            {app.name.substring(0, 2).toUpperCase()}
                                        </AvatarFallback>
                                    </Avatar>
                                    <DropdownMenu>
                                        <DropdownMenuTrigger asChild>
                                            <Button variant="ghost" size="icon" className="h-8 w-8">
                                                <MoreHorizontal className="w-4 h-4" />
                                            </Button>
                                        </DropdownMenuTrigger>
                                        <DropdownMenuContent align="end">
                                            <DropdownMenuItem onClick={() => handleOpenDialog(app)} className="gap-2">
                                                <Edit2 className="w-4 h-4" /> 编辑配置
                                            </DropdownMenuItem>
                                            <DropdownMenuItem className="gap-2">
                                                <Key className="w-4 h-4" /> 重置 Secret
                                            </DropdownMenuItem>
                                            <Separator className="my-1" />
                                            <DropdownMenuItem className="gap-2 text-destructive focus:text-destructive">
                                                <Trash2 className="w-4 h-4" /> 删除应用
                                            </DropdownMenuItem>
                                        </DropdownMenuContent>
                                    </DropdownMenu>
                                </div>
                                <div className="mt-3">
                                    <div className="flex items-center gap-2">
                                        <CardTitle className="text-lg">{app.name}</CardTitle>
                                        {!app.enabled && <Badge variant="secondary" className="text-[10px] h-4">已禁用</Badge>}
                                    </div>
                                    <CardDescription className="line-clamp-2 mt-1 h-10">
                                        {app.description || '暂无应用描述'}
                                    </CardDescription>
                                </div>
                            </CardHeader>
                            <CardContent className="space-y-4 py-2 grow">
                                <div className="space-y-2.5">
                                    <div className="space-y-1">
                                        <label className="text-[10px] font-semibold text-muted-foreground uppercase flex items-center gap-1.5">
                                            <Shield className="w-3 h-3" /> AppKey (公开)
                                        </label>
                                        <div className="flex items-center gap-2 bg-muted/40 p-1.5 px-3 rounded-md border group-hover:border-primary/10 transition-colors">
                                            <code className="text-xs font-mono flex-1 overflow-hidden truncate">{app.appKey}</code>
                                            <Button variant="ghost" size="icon" className="h-6 w-6" onClick={() => copyToClipboard(app.appKey, 'AppKey')}>
                                                <Copy className="w-3 h-3" />
                                            </Button>
                                        </div>
                                    </div>
                                    <div className="space-y-1">
                                        <label className="text-[10px] font-semibold text-muted-foreground uppercase flex items-center gap-1.5">
                                            <Key className="w-3 h-3" /> AppSecret (私密)
                                        </label>
                                        <div className="flex items-center gap-2 bg-muted/40 p-1.5 px-3 rounded-md border">
                                            <code className="text-xs font-mono flex-1 overflow-hidden truncate">
                                                {showSecretId === app.id ? app.appSecret : '••••••••••••••••••••••••'}
                                            </code>
                                            <div className="flex gap-1">
                                                <Button variant="ghost" size="icon" className="h-6 w-6" onClick={() => setShowSecretId(showSecretId === app.id ? null : app.id)}>
                                                    {showSecretId === app.id ? <EyeOff className="w-3 h-3" /> : <Eye className="w-3 h-3" />}
                                                </Button>
                                                <Button variant="ghost" size="icon" className="h-6 w-6" onClick={() => copyToClipboard(app.appSecret || '', 'AppSecret')}>
                                                    <Copy className="w-3 h-3" />
                                                </Button>
                                            </div>
                                        </div>
                                    </div>
                                </div>
                            </CardContent>
                            <CardFooter className="pt-2 border-t mt-auto flex justify-between">
                                <div className="flex items-center gap-4 text-xs text-muted-foreground">
                                    <div className="flex items-center gap-1">
                                        <div className="w-2 h-2 rounded-full" style={{ backgroundColor: app.themeColor }} />
                                        主题预览
                                    </div>
                                </div>
                                <div className="flex items-center gap-2">
                                    <span className="text-[10px] font-medium transition-colors" style={{ color: app.enabled ? '#10b981' : '#94a3b8' }}>
                                        {app.enabled ? '正常运行' : '已停止'}
                                    </span>
                                    <Switch
                                        checked={app.enabled}
                                        onCheckedChange={() => toggleAppStatus(app)}
                                        className="scale-90"
                                    />
                                </div>
                            </CardFooter>
                        </Card>
                    ))}
                </div>
            )}

            <Dialog open={isDialogOpen} onOpenChange={setIsDialogOpen}>
                <DialogContent className="sm:max-w-[500px]">
                    <DialogHeader>
                        <DialogTitle>{editingApp ? '编辑应用配置' : '创建新应用'}</DialogTitle>
                        <DialogDescription>
                            设置应用的基本信息和欢迎语。创建后系统将自动生成唯一的 API 凭证。
                        </DialogDescription>
                    </DialogHeader>
                    <form onSubmit={handleSubmit} className="space-y-5 py-2">
                        <div className="space-y-2">
                            <label className="text-sm font-medium">应用名称</label>
                            <Input
                                required
                                value={formData.name}
                                onChange={(e) => setFormData({ ...formData, name: e.target.value })}
                                placeholder="例如：官方智能客服"
                            />
                        </div>
                        <div className="space-y-2">
                            <label className="text-sm font-medium">应用描述</label>
                            <Input
                                value={formData.description}
                                onChange={(e) => setFormData({ ...formData, description: e.target.value })}
                                placeholder="简要描述此应用的服务目标"
                            />
                        </div>
                        <div className="space-y-2">
                            <label className="text-sm font-medium flex items-center gap-1.5"><MessageSquare className="w-4 h-4 text-primary" /> 欢迎语</label>
                            <textarea
                                className="w-full flex min-h-[80px] rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background placeholder:text-muted-foreground focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-50"
                                value={formData.welcomeMessage}
                                onChange={(e) => setFormData({ ...formData, welcomeMessage: e.target.value })}
                                placeholder="用户进入对话时首先看到的消息"
                            />
                        </div>
                        <div className="space-y-2">
                            <label className="text-sm font-medium flex items-center gap-1.5"><Palette className="w-4 h-4 text-primary" /> 主题色</label>
                            <div className="flex items-center gap-4">
                                <Input
                                    type="color"
                                    className="w-16 h-10 p-1 cursor-pointer"
                                    value={formData.themeColor}
                                    onChange={(e) => setFormData({ ...formData, themeColor: e.target.value })}
                                />
                                <code className="text-xs font-mono bg-muted px-2 py-1 rounded">{formData.themeColor}</code>
                            </div>
                        </div>
                        <DialogFooter className="pt-2">
                            <Button type="button" variant="ghost" onClick={() => setIsDialogOpen(false)}>取消</Button>
                            <Button type="submit" disabled={isSubmitting}>
                                {isSubmitting && <Loader2 className="w-4 h-4 mr-2 animate-spin" />}
                                {editingApp ? '保存配置' : '立即创建'}
                            </Button>
                        </DialogFooter>
                    </form>
                </DialogContent>
            </Dialog>
        </div>
    );
};

export default ApplicationListPage;
