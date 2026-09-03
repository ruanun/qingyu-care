#!/usr/bin/env bash
# 依赖漏洞扫描：后端 OWASP dependency-check + 三个 pnpm 子项目的 pnpm audit（安全审查 L13，2026-09-03）。
#
# 本仓库没有 CI，发版前手动跑一次（见 docs/部署文档.md「生产环境安全与调优清单」）。
# 刻意不绑进 mvn verify：首次要下载整个 NVD 漏洞库，国内网络可能十几分钟甚至失败，
# 绑进去会让每次 verify 都变成赌网络。
#
# 用法：
#   NVD_API_KEY=xxxx ./scripts/dep-audit.sh
# NVD API key 免费申请：https://nvd.nist.gov/developers/request-an-api-key
# 没有 key 时 dependency-check 会退化到极慢的匿名限速模式，多半超时。

set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
status=0

if [ -z "${NVD_API_KEY:-}" ]; then
  echo "⚠️  未设置 NVD_API_KEY，dependency-check 大概率超时；建议先申请（免费）" >&2
fi

echo "▶ 后端：OWASP dependency-check（CVSS ≥ 7 视为不通过；报告 backend/target/dependency-check-report.html）"
if (cd "$ROOT/backend" && mvn -q -Pdepcheck -Dnvd.api.key="${NVD_API_KEY:-}" org.owasp:dependency-check-maven:check); then
  echo "  ✓ 后端通过"
else
  echo "  ✗ 后端未通过或执行失败，展开读 backend/target/dependency-check-report.html"
  status=1
fi

# pnpm audit 只有官方 registry 实现了漏洞查询端点：本机若配了 npmmirror 之类的镜像会报
# ERR_PNPM_AUDIT_ENDPOINT_NOT_EXISTS，故这里显式指向官方源（只查漏洞库，不重新装依赖）。
NPM_AUDIT_REGISTRY="${NPM_AUDIT_REGISTRY:-https://registry.npmjs.org}"
for p in frontend widget cli; do
  echo "▶ $p：pnpm audit --prod --audit-level=high（registry=$NPM_AUDIT_REGISTRY）"
  if (cd "$ROOT/$p" && pnpm audit --prod --audit-level=high --registry="$NPM_AUDIT_REGISTRY"); then
    echo "  ✓ $p 通过"
  else
    echo "  ✗ $p 存在 high 及以上漏洞"
    status=1
  fi
done

exit $status
