import React from 'react';
import { Bot } from 'lucide-react';
import { ProviderModelManager } from '@/components/model/ProviderModelManager';
import { useAppStore } from '@/stores/appStore';

/**
 * 租户的模型配置：供应商连接（租户级，Key 填一次）+ 当前应用在各连接下的对话模型与向量模型。
 */
const ModelConfigPage: React.FC = () => {
    const currentApp = useAppStore((s) => s.currentApp);
    return (
        <div className="p-8 h-full overflow-y-auto">
            <div className="max-w-6xl mx-auto space-y-6">
                <div>
                    <h1 className="text-2xl font-bold flex items-center gap-2">
                        <Bot className="w-6 h-6" />
                        多模型与模型治理
                    </h1>
                    <p className="text-muted-foreground text-sm mt-1">
                        先添加供应商连接，再勾选要用的对话模型与向量模型；对话模型按场景与优先级路由、失败自动降级
                    </p>
                </div>
                <ProviderModelManager scope="TENANT" appName={currentApp?.name} />
            </div>
        </div>
    );
};

export default ModelConfigPage;
