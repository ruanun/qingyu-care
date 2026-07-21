import React, { useState, useEffect, useCallback } from 'react';
import {
    LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, Legend, ResponsiveContainer,
} from 'recharts';
import { Loader2, Bot, Gauge, ThumbsUp, ThumbsDown, ShieldAlert, LifeBuoy } from 'lucide-react';
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card';
import { analyticsService } from '@/api/analytics';
import type { QualityAnalytics } from '@/api/analytics';
import { useToast } from '@/hooks/use-toast';

interface Props { days: number; appId?: number }

/** 对话质量看板 Tab（数据来自 rollup，T+1，statCutoff 为统计截止日） */
const QualityTab: React.FC<Props> = ({ days, appId }) => {
    const [data, setData] = useState<QualityAnalytics | null>(null);
    const [isLoading, setIsLoading] = useState(true);
    const { toast } = useToast();

    const fetchData = useCallback(async () => {
        setIsLoading(true);
        try {
            const res = await analyticsService.getQuality(days, appId);
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
    const s = data?.summary;

    return (
        <div className="space-y-6">
            <p className="text-xs text-muted-foreground">统计截止 {data?.statCutoff}（预聚合 T+1，不含当日）</p>
            <div className="grid grid-cols-2 md:grid-cols-6 gap-4">
                <Stat icon={<Bot className="w-4 h-4 text-primary" />} label="AI 回复数" value={s?.aiMsgCount ?? 0} />
                <Stat icon={<Gauge className="w-4 h-4 text-blue-500" />} label="平均置信度" value={s?.avgConfidence ?? 0} />
                <Stat icon={<ShieldAlert className="w-4 h-4 text-orange-500" />} label="低置信率" value={`${s?.lowConfidenceRate ?? 0}%`} />
                <Stat icon={<LifeBuoy className="w-4 h-4 text-red-500" />} label="兜底回复率" value={`${s?.fallbackRate ?? 0}%`} />
                <Stat icon={<ThumbsUp className="w-4 h-4 text-green-500" />} label="赞" value={s?.feedbackUp ?? 0} />
                <Stat icon={<ThumbsDown className="w-4 h-4 text-rose-500" />} label="踩" value={s?.feedbackDown ?? 0} />
            </div>
            <Card>
                <CardHeader><CardTitle className="text-lg">回复量与反馈趋势</CardTitle></CardHeader>
                <CardContent>
                    <ResponsiveContainer width="100%" height={300}>
                        <LineChart data={data?.trend ?? []}>
                            <CartesianGrid strokeDasharray="3 3" className="stroke-muted" />
                            <XAxis dataKey="date" tick={{ fontSize: 12 }} />
                            <YAxis tick={{ fontSize: 12 }} />
                            <Tooltip />
                            <Legend />
                            <Line type="monotone" dataKey="aiMsgCount" name="AI 回复数" stroke="#6366f1" dot={false} />
                            <Line type="monotone" dataKey="feedbackUp" name="赞" stroke="#10b981" dot={false} />
                            <Line type="monotone" dataKey="feedbackDown" name="踩" stroke="#f43f5e" dot={false} />
                        </LineChart>
                    </ResponsiveContainer>
                </CardContent>
            </Card>
            <Card>
                <CardHeader><CardTitle className="text-lg">平均置信度趋势</CardTitle></CardHeader>
                <CardContent>
                    <ResponsiveContainer width="100%" height={220}>
                        <LineChart data={data?.trend ?? []}>
                            <CartesianGrid strokeDasharray="3 3" className="stroke-muted" />
                            <XAxis dataKey="date" tick={{ fontSize: 12 }} />
                            <YAxis domain={[0, 1]} tick={{ fontSize: 12 }} />
                            <Tooltip />
                            <Line type="monotone" dataKey="avgConfidence" name="平均置信度" stroke="#f59e0b" dot={false} />
                        </LineChart>
                    </ResponsiveContainer>
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

export default QualityTab;
