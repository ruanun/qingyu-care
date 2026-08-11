import React, { useState, useEffect, useCallback } from 'react';
import { getErrorMessage, throwIfFailed } from '@/lib/apiResult';
import { Upload, Trash2, FileText, CheckCircle2, XCircle, Clock, RefreshCw, Plus, Database, Loader2, Globe } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Card, CardHeader, CardTitle, CardDescription, CardContent } from '@/components/ui/card';
import { Progress } from '@/components/ui/progress';
import { Separator } from '@/components/ui/separator';
import { knowledgeService } from '@/api/knowledge';
import type { KnowledgeBase, Document } from '@/types';
import { useToast } from '@/hooks/use-toast';
import { cn } from '@/lib/utils';
import QaPairPanel from '@/components/QaPairPanel';
import { CrawlSiteList } from '@/components/knowledge/CrawlSiteList';
import {
    Dialog,
    DialogContent,
    DialogDescription,
    DialogHeader,
    DialogTitle,
    DialogTrigger,
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

const KnowledgePage: React.FC = () => {
    const [knowledgeBases, setKnowledgeBases] = useState<KnowledgeBase[]>([]);
    const [selectedKb, setSelectedKb] = useState<KnowledgeBase | null>(null);
    const [documents, setDocuments] = useState<Document[]>([]);
    const [isLoading, setIsLoading] = useState(false);
    const [isDocLoading, setIsDocLoading] = useState(false);
    const [isUploading, setIsUploading] = useState(false);
    const [deleteDocId, setDeleteDocId] = useState<number | null>(null);
    const [activeTab, setActiveTab] = useState<'docs' | 'qa'>('docs');
    const { toast } = useToast();

    const fetchDocuments = useCallback(async (kbId: number) => {
        setIsDocLoading(true);
        try {
            const response = await knowledgeService.getDocuments(kbId);
            if (response.code === 200) {
                setDocuments(response.data);
            }
        } catch (error: unknown) {
            toast({ variant: "destructive", title: "获取文档失败", description: getErrorMessage(error) });
        } finally {
            setIsDocLoading(false);
        }
    }, [toast]);

    async function fetchKnowledgeBases() {
        setIsLoading(true);
        try {
            const response = await knowledgeService.getKnowledgeBases();
            if (response.code === 200) {
                setKnowledgeBases(response.data);
                if (response.data.length > 0 && !selectedKb) {
                    const firstKb = response.data[0];
                    setSelectedKb(firstKb);
                    fetchDocuments(firstKb.id);
                }
            }
        } catch (error: unknown) {
            toast({ variant: "destructive", title: "加载失败", description: getErrorMessage(error) });
        } finally {
            setIsLoading(false);
        }
    }

    useEffect(() => {
        // 挂载仅拉取一次；set-state 属预期（mount 数据加载，非级联渲染）
        // eslint-disable-next-line react-hooks/set-state-in-effect
        fetchKnowledgeBases();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    const handleSelectKb = (kb: KnowledgeBase) => {
        setSelectedKb(kb);
        fetchDocuments(kb.id);
    };

    const handleUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
        const file = e.target.files?.[0];
        if (!file || !selectedKb) return;

        setIsUploading(true);
        try {
            const response = await knowledgeService.uploadDocument(selectedKb.id, file);
            throwIfFailed(response);
            toast({ title: "上传成功", description: "文档正在处理中..." });
            // 刷新文档列表
            fetchDocuments(selectedKb.id);
        } catch (error: unknown) {
            toast({ variant: "destructive", title: "上传失败", description: getErrorMessage(error) });
        } finally {
            setIsUploading(false);
            // 清空文件输入
            e.target.value = '';
        }
    };

    const handleDeleteDocument = async () => {
        if (!deleteDocId) return;

        try {
            const response = await knowledgeService.deleteDocument(deleteDocId);
            throwIfFailed(response);
            toast({ title: "删除成功" });
            if (selectedKb) {
                fetchDocuments(selectedKb.id);
            }
        } catch (error: unknown) {
            toast({ variant: "destructive", title: "删除失败", description: getErrorMessage(error) });
        } finally {
            setDeleteDocId(null);
        }
    };

    const handleToggleVisibility = async (doc: Document) => {
        const next = doc.visibility === 'INTERNAL' ? 'PUBLIC' : 'INTERNAL';
        try {
            const response = await knowledgeService.updateDocumentVisibility(doc.id, next);
            throwIfFailed(response);
            setDocuments((prev) => prev.map((d) => (d.id === doc.id ? { ...d, visibility: next } : d)));
            toast({ title: next === 'INTERNAL' ? '文档已设为内部（仅受信渠道可检索）' : '文档已设为公开' });
        } catch (error) {
            const message = error instanceof Error ? error.message : String(error);
            toast({ variant: "destructive", title: "切换可见性失败", description: message });
        }
    };

    const handleToggleEnabled = async (doc: Document) => {
        const next = !(doc.enabled ?? true);
        try {
            const response = await knowledgeService.setDocumentEnabled(doc.id, next);
            throwIfFailed(response);
            setDocuments((prev) => prev.map((d) => (d.id === doc.id ? { ...d, enabled: next } : d)));
            toast({ title: next ? '文档已启用检索' : '文档已停用检索' });
        } catch (error) {
            const message = error instanceof Error ? error.message : String(error);
            toast({ variant: "destructive", title: "切换启用状态失败", description: message });
        }
    };

    const [webPageOpen, setWebPageOpen] = useState(false);
    const [webPageUrl, setWebPageUrl] = useState('');
    const [webPageInterval, setWebPageInterval] = useState('DAILY');
    const [syncingDocId, setSyncingDocId] = useState<number | null>(null);

    const handleAddWebPage = async () => {
        if (!webPageUrl.trim() || !selectedKb) return;
        try {
            await knowledgeService.addWebPage(selectedKb.id, webPageUrl.trim(), webPageInterval);
            setWebPageOpen(false);
            setWebPageUrl('');
            toast({ title: '网页已添加', description: '正在后台抓取，稍后刷新查看结果' });
            fetchDocuments(selectedKb.id);
        } catch (error) {
            const message = error instanceof Error ? error.message : String(error);
            toast({ variant: 'destructive', title: '添加失败', description: message });
        }
    };

    const handleSyncWebPage = async (docId: number) => {
        setSyncingDocId(docId);
        try {
            await knowledgeService.syncWebPage(docId);
            toast({ title: '已触发同步', description: '正在后台重新抓取' });
            setTimeout(() => selectedKb && fetchDocuments(selectedKb.id), 2000);
        } catch (error) {
            const message = error instanceof Error ? error.message : String(error);
            toast({ variant: 'destructive', title: '同步失败', description: message });
        } finally {
            setSyncingDocId(null);
        }
    };

    const getStatusIcon = (status: string) => {
        switch (status) {
            case 'COMPLETED': return <CheckCircle2 className="w-4 h-4 text-green-500" />;
            case 'FAILED': return <XCircle className="w-4 h-4 text-red-500" />;
            case 'PROCESSING': return <RefreshCw className="w-4 h-4 text-blue-500 animate-spin" />;
            default: return <Clock className="w-4 h-4 text-gray-500" />;
        }
    };

    const getStatusProgress = (status: string) => {
        switch (status) {
            case 'COMPLETED': return 100;
            case 'PROCESSING': return 50;
            case 'PENDING': return 10;
            default: return 0;
        }
    };

    return (
        <div className="p-8 max-w-6xl mx-auto space-y-8 h-full overflow-y-auto">
            <div className="flex justify-between items-end">
                <div>
                    <h1 className="text-3xl font-bold tracking-tight">知识库管理</h1>
                    <p className="text-muted-foreground mt-2">创建并管理您的 AI 知识资源，上传文档以增强机器人回答能力。</p>
                </div>
                <Dialog>
                    <DialogTrigger asChild>
                        <Button className="gap-2">
                            <Plus className="w-4 h-4" /> 创建知识库
                        </Button>
                    </DialogTrigger>
                    <DialogContent>
                        <DialogHeader>
                            <DialogTitle>新建知识库</DialogTitle>
                            <DialogDescription>
                                为您的知识库起一个直观的名字并添加描述。
                            </DialogDescription>
                        </DialogHeader>
                        <div className="space-y-4 py-4">
                            <div className="space-y-2">
                                <label className="text-sm font-medium">名称</label>
                                <Input placeholder="例如：算法导论" />
                            </div>
                            <div className="space-y-2">
                                <label className="text-sm font-medium">描述</label>
                                <Input placeholder="关于基础算法的经典教材" />
                            </div>
                            <Button className="w-full">创建</Button>
                        </div>
                    </DialogContent>
                </Dialog>
            </div>

            <div className="grid grid-cols-1 md:grid-cols-4 gap-6">
                <div className="md:col-span-1 space-y-3">
                    <h3 className="text-xs font-semibold uppercase tracking-wider text-muted-foreground px-2">知识库列表</h3>
                    {isLoading ? (
                        <div className="flex items-center justify-center py-8">
                            <Loader2 className="w-6 h-6 animate-spin text-muted-foreground" />
                        </div>
                    ) : knowledgeBases.length === 0 ? (
                        <div className="text-center py-8 text-muted-foreground text-sm">
                            暂无知识库
                        </div>
                    ) : (
                        knowledgeBases.map((kb) => (
                            <div
                                key={kb.id}
                                onClick={() => handleSelectKb(kb)}
                                className={cn(
                                    "p-3 rounded-xl border flex items-center gap-3 cursor-pointer transition-all",
                                    selectedKb?.id === kb.id
                                        ? "bg-primary/5 border-primary/20 ring-1 ring-primary/20"
                                        : "hover:bg-muted/50 text-muted-foreground"
                                )}
                            >
                                <Database className={cn("w-4 h-4", selectedKb?.id === kb.id ? "text-primary" : "")} />
                                <div className="flex-1 overflow-hidden">
                                    <p className="text-sm font-semibold truncate">{kb.name}</p>
                                    <p className="text-[10px] truncate opacity-60">{kb.description || '无描述'}</p>
                                </div>
                            </div>
                        ))
                    )}
                </div>

                <div className="md:col-span-3 space-y-6">
                    {selectedKb ? (
                        <Card className="shadow-xs">
                            <CardHeader className="flex flex-row items-center justify-between space-y-0">
                                <div>
                                    <CardTitle>{selectedKb.name}</CardTitle>
                                    <CardDescription>{selectedKb.description}</CardDescription>
                                </div>
                                <div className="flex gap-2">
                                    <Button variant="outline" size="sm" className="relative cursor-pointer" disabled={isUploading}>
                                        {isUploading ? (
                                            <Loader2 className="w-4 h-4 mr-2 animate-spin" />
                                        ) : (
                                            <Upload className="w-4 h-4 mr-2" />
                                        )}
                                        上传文档
                                        <input
                                            type="file"
                                            className="absolute inset-0 opacity-0 cursor-pointer"
                                            onChange={handleUpload}
                                            disabled={isUploading}
                                            accept=".pdf,.txt,.doc,.docx"
                                        />
                                    </Button>
                                    <Dialog open={webPageOpen} onOpenChange={setWebPageOpen}>
                                        <DialogTrigger asChild>
                                            <Button variant="outline" size="sm" className="gap-2">
                                                <Globe className="w-4 h-4" /> 添加网页
                                            </Button>
                                        </DialogTrigger>
                                        <DialogContent>
                                            <DialogHeader>
                                                <DialogTitle>添加网页到知识库</DialogTitle>
                                                <DialogDescription>
                                                    抓取网页正文入库（自动剔除导航、页脚等噪声）。只支持 http/https，且不能是内网地址。
                                                </DialogDescription>
                                            </DialogHeader>
                                            <div className="space-y-4">
                                                <Input
                                                    placeholder="https://example.com/faq"
                                                    value={webPageUrl}
                                                    onChange={(e) => setWebPageUrl(e.target.value)}
                                                />
                                                <div className="space-y-1">
                                                    <label className="text-xs text-muted-foreground">自动同步周期</label>
                                                    <select
                                                        className="w-full h-9 px-3 rounded-md border bg-background text-sm"
                                                        value={webPageInterval}
                                                        onChange={(e) => setWebPageInterval(e.target.value)}
                                                    >
                                                        <option value="MANUAL">不自动同步</option>
                                                        <option value="HOURLY">每小时</option>
                                                        <option value="DAILY">每天</option>
                                                        <option value="WEEKLY">每周</option>
                                                    </select>
                                                </div>
                                                <Button className="w-full" onClick={handleAddWebPage} disabled={!webPageUrl.trim()}>
                                                    添加
                                                </Button>
                                            </div>
                                        </DialogContent>
                                    </Dialog>
                                </div>
                            </CardHeader>
                            <CardContent>
                                <Separator className="mb-6" />
                                <CrawlSiteList
                                    key={selectedKb.id}
                                    knowledgeBaseId={selectedKb.id}
                                    onCrawlFinished={() => fetchDocuments(selectedKb.id)}
                                />
                                <Separator className="my-6" />
                                <div className="flex gap-4 border-b mb-4">
                                    <button className={cn('pb-2 text-sm font-medium border-b-2 -mb-px', activeTab === 'docs' ? 'border-primary text-primary' : 'border-transparent text-muted-foreground')} onClick={() => setActiveTab('docs')}>文档</button>
                                    <button className={cn('pb-2 text-sm font-medium border-b-2 -mb-px', activeTab === 'qa' ? 'border-primary text-primary' : 'border-transparent text-muted-foreground')} onClick={() => setActiveTab('qa')}>问答对</button>
                                </div>
                                {activeTab === 'qa' ? (
                                    <QaPairPanel kbId={selectedKb.id} documents={documents} />
                                ) : (
                                <div className="space-y-4">
                                    <h4 className="text-sm font-semibold">已上传文档 ({documents.length})</h4>

                                    {isDocLoading ? (
                                        <div className="flex items-center justify-center py-12">
                                            <Loader2 className="w-6 h-6 animate-spin text-muted-foreground" />
                                        </div>
                                    ) : documents.length === 0 ? (
                                        <div className="py-12 text-center border-2 border-dashed rounded-xl">
                                            <FileText className="w-12 h-12 mx-auto text-muted-foreground/30 mb-3" />
                                            <p className="text-muted-foreground text-sm">暂无文档，点击上方按钮上传</p>
                                        </div>
                                    ) : (
                                        documents.map((doc) => (
                                            <div key={doc.id} className="group p-4 rounded-xl border bg-muted/20 flex items-center gap-4 hover:border-primary/20 transition-all">
                                                <div className="w-10 h-10 rounded-lg bg-background border flex items-center justify-center">
                                                    <FileText className="w-5 h-5 text-muted-foreground" />
                                                </div>
                                                <div className="flex-1">
                                                    <div className="flex items-center gap-2">
                                                        <span className="text-sm font-medium">{doc.name}</span>
                                                        {getStatusIcon(doc.status)}
                                                    </div>
                                                    <div className="flex items-center gap-3 mt-1 text-[10px] text-muted-foreground">
                                                        {doc.fileSize && (
                                                            <span>大小: {doc.fileSize > 1024 * 1024
                                                                ? (doc.fileSize / 1024 / 1024).toFixed(1) + ' MB'
                                                                : (doc.fileSize / 1024).toFixed(1) + ' KB'}</span>
                                                        )}
                                                        <span>切片: {doc.chunkCount || 0}</span>
                                                        <span>{new Date(doc.createdAt).toLocaleDateString()}</span>
                                                    </div>
                                                    {doc.sourceUrl && (
                                                        <div className="flex items-center gap-3 mt-1 text-[10px]">
                                                            <a
                                                                href={doc.sourceUrl}
                                                                target="_blank"
                                                                rel="noopener noreferrer"
                                                                className="text-primary hover:underline truncate max-w-[240px]"
                                                            >
                                                                {doc.sourceUrl}
                                                            </a>
                                                            <span className="text-muted-foreground">
                                                                上次同步: {doc.lastSyncedAt
                                                                    ? new Date(doc.lastSyncedAt).toLocaleString()
                                                                    : '从未'}
                                                            </span>
                                                            {(doc.syncFailureCount ?? 0) > 0 && (
                                                                <span className="text-amber-600">
                                                                    连续失败 {doc.syncFailureCount} 次
                                                                </span>
                                                            )}
                                                        </div>
                                                    )}
                                                    {doc.status === 'PROCESSING' && (
                                                        <Progress value={getStatusProgress(doc.status)} className="h-1 mt-2 w-32" />
                                                    )}
                                                    {/* 网页同步失败时状态未必是 FAILED：从未成功过的停在 FAILED，
                                                        而已成功过的会保留 COMPLETED（旧内容与旧向量仍在服务，
                                                        标成 FAILED 是撒谎）。只认 FAILED 会让这两类失败在界面上
                                                        彻底不显示——而运营只看界面 */}
                                                    {doc.errorMessage && (doc.status === 'FAILED' || (doc.syncFailureCount ?? 0) > 0) && (
                                                        <p className="text-[10px] text-red-500 mt-1">{doc.errorMessage}</p>
                                                    )}
                                                </div>
                                                <div className="flex items-center gap-2">
                                                    <button
                                                        onClick={() => handleToggleVisibility(doc)}
                                                        className={`text-[10px] px-2 py-0.5 rounded-full border transition-colors ${
                                                            doc.visibility === 'INTERNAL'
                                                                ? 'bg-amber-50 text-amber-700 border-amber-200'
                                                                : 'bg-emerald-50 text-emerald-700 border-emerald-200'
                                                        }`}
                                                        title="点击切换可见性（公开=所有渠道可检索；内部=仅 API Key/后台可检索）"
                                                    >
                                                        {doc.visibility === 'INTERNAL' ? '内部' : '公开'}
                                                    </button>
                                                    <button
                                                        onClick={() => handleToggleEnabled(doc)}
                                                        className={`text-[10px] px-2 py-0.5 rounded-full border transition-colors ${
                                                            (doc.enabled ?? true)
                                                                ? 'bg-primary/10 text-primary border-primary/20'
                                                                : 'bg-muted text-muted-foreground border-border line-through'
                                                        }`}
                                                        title="点击切换是否参与检索（禁用不删向量，重新启用免重嵌入）"
                                                    >
                                                        {(doc.enabled ?? true) ? '检索中' : '已停用'}
                                                    </button>
                                                    {doc.sourceUrl && (
                                                        <Button
                                                            variant="ghost"
                                                            size="icon"
                                                            className="h-8 w-8"
                                                            title="立即重新抓取"
                                                            disabled={syncingDocId === doc.id}
                                                            onClick={() => handleSyncWebPage(doc.id)}
                                                        >
                                                            <RefreshCw className={`w-4 h-4 ${syncingDocId === doc.id ? 'animate-spin' : ''}`} />
                                                        </Button>
                                                    )}
                                                    <Button
                                                        variant="ghost"
                                                        size="icon"
                                                        className="h-8 w-8 text-destructive hover:bg-destructive/10"
                                                        onClick={() => setDeleteDocId(doc.id)}
                                                    >
                                                        <Trash2 className="w-4 h-4" />
                                                    </Button>
                                                </div>
                                            </div>
                                        ))
                                    )}
                                </div>
                                )}
                            </CardContent>
                        </Card>
                    ) : (
                        <div className="h-64 flex flex-col items-center justify-center text-center border-2 border-dashed rounded-3xl opacity-40">
                            <Database className="w-12 h-12 mb-4" />
                            <p>请选择一个知识库以查看文档</p>
                        </div>
                    )}
                </div>
            </div>

            {/* 删除确认弹窗 */}
            <AlertDialog open={deleteDocId !== null} onOpenChange={(open) => !open && setDeleteDocId(null)}>
                <AlertDialogContent>
                    <AlertDialogHeader>
                        <AlertDialogTitle>确认删除</AlertDialogTitle>
                        <AlertDialogDescription>
                            确定要删除此文档吗？相关向量数据也会被清除，此操作无法撤销。
                        </AlertDialogDescription>
                    </AlertDialogHeader>
                    <AlertDialogFooter>
                        <AlertDialogCancel>取消</AlertDialogCancel>
                        <AlertDialogAction onClick={handleDeleteDocument} className="bg-destructive text-destructive-foreground hover:bg-destructive/90">
                            删除
                        </AlertDialogAction>
                    </AlertDialogFooter>
                </AlertDialogContent>
            </AlertDialog>
        </div>
    );
};

export default KnowledgePage;
