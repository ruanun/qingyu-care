import React, { useState, useEffect, useCallback } from 'react';
import { Plus, Trash2, Check, Loader2, Sparkles, MessageCircleQuestion } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { qaService } from '@/api/qa';
import type { QaPair } from '@/api/qa';
import type { Document } from '@/types';
import { useToast } from '@/hooks/use-toast';
import { getErrorMessage, throwIfFailed } from '@/lib/apiResult';

interface Props {
    kbId: number;
    documents: Document[];
}

const QaPairPanel: React.FC<Props> = ({ kbId, documents }) => {
    const [pairs, setPairs] = useState<QaPair[]>([]);
    const [loading, setLoading] = useState(false);
    const [question, setQuestion] = useState('');
    const [answer, setAnswer] = useState('');
    const [genDocId, setGenDocId] = useState<string>('');
    const { toast } = useToast();

    const fetchPairs = useCallback(async () => {
        setLoading(true);
        try {
            const res = await qaService.list(kbId);
            throwIfFailed(res);
            setPairs(res.data);
        } catch (e) {
            toast({ variant: 'destructive', title: '获取问答对失败', description: getErrorMessage(e) });
        } finally {
            setLoading(false);
        }
    }, [kbId, toast]);

    useEffect(() => {
        // eslint-disable-next-line react-hooks/set-state-in-effect
        fetchPairs();
    }, [fetchPairs]);

    const handleCreate = async () => {
        if (!question.trim() || !answer.trim()) {
            toast({ variant: 'destructive', title: '请填写问题和答案' });
            return;
        }
        try {
            const res = await qaService.create(kbId, { question, answer });
            throwIfFailed(res);
            toast({ title: '问答对已创建' }); setQuestion(''); setAnswer(''); fetchPairs();
        } catch (e) { toast({ variant: 'destructive', title: '创建失败', description: getErrorMessage(e) }); }
    };

    const handleApprove = async (id: number) => {
        try { const res = await qaService.approve(id); throwIfFailed(res); toast({ title: '已通过复核' }); fetchPairs(); }
        catch (e) { toast({ variant: 'destructive', title: '操作失败', description: getErrorMessage(e) }); }
    };

    const handleToggle = async (p: QaPair) => {
        try { const res = await qaService.setEnabled(p.id, p.status === 'DISABLED'); throwIfFailed(res); fetchPairs(); }
        catch (e) { toast({ variant: 'destructive', title: '操作失败', description: getErrorMessage(e) }); }
    };

    const handleDelete = async (id: number) => {
        if (!confirm('确定删除该问答对吗？相关向量也会清除。')) return;
        try { const res = await qaService.remove(id); throwIfFailed(res); toast({ title: '已删除' }); fetchPairs(); }
        catch (e) { toast({ variant: 'destructive', title: '删除失败', description: getErrorMessage(e) }); }
    };

    const handleGenerate = async () => {
        if (!genDocId) { toast({ variant: 'destructive', title: '请选择用于生成的文档' }); return; }
        try {
            const res = await qaService.generate(Number(genDocId));
            throwIfFailed(res);
            toast({ title: '已开始生成', description: '稍后刷新查看草稿并复核' });
        } catch (e) { toast({ variant: 'destructive', title: '生成失败', description: getErrorMessage(e) }); }
    };

    const statusBadge = (p: QaPair) => {
        const map: Record<string, string> = {
            DRAFT: 'bg-amber-50 text-amber-700 border-amber-200',
            ACTIVE: 'bg-primary/10 text-primary border-primary/20',
            DISABLED: 'bg-muted text-muted-foreground border-border line-through',
        };
        const label: Record<string, string> = { DRAFT: '待复核', ACTIVE: '检索中', DISABLED: '已停用' };
        return <span className={`text-[10px] px-2 py-0.5 rounded-full border ${map[p.status]}`}>{label[p.status]}</span>;
    };

    const completedDocs = documents.filter((d) => d.status === 'COMPLETED');

    return (
        <div className="space-y-6">
            {/* 从文档生成 */}
            <div className="flex items-center gap-2 rounded-xl border bg-muted/20 p-3">
                <Sparkles className="w-4 h-4 text-primary shrink-0" />
                <select className="flex-1 rounded-md border bg-background px-2 py-1.5 text-sm" value={genDocId} onChange={(e) => setGenDocId(e.target.value)}>
                    <option value="">从文档生成问答对（选一个已完成文档）…</option>
                    {completedDocs.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
                </select>
                <Button size="sm" variant="outline" onClick={handleGenerate}>生成草稿</Button>
            </div>

            {/* 手动新增 */}
            <div className="rounded-xl border p-4 space-y-2">
                <Input placeholder="问题，如：如何退货？" value={question} onChange={(e) => setQuestion(e.target.value)} />
                <textarea className="w-full min-h-[70px] rounded-md border px-3 py-2 text-sm" placeholder="答案" value={answer} onChange={(e) => setAnswer(e.target.value)} />
                <div className="flex justify-end">
                    <Button size="sm" onClick={handleCreate}><Plus className="w-4 h-4 mr-1" />添加问答对</Button>
                </div>
            </div>

            {/* 列表 */}
            <div className="space-y-3">
                <h4 className="text-sm font-semibold">问答对（{pairs.length}）</h4>
                {loading ? (
                    <div className="flex justify-center py-8"><Loader2 className="w-6 h-6 animate-spin text-muted-foreground" /></div>
                ) : pairs.length === 0 ? (
                    <div className="py-10 text-center border-2 border-dashed rounded-xl">
                        <MessageCircleQuestion className="w-10 h-10 mx-auto text-muted-foreground/30 mb-2" />
                        <p className="text-muted-foreground text-sm">暂无问答对</p>
                    </div>
                ) : pairs.map((p) => (
                    <div key={p.id} className="p-4 rounded-xl border bg-muted/20">
                        <div className="flex items-start justify-between gap-2">
                            <div className="flex-1">
                                <div className="flex items-center gap-2 flex-wrap">
                                    <span className="text-sm font-medium">{p.question}</span>
                                    {statusBadge(p)}
                                    {p.source === 'GENERATED' && <span className="text-[10px] px-2 py-0.5 rounded-full border bg-blue-50 text-blue-700 border-blue-200">AI 生成</span>}
                                </div>
                                <p className="text-xs text-muted-foreground mt-1 whitespace-pre-wrap">{p.answer}</p>
                            </div>
                            <div className="flex items-center gap-1 shrink-0">
                                {p.status === 'DRAFT' && (
                                    <Button size="icon" variant="ghost" className="h-8 w-8 text-primary" title="通过复核" onClick={() => handleApprove(p.id)}><Check className="w-4 h-4" /></Button>
                                )}
                                {p.status !== 'DRAFT' && (
                                    <button className="text-[10px] px-2 py-0.5 rounded-full border" onClick={() => handleToggle(p)}>{p.status === 'DISABLED' ? '启用' : '停用'}</button>
                                )}
                                <Button size="icon" variant="ghost" className="h-8 w-8 text-destructive hover:bg-destructive/10" onClick={() => handleDelete(p.id)}><Trash2 className="w-4 h-4" /></Button>
                            </div>
                        </div>
                    </div>
                ))}
            </div>
        </div>
    );
};

export default QaPairPanel;
