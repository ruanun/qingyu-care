import React, { useEffect, useState } from 'react';
import { Store } from 'lucide-react';
import { toolPackageService } from '@/api/toolPackage';
import type { PackageDetail, PackageInstallResult } from '@/api/toolPackage';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import PackageInstallDialog from '@/components/pkg/PackageInstallDialog';
import { useToast } from '@/hooks/use-toast';
import { getErrorMessage, throwIfFailed } from '@/lib/apiResult';

/**
 * 工具商店（租户侧）：挑一个包 → 填自己的凭据 → 装好能用。
 *
 * ⚠️ 安装结果里的 warnings 必须**留在页面上**而不是只弹一次 toast：最典型的那条
 * 「MCP 服务同步失败，请手动同步后到技能里勾选工具」是要人照着做的，一闪而过等于没说。
 *
 * @author ruan
 */
const PackageStorePage: React.FC = () => {
    const { toast } = useToast();
    const [list, setList] = useState<PackageDetail[]>([]);
    const [isLoading, setIsLoading] = useState(true);
    const [installing, setInstalling] = useState<PackageDetail | null>(null);
    const [lastResult, setLastResult] = useState<PackageInstallResult | null>(null);

    const reload = async () => {
        try {
            setIsLoading(true);
            const res = await toolPackageService.listStore();
            throwIfFailed(res);
            setList(res.data ?? []);
        } catch (error: unknown) {
            toast({ variant: 'destructive', title: '加载失败', description: getErrorMessage(error) });
        } finally {
            setIsLoading(false);
        }
    };

    useEffect(() => {
        // eslint-disable-next-line react-hooks/set-state-in-effect -- 同 SharedToolPage
        reload();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    return (
        <div className="p-6 space-y-4 overflow-y-auto h-full">
            <div>
                <h3 className="text-lg font-semibold flex items-center gap-2">
                    <Store className="w-5 h-5" /> 工具商店
                </h3>
                <p className="text-sm text-muted-foreground">
                    挑一个能力包，填上你自己的凭据即可安装到当前应用。装下来的是你自己的副本，
                    之后可以随意修改、停用或删除。
                </p>
            </div>

            {lastResult && (
                <div className="border rounded-md p-3 space-y-1 bg-muted/40">
                    <p className="text-sm font-medium">安装完成</p>
                    <p className="text-sm text-muted-foreground">
                        新增 {lastResult.toolNames.length} 个工具、
                        {lastResult.mcpServerNames.length} 个 MCP 服务、
                        {lastResult.skillNames.length} 个技能
                    </p>
                    {lastResult.warnings.length > 0 && (
                        <ul className="text-sm list-disc pl-5 text-destructive space-y-0.5">
                            {lastResult.warnings.map((w) => <li key={w}>{w}</li>)}
                        </ul>
                    )}
                </div>
            )}

            {isLoading ? (
                <p className="text-sm text-muted-foreground">加载中…</p>
            ) : list.length === 0 ? (
                <p className="text-sm text-muted-foreground">暂时还没有上架的能力包。</p>
            ) : (
                <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
                    {list.map((pkg) => (
                        <div key={pkg.code} className="border rounded-lg p-4 space-y-2">
                            <div className="flex items-start justify-between gap-2">
                                <div className="min-w-0">
                                    <p className="font-medium">{pkg.name}</p>
                                    <p className="text-sm text-muted-foreground">{pkg.description}</p>
                                </div>
                                <Button size="sm" onClick={() => setInstalling(pkg)}>安装</Button>
                            </div>
                            <div className="flex flex-wrap gap-1">
                                {pkg.toolNames.map((n) => (
                                    <Badge key={`t-${n}`} variant="secondary">{n}</Badge>
                                ))}
                                {pkg.mcpServerNames.map((n) => (
                                    <Badge key={`m-${n}`} variant="secondary">MCP: {n}</Badge>
                                ))}
                                {pkg.skillNames.map((n) => (
                                    <Badge key={`s-${n}`} variant="outline">技能: {n}</Badge>
                                ))}
                            </div>
                        </div>
                    ))}
                </div>
            )}

            <PackageInstallDialog
                pkg={installing}
                onClose={() => setInstalling(null)}
                onInstalled={(result) => {
                    setLastResult(result);
                    toast({ title: '安装完成', description: '可以到工具与技能页查看装下来的副本' });
                }}
            />
        </div>
    );
};

export default PackageStorePage;
