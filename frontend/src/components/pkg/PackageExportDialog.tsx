import React, { useEffect, useState } from 'react';
import { aiToolService, aiSkillService } from '@/api/chat-tool';
import { mcpApi } from '@/api/mcp';
import { toolPackageService } from '@/api/toolPackage';
import type { AiTool, AiSkill } from '@/api/chat-tool';
import type { McpServer } from '@/api/mcp';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import {
    Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter,
} from '@/components/ui/dialog';
import { useToast } from '@/hooks/use-toast';
import { getErrorMessage, throwIfFailed } from '@/lib/apiResult';

interface Props {
    open: boolean;
    onOpenChange: (open: boolean) => void;
    /** 导出成功后由父组件刷新列表并展示 warnings */
    onExported: (warnings: string[]) => void;
}

/**
 * 从现成配置导出成草稿包。
 *
 * ⚠️ 勾选列表来自**当前选中应用**下的工具/MCP 服务/技能。平台管理员必须先在上方选中自己的
 * 应用——选了别人租户的应用时，后端会拒绝导出（那些行的 tenant_id 非空）。
 *
 * @author ruan
 */
const PackageExportDialog: React.FC<Props> = ({ open, onOpenChange, onExported }) => {
    const { toast } = useToast();
    const [tools, setTools] = useState<AiTool[]>([]);
    const [servers, setServers] = useState<McpServer[]>([]);
    const [skills, setSkills] = useState<AiSkill[]>([]);
    const [code, setCode] = useState('');
    const [name, setName] = useState('');
    const [description, setDescription] = useState('');
    const [toolIds, setToolIds] = useState<number[]>([]);
    const [serverIds, setServerIds] = useState<number[]>([]);
    const [skillIds, setSkillIds] = useState<number[]>([]);
    const [isSaving, setIsSaving] = useState(false);

    useEffect(() => {
        if (!open) return;
        // 每次打开都复位，否则上一次的勾选会残留（「组件级 state + 每次打开时复位」那类坑）
        // eslint-disable-next-line react-hooks/set-state-in-effect -- 效果的主要目的是发起数据请求，复位表单字段是同一次「打开对话框」同步动作的一部分
        setCode('');
        setName('');
        setDescription('');
        setToolIds([]);
        setServerIds([]);
        setSkillIds([]);
        void (async () => {
            try {
                const [toolRes, serverRes, skillRes] = await Promise.all([
                    aiToolService.list(), mcpApi.listServers(), aiSkillService.list(),
                ]);
                throwIfFailed(toolRes);
                throwIfFailed(serverRes);
                throwIfFailed(skillRes);
                setTools(toolRes.data ?? []);
                setServers(serverRes.data ?? []);
                setSkills(skillRes.data ?? []);
            } catch (error: unknown) {
                toast({ variant: 'destructive', title: '加载可选项失败', description: getErrorMessage(error) });
            }
        })();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [open]);

    const toggle = (ids: number[], setIds: (v: number[]) => void, id: number) =>
        setIds(ids.includes(id) ? ids.filter((x) => x !== id) : [...ids, id]);

    const handleExport = async () => {
        try {
            setIsSaving(true);
            const res = await toolPackageService.exportPackage({
                code: code.trim(), name: name.trim(), description: description.trim(),
                toolIds, mcpServerIds: serverIds, skillIds,
            });
            throwIfFailed(res);
            onExported(res.data?.warnings ?? []);
            onOpenChange(false);
        } catch (error: unknown) {
            toast({ variant: 'destructive', title: '导出失败', description: getErrorMessage(error) });
        } finally {
            setIsSaving(false);
        }
    };

    // ⚠️ label 返回 ReactNode 而不是 string：勾选项要显示「名字 + 灰色描述」两段，
    // 用 JSX + Tailwind 间距做视觉分隔。**不要**在模板字符串里塞全角空格当分隔符——
    // 那会触发 eslint 的 no-irregular-whitespace（该规则专防不可见异常空白字符混入代码，
    // 全角空格在等宽字体下与普通空格几乎无法区分），而压掉它等于在代码里留一个隐形字符。
    const checkboxList = <T extends { id: number }>(
        items: T[], selected: number[], setSelected: (v: number[]) => void,
        label: (item: T) => React.ReactNode, emptyHint: string,
    ) => (
        items.length === 0
            ? <p className="text-sm text-muted-foreground py-2">{emptyHint}</p>
            : <div className="space-y-1 max-h-40 overflow-y-auto border rounded-md p-2">
                {items.map((item) => (
                    <label key={item.id} className="flex items-center gap-2 text-sm cursor-pointer">
                        <input
                            type="checkbox"
                            checked={selected.includes(item.id)}
                            onChange={() => toggle(selected, setSelected, item.id)}
                        />
                        <span>{label(item)}</span>
                    </label>
                ))}
            </div>
    );

    return (
        <Dialog open={open} onOpenChange={onOpenChange}>
            <DialogContent className="max-w-2xl">
                <DialogHeader>
                    <DialogTitle>导出能力包</DialogTitle>
                </DialogHeader>
                <div className="space-y-3">
                    <p className="text-sm text-muted-foreground">
                        勾选你已经调通、并用真实对话验证过的配置。凭据会自动升格成安装时要租户填的变量，
                        明文不会写进包里。
                    </p>
                    <Input placeholder="包标识（小写字母开头，如 weather_pack）"
                           value={code} onChange={(e) => setCode(e.target.value)} />
                    <Input placeholder="包名称（展示给租户看）"
                           value={name} onChange={(e) => setName(e.target.value)} />
                    <Input placeholder="一句话说明这个包能做什么"
                           value={description} onChange={(e) => setDescription(e.target.value)} />

                    <div>
                        <p className="text-sm font-medium mb-1">工具</p>
                        {checkboxList(tools, toolIds, setToolIds,
                            (t) => (
                                <>
                                    <span>{t.name}</span>
                                    <span className="ml-2 text-muted-foreground">{t.description}</span>
                                </>
                            ), '当前应用下没有工具')}
                    </div>
                    <div>
                        <p className="text-sm font-medium mb-1">MCP 服务</p>
                        {checkboxList(servers, serverIds, setServerIds,
                            (s) => `${s.name}（${s.code}）`, '当前应用下没有 MCP 服务')}
                    </div>
                    <div>
                        <p className="text-sm font-medium mb-1">技能</p>
                        {checkboxList(skills, skillIds, setSkillIds,
                            (s) => s.name, '当前应用下没有技能')}
                    </div>
                </div>
                <DialogFooter>
                    <Button variant="outline" onClick={() => onOpenChange(false)}>取消</Button>
                    <Button onClick={handleExport} disabled={isSaving}>
                        {isSaving ? '导出中…' : '导出为草稿'}
                    </Button>
                </DialogFooter>
            </DialogContent>
        </Dialog>
    );
};

export default PackageExportDialog;
