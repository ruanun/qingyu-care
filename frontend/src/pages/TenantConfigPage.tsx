import React from 'react';
import ConfigForm from '@/components/ConfigForm';
import type { ConfigField } from '@/components/ConfigForm';
import { tenantConfigService } from '@/api/platform';

const FIELDS: ConfigField[] = [
  { key: 'tenant.display.name', label: '对外显示名 / 品牌', type: 'text', default: '', help: '暂未生效，规划中' },
  { key: 'tenant.session.timeout.minutes', label: '会话超时（分钟）', type: 'number', default: '', help: '留空则使用全局默认（暂未生效，规划中）' },
  { key: 'tenant.default.model.scenario', label: '默认模型场景', type: 'text', default: 'default', help: '暂未生效，规划中' },
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
