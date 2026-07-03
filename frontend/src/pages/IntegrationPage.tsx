import React, { useEffect, useState } from 'react';
import { useAppStore } from '@/stores/appStore';
import { integrationService } from '@/api/integration';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { useToast } from '@/hooks/use-toast';

const IntegrationPage: React.FC = () => {
    const { toast } = useToast();
    const { currentApp } = useAppStore();
    const [appKey, setAppKey] = useState('');
    const [appSecret, setAppSecret] = useState('');
    const [showSecret, setShowSecret] = useState(false);
    const [rateLimit, setRateLimit] = useState<string>('');

    const load = async () => {
        if (!currentApp) return;
        try {
            const r = await integrationService.getApp(currentApp.id);
            if (r.code !== 200) throw new Error(r.message);
            setAppKey(r.data.appKey ?? '');
            setAppSecret(r.data.appSecret ?? '');
            setRateLimit(r.data.rateLimitPerMinute != null ? String(r.data.rateLimitPerMinute) : '');
        } catch (e: unknown) {
            const msg = e instanceof Error ? e.message : '未知错误';
            toast({ title: '加载失败', description: msg, variant: 'destructive' });
        }
    };

    // eslint-disable-next-line react-hooks/exhaustive-deps
    useEffect(() => { load(); }, [currentApp?.id]);

    const copy = async (text: string) => {
        try {
            if (!navigator.clipboard) throw new Error('剪贴板不可用');
            await navigator.clipboard.writeText(text);
            toast({ title: '已复制' });
        } catch {
            toast({ title: '复制失败，请手动复制', variant: 'destructive' });
        }
    };

    const regenerate = async () => {
        if (!currentApp) return;
        if (!confirm('确认重置密钥？重置后旧密钥立即失效，已接入的服务将中断。')) return;
        try {
            const r = await integrationService.regenerateSecret(currentApp.id);
            if (r.code !== 200) throw new Error(r.message);
            setAppSecret(r.data.appSecret ?? '');
            toast({ title: '密钥已重置' });
        } catch (e: unknown) {
            const msg = e instanceof Error ? e.message : '未知错误';
            toast({ title: '重置失败', description: msg, variant: 'destructive' });
        }
    };

    const saveRateLimit = async () => {
        if (!currentApp) return;
        const val = rateLimit.trim() === '' ? null : Number(rateLimit);
        if (val !== null && (!Number.isInteger(val) || val <= 0)) {
            toast({ title: '请输入正整数', variant: 'destructive' });
            return;
        }
        try {
            const r = await integrationService.updateRateLimit(currentApp.id, val);
            if (r.code !== 200) throw new Error(r.message);
            toast({ title: '已保存' });
        } catch (e: unknown) {
            const msg = e instanceof Error ? e.message : '未知错误';
            toast({ title: '保存失败', description: msg, variant: 'destructive' });
        }
    };

    if (!currentApp) return <div className="p-6 text-muted-foreground">请先在上方选择应用</div>;

    const maskedSecret = appSecret ? appSecret.slice(0, 6) + '••••••••' + appSecret.slice(-4) : '';
    const curl = `curl -X POST ${window.location.origin}/api/open/v1/chat/completions \\
  -H "X-App-Id: ${appKey}" \\
  -H "X-Api-Key: ${showSecret ? appSecret : maskedSecret}" \\
  -H "Content-Type: application/json" \\
  -d '{"message":"你好"}'`;

    return (
        <div className="p-6 space-y-6 max-w-2xl overflow-y-auto h-full">
            <h3 className="text-lg font-semibold">接入配置 · {currentApp.name}</h3>

            <div className="space-y-2">
                <label className="text-sm font-medium">App Id（X-App-Id）</label>
                <div className="flex gap-2">
                    <Input readOnly value={appKey} />
                    <Button variant="outline" onClick={() => copy(appKey)}>复制</Button>
                </div>
            </div>

            <div className="space-y-2">
                <label className="text-sm font-medium">App Secret（X-Api-Key）</label>
                <div className="flex gap-2">
                    <Input readOnly value={showSecret ? appSecret : maskedSecret} />
                    <Button variant="outline" onClick={() => setShowSecret((s) => !s)}>{showSecret ? '隐藏' : '显示'}</Button>
                    <Button variant="outline" onClick={() => copy(appSecret)}>复制</Button>
                    <Button variant="destructive" onClick={regenerate}>重置</Button>
                </div>
            </div>

            <div className="space-y-2">
                <label className="text-sm font-medium">每分钟请求上限（留空用平台默认）</label>
                <div className="flex gap-2">
                    <Input type="number" value={rateLimit} onChange={(e) => setRateLimit(e.target.value)} className="w-40" />
                    <Button onClick={saveRateLimit}>保存</Button>
                </div>
            </div>

            <div className="space-y-2">
                <label className="text-sm font-medium">调用示例</label>
                <pre className="text-xs bg-muted p-3 rounded-md overflow-x-auto whitespace-pre-wrap">{curl}</pre>
            </div>

            <div className="space-y-2">
                <label className="text-sm font-medium">Widget 嵌入代码</label>
                <p className="text-xs text-muted-foreground">Widget 子项完成后在此生成一行嵌入代码（暂未生效）。</p>
            </div>
        </div>
    );
};

export default IntegrationPage;
