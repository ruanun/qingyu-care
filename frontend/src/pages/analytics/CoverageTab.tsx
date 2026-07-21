import React, { useState, useEffect, useCallback } from 'react';
import {
    LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer,
} from 'recharts';
import { Loader2, Target, FileSearch, FileX } from 'lucide-react';
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { analyticsService } from '@/api/analytics';
import type { CoverageAnalytics, MissedQuestion } from '@/api/analytics';
import type { PageResponse } from '@/types';
import { useToast } from '@/hooks/use-toast';

interface Props { days: number; appId?: number }

/** 知识覆盖率 Tab：命中率趋势 + 文档命中排行 + 零命中文档 + 未命中提问明细 */
const CoverageTab: React.FC<Props> = ({ days, appId }) => {
    const [data, setData] = useState<CoverageAnalytics | null>(null);
    const [misses, setMisses] = useState<PageResponse<MissedQuestion> | null>(null);
    const [missPage, setMissPage] = useState(0);
    const [isLoading, setIsLoading] = useState(true);
    const { toast } = useToast();

    // days/appId 变化时在渲染期同步重置页码（避免 set-state-in-effect 反模式与旧页码多余请求）
    const [prevScopeKey, setPrevScopeKey] = useState(`${days}|${appId ?? ''}`);
    const scopeKey = `${days}|${appId ?? ''}`;
    if (scopeKey !== prevScopeKey) {
        setPrevScopeKey(scopeKey);
        setMissPage(0);
    }

    const fetchData = useCallback(async () => {
        setIsLoading(true);
        try {
            const res = await analyticsService.getCoverage(days, appId);
            if (res.code === 200) setData(res.data);
        } catch (error) {
            const msg = error instanceof Error ? error.message : String(error);
            toast({ variant: 'destructive', title: '加载失败', description: msg });
        } finally {
            setIsLoading(false);
        }
    }, [days, appId, toast]);

    const fetchMisses = useCallback(async () => {
        try {
            const res = await analyticsService.getMisses(days, missPage, appId);
            if (res.code === 200) setMisses(res.data);
        } catch { /* 明细失败不阻断整页 */ }
    }, [days, missPage, appId]);

    useEffect(() => { fetchData(); }, [fetchData]);
    useEffect(() => { fetchMisses(); }, [fetchMisses]);

    if (isLoading && !data) {
        return <div className="flex h-64 items-center justify-center"><Loader2 className="w-8 h-8 animate-spin text-primary" /></div>;
    }
    const s = data?.summary;

    return (
        <div className="space-y-6">
            <p className="text-xs text-muted-foreground">命中统计截止 {data?.statCutoff}（T+1）；未命中明细为实时数据</p>
            <div className="grid grid-cols-3 gap-4">
                <Stat icon={<FileSearch className="w-4 h-4 text-primary" />} label="AI 回答数" value={s?.answeredCount ?? 0} />
                <Stat icon={<Target className="w-4 h-4 text-green-500" />} label="命中知识数" value={s?.hitCount ?? 0} />
                <Stat icon={<Target className="w-4 h-4 text-blue-500" />} label="命中率" value={`${s?.hitRate ?? 0}%`} />
            </div>
            <Card>
                <CardHeader><CardTitle className="text-lg">命中率趋势</CardTitle></CardHeader>
                <CardContent>
                    <ResponsiveContainer width="100%" height={260}>
                        <LineChart data={data?.trend ?? []}>
                            <CartesianGrid strokeDasharray="3 3" className="stroke-muted" />
                            <XAxis dataKey="date" tick={{ fontSize: 12 }} />
                            <YAxis domain={[0, 100]} tick={{ fontSize: 12 }} unit="%" />
                            <Tooltip />
                            <Line type="monotone" dataKey="hitRate" name="命中率" stroke="#10b981" dot={false} />
                        </LineChart>
                    </ResponsiveContainer>
                </CardContent>
            </Card>
            <div className="grid md:grid-cols-2 gap-6">
                <Card>
                    <CardHeader><CardTitle className="text-lg">文档命中排行 Top 10</CardTitle></CardHeader>
                    <CardContent>
                        {(data?.topDocs?.length ?? 0) === 0 ? (
                            <div className="text-center py-8 text-muted-foreground opacity-50">暂无数据</div>
                        ) : (
                            <table className="w-full text-sm">
                                <thead>
                                    <tr className="text-muted-foreground border-b">
                                        <th className="text-left py-2 font-medium">文档</th>
                                        <th className="text-right py-2 font-medium">命中次数</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    {data?.topDocs.map((d) => (
                                        <tr key={d.documentId} className="border-b last:border-0">
                                            <td className="py-2">{d.name}</td>
                                            <td className="py-2 text-right font-mono">{d.hits}</td>
                                        </tr>
                                    ))}
                                </tbody>
                            </table>
                        )}
                    </CardContent>
                </Card>
                <Card>
                    <CardHeader>
                        <CardTitle className="text-lg flex items-center gap-2">
                            <FileX className="w-4 h-4 text-orange-500" /> 零命中文档（死知识）
                        </CardTitle>
                    </CardHeader>
                    <CardContent>
                        {(data?.zeroHitDocs?.length ?? 0) === 0 ? (
                            <div className="text-center py-8 text-muted-foreground opacity-50">窗口内全部文档均有命中</div>
                        ) : (
                            <ul className="text-sm space-y-1">
                                {data?.zeroHitDocs.map((d) => (
                                    <li key={d.documentId} className="border-b last:border-0 py-1.5">{d.name}</li>
                                ))}
                            </ul>
                        )}
                    </CardContent>
                </Card>
            </div>
            <Card>
                <CardHeader><CardTitle className="text-lg">未命中提问（知识缺口）</CardTitle></CardHeader>
                <CardContent className="space-y-3">
                    {(misses?.content?.length ?? 0) === 0 ? (
                        <div className="text-center py-8 text-muted-foreground opacity-50">暂无未命中提问</div>
                    ) : (
                        <table className="w-full text-sm">
                            <thead>
                                <tr className="text-muted-foreground border-b">
                                    <th className="text-left py-2 font-medium">提问</th>
                                    <th className="text-right py-2 font-medium w-40">时间</th>
                                </tr>
                            </thead>
                            <tbody>
                                {misses?.content.map((m, i) => (
                                    <tr key={`${m.sessionId}-${i}`} className="border-b last:border-0">
                                        <td className="py-2">{m.content}</td>
                                        <td className="py-2 text-right text-xs text-muted-foreground font-mono">
                                            {new Date(m.createdAt).toLocaleString()}
                                        </td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    )}
                    {(misses?.totalPages ?? 0) > 1 && (
                        <div className="flex justify-end gap-2">
                            <Button variant="outline" size="sm" disabled={missPage === 0}
                                onClick={() => setMissPage((p) => p - 1)}>上一页</Button>
                            <Button variant="outline" size="sm"
                                disabled={missPage >= (misses?.totalPages ?? 1) - 1}
                                onClick={() => setMissPage((p) => p + 1)}>下一页</Button>
                        </div>
                    )}
                </CardContent>
            </Card>
        </div>
    );
};

const Stat: React.FC<{ icon: React.ReactNode; label: string; value: React.ReactNode }> = ({ icon, label, value }) => (
    <Card>
        <CardHeader className="pb-2">
            <CardTitle className="text-sm font-medium flex items-center gap-2">{icon} {label}</CardTitle>
        </CardHeader>
        <CardContent><div className="text-2xl font-bold">{value}</div></CardContent>
    </Card>
);

export default CoverageTab;
