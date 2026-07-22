import React, { useState, useEffect } from 'react';
import { Settings, Edit2, Save, X } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardHeader, CardTitle, CardDescription, CardContent } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Switch } from '@/components/ui/switch';
import { Badge } from '@/components/ui/badge';
import { handoverService } from '@/api/handover';
import type { HandoverRule, HandoverRuleType } from '@/api/handover';
import { useToast } from '@/hooks/use-toast';

const HandoverRulePage: React.FC = () => {
    const [rules, setRules] = useState<HandoverRule[]>([]);
    const [isLoading, setIsLoading] = useState(true);
    const [editingId, setEditingId] = useState<number | null>(null);
    const [editConfig, setEditConfig] = useState<any>({});
    const { toast } = useToast();

    const fetchRules = async () => {
        try {
            setIsLoading(true);
            const response = await handoverService.getAllRules();
            if (response.code === 200) {
                setRules(response.data);
            }
        } catch (error: any) {
            toast({ variant: "destructive", title: "获取规则失败", description: error.message });
        } finally {
            setIsLoading(false);
        }
    };

    useEffect(() => {
        fetchRules();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    const handleToggleEnabled = async (rule: HandoverRule) => {
        try {
            const response = await handoverService.updateRule(rule.id, { enabled: !rule.enabled });
            if (response.code === 200) {
                toast({ title: rule.enabled ? "规则已禁用" : "规则已启用" });
                fetchRules();
            }
        } catch (error: any) {
            toast({ variant: "destructive", title: "更新失败", description: error.message });
        }
    };

    const handleEdit = (rule: HandoverRule) => {
        setEditingId(rule.id);
        try {
            setEditConfig(JSON.parse(rule.config));
        } catch {
            setEditConfig({});
        }
    };

    const handleSave = async (rule: HandoverRule) => {
        try {
            const response = await handoverService.updateRule(rule.id, {
                config: JSON.stringify(editConfig)
            });
            if (response.code === 200) {
                toast({ title: "配置已保存" });
                setEditingId(null);
                fetchRules();
            }
        } catch (error: any) {
            toast({ variant: "destructive", title: "保存失败", description: error.message });
        }
    };

    const getRuleTypeLabel = (type: HandoverRuleType) => {
        switch (type) {
            case 'KEYWORD': return { label: '关键词', color: 'bg-blue-500' };
            case 'LOW_CONFIDENCE': return { label: '低置信度', color: 'bg-orange-500' };
            case 'NO_HIT_STREAK': return { label: '连续未命中', color: 'bg-purple-500' };
            default: return { label: type, color: 'bg-gray-500' };
        }
    };

    const renderConfigEditor = (rule: HandoverRule) => {
        if (editingId !== rule.id) {
            // 显示模式
            try {
                const config = JSON.parse(rule.config);
                switch (rule.ruleType) {
                    case 'KEYWORD':
                        return <span className="text-sm text-muted-foreground">关键词: {config.keywords?.join(', ')}</span>;
                    case 'LOW_CONFIDENCE':
                        return <span className="text-sm text-muted-foreground">阈值: {config.threshold}</span>;
                    case 'NO_HIT_STREAK':
                        return <span className="text-sm text-muted-foreground">连续次数: {config.count}</span>;
                }
            } catch {
                return <span className="text-sm text-red-500">配置解析失败</span>;
            }
        }

        // 编辑模式
        switch (rule.ruleType) {
            case 'KEYWORD':
                return (
                    <div className="space-y-2">
                        <label className="text-xs text-muted-foreground">关键词（逗号分隔）</label>
                        <Input
                            value={editConfig.keywords?.join(', ') || ''}
                            onChange={(e) => setEditConfig({
                                ...editConfig,
                                keywords: e.target.value.split(',').map((k: string) => k.trim()).filter(Boolean)
                            })}
                            placeholder="转人工, 找客服, 人工服务"
                        />
                    </div>
                );
            case 'LOW_CONFIDENCE':
                return (
                    <div className="space-y-2">
                        <label className="text-xs text-muted-foreground">置信度阈值 (0-1)</label>
                        <Input
                            type="number"
                            step="0.1"
                            min="0"
                            max="1"
                            value={editConfig.threshold || 0.3}
                            onChange={(e) => setEditConfig({ ...editConfig, threshold: parseFloat(e.target.value) })}
                        />
                    </div>
                );
            case 'NO_HIT_STREAK':
                return (
                    <div className="space-y-2">
                        <label className="text-xs text-muted-foreground">连续未命中次数</label>
                        <Input
                            type="number"
                            min="1"
                            max="10"
                            value={editConfig.count || 3}
                            onChange={(e) => setEditConfig({ ...editConfig, count: parseInt(e.target.value) })}
                        />
                    </div>
                );
        }
    };

    return (
        <div className="p-8 h-full overflow-y-auto">
            <div className="max-w-3xl mx-auto space-y-6">
                {/* 标题 */}
                <div>
                    <h1 className="text-2xl font-bold flex items-center gap-2">
                        <Settings className="w-6 h-6" />
                        自动转人工策略
                    </h1>
                    <p className="text-muted-foreground text-sm mt-1">配置触发自动转人工的规则</p>
                </div>

                {/* 规则列表 */}
                {isLoading ? (
                    <div className="text-center py-12 text-muted-foreground">加载中...</div>
                ) : (
                    <div className="space-y-4">
                        {rules.map((rule) => {
                            const typeInfo = getRuleTypeLabel(rule.ruleType);
                            return (
                                <Card key={rule.id} className={!rule.enabled ? 'opacity-60' : ''}>
                                    <CardHeader className="pb-2">
                                        <div className="flex items-center justify-between">
                                            <div className="flex items-center gap-3">
                                                <Badge className={typeInfo.color}>{typeInfo.label}</Badge>
                                                <CardTitle className="text-lg">{rule.name}</CardTitle>
                                            </div>
                                            <div className="flex items-center gap-2">
                                                <Switch
                                                    checked={rule.enabled}
                                                    onCheckedChange={() => handleToggleEnabled(rule)}
                                                />
                                            </div>
                                        </div>
                                        {rule.description && (
                                            <CardDescription>{rule.description}</CardDescription>
                                        )}
                                    </CardHeader>
                                    <CardContent>
                                        <div className="flex items-center justify-between">
                                            <div className="flex-1">
                                                {renderConfigEditor(rule)}
                                            </div>
                                            <div className="flex gap-2 ml-4">
                                                {editingId === rule.id ? (
                                                    <>
                                                        <Button size="sm" onClick={() => handleSave(rule)}>
                                                            <Save className="w-4 h-4 mr-1" /> 保存
                                                        </Button>
                                                        <Button size="sm" variant="ghost" onClick={() => setEditingId(null)}>
                                                            <X className="w-4 h-4" />
                                                        </Button>
                                                    </>
                                                ) : (
                                                    <Button size="sm" variant="outline" onClick={() => handleEdit(rule)}>
                                                        <Edit2 className="w-4 h-4 mr-1" /> 编辑
                                                    </Button>
                                                )}
                                            </div>
                                        </div>
                                    </CardContent>
                                </Card>
                            );
                        })}
                    </div>
                )}
            </div>
        </div>
    );
};

export default HandoverRulePage;
