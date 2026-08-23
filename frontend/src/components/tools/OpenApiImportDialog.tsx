import React, { useState } from 'react';
import { throwIfFailed } from '@/lib/apiResult';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Badge } from '@/components/ui/badge';
import {
    Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '@/components/ui/dialog';
import { openApiImportService } from '@/api/chat-tool';
import type { OpenApiToolCandidate } from '@/api/chat-tool';
import { useToast } from '@/hooks/use-toast';

interface Props {
    open: boolean;
    onOpenChange: (open: boolean) => void;
    /** 导入成功后通知父组件刷新列表 */
    onImported: () => void;
}

/**
 * 从 OpenAPI schema 批量导入工具。一个对话框内三步：输入 → 预览勾选 → 提交。
 *
 * ⚠️ 改 baseUrl 后必须重新解析：urlTemplate 是在后端映射时拼好的，前端不做二次拼接
 * ——那会变成第二份映射规则，与后端各自漂移。
 *
 * @author ruan
 */
const OpenApiImportDialog: React.FC<Props> = ({ open, onOpenChange, onImported }) => {
    const [schemaText, setSchemaText] = useState('');
    const [schemaUrl, setSchemaUrl] = useState('');
    const [baseUrl, setBaseUrl] = useState('');
    const [authHeader, setAuthHeader] = useState('');
    const [candidates, setCandidates] = useState<OpenApiToolCandidate[]>([]);
    const [globalWarnings, setGlobalWarnings] = useState<string[]>([]);
    const [selected, setSelected] = useState<Set<number>>(new Set());
    const [isBusy, setIsBusy] = useState(false);
    const { toast } = useToast();

    const reset = () => {
        setSchemaText(''); setSchemaUrl(''); setBaseUrl(''); setAuthHeader('');
        setCandidates([]); setGlobalWarnings([]); setSelected(new Set());
    };

    const handleParse = async () => {
        try {
            setIsBusy(true);
            const res = await openApiImportService.parse({
                schemaText: schemaText || undefined,
                schemaUrl: schemaUrl || undefined,
                baseUrl: baseUrl || undefined,
            });
            throwIfFailed(res);
            setCandidates(res.data.candidates);
            setGlobalWarnings(res.data.globalWarnings);
            // 默认全选无冲突的
            setSelected(new Set(res.data.candidates
                .map((c, i) => (c.nameConflict ? -1 : i)).filter(i => i >= 0)));
        } catch (e: unknown) {
            const msg = e instanceof Error ? e.message : '未知错误';
            toast({ variant: 'destructive', title: '解析失败', description: msg });
        } finally {
            setIsBusy(false);
        }
    };

    const handleImport = async () => {
        const picked = candidates.filter((_, i) => selected.has(i));
        if (picked.length === 0) {
            toast({ variant: 'destructive', title: '请至少勾选一个端点' });
            return;
        }
        try {
            setIsBusy(true);
            const res = await openApiImportService.import({
                candidates: picked,
                authHeader: authHeader || undefined,
                enabled: true,
            });
            throwIfFailed(res);
            toast({ title: `已导入 ${picked.length} 个工具` });
            reset();
            onOpenChange(false);
            onImported();
        } catch (e: unknown) {
            const msg = e instanceof Error ? e.message : '未知错误';
            toast({ variant: 'destructive', title: '导入失败', description: msg });
        } finally {
            setIsBusy(false);
        }
    };

    const updateCandidate = (index: number, patch: Partial<OpenApiToolCandidate>) => {
        setCandidates(prev => prev.map((c, i) => (i === index ? { ...c, ...patch } : c)));
    };

    const toggle = (index: number) => {
        setSelected(prev => {
            const next = new Set(prev);
            if (next.has(index)) { next.delete(index); } else { next.add(index); }
            return next;
        });
    };

    return (
        <Dialog open={open} onOpenChange={(v) => { if (!v) { reset(); } onOpenChange(v); }}>
            <DialogContent className="max-w-3xl">
                <DialogHeader>
                    <DialogTitle>从 OpenAPI 导入工具</DialogTitle>
                    <DialogDescription>
                        支持 OpenAPI 3.x（JSON / YAML）。只会导入 GET 与 POST 端点。
                    </DialogDescription>
                </DialogHeader>

                <div className="space-y-3 max-h-[60vh] overflow-y-auto">
                    <label className="text-sm font-medium">粘贴 schema 内容</label>
                    <textarea
                        className="w-full h-32 rounded-md border p-2 text-sm font-mono"
                        value={schemaText}
                        onChange={(e) => setSchemaText(e.target.value)}
                        placeholder='{"openapi":"3.0.0", ...}'
                    />
                    <label className="text-sm font-medium">或填写 schema 地址</label>
                    <Input value={schemaUrl} onChange={(e) => setSchemaUrl(e.target.value)}
                        placeholder="https://api.example.com/openapi.json" />
                    <label className="text-sm font-medium">服务器地址（留空则取 schema 的 servers）</label>
                    <Input value={baseUrl} onChange={(e) => setBaseUrl(e.target.value)}
                        placeholder="https://api.example.com" />
                    <Button onClick={handleParse} disabled={isBusy}>解析</Button>

                    {globalWarnings.length > 0 && (
                        <div className="rounded-md border border-amber-300 bg-amber-50 p-2 text-xs">
                            {globalWarnings.map((w, i) => <div key={i}>{w}</div>)}
                        </div>
                    )}

                    {candidates.length > 0 && (
                        <>
                            <label className="text-sm font-medium">鉴权头（统一应用到本批全部工具，可选）</label>
                            <Input type="password" value={authHeader}
                                onChange={(e) => setAuthHeader(e.target.value)}
                                placeholder="Authorization: Bearer xxx" />
                            <div className="space-y-2">
                                {candidates.map((c, i) => (
                                    <div key={i} className="rounded-md border p-2 space-y-1">
                                        <div className="flex items-center gap-2">
                                            <input type="checkbox" checked={selected.has(i)}
                                                onChange={() => toggle(i)} aria-label={`选择 ${c.name}`} />
                                            <Badge>{c.httpMethod}</Badge>
                                            <Input value={c.name} aria-label={`名称 ${i}`}
                                                onChange={(e) => updateCandidate(i, { name: e.target.value })}
                                                className={c.nameConflict ? 'border-destructive' : ''} />
                                        </div>
                                        <Input value={c.description} aria-label={`描述 ${i}`}
                                            onChange={(e) => updateCandidate(i, { description: e.target.value })} />
                                        <div className="text-xs text-muted-foreground break-all">{c.urlTemplate}</div>
                                        {c.nameConflict && (
                                            <div className="text-xs text-destructive">名称与已有工具重复，请修改后再导入</div>
                                        )}
                                        {c.warnings.map((w, wi) => (
                                            <div key={wi} className="text-xs text-amber-600">{w}</div>
                                        ))}
                                    </div>
                                ))}
                            </div>
                        </>
                    )}
                </div>

                <DialogFooter>
                    <Button variant="outline" onClick={() => onOpenChange(false)}>取消</Button>
                    <Button onClick={handleImport} disabled={isBusy || candidates.length === 0}>
                        导入选中项
                    </Button>
                </DialogFooter>
            </DialogContent>
        </Dialog>
    );
};

export default OpenApiImportDialog;
