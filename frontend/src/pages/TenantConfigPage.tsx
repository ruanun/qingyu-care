import React from 'react';
import ConfigForm from '@/components/ConfigForm';
import type { ConfigField } from '@/components/ConfigForm';
import { tenantConfigService } from '@/api/platform';

const FIELDS: ConfigField[] = [
  { key: 'tenant.display.name', label: '对外显示名 / 品牌', type: 'text', default: '', help: '配置后覆盖 Widget 挂件对外展示的应用名，留空则用应用名（已生效）' },
  { key: 'tenant.session.timeout.minutes', label: '会话超时（分钟）', type: 'number', default: '', help: '本租户会话超时关闭阈值，留空/非法值使用全局默认 30 分钟（已生效）' },
  { key: 'tenant.default.model.scenario', label: '默认模型场景', type: 'text', default: 'default', help: '对话模型路由使用的场景名（须与模型配置的「适用场景」精确一致），留空为 default（已生效）' },
];

const TenantConfigPage: React.FC = () => (
  <ConfigForm
    title="租户配置"
    fields={FIELDS}
    load={tenantConfigService.getTenantConfig}
    save={tenantConfigService.updateTenantConfig}
  />
);

export default TenantConfigPage;
