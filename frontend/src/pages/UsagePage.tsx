import React, { useState, useEffect, useCallback } from 'react';
import {
    LineChart, Line, BarChart, Bar, XAxis, YAxis, CartesianGrid,
    Tooltip, Legend, ResponsiveContainer,
} from 'recharts';
import { Loader2, RefreshCw, Phone, MessageCircle, Coins, Bot, UserPen } from 'lucide-react';
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs';
import {
    Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '@/components/ui/select';
import { analyticsService } from '@/api/analytics';
import type { UsageAnalytics } from '@/api/analytics';
import { applicationService } from '@/api/application';
import type { Application } from '@/types';
import { useToast } from '@/hooks/use-toast';
import { billingService } from '@/api/billing';
import type { QuotaView } from '@/api/billing';

const DAY_OPTIONS = [
    { value: '7', label: '近 7 天' },
    { value: '30', label: '近 30 天' },
    { value: '90', label: '近 90 天' },
];
const ALL_APPS = '__all__';

const UsagePage: React.FC = () => {
    const [days, setDays] = useState('30');
    const [appId, setAppId] = useState(ALL_APPS);
    const [data, setData] = useState<UsageAnalytics | null>(null);
    const [apps, setApps] = useState<Application[]>([]);
    const [quota, setQuota] = useState<QuotaView | null>(null);
    const [isLoading, setIsLoading] = useState(true);
    const { toast } = useToast();

    const fetchData = useCallback(async () => {
        setIsLoading(true);
        try {
            const res = await analyticsService.getUsage(
                Number(days),
                appId === ALL_APPS ? undefined : Number(appId),
            );
            if (res.code === 200) setData(res.data);
        } catch (error) {
            const msg = error instanceof Error ? error.message : String(error);
            toast({ variant: 'destructive', title: '加载失败', description: msg });
        } finally {
            setIsLoading(false);
        }
    }, [days, appId, toast]);

    // days 或 appId 变化时重新拉取
    useEffect(() => { fetchData(); }, [fetchData]);

    // 租户视角才需要应用下钻列表；平台视角接口忽略 appId，无需拉取
    useEffect(() => {
        if (data?.scope === 'TENANT' && apps.length === 0) {
            applicationService.list().then((res) => {
                if (res.code === 200) setApps(res.data);
            }).catch(() => { /* 下钻列表拉取失败不阻断看板 */ });
        }
    }, [data?.scope, apps.length]);

    // 租户视角拉取本月额度
    useEffect(() => {
        if (data?.scope === 'TENANT') {
            billingService.getQuota().then((res) => {
                if (res.code === 200 && res.data?.plan) setQuota(res.data);
            }).catch(() => { /* 额度拉取失败不阻断看板 */ });
        } else {
            setQuota(null); // 非租户视角清零，防旧额度残留
        }
    }, [data?.scope]);

    const isTenant = data?.scope === 'TENANT';
    const rankingTitle = isTenant ? '应用用量 Top 10' : '租户用量 Top 10';

    if (isLoading && !data) {
        return (
            <div className="flex h-full items-center justify-center">
                <Loader2 className="w-8 h-8 animate-spin text-primary" />
            </div>
        );
    }

    const s = data?.summary;

    return (
        <div className="p-8 max-w-6xl mx-auto space-y-6 h-full overflow-y-auto">
            <div className="flex justify-between items-end flex-wrap gap-4">
                <div>
                    <h1 className="text-3xl font-bold tracking-tight">用量统计</h1>
                    <p className="text-muted-foreground mt-2">
                        开放 API 调用与会话效果概览。
                        <span className="text-xs">（用量 = 开放 API 接入；会话 = 全部渠道，含后台测试对话，二者口径不同）</span>
                    </p>
                </div>
                <div className="flex items-center gap-3">
                    {isTenant && (
                        <Select value={appId} onValueChange={setAppId}>
                            <SelectTrigger className="w-40"><SelectValue /></SelectTrigger>
                            <SelectContent>
                                <SelectItem value={ALL_APPS}>全部应用</SelectItem>
                                {apps.map((a) => (
                                    <SelectItem key={a.id} value={String(a.id)}>{a.name}</SelectItem>
                                ))}
                            </SelectContent>
                        </Select>
                    )}
                    <Tabs value={days} onValueChange={setDays}>
                        <TabsList>
                            {DAY_OPTIONS.map((o) => (
                                <TabsTrigger key={o.value} value={o.value}>{o.label}</TabsTrigger>
                            ))}
                        </TabsList>
                    </Tabs>
                    <Button variant="outline" size="sm" onClick={fetchData} className="gap-2">
                        <RefreshCw className="w-4 h-4" /> 刷新
                    </Button>
                </div>
            </div>

            {/* 汇总卡片 */}
            <div className="grid grid-cols-2 md:grid-cols-5 gap-4">
                <StatCard icon={<Phone className="w-4 h-4 text-primary" />} label="调用数" value={s?.totalCalls ?? 0} />
                <StatCard icon={<Coins className="w-4 h-4 text-amber-500" />} label="Token" value={s?.totalTokens ?? 0} />
                <StatCard icon={<MessageCircle className="w-4 h-4 text-green-500" />} label="会话数" value={s?.totalSessions ?? 0} />
                <StatCard icon={<Bot className="w-4 h-4 text-blue-500" />} label="AI 解决率" value={`${s?.aiResolutionRate ?? 0}%`} />
                <StatCard icon={<UserPen className="w-4 h-4 text-purple-500" />} label="转人工率" value={`${s?.handoverRate ?? 0}%`} />
            </div>

            {/* 本月额度（租户视角） */}
            {isTenant && quota && (
                <Card>
                    <CardHeader className="pb-2">
                        <CardTitle className="text-base flex items-center gap-2">
                            本月额度
                            <span className="text-xs font-normal text-muted-foreground">套餐 {quota.plan}</span>
                            {quota.resetDate && (
                                <span className="text-xs font-normal text-muted-foreground ml-auto">
                                    {Number(quota.resetDate.slice(5, 7))} 月 {Number(quota.resetDate.slice(8, 10))} 日重置
                                </span>
                            )}
                        </CardTitle>
                    </CardHeader>
                    <CardContent className="grid md:grid-cols-2 gap-6">
                        <QuotaBar label="调用次数" used={quota.callsUsed} limit={quota.callsLimit} />
                        <QuotaBar label="Token" used={quota.tokensUsed} limit={quota.tokensLimit} />
                    </CardContent>
                </Card>
            )}

            {/* 趋势折线图 */}
            <Card>
                <CardHeader><CardTitle className="text-lg">用量趋势</CardTitle></CardHeader>
                <CardContent>
                    <ResponsiveContainer width="100%" height={320}>
                        <LineChart data={data?.trend ?? []}>
                            <CartesianGrid strokeDasharray="3 3" className="stroke-muted" />
                            <XAxis dataKey="date" tick={{ fontSize: 12 }} />
                            <YAxis tick={{ fontSize: 12 }} />
                            <Tooltip />
                            <Legend />
                            <Line type="monotone" dataKey="calls" name="调用数" stroke="#6366f1" dot={false} />
                            <Line type="monotone" dataKey="tokens" name="Token" stroke="#f59e0b" dot={false} />
                            <Line type="monotone" dataKey="sessions" name="会话数" stroke="#10b981" dot={false} />
                        </LineChart>
                    </ResponsiveContainer>
                </CardContent>
            </Card>

            {/* 排行榜：柱状图 + 表格 */}
            <Card>
                <CardHeader><CardTitle className="text-lg">{rankingTitle}</CardTitle></CardHeader>
                <CardContent>
                    {(data?.ranking?.length ?? 0) === 0 ? (
                        <div className="text-center py-8 text-muted-foreground opacity-50">暂无数据</div>
                    ) : (
                        <div className="grid md:grid-cols-2 gap-6 items-center">
                            <ResponsiveContainer width="100%" height={280}>
                                <BarChart data={data?.ranking ?? []} layout="vertical" margin={{ left: 24 }}>
                                    <XAxis type="number" tick={{ fontSize: 12 }} />
                                    <YAxis type="category" dataKey="name" width={100} tick={{ fontSize: 12 }} />
                                    <Tooltip />
                                    <Bar dataKey="calls" name="调用数" fill="#6366f1" radius={[0, 4, 4, 0]} />
                                </BarChart>
                            </ResponsiveContainer>
                            <table className="w-full text-sm">
                                <thead>
                                    <tr className="text-muted-foreground border-b">
                                        <th className="text-left py-2 font-medium">{isTenant ? '应用' : '租户'}</th>
                                        <th className="text-right py-2 font-medium">调用数</th>
                                        <th className="text-right py-2 font-medium">Token</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    {data?.ranking.map((r) => (
                                        <tr key={r.id} className="border-b last:border-0">
                                            <td className="py-2">{r.name}</td>
                                            <td className="py-2 text-right font-mono">{r.calls}</td>
                                            <td className="py-2 text-right font-mono">{r.tokens}</td>
                                        </tr>
                                    ))}
                                </tbody>
                            </table>
                        </div>
                    )}
                </CardContent>
            </Card>
        </div>
    );
};

// 汇总统计卡片子组件
const StatCard: React.FC<{ icon: React.ReactNode; label: string; value: React.ReactNode }> = ({ icon, label, value }) => (
    <Card>
        <CardHeader className="pb-2">
            <CardTitle className="text-sm font-medium flex items-center gap-2">{icon} {label}</CardTitle>
        </CardHeader>
        <CardContent><div className="text-2xl font-bold">{value}</div></CardContent>
    </Card>
);

// 额度进度条子组件
const QuotaBar: React.FC<{ label: string; used: number; limit: number }> = ({ label, used, limit }) => {
    const unlimited = limit <= 0;
    const pct = unlimited ? 0 : Math.min(100, Math.round((used / limit) * 100));
    const danger = pct >= 100;
    const warn = pct >= 80 && pct < 100;
    return (
        <div className="space-y-1">
            <div className="flex justify-between text-sm">
                <span className="text-muted-foreground">{label}</span>
                <span className="font-mono">{used}{unlimited ? '' : ` / ${limit}`}{unlimited && ' （不限）'}</span>
            </div>
            {!unlimited && (
                <div className="h-2 w-full rounded-full bg-muted overflow-hidden">
                    <div
                        className={danger ? 'h-full bg-destructive' : warn ? 'h-full bg-amber-500' : 'h-full bg-primary'}
                        style={{ width: `${pct}%` }}
                    />
                </div>
            )}
        </div>
    );
};

export default UsagePage;
