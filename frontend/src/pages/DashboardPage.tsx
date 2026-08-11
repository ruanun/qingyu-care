import React, { useState, useEffect } from 'react';
import { Users, MessageCircle, Database, Bot, UserPen, Clock, RefreshCw, Loader2, AlertCircle } from 'lucide-react';
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { dashboardService } from '@/api/dashboard';
import type { DashboardStats } from '@/api/dashboard';
import type { ChatSession } from '@/types';
import { useToast } from '@/hooks/use-toast';
import { getErrorMessage } from '@/lib/apiResult';

const DashboardPage: React.FC = () => {
    console.log("DashboardPage.tsx: Rendering...");
    const [stats, setStats] = useState<DashboardStats | null>(null);
    const [recentSessions, setRecentSessions] = useState<ChatSession[]>([]);
    const [isLoading, setIsLoading] = useState(true);
    const { toast } = useToast();

    useEffect(() => {
        fetchData();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    async function fetchData() {
        setIsLoading(true);
        try {
            const [statsRes, sessionsRes] = await Promise.all([
                dashboardService.getStats(),
                dashboardService.getRecentSessions(5),
            ]);
            if (statsRes.code === 200) setStats(statsRes.data);
            if (sessionsRes.code === 200) setRecentSessions(sessionsRes.data);
        } catch (error: unknown) {
            toast({ variant: 'destructive', title: '加载失败', description: getErrorMessage(error) });
        } finally {
            setIsLoading(false);
        }
    }

    const getStatusBadge = (status: string) => {
        switch (status) {
            case 'AI_PROCESSING':
                return <Badge variant="secondary" className="text-[10px] gap-1"><Bot className="w-3 h-3" /> AI</Badge>;
            case 'AGENT_PROCESSING':
                return <Badge className="bg-blue-500 text-[10px] gap-1"><UserPen className="w-3 h-3" /> 人工</Badge>;
            case 'WAITING_AGENT':
                return <Badge variant="destructive" className="text-[10px] animate-pulse gap-1"><AlertCircle className="w-3 h-3" /> 待接管</Badge>;
            case 'ENDED':
                return <Badge variant="outline" className="text-[10px] text-muted-foreground">已结束</Badge>;
            default:
                return <Badge variant="outline" className="text-[10px]">{status}</Badge>;
        }
    };

    if (isLoading) {
        return (
            <div className="flex h-full items-center justify-center">
                <Loader2 className="w-8 h-8 animate-spin text-primary" />
            </div>
        );
    }

    return (
        <div className="p-8 max-w-6xl mx-auto space-y-8 h-full overflow-y-auto">
            <div className="flex justify-between items-end">
                <div>
                    <h1 className="text-3xl font-bold tracking-tight">系统仪表盘</h1>
                    <p className="text-muted-foreground mt-2">监控系统运行状态、会话统计及知识库使用情况。</p>
                </div>
                <Button variant="outline" size="sm" onClick={fetchData} className="gap-2">
                    <RefreshCw className="w-4 h-4" /> 刷新
                </Button>
            </div>

            {/* 统计卡片 */}
            <div className="grid grid-cols-1 md:grid-cols-4 gap-6">
                <Card>
                    <CardHeader className="pb-2">
                        <CardTitle className="text-sm font-medium flex items-center gap-2">
                            <MessageCircle className="w-4 h-4 text-primary" /> 总会话数
                        </CardTitle>
                    </CardHeader>
                    <CardContent>
                        <div className="text-2xl font-bold">{stats?.totalSessions || 0}</div>
                        <p className="text-xs text-muted-foreground mt-1">今日 +{stats?.todaySessions || 0}</p>
                    </CardContent>
                </Card>
                <Card>
                    <CardHeader className="pb-2">
                        <CardTitle className="text-sm font-medium flex items-center gap-2">
                            <Users className="w-4 h-4 text-green-500" /> 活跃会话
                        </CardTitle>
                    </CardHeader>
                    <CardContent>
                        <div className="text-2xl font-bold">{stats?.activeSessions || 0}</div>
                        <p className="text-xs text-muted-foreground mt-1">
                            {stats?.waitingAgentSessions || 0} 个待接管
                        </p>
                    </CardContent>
                </Card>
                <Card>
                    <CardHeader className="pb-2">
                        <CardTitle className="text-sm font-medium flex items-center gap-2">
                            <Database className="w-4 h-4 text-purple-500" /> 知识库
                        </CardTitle>
                    </CardHeader>
                    <CardContent>
                        <div className="text-2xl font-bold">{stats?.totalKnowledgeBases || 0}</div>
                        <p className="text-xs text-muted-foreground mt-1">{stats?.totalDocuments || 0} 个文档</p>
                    </CardContent>
                </Card>
                <Card>
                    <CardHeader className="pb-2">
                        <CardTitle className="text-sm font-medium flex items-center gap-2">
                            <Bot className="w-4 h-4 text-amber-500" /> AI 解决率
                        </CardTitle>
                    </CardHeader>
                    <CardContent>
                        <div className="text-2xl font-bold">{stats?.aiResolutionRate || 0}%</div>
                        <p className="text-xs text-muted-foreground mt-1">无人工介入结束占比</p>
                    </CardContent>
                </Card>
            </div>

            {/* 最近会话 */}
            <Card>
                <CardHeader>
                    <CardTitle className="text-lg flex items-center gap-2">
                        <Clock className="w-5 h-5 text-primary" />
                        最近会话
                    </CardTitle>
                </CardHeader>
                <CardContent>
                    {recentSessions.length === 0 ? (
                        <div className="text-center py-8 text-muted-foreground opacity-50">
                            暂无会话记录
                        </div>
                    ) : (
                        <div className="space-y-4">
                            {recentSessions.map((session) => (
                                <div key={session.id} className="flex items-center gap-3">
                                    <div className="w-8 h-8 rounded-full bg-muted flex items-center justify-center">
                                        <MessageCircle className="w-4 h-4 text-muted-foreground" />
                                    </div>
                                    <div className="flex-1">
                                        <p className="text-sm font-medium font-mono">{session.userId}</p>
                                        <p className="text-xs text-muted-foreground">
                                            {new Date(session.createdAt).toLocaleString()}
                                        </p>
                                    </div>
                                    {getStatusBadge(session.status)}
                                </div>
                            ))}
                        </div>
                    )}
                </CardContent>
            </Card>
        </div>
    );
};

export default DashboardPage;
