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
    const [allowedOrigins, setAllowedOrigins] = useState('');

    const load = async () => {
        if (!currentApp) return;
        try {
            const r = await integrationService.getApp(currentApp.id);
            if (r.code !== 200) throw new Error(r.message);
            setAppKey(r.data.appKey ?? '');
            setAppSecret(r.data.appSecret ?? '');
            setRateLimit(r.data.rateLimitPerMinute != null ? String(r.data.rateLimitPerMinute) : '');
            setAllowedOrigins(r.data.allowedOrigins ?? '');
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

    const saveAllowedOrigins = async () => {
        if (!currentApp) return;
        try {
            const r = await integrationService.updateAllowedOrigins(currentApp.id, allowedOrigins.trim());
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

    const embed = `<script src="${window.location.origin}/api/widget.js?appKey=${appKey}&v=1"></script>`;

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
                <label className="text-sm font-medium">Widget 允许来源域名（每行一个，如 https://www.a.com）</label>
                <textarea
                    className="w-full min-h-[80px] text-sm rounded-md border p-2 bg-background"
                    value={allowedOrigins}
                    onChange={(e) => setAllowedOrigins(e.target.value.split('\n').map((s) => s.trim()).filter(Boolean).join(','))}
                    placeholder={'https://www.a.com\nhttps://a.com'}
                />
                <p className="text-xs text-muted-foreground">留空表示未开放 Widget。仅这些域名的页面可加载挂件。</p>
                <Button onClick={saveAllowedOrigins}>保存来源</Button>
            </div>

            <div className="space-y-2">
                <label className="text-sm font-medium">Widget 嵌入代码（粘贴到目标网站 &lt;/body&gt; 前）</label>
                <pre className="text-xs bg-muted p-3 rounded-md overflow-x-auto whitespace-pre-wrap">{embed}</pre>
                <Button variant="outline" onClick={() => copy(embed)}>复制嵌入代码</Button>
            </div>
        </div>
    );
};

export default IntegrationPage;
