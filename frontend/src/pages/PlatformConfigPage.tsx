import React from 'react';
import ConfigForm from '@/components/ConfigForm';
import type { ConfigField } from '@/components/ConfigForm';
import { platformService } from '@/api/platform';

const FIELDS: ConfigField[] = [
  { key: 'platform.registration.enabled', label: '开放注册', type: 'switch', default: 'true', help: '关闭后新用户无法自助注册（已生效）' },
  { key: 'platform.default.plan', label: '新租户默认套餐', type: 'text', default: 'FREE', help: '取值：FREE / PRO / ENTERPRISE，新注册租户将使用此套餐（已生效）' },
  { key: 'platform.announcement', label: '系统公告', type: 'textarea', default: '', help: '展示在管理后台顶部横幅的公告文本，留空则不展示（已生效）' },
];

const PlatformConfigPage: React.FC = () => (
  <ConfigForm
    title="平台配置"
    fields={FIELDS}
    load={platformService.getPlatformConfig}
    save={platformService.updatePlatformConfig}
  />
);

export default PlatformConfigPage;
