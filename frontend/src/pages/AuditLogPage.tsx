import React, { useEffect, useState } from 'react';
import { auditService } from '@/api/audit';
import type { AuditLog } from '@/api/audit';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { useToast } from '@/hooks/use-toast';

const AuditLogPage: React.FC = () => {
  const { toast } = useToast();
  const [rows, setRows] = useState<AuditLog[]>([]);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [action, setAction] = useState('');

  const load = (p = page) => {
    auditService.query({ action: action || undefined, page: p, size: 20 })
      .then((res) => {
        if (res.code !== 200) throw new Error(res.message);
        setRows(res.data.content);
        setTotalPages(res.data.totalPages);
        setPage(res.data.number);
      })
      .catch((e: Error) =>
        toast({ title: '加载失败', description: e.message, variant: 'destructive' })
      );
  };

  useEffect(() => {
    // load 仅在挂载时拉首页；后续查询/翻页由按钮显式调用，故不放入依赖
    load(0);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // 安全格式化时间：空/非法值显示 '-'
  const formatTime = (value: string): string => {
    if (!value) return '-';
    const d = new Date(value);
    return isNaN(d.getTime()) ? '-' : d.toLocaleString();
  };

  return (
    <div className="p-6 space-y-4 overflow-y-auto h-full">
      <div className="flex items-center gap-2">
        <h3 className="text-lg font-semibold mr-auto">审计日志</h3>
        <Input
          placeholder="按 action 过滤"
          value={action}
          onChange={(e) => setAction(e.target.value)}
          className="w-48"
        />
        <Button onClick={() => load(0)}>查询</Button>
      </div>

      <table className="w-full text-sm border rounded-md overflow-hidden">
        <thead className="bg-muted/50">
          <tr>
            <th className="text-left p-2">时间</th>
            <th className="text-left p-2">操作者</th>
            <th className="text-left p-2">动作</th>
            <th className="text-left p-2">目标</th>
            <th className="text-left p-2">IP</th>
            <th className="text-left p-2">详情</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.id} className="border-t align-top">
              <td className="p-2 whitespace-nowrap">
                {formatTime(r.createdAt)}
              </td>
              <td className="p-2">{r.username ?? r.userId ?? '-'}</td>
              <td className="p-2">{r.action}</td>
              <td className="p-2">
                {[r.targetType, r.targetId ? `#${r.targetId}` : null].filter(Boolean).join('') || '-'}
              </td>
              <td className="p-2">{r.ip ?? '-'}</td>
              <td className="p-2 max-w-xs truncate" title={r.detail ?? ''}>
                {r.detail ?? '-'}
              </td>
            </tr>
          ))}
          {rows.length === 0 && (
            <tr>
              <td colSpan={6} className="p-4 text-center text-muted-foreground">
                暂无记录
              </td>
            </tr>
          )}
        </tbody>
      </table>

      <div className="flex items-center gap-2 justify-end">
        <Button
          variant="outline"
          size="sm"
          disabled={page <= 0}
          onClick={() => load(page - 1)}
        >
          上一页
        </Button>
        <span className="text-sm text-muted-foreground">
          {page + 1} / {Math.max(totalPages, 1)}
        </span>
        <Button
          variant="outline"
          size="sm"
          disabled={page >= totalPages - 1}
          onClick={() => load(page + 1)}
        >
          下一页
        </Button>
      </div>
    </div>
  );
};

export default AuditLogPage;
