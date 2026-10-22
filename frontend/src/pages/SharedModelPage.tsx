import React from 'react';
import { ProviderModelManager } from '@/components/model/ProviderModelManager';

/**
 * 平台共享模型池（仅平台管理员）：平台供应商连接 + 其下的共享对话模型与向量模型。
 */
const SharedModelPage: React.FC = () => (
  <div className="p-8 h-full overflow-y-auto">
    <div className="max-w-6xl mx-auto space-y-6">
      <div>
        <h1 className="text-2xl font-bold">共享模型池</h1>
        <p className="text-muted-foreground text-sm mt-1">
          平台级共享 AI 模型，所有租户均可使用，优先级低于租户自有模型
        </p>
      </div>
      <ProviderModelManager scope="PLATFORM" />
    </div>
  </div>
);

export default SharedModelPage;
