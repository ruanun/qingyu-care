import React from 'react';
import ConfigForm from '@/components/ConfigForm';
import TurnstileTestButton from '@/components/TurnstileTestButton';
import type { ConfigField } from '@/components/ConfigForm';
import { platformService } from '@/api/platform';

const FIELDS: ConfigField[] = [
  { key: 'platform.registration.enabled', label: '开放注册', type: 'switch', default: 'false', help: '开启后任何人都能在登录页注册并自动获得一个新租户（系统无邮件/短信验证，建议同时启用下方的注册验证码）。未设置时按部署配置，生产默认关闭' },
  {
    key: 'platform.registration.captcha', label: '注册验证码', type: 'select', default: 'NONE',
    options: [
      { value: 'NONE', label: '不启用' },
      { value: 'IMAGE', label: '图形验证码' },
      { value: 'TURNSTILE', label: 'Cloudflare Turnstile' },
    ],
    help: '注册时的人机验证方式。选 Cloudflare Turnstile 需同时填写下面两项；注意访客需能访问 challenges.cloudflare.com',
  },
  { key: 'platform.captcha.turnstile.site-key', label: 'Turnstile Site Key', type: 'text', default: '', help: '在 Cloudflare 控制台 Turnstile 页面创建站点后获得，会下发给浏览器' },
  {
    key: 'platform.captcha.turnstile.secret-key', label: 'Turnstile Secret Key', type: 'secret', default: '',
    help: '仅保存在服务端、不回显；留空保存表示不修改',
    renderAfter: (values) => (
      <TurnstileTestButton
        siteKey={values['platform.captcha.turnstile.site-key'] ?? ''}
        secretKey={values['platform.captcha.turnstile.secret-key'] ?? ''}
      />
    ),
  },
  { key: 'platform.default.plan', label: '新租户默认套餐', type: 'text', default: 'FREE', help: '取值：FREE / PRO / ENTERPRISE，新注册租户将使用此套餐（已生效）' },
  { key: 'platform.announcement', label: '系统公告', type: 'textarea', default: '', help: '展示在管理后台顶部横幅的公告文本，留空则不展示（已生效）' },
  { key: 'billing.cost.price-per-1k-tokens', label: 'Token 费率（元 / 1K token）', type: 'text', default: '', help: '用量看板据此估算成本（总 token ÷ 1000 × 费率），留空或 0 表示不启用成本估算（已生效）' },
  { key: 'billing.quota.warn-threshold', label: '额度预警阈值（%）', type: 'number', default: '', help: '本月用量达到额度该百分比时开放 API 返回 X-Quota-Usage 预警头并记 QUOTA_WARN 审计，留空用默认 80（已生效）' },
  { key: 'billing.quota.FREE.calls', label: 'FREE 套餐月调用额度', type: 'number', default: '', help: '留空用内置默认 1000；填 0 表示不限（已生效）' },
  { key: 'billing.quota.FREE.tokens', label: 'FREE 套餐月 Token 额度', type: 'number', default: '', help: '留空用内置默认 100000；填 0 表示不限（已生效）' },
  { key: 'billing.quota.PRO.calls', label: 'PRO 套餐月调用额度', type: 'number', default: '', help: '留空用内置默认 50000；填 0 表示不限（已生效）' },
  { key: 'billing.quota.PRO.tokens', label: 'PRO 套餐月 Token 额度', type: 'number', default: '', help: '留空用内置默认 5000000；填 0 表示不限（已生效）' },
  { key: 'billing.quota.ENTERPRISE.calls', label: 'ENTERPRISE 套餐月调用额度', type: 'number', default: '', help: '留空用内置默认 0（不限）（已生效）' },
  { key: 'billing.quota.ENTERPRISE.tokens', label: 'ENTERPRISE 套餐月 Token 额度', type: 'number', default: '', help: '留空用内置默认 0（不限）（已生效）' },
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
