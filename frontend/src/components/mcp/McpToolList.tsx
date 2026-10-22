import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Switch } from '@/components/ui/switch';
import { cn } from '@/lib/utils';
import type { McpTool } from '@/api/mcp';

/**
 * 单个 MCP 服务的工具清单：逐条只读开关 + 「按远端建议全选只读」批量按钮。
 *
 * ⚠️ `readOnlyHint` 只是远端自报的建议值，仅用于界面默认勾选，真正的安全判据是
 * `readOnly`（租户显式标注，见后端 McpTool 实体注释）。批量按钮本身就是摩擦管理
 * ——没有它，接一个 20 工具的 server 要点 20 次，用户会转而要求"自动信任远端"，
 * 安全判据就是这样一步步被磨掉的（见 CLAUDE.md 记载的同类事故）。
 *
 * `DEPRECATED` 的工具（远端已下线，见后端 McpToolStatus 注释）禁用其只读开关——
 * 标了也没用，且容易让人误以为该工具还在生效。
 *
 * @author ruan
 */
interface Props {
    tools: McpTool[];
    /** 单个或批量标注只读位；父组件统一走 markReadOnly API + 判 code + toast + 刷新 */
    onMarkReadOnly: (toolIds: number[], readOnly: boolean) => void;
}

export function McpToolList({ tools, onMarkReadOnly }: Props) {
    const suggestedIds = tools.filter((t) => t.readOnlyHint === true).map((t) => t.id);

    return (
        <div className="space-y-3">
            <div className="flex items-center justify-between">
                <h4 className="text-sm font-medium">工具清单（{tools.length}）</h4>
                <Button
                    size="sm"
                    variant="outline"
                    disabled={suggestedIds.length === 0}
                    onClick={() => onMarkReadOnly(suggestedIds, true)}
                >
                    按远端建议全选只读
                </Button>
            </div>

            {tools.length === 0 ? (
                <p className="text-sm text-muted-foreground">
                    还没有工具，点击上方「同步工具」从远端拉取。
                </p>
            ) : (
                <div className="space-y-2">
                    {tools.map((tool) => {
                        const deprecated = tool.status === 'DEPRECATED';
                        return (
                            <div key={tool.id} className="flex items-center justify-between gap-4 rounded-md border p-3">
                                <div className="min-w-0 space-y-0.5">
                                    <div className="flex items-center gap-2">
                                        <span className={cn('text-sm font-mono', deprecated && 'text-muted-foreground line-through')}>
                                            {tool.name}
                                        </span>
                                        {deprecated && (
                                            <Badge variant="destructive" className="text-[10px]">DEPRECATED</Badge>
                                        )}
                                        {tool.readOnlyHint === true && (
                                            <Badge variant="outline" className="text-[10px]">远端建议只读</Badge>
                                        )}
                                    </div>
                                    {tool.description && (
                                        <p className="text-xs text-muted-foreground truncate">{tool.description}</p>
                                    )}
                                </div>
                                <div className="flex shrink-0 items-center gap-2 text-xs text-muted-foreground">
                                    <span>只读</span>
                                    <Switch
                                        aria-label={`只读-${tool.name}`}
                                        checked={tool.readOnly}
                                        disabled={deprecated}
                                        onCheckedChange={(checked) => onMarkReadOnly([tool.id], checked)}
                                    />
                                </div>
                            </div>
                        );
                    })}
                </div>
            )}
        </div>
    );
}
