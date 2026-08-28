import React, { useEffect, useState } from 'react';
import { Package, Upload, Trash2, Send, Archive } from 'lucide-react';
import { toolPackageService } from '@/api/toolPackage';
import type { ToolPackage } from '@/api/toolPackage';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Input } from '@/components/ui/input';
import {
    Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter,
} from '@/components/ui/dialog';
import PackageExportDialog from '@/components/pkg/PackageExportDialog';
import { useToast } from '@/hooks/use-toast';
import { getErrorMessage, throwIfFailed } from '@/lib/apiResult';

const statusLabel: Record<ToolPackage['status'], string> = {
    DRAFT: '草稿',
    PUBLISHED: '已发布',
    ARCHIVED: '已下架',
};

/**
 * 能力包管理（平台管理员）。
 *
 * ⚠️ 发布前必须逐字看一遍 manifest——凭据升格与密钥启发式都是启发式，没法自动化
 * （spec §4.1）。所以编辑对话框里的 manifest 文本框既是编辑器也是那份「预览」。
 *
 * @author ruan
 */
const PlatformPackagePage: React.FC = () => {
    const { toast } = useToast();
    const [list, setList] = useState<ToolPackage[]>([]);
    const [isLoading, setIsLoading] = useState(true);
    const [exportOpen, setExportOpen] = useState(false);
    const [warnings, setWarnings] = useState<string[]>([]);
    const [editing, setEditing] = useState<ToolPackage | null>(null);
    const [draftName, setDraftName] = useState('');
    const [draftDescription, setDraftDescription] = useState('');
    const [draftManifest, setDraftManifest] = useState('');
    const [isSaving, setIsSaving] = useState(false);

    const reload = async () => {
        try {
            setIsLoading(true);
            const res = await toolPackageService.listAll();
            throwIfFailed(res);
            setList(res.data ?? []);
        } catch (error: unknown) {
            toast({ variant: 'destructive', title: '加载失败', description: getErrorMessage(error) });
        } finally {
            setIsLoading(false);
        }
    };

    useEffect(() => {
        // eslint-disable-next-line react-hooks/set-state-in-effect -- 同 SharedToolPage：取数函数开头会 setIsLoading(true)
        reload();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    const openEditor = async (pkg: ToolPackage) => {
        try {
            const res = await toolPackageService.getById(pkg.id);
            throwIfFailed(res);
            const full = res.data;
            setEditing(full);
            setDraftName(full.name);
            setDraftDescription(full.description ?? '');
            setDraftManifest(prettify(full.manifest));
        } catch (error: unknown) {
            toast({ variant: 'destructive', title: '打开失败', description: getErrorMessage(error) });
        }
    };

    /** manifest 是一行紧凑 JSON，格式化后人才读得下去；解析不了就原样显示 */
    const prettify = (json: string): string => {
        try {
            return JSON.stringify(JSON.parse(json), null, 2);
        } catch {
            return json;
        }
    };

    const handleSave = async () => {
        if (!editing) return;
        try {
            setIsSaving(true);
            const res = await toolPackageService.update(editing.id, {
                name: draftName, description: draftDescription, manifest: draftManifest,
            });
            throwIfFailed(res);
            toast({ title: '已保存' });
            setEditing(null);
            await reload();
        } catch (error: unknown) {
            toast({ variant: 'destructive', title: '保存失败', description: getErrorMessage(error) });
        } finally {
            setIsSaving(false);
        }
    };

    const handlePublish = async (pkg: ToolPackage) => {
        try {
            const res = await toolPackageService.publish(pkg.id);
            throwIfFailed(res);
            toast({ title: '已发布', description: '租户现在可以在工具商店里看到它' });
            await reload();
        } catch (error: unknown) {
            toast({ variant: 'destructive', title: '发布失败', description: getErrorMessage(error) });
        }
    };

    const handleArchive = async (pkg: ToolPackage) => {
        try {
            const res = await toolPackageService.archive(pkg.id);
            throwIfFailed(res);
            toast({ title: '已下架', description: '已安装的副本不受影响' });
            await reload();
        } catch (error: unknown) {
            toast({ variant: 'destructive', title: '下架失败', description: getErrorMessage(error) });
        }
    };

    const handleDelete = async (pkg: ToolPackage) => {
        try {
            const res = await toolPackageService.remove(pkg.id);
            throwIfFailed(res);
            toast({ title: '已删除' });
            await reload();
        } catch (error: unknown) {
            toast({ variant: 'destructive', title: '删除失败', description: getErrorMessage(error) });
        }
    };

    return (
        <div className="p-6 space-y-4 overflow-y-auto h-full">
            <div className="flex items-center justify-between">
                <div>
                    <h3 className="text-lg font-semibold flex items-center gap-2">
                        <Package className="w-5 h-5" /> 能力包
                    </h3>
                    <p className="text-sm text-muted-foreground">
                        把你已经调通的工具与技能导出成包，租户填自己的凭据即可一键安装
                    </p>
                </div>
                <Button onClick={() => setExportOpen(true)}>
                    <Upload className="w-4 h-4 mr-1" /> 导出能力包
                </Button>
            </div>

            {warnings.length > 0 && (
                <div className="border border-destructive/50 bg-destructive/10 rounded-md p-3 space-y-1">
                    <p className="text-sm font-medium text-destructive">
                        导出完成，但有 {warnings.length} 条需要你确认的地方：
                    </p>
                    <ul className="text-sm list-disc pl-5 space-y-0.5">
                        {warnings.map((w) => <li key={w}>{w}</li>)}
                    </ul>
                    <p className="text-xs text-muted-foreground">
                        发布前请点开包、逐字看一遍 manifest——凭据识别与密钥扫描都是启发式，只能靠人最终确认。
                    </p>
                </div>
            )}

            {isLoading ? (
                <p className="text-sm text-muted-foreground">加载中…</p>
            ) : list.length === 0 ? (
                <p className="text-sm text-muted-foreground">还没有能力包，点右上角导出一个。</p>
            ) : (
                <div className="space-y-2">
                    {list.map((pkg) => (
                        <div key={pkg.id} className="border rounded-lg p-3 flex items-center justify-between">
                            <div className="min-w-0">
                                <div className="flex items-center gap-2">
                                    <span className="font-medium">{pkg.name}</span>
                                    <Badge variant={pkg.status === 'PUBLISHED' ? 'default' : 'secondary'}>
                                        {statusLabel[pkg.status]}
                                    </Badge>
                                    <span className="text-xs text-muted-foreground">{pkg.code}</span>
                                </div>
                                <p className="text-sm text-muted-foreground truncate">{pkg.description}</p>
                            </div>
                            <div className="flex items-center gap-2 shrink-0">
                                <Button variant="outline" size="sm" onClick={() => openEditor(pkg)}>
                                    编辑与预览
                                </Button>
                                {pkg.status !== 'PUBLISHED' && (
                                    <Button size="sm" onClick={() => handlePublish(pkg)}>
                                        <Send className="w-4 h-4 mr-1" /> 发布
                                    </Button>
                                )}
                                {pkg.status === 'PUBLISHED' && (
                                    <Button variant="outline" size="sm" onClick={() => handleArchive(pkg)}>
                                        <Archive className="w-4 h-4 mr-1" /> 下架
                                    </Button>
                                )}
                                <Button variant="outline" size="sm" onClick={() => handleDelete(pkg)}>
                                    <Trash2 className="w-4 h-4" />
                                </Button>
                            </div>
                        </div>
                    ))}
                </div>
            )}

            <PackageExportDialog
                open={exportOpen}
                onOpenChange={setExportOpen}
                onExported={async (w) => {
                    setWarnings(w);
                    toast({ title: '已导出为草稿包', description: '补完文案与变量说明后再发布' });
                    await reload();
                }}
            />

            <Dialog open={editing !== null} onOpenChange={(v) => !v && setEditing(null)}>
                <DialogContent className="max-w-3xl">
                    <DialogHeader>
                        <DialogTitle>编辑能力包</DialogTitle>
                    </DialogHeader>
                    <div className="space-y-3">
                        <Input value={draftName} onChange={(e) => setDraftName(e.target.value)}
                               placeholder="包名称" />
                        <Input value={draftDescription} onChange={(e) => setDraftDescription(e.target.value)}
                               placeholder="包说明" />
                        <div>
                            <p className="text-sm font-medium mb-1">
                                manifest（发布前请逐字核对：不该有明文密钥，变量的标签与说明要是人话）
                            </p>
                            <textarea
                                className="w-full h-80 font-mono text-xs border rounded-md p-2 bg-background"
                                value={draftManifest}
                                onChange={(e) => setDraftManifest(e.target.value)}
                            />
                        </div>
                    </div>
                    <DialogFooter>
                        <Button variant="outline" onClick={() => setEditing(null)}>取消</Button>
                        <Button onClick={handleSave} disabled={isSaving}>
                            {isSaving ? '保存中…' : '保存'}
                        </Button>
                    </DialogFooter>
                </DialogContent>
            </Dialog>
        </div>
    );
};

export default PlatformPackagePage;
