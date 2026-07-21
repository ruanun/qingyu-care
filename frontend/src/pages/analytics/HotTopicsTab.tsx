import React, { useState, useEffect, useCallback } from 'react';
import {
    BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer,
} from 'recharts';
import { Loader2 } from 'lucide-react';
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card';
import { analyticsService } from '@/api/analytics';
import type { HotTopics } from '@/api/analytics';
import { useToast } from '@/hooks/use-toast';

interface Props { days: number; appId?: number }

/** 热点问题 Tab：关键词 TopN（rollup）+ 高频问句 TopN（实时） */
const HotTopicsTab: React.FC<Props> = ({ days, appId }) => {
    const [data, setData] = useState<HotTopics | null>(null);
    const [isLoading, setIsLoading] = useState(true);
    const { toast } = useToast();

    const fetchData = useCallback(async () => {
        setIsLoading(true);
        try {
            const res = await analyticsService.getHotTopics(days, appId);
            if (res.code === 200) setData(res.data);
        } catch (error) {
            const msg = error instanceof Error ? error.message : String(error);
            toast({ variant: 'destructive', title: '加载失败', description: msg });
        } finally {
            setIsLoading(false);
        }
    }, [days, appId, toast]);

    useEffect(() => { fetchData(); }, [fetchData]);

    if (isLoading && !data) {
        return <div className="flex h-64 items-center justify-center"><Loader2 className="w-8 h-8 animate-spin text-primary" /></div>;
    }

    return (
        <div className="space-y-6">
            <p className="text-xs text-muted-foreground">关键词统计截止 {data?.statCutoff}（T+1）；高频问句为实时数据</p>
            <Card>
                <CardHeader><CardTitle className="text-lg">热点关键词 Top 20</CardTitle></CardHeader>
                <CardContent>
                    {(data?.keywords?.length ?? 0) === 0 ? (
                        <div className="text-center py-8 text-muted-foreground opacity-50">暂无数据</div>
                    ) : (
                        <ResponsiveContainer width="100%" height={Math.max(240, (data?.keywords.length ?? 0) * 28)}>
                            <BarChart data={data?.keywords ?? []} layout="vertical" margin={{ left: 24 }}>
                                <XAxis type="number" tick={{ fontSize: 12 }} />
                                <YAxis type="category" dataKey="keyword" width={100} tick={{ fontSize: 12 }} />
                                <Tooltip />
                                <Bar dataKey="freq" name="词频" fill="#6366f1" radius={[0, 4, 4, 0]} />
                            </BarChart>
                        </ResponsiveContainer>
                    )}
                </CardContent>
            </Card>
            <Card>
                <CardHeader><CardTitle className="text-lg">高频问句 Top 10</CardTitle></CardHeader>
                <CardContent>
                    {(data?.questions?.length ?? 0) === 0 ? (
                        <div className="text-center py-8 text-muted-foreground opacity-50">暂无数据</div>
                    ) : (
                        <table className="w-full text-sm">
                            <thead>
                                <tr className="text-muted-foreground border-b">
                                    <th className="text-left py-2 font-medium">问句</th>
                                    <th className="text-right py-2 font-medium">次数</th>
                                </tr>
                            </thead>
                            <tbody>
                                {data?.questions.map((q) => (
                                    <tr key={q.content} className="border-b last:border-0">
                                        <td className="py-2">{q.content}</td>
                                        <td className="py-2 text-right font-mono">{q.count}</td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    )}
                </CardContent>
            </Card>
        </div>
    );
};

export default HotTopicsTab;
