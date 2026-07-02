import React, { useState, useEffect } from 'react';
import { FileText, Plus, Check, Trash2, Clock, AlertCircle } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Badge } from '@/components/ui/badge';
import { promptService } from '@/api/prompt';
import type { PromptTemplate, CreatePromptRequest } from '@/api/prompt';
import { useToast } from '@/hooks/use-toast';
import {
    Dialog,
    DialogContent,
    DialogDescription,
    DialogHeader,
    DialogTitle,
    DialogTrigger,
    DialogFooter,
} from "@/components/ui/dialog";
import {
    AlertDialog,
    AlertDialogAction,
    AlertDialogCancel,
    AlertDialogContent,
    AlertDialogDescription,
    AlertDialogFooter,
    AlertDialogHeader,
    AlertDialogTitle,
} from "@/components/ui/alert-dialog";

const PromptPage: React.FC = () => {
    const [templates, setTemplates] = useState<PromptTemplate[]>([]);
    const [isLoading, setIsLoading] = useState(true);
    const [isDialogOpen, setIsDialogOpen] = useState(false);
    const [deleteId, setDeleteId] = useState<number | null>(null);
    const [newPrompt, setNewPrompt] = useState<CreatePromptRequest>({
        name: '',
        content: '',
        description: ''
    });
    const { toast } = useToast();

    const fetchTemplates = async () => {
        try {
            setIsLoading(true);
            const response = await promptService.getAllVersions();
            if (response.code === 200) {
                setTemplates(response.data);
            }
        } catch (error: any) {
            toast({ variant: "destructive", title: "获取模板失败", description: error.message });
        } finally {
            setIsLoading(false);
        }
    };

    useEffect(() => {
        fetchTemplates();
    }, []);

    const handleCreate = async () => {
        if (!newPrompt.name.trim() || !newPrompt.content.trim()) {
            toast({ variant: "destructive", title: "请填写名称和内容" });
            return;
        }

        try {
            const response = await promptService.createVersion(newPrompt);
            if (response.code === 200) {
                toast({ title: "创建成功" });
                setIsDialogOpen(false);
                setNewPrompt({ name: '', content: '', description: '' });
                fetchTemplates();
            }
        } catch (error: any) {
            toast({ variant: "destructive", title: "创建失败", description: error.message });
        }
    };

    const handleActivate = async (id: number) => {
        try {
            const response = await promptService.activateVersion(id);
            if (response.code === 200) {
                toast({ title: "激活成功" });
                fetchTemplates();
            }
        } catch (error: any) {
            toast({ variant: "destructive", title: "激活失败", description: error.message });
        }
    };

    const handleDelete = async () => {
        if (!deleteId) return;
        try {
            const response = await promptService.deleteVersion(deleteId);
            if (response.code === 200) {
                toast({ title: "删除成功" });
                fetchTemplates();
            }
        } catch (error: any) {
            toast({ variant: "destructive", title: "删除失败", description: error.message });
        } finally {
            setDeleteId(null);
        }
    };

    return (
        <div className="p-8 h-full overflow-y-auto">
            <div className="max-w-4xl mx-auto space-y-6">
                {/* 标题栏 */}
                <div className="flex justify-between items-center">
                    <div>
                        <h1 className="text-2xl font-bold">Prompt 版本管理</h1>
                        <p className="text-muted-foreground text-sm mt-1">管理 AI 客服的系统提示词模板</p>
                    </div>
                    <Dialog open={isDialogOpen} onOpenChange={setIsDialogOpen}>
                        <DialogTrigger asChild>
                            <Button>
                                <Plus className="w-4 h-4 mr-2" />
                                新建版本
                            </Button>
                        </DialogTrigger>
                        <DialogContent className="max-w-2xl">
                            <DialogHeader>
                                <DialogTitle>创建新 Prompt 版本</DialogTitle>
                                <DialogDescription>
                                    创建新版本后可以在列表中激活使用
                                </DialogDescription>
                            </DialogHeader>
                            <div className="space-y-4 py-4">
                                <div>
                                    <label className="text-sm font-medium">名称</label>
                                    <Input
                                        value={newPrompt.name}
                                        onChange={(e) => setNewPrompt({ ...newPrompt, name: e.target.value })}
                                        placeholder="如：formal, casual, technical"
                                        className="mt-1"
                                    />
                                </div>
                                <div>
                                    <label className="text-sm font-medium">描述（可选）</label>
                                    <Input
                                        value={newPrompt.description}
                                        onChange={(e) => setNewPrompt({ ...newPrompt, description: e.target.value })}
                                        placeholder="版本描述"
                                        className="mt-1"
                                    />
                                </div>
                                <div>
                                    <label className="text-sm font-medium">Prompt 内容</label>
                                    <p className="text-xs text-muted-foreground mt-1 mb-2">
                                        使用 {'{context}'} 作为知识库检索结果的占位符
                                    </p>
                                    <textarea
                                        value={newPrompt.content}
                                        onChange={(e) => setNewPrompt({ ...newPrompt, content: e.target.value })}
                                        placeholder="你是轻语客服，一个专业、友好的AI智能客服助手..."
                                        className="w-full h-64 p-3 text-sm border rounded-lg resize-none focus:outline-none focus:ring-2 focus:ring-primary"
                                    />
                                </div>
                            </div>
                            <DialogFooter>
                                <Button variant="outline" onClick={() => setIsDialogOpen(false)}>
                                    取消
                                </Button>
                                <Button onClick={handleCreate}>
                                    创建
                                </Button>
                            </DialogFooter>
                        </DialogContent>
                    </Dialog>
                </div>

                {/* 版本列表 */}
                {isLoading ? (
                    <div className="text-center py-12 text-muted-foreground">加载中...</div>
                ) : templates.length === 0 ? (
                    <Card>
                        <CardContent className="py-12">
                            <div className="text-center text-muted-foreground">
                                <AlertCircle className="w-12 h-12 mx-auto mb-4 opacity-50" />
                                <p>暂无 Prompt 模板</p>
                            </div>
                        </CardContent>
                    </Card>
                ) : (
                    <div className="space-y-4">
                        {templates.map((template) => (
                            <Card key={template.id} className={template.isActive ? 'border-primary' : ''}>
                                <CardHeader className="pb-2">
                                    <div className="flex items-center justify-between">
                                        <div className="flex items-center gap-3">
                                            <FileText className="w-5 h-5 text-muted-foreground" />
                                            <CardTitle className="text-lg">{template.name}</CardTitle>
                                            <Badge variant="outline">v{template.version}</Badge>
                                            {template.isActive && (
                                                <Badge className="bg-green-500">激活中</Badge>
                                            )}
                                        </div>
                                        <div className="flex items-center gap-2">
                                            {!template.isActive && (
                                                <>
                                                    <Button
                                                        variant="outline"
                                                        size="sm"
                                                        onClick={() => handleActivate(template.id)}
                                                    >
                                                        <Check className="w-4 h-4 mr-1" />
                                                        激活
                                                    </Button>
                                                    <Button
                                                        variant="ghost"
                                                        size="icon"
                                                        className="text-destructive hover:bg-destructive/10"
                                                        onClick={() => setDeleteId(template.id)}
                                                    >
                                                        <Trash2 className="w-4 h-4" />
                                                    </Button>
                                                </>
                                            )}
                                        </div>
                                    </div>
                                    {template.description && (
                                        <p className="text-sm text-muted-foreground mt-1">{template.description}</p>
                                    )}
                                </CardHeader>
                                <CardContent>
                                    <pre className="text-xs bg-muted p-4 rounded-lg overflow-x-auto whitespace-pre-wrap max-h-48">
                                        {template.content}
                                    </pre>
                                    <div className="flex items-center gap-4 mt-3 text-xs text-muted-foreground">
                                        <span className="flex items-center gap-1">
                                            <Clock className="w-3 h-3" />
                                            创建于 {new Date(template.createdAt).toLocaleString()}
                                        </span>
                                    </div>
                                </CardContent>
                            </Card>
                        ))}
                    </div>
                )}
            </div>

            {/* 删除确认弹窗 */}
            <AlertDialog open={deleteId !== null} onOpenChange={(open) => !open && setDeleteId(null)}>
                <AlertDialogContent>
                    <AlertDialogHeader>
                        <AlertDialogTitle>确认删除</AlertDialogTitle>
                        <AlertDialogDescription>
                            确定要删除此 Prompt 版本吗？此操作无法撤销。
                        </AlertDialogDescription>
                    </AlertDialogHeader>
                    <AlertDialogFooter>
                        <AlertDialogCancel>取消</AlertDialogCancel>
                        <AlertDialogAction onClick={handleDelete} className="bg-destructive text-destructive-foreground hover:bg-destructive/90">
                            删除
                        </AlertDialogAction>
                    </AlertDialogFooter>
                </AlertDialogContent>
            </AlertDialog>
        </div>
    );
};

export default PromptPage;
