import React, { useState } from 'react';
import { CheckCircle2, Loader2, XCircle } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import TurnstileWidget from '@/components/TurnstileWidget';
import { platformService } from '@/api/platform';
import type { TurnstileTestResult } from '@/api/platform';
import { getErrorMessage } from '@/lib/apiResult';

/**
 * 平台配置页的「测试 Turnstile」：用表单当前填写的 Site Key 渲染真实组件，拿到令牌后交后端向 Cloudflare 校验。
 *
 * 能测出三类问题：Site Key 不对或当前域名不在该站点的允许列表里（组件自身就会报错、拿不到令牌）；
 * Secret Key 不对或与 Site Key 不属于同一站点（Cloudflare 返回 error-codes）；服务器出站连不上 Cloudflare（后端 503）。
 */

// Cloudflare siteverify 的错误码 → 管理员能照着改的说明
const ERROR_HINTS: Record<string, string> = {
    'missing-input-secret': 'Secret Key 为空',
    'invalid-input-secret': 'Secret Key 不正确',
    'missing-input-response': '令牌为空',
    'invalid-input-response': '令牌无效：Site Key 与 Secret Key 可能不属于同一个 Turnstile 站点',
    'timeout-or-duplicate': '令牌已过期或已被校验过，请再测一次',
    'internal-error': 'Cloudflare 内部错误，请稍后重试',
    'bad-request': '请求格式错误',
};

interface TurnstileTestButtonProps {
    siteKey: string;
    /** 表单里当前填写的 Secret Key，留空表示用已保存的 */
    secretKey: string;
}

type Outcome = { kind: 'result'; result: TurnstileTestResult } | { kind: 'error'; message: string };

const TurnstileTestButton: React.FC<TurnstileTestButtonProps> = ({ siteKey, secretKey }) => {
    const [open, setOpen] = useState(false);
    const [verifying, setVerifying] = useState(false);
    const [outcome, setOutcome] = useState<Outcome | null>(null);
    const [resetSignal, setResetSignal] = useState(0);

    const handleToken = async (token: string | null) => {
        if (!token) return;
        setVerifying(true);
        setOutcome(null);
        try {
            const res = await platformService.testTurnstile({ token, secretKey: secretKey.trim() || undefined });
            if (res.code !== 200) throw new Error(res.message);
            setOutcome({ kind: 'result', result: res.data });
        } catch (e: unknown) {
            setOutcome({ kind: 'error', message: getErrorMessage(e) });
        } finally {
            setVerifying(false);
        }
    };

    const handleOpenChange = (next: boolean) => {
        setOpen(next);
        if (!next) {
            setOutcome(null);
            setVerifying(false);
        }
    };

    const retry = () => {
        setOutcome(null);
        setResetSignal((n) => n + 1);
    };

    return (
        <>
            <div className="space-y-1">
                <Button type="button" variant="outline" disabled={!siteKey.trim()} onClick={() => setOpen(true)}>
                    测试 Turnstile
                </Button>
                <p className="text-xs text-muted-foreground">
                    使用上方当前填写的 Site Key 与 Secret Key（Secret 留空则用已保存的），可在保存前先测试
                </p>
            </div>
            <Dialog open={open} onOpenChange={handleOpenChange}>
                <DialogContent>
                    <DialogHeader>
                        <DialogTitle>测试 Cloudflare Turnstile</DialogTitle>
                        <DialogDescription>
                            完成下方验证后，服务器会拿令牌向 Cloudflare 校验。组件本身报错通常是 Site Key 不对，或当前域名不在该站点的允许列表里。
                        </DialogDescription>
                    </DialogHeader>
                    {open && <TurnstileWidget siteKey={siteKey.trim()} onToken={handleToken} resetSignal={resetSignal} />}
                    {verifying && (
                        <p className="flex items-center gap-2 text-sm text-muted-foreground">
                            <Loader2 className="h-4 w-4 animate-spin" />服务器校验中…
                        </p>
                    )}
                    {outcome?.kind === 'result' && outcome.result.success && (
                        <p className="flex items-center gap-2 text-sm text-green-600">
                            <CheckCircle2 className="h-4 w-4" />
                            校验通过{outcome.result.hostname ? `（主机名：${outcome.result.hostname}）` : ''}，配置可用
                        </p>
                    )}
                    {outcome?.kind === 'result' && !outcome.result.success && (
                        <div className="space-y-1 text-sm text-destructive">
                            <p className="flex items-center gap-2"><XCircle className="h-4 w-4" />校验未通过</p>
                            <ul className="list-disc pl-6">
                                {(outcome.result.errorCodes.length ? outcome.result.errorCodes : ['unknown']).map((c) => (
                                    <li key={c}>{ERROR_HINTS[c] ?? '未知原因'}（{c}）</li>
                                ))}
                            </ul>
                        </div>
                    )}
                    {outcome?.kind === 'error' && (
                        <p className="flex items-center gap-2 text-sm text-destructive">
                            <XCircle className="h-4 w-4" />{outcome.message}
                        </p>
                    )}
                    {outcome && (
                        <div>
                            <Button type="button" variant="outline" size="sm" onClick={retry}>再测一次</Button>
                        </div>
                    )}
                </DialogContent>
            </Dialog>
        </>
    );
};

export default TurnstileTestButton;
