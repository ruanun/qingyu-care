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
    const [webhookUrl, setWebhookUrl] = useState('');
    const [webhookSecret, setWebhookSecret] = useState('');
    const [webhookEvents, setWebhookEvents] = useState('');

    const load = async () => {
        if (!currentApp) return;
        try {
            const r = await integrationService.getApp(currentApp.id);
            if (r.code !== 200) throw new Error(r.message);
            setAppKey(r.data.appKey ?? '');
            setAppSecret(r.data.appSecret ?? '');
            setRateLimit(r.data.rateLimitPerMinute != null ? String(r.data.rateLimitPerMinute) : '');
            setAllowedOrigins(r.data.allowedOrigins ?? '');
            setWebhookUrl(r.data.webhookUrl ?? '');
            setWebhookSecret(r.data.webhookSecret ?? '');
            setWebhookEvents(r.data.webhookEvents ?? '');
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

    const WEBHOOK_EVENTS = [
        { value: 'session.handover_requested', label: '转人工请求' },
        { value: 'session.ended', label: '会话结束' },
    ];

    const toggleWebhookEvent = (value: string) => {
        const set = new Set(webhookEvents.split(',').map((s) => s.trim()).filter(Boolean));
        if (set.has(value)) set.delete(value); else set.add(value);
        setWebhookEvents(Array.from(set).join(','));
    };

    const saveWebhook = async () => {
        if (!currentApp) return;
        try {
            const r = await integrationService.updateWebhook(currentApp.id, { webhookUrl: webhookUrl.trim(), webhookEvents });
            if (r.code !== 200) throw new Error(r.message);
            toast({ title: '已保存' });
        } catch (e: unknown) {
            const msg = e instanceof Error ? e.message : '未知错误';
            toast({ title: '保存失败', description: msg, variant: 'destructive' });
        }
    };

    const resetWebhookSecret = async () => {
        if (!currentApp) return;
        if (!confirm('确认重置 Webhook 密钥？重置后旧密钥立即失效，接收方需更新。')) return;
        try {
            const r = await integrationService.regenerateWebhookSecret(currentApp.id);
            if (r.code !== 200) throw new Error(r.message);
            setWebhookSecret(r.data.webhookSecret ?? '');
            toast({ title: 'Webhook 密钥已重置' });
        } catch (e: unknown) {
            const msg = e instanceof Error ? e.message : '未知错误';
            toast({ title: '重置失败', description: msg, variant: 'destructive' });
        }
    };

    const testWebhook = async () => {
        if (!currentApp) return;
        try {
            const r = await integrationService.sendWebhookTest(currentApp.id);
            if (r.code !== 200) throw new Error(r.message);
            if (r.data.delivered) {
                toast({ title: '测试事件已送达', description: `HTTP ${r.data.responseStatus}` });
            } else {
                toast({ title: '测试事件投递失败', description: r.data.error ?? `HTTP ${r.data.responseStatus}`, variant: 'destructive' });
            }
        } catch (e: unknown) {
            const msg = e instanceof Error ? e.message : '未知错误';
            toast({ title: '测试失败', description: msg, variant: 'destructive' });
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

            <div className="space-y-2">
                <label className="text-sm font-medium">Webhook 回调 URL</label>
                <Input
                    value={webhookUrl}
                    onChange={(e) => setWebhookUrl(e.target.value)}
                    placeholder="https://your-system.com/webhooks/qingyu"
                />
                <p className="text-xs text-muted-foreground">留空表示未启用 Webhook。事件发生时向此地址 POST 带签名的 JSON。</p>

                <label className="text-sm font-medium">订阅事件</label>
                <div className="flex flex-col gap-1">
                    {WEBHOOK_EVENTS.map((ev) => (
                        <label key={ev.value} className="flex items-center gap-2 text-sm">
                            <input
                                type="checkbox"
                                checked={webhookEvents.split(',').map((s) => s.trim()).includes(ev.value)}
                                onChange={() => toggleWebhookEvent(ev.value)}
                            />
                            {ev.label}（{ev.value}）
                        </label>
                    ))}
                </div>

                <div className="flex gap-2">
                    <Button onClick={saveWebhook}>保存 Webhook</Button>
                    <Button variant="outline" onClick={testWebhook}>发送测试事件</Button>
                </div>

                <label className="text-sm font-medium">Webhook 签名密钥</label>
                <div className="flex items-center gap-2">
                    <code className="text-xs bg-muted px-2 py-1 rounded">
                        {webhookSecret ? webhookSecret.slice(0, 6) + '••••••••' + webhookSecret.slice(-4) : '（重置后生成）'}
                    </code>
                    <Button variant="outline" onClick={resetWebhookSecret}>重置密钥</Button>
                </div>
                <p className="text-xs text-muted-foreground">
                    接收方用此密钥校验请求头 <code>X-Qingyu-Signature</code>（<code>sha256=HMAC-SHA256(secret, 请求体)</code>），并按 <code>X-Qingyu-Delivery-Id</code> 幂等去重。
                </p>
            </div>
        </div>
    );
};

export default IntegrationPage;
