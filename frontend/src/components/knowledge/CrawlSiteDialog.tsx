import { useState } from 'react';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import type { CrawlSite, CrawlSiteRequest } from '@/types';

/**
 * 爬取站点配置表单。建站与改配置共用。
 *
 * @author ruan
 */
interface Props {
    open: boolean;
    onOpenChange: (open: boolean) => void;
    /** 传入则为编辑模式 */
    initial?: CrawlSite | null;
    onSubmit: (req: CrawlSiteRequest) => Promise<void>;
}

/**
 * 由入口 URL 推导范围前缀，用于表单预填。
 *
 * ⚠️ 这只是给用户的预填提示——真值由后端 SiteCrawlService.deriveScopePrefix 推导，
 * 并强制校验 host 与入口一致。前端这份不要当成权威。
 *
 * 规则：`…/help/` → 原样；`…/help/faq/a.html` → `…/help/faq/`；`…/` 或无路径 → 整站
 */
function deriveScopePrefix(entryUrl: string): string {
    try {
        const u = new URL(entryUrl);
        const path = u.pathname;
        if (!path || path === '/') return `${u.origin}/`;
        if (path.endsWith('/')) return `${u.origin}${path}`;
        return `${u.origin}${path.slice(0, path.lastIndexOf('/') + 1)}`;
    } catch {
        return '';
    }
}

export function CrawlSiteDialog({ open, onOpenChange, initial, onSubmit }: Props) {
    const [entryUrl, setEntryUrl] = useState('');
    const [scopePrefix, setScopePrefix] = useState('');
    const [prefixTouched, setPrefixTouched] = useState(false);
    const [maxPages, setMaxPages] = useState(50);
    const [maxDepth, setMaxDepth] = useState(3);
    const [contentSelector, setContentSelector] = useState('');
    const [syncInterval, setSyncInterval] = useState('DAILY');
    const [submitting, setSubmitting] = useState(false);

    // 打开时按 initial 重置；新建模式回到默认值。在渲染期同步完成（而非 useEffect
    // 里 setState），避免 react-hooks/set-state-in-effect 反模式——写法参考
    // CoverageTab 的 prevScopeKey 惯例：用一个「上次已处理的 key」判断是否需要重置
    const resetKey = open ? `open:${initial?.id ?? 'new'}` : 'closed';
    const [prevResetKey, setPrevResetKey] = useState(resetKey);
    if (resetKey !== prevResetKey) {
        setPrevResetKey(resetKey);
        if (open) {
            setEntryUrl(initial?.entryUrl ?? '');
            setScopePrefix(initial?.scopePrefix ?? '');
            setPrefixTouched(!!initial);
            setMaxPages(initial?.maxPages ?? 50);
            setMaxDepth(initial?.maxDepth ?? 3);
            setContentSelector(initial?.contentSelector ?? '');
            setSyncInterval(initial?.syncInterval ?? 'DAILY');
        }
    }

    // 用户没手动改过前缀时，跟着入口 URL 自动推导
    const handleEntryChange = (value: string) => {
        setEntryUrl(value);
        if (!prefixTouched) setScopePrefix(deriveScopePrefix(value));
    };

    const handleSubmit = async () => {
        setSubmitting(true);
        try {
            await onSubmit({
                entryUrl: entryUrl.trim(),
                scopePrefix: scopePrefix.trim() || undefined,
                maxPages,
                maxDepth,
                contentSelector: contentSelector.trim() || undefined,
                syncInterval,
            });
            onOpenChange(false);
        } catch {
            // 失败信息已由父组件（CrawlSiteList）通过 toast 展示；这里只需保持对话框
            // 打开，方便用户修正后重试——不重新抛出，避免产生未处理的 promise rejection
        } finally {
            setSubmitting(false);
        }
    };

    return (
        <Dialog open={open} onOpenChange={onOpenChange}>
            <DialogContent>
                <DialogHeader>
                    <DialogTitle>{initial ? '编辑爬取站点' : '爬取整个站点'}</DialogTitle>
                    <DialogDescription>
                        从入口页出发自动发现同域链接并逐页入库，每页一篇文档。遵守站点的 robots.txt，
                        并按 1 秒/页限速（站点声明 Crawl-delay 时以它为准）。
                    </DialogDescription>
                </DialogHeader>
                <div className="space-y-4">
                    <div className="space-y-1">
                        <label className="text-xs text-muted-foreground">
                            入口 URL{initial ? '（创建后不可修改）' : ''}
                        </label>
                        <Input
                            placeholder="https://example.com/help/"
                            value={entryUrl}
                            disabled={!!initial}
                            onChange={(e) => handleEntryChange(e.target.value)}
                        />
                        {initial && (
                            <p className="text-xs text-muted-foreground">
                                如需更换入口地址，请建一个新的爬取站点；这里只能改抓取范围/深度/页数/
                                选择器/同步周期。
                            </p>
                        )}
                    </div>

                    <div className="space-y-1">
                        <label className="text-xs text-muted-foreground">爬取范围（只抓以此为前缀的地址）</label>
                        <Input
                            placeholder="https://example.com/help/"
                            value={scopePrefix}
                            onChange={(e) => { setPrefixTouched(true); setScopePrefix(e.target.value); }}
                        />
                        <p className="text-xs text-muted-foreground">
                            默认按入口地址推导：<code>/help/</code> 取整个 help 目录；
                            <code>/help/faq/a.html</code> 取 <code>/help/faq/</code>；根地址则为整站。
                            必须与入口同域。
                        </p>
                    </div>

                    <div className="grid grid-cols-2 gap-3">
                        <div className="space-y-1">
                            <label className="text-xs text-muted-foreground">最多爬取页数（上限 500）</label>
                            <Input
                                type="number" min={1} max={500} value={maxPages}
                                onChange={(e) => setMaxPages(Number(e.target.value))}
                            />
                        </div>
                        <div className="space-y-1">
                            <label className="text-xs text-muted-foreground">链接深度（上限 5）</label>
                            <Input
                                type="number" min={0} max={5} value={maxDepth}
                                onChange={(e) => setMaxDepth(Number(e.target.value))}
                            />
                        </div>
                    </div>

                    <div className="space-y-1">
                        <label className="text-xs text-muted-foreground">正文选择器（可选）</label>
                        <Input
                            placeholder="article 或 .doc-content"
                            value={contentSelector}
                            onChange={(e) => setContentSelector(e.target.value)}
                        />
                        <p className="text-xs text-muted-foreground">
                            填了就只取该容器内的文字，能彻底避开用 div 做的导航栏；不填则自动剔除
                            nav/header/footer 等噪声标签。F12 找一次 class 换整站准确度。
                        </p>
                    </div>

                    <div className="space-y-1">
                        <label className="text-xs text-muted-foreground">每篇文档的自动同步周期</label>
                        <Select value={syncInterval} onValueChange={setSyncInterval}>
                            <SelectTrigger className="h-9">
                                <SelectValue />
                            </SelectTrigger>
                            <SelectContent>
                                <SelectItem value="MANUAL">不自动同步</SelectItem>
                                <SelectItem value="HOURLY">每小时</SelectItem>
                                <SelectItem value="DAILY">每天</SelectItem>
                                <SelectItem value="WEEKLY">每周</SelectItem>
                            </SelectContent>
                        </Select>
                    </div>

                    <Button
                        className="w-full"
                        onClick={handleSubmit}
                        disabled={submitting || !entryUrl.trim()}
                    >
                        {initial ? '保存配置' : submitting ? '正在启动…' : '开始爬取'}
                    </Button>
                </div>
            </DialogContent>
        </Dialog>
    );
}
