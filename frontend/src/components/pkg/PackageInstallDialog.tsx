import React, { useState } from 'react';
import { toolPackageService } from '@/api/toolPackage';
import type { PackageDetail, PackageInstallResult } from '@/api/toolPackage';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import {
    Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter,
} from '@/components/ui/dialog';
import { useToast } from '@/hooks/use-toast';
import { getErrorMessage, throwIfFailed } from '@/lib/apiResult';

interface Props {
    pkg: PackageDetail | null;
    onClose: () => void;
    onInstalled: (result: PackageInstallResult) => void;
}

/**
 * 填变量并安装。
 *
 * ⚠️ `secret` 变量用 password 输入框且**永不回显**——同本仓库其它凭据字段的既有约定。
 *
 * @author ruan
 */
const PackageInstallDialog: React.FC<Props> = ({ pkg, onClose, onInstalled }) => {
    const { toast } = useToast();
    const [values, setValues] = useState<Record<string, string>>({});
    const [isInstalling, setIsInstalling] = useState(false);

    // 换一个包时必须清空上一个包填的值，否则会把别的包的凭据带过去。在渲染期同步完成
    // （而非 useEffect 里 setState），避免 react-hooks/set-state-in-effect 反模式——
    // 写法参考 CoverageTab 的 prevScopeKey 惯例。
    const [prevCode, setPrevCode] = useState(pkg?.code);
    if (pkg?.code !== prevCode) {
        setPrevCode(pkg?.code);
        setValues({});
    }

    if (!pkg) return null;

    const handleInstall = async () => {
        try {
            setIsInstalling(true);
            const res = await toolPackageService.install(pkg.code, values);
            throwIfFailed(res);
            onInstalled(res.data);
            onClose();
        } catch (error: unknown) {
            // 名字冲突、必填项没填、没选应用……全都是这条路回来的
            toast({ variant: 'destructive', title: '安装失败', description: getErrorMessage(error) });
        } finally {
            setIsInstalling(false);
        }
    };

    return (
        <Dialog open onOpenChange={(v) => !v && onClose()}>
            <DialogContent className="max-w-lg">
                <DialogHeader>
                    <DialogTitle>安装「{pkg.name}」</DialogTitle>
                </DialogHeader>
                <div className="space-y-3">
                    <div className="text-sm text-muted-foreground space-y-1">
                        <p>{pkg.description}</p>
                        <p>
                            将安装到当前应用：
                            {pkg.toolNames.length > 0 && ` ${pkg.toolNames.length} 个工具`}
                            {pkg.mcpServerNames.length > 0 && ` ${pkg.mcpServerNames.length} 个 MCP 服务`}
                            {pkg.skillNames.length > 0 && ` ${pkg.skillNames.length} 个技能`}
                        </p>
                    </div>

                    {pkg.variables.length === 0 ? (
                        <p className="text-sm text-muted-foreground">这个包不需要填写任何配置。</p>
                    ) : (
                        pkg.variables.map((v) => (
                            <div key={v.name} className="space-y-1">
                                <label className="text-sm font-medium" htmlFor={`pkg-var-${v.name}`}>
                                    {v.label}
                                    {v.required && <span className="text-destructive ml-0.5">*</span>}
                                </label>
                                <Input
                                    id={`pkg-var-${v.name}`}
                                    type={v.secret ? 'password' : 'text'}
                                    value={values[v.name] ?? ''}
                                    onChange={(e) =>
                                        setValues({ ...values, [v.name]: e.target.value })}
                                />
                                {v.help && <p className="text-xs text-muted-foreground">{v.help}</p>}
                            </div>
                        ))
                    )}
                </div>
                <DialogFooter>
                    <Button variant="outline" onClick={onClose}>取消</Button>
                    <Button onClick={handleInstall} disabled={isInstalling}>
                        {isInstalling ? '安装中…' : '确认安装'}
                    </Button>
                </DialogFooter>
            </DialogContent>
        </Dialog>
    );
};

export default PackageInstallDialog;
