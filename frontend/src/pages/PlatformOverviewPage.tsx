import React, { useEffect, useState } from 'react';
import { platformService } from '@/api/platform';
import type { PlatformOverview } from '@/api/platform';
import { useToast } from '@/hooks/use-toast';

const StatCard: React.FC<{ label: string; value: React.ReactNode }> = ({ label, value }) => (
  <div className="rounded-lg border p-4 bg-muted/20">
    <p className="text-xs text-muted-foreground">{label}</p>
    <p className="text-2xl font-bold mt-1">{value}</p>
  </div>
);

const PlatformOverviewPage: React.FC = () => {
  const { toast } = useToast();
  const [data, setData] = useState<PlatformOverview | null>(null);
  const [error, setError] = useState(false);

  useEffect(() => {
    platformService
      .getOverview()
      .then((res) => {
        if (res.code !== 200) throw new Error(res.message);
        setData(res.data);
      })
      .catch((e: Error) => {
        setError(true);
        toast({ title: '加载失败', description: e.message, variant: 'destructive' });
      });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  if (error) return <div className="p-6 text-destructive">加载失败，请刷新重试</div>;
  if (!data) return <div className="p-6 text-muted-foreground">加载中…</div>;

  return (
    <div className="p-6 space-y-4 overflow-y-auto h-full">
      <h3 className="text-lg font-semibold">系统概览</h3>
      <div className="grid grid-cols-2 md:grid-cols-3 gap-4">
        <StatCard
          label="系统健康"
          value={
            <span className={data.health === 'UP' ? 'text-green-600' : 'text-red-600'}>
              {data.health}
            </span>
          }
        />
        <StatCard label="租户数" value={data.tenantCount} />
        <StatCard label="应用数" value={data.applicationCount} />
        <StatCard label="用户数" value={data.userCount} />
        <StatCard label="会话总数" value={data.sessionCount} />
        <StatCard label="今日对话" value={data.todaySessionCount} />
      </div>
    </div>
  );
};

export default PlatformOverviewPage;
