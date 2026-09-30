#!/usr/bin/env bash
#
# 改到哪弹哪条：按本次改动的文件，列出该读的文档章节。
#
# 为什么需要它：那些「必须手工验证」「容易写错的地方」都是散文，只有被完整读到才生效，
# 而改一行附件代码不该要背下站点爬取的 13 条。这个脚本把它变成「改了哪些文件，
# 只弹哪几节」。CLAUDE.md 在 2026-09-20 拆分后，它同时负责把人指到正确的**文件**——
# 拆出去的内容默认不进上下文，没有这层路由就会静默漏读。
#
# 为什么不判红：这些条目自动化测试<b>永远</b>覆盖不到（IT 里 ChatClient 与 VectorStore
# 全被 mock，真实 SSE 时序、embedding 计费、robots 遵守都看不见）。判红只会逼人
# 为了绿而空跑流程，反而让「验过了」这句话失去含义。
#
# 用法：
#   ./scripts/manual-smoke-hints.sh              # 看工作区（含暂存与未跟踪）的改动
#   ./scripts/manual-smoke-hints.sh master...HEAD  # 看整个分支的改动
#
# @author ruan

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

readonly MANUAL="docs/dev/手工验证清单.md"
readonly SUBSYS="docs/dev/子系统要点.md"
readonly BASELINE="docs/dev/安全基线.md"

# 「路径匹配正则 :: 文件 :: 章节标题」。
#
# 刻意只存标题、不复述条目内容——复述就会产生第二份文案，改一处忘另一处正是本仓库
# 反复踩的坑（卡片协议白名单分裂三处就是同一个病）。脚本负责告诉你「该读哪个文件的
# 哪一节」，内容永远以那份文档为唯一来源。
readonly RULES=(
  'ChatServiceImpl|controller/.*[Cc]hat|chatStream|widget/src/core/sse\.ts|ChatStreamEvent::MANUAL::## 改动流式对话链路后必须手工验证'
  'CARD_PROMPT|actionUri|[Uu]iSpec|CardErrorBoundary::MANUAL::## 改动卡片 Action URI 协议后必须同步三处'
  'service/web/|WebPageSync|WebPageFetcher|SiteCrawl|LinkExtractor|RobotsChecker|CrawlSite::MANUAL::## 改动网站知识库同步 / 站点爬取链路后必须手工验证'
  'service/web/|WebPageSync|WebPageFetcher|SiteCrawl|LinkExtractor|RobotsChecker|CrawlSite::SUBSYS::## 网站知识库（`service/web/` + `scheduler/WebPageSyncScheduler`）'
  'service/media/|ChatAttachment|Attachment|widget/src/attachments\.ts::MANUAL::## 改动聊天附件链路后必须手工验证'
  'service/media/|ChatAttachment|Attachment|widget/src/attachments\.ts::SUBSYS::## 聊天附件（多媒体消息，`service/media/` + `controller/support/AttachmentResponses`）'
  # ChatStrategy / 混合检索 / 检索范围也归到这一节：该节第 1 条「向量检索活性（命中且
  # score > 0）」正是它们共同决定的东西。相似度阈值改错的症状与换模型没标定完全一样——
  # 知识库有内容却问什么都答不知道，且检索返回空是 fail-soft 正常路径，日志里什么都没有。
  'pom\.xml|AiModelConfig|ModelRouterService|EmbeddingModel|application.*\.ya?ml|ChatStrategy|HybridRetrieval|RetrievalScope|KeywordSearchDao::MANUAL::## 升级 Spring AI / Spring Boot / 更换 embedding 模型后必须手工验证的三件事'
  'AiTool|ChatToolService|SharedCredentialMasker|AiToolRepository::SUBSYS::## 平台共享工具（`AiTool.scope`）'
  'ReadReceipt|read_receipt|AgentPage|widget/src/main\.ts::MANUAL::## 改动已读回执链路后必须手工验证'
  'service/mcp/|McpServer|McpTool|McpServerPage\.tsx::MANUAL::## 改动 MCP 接入链路后必须手工验证'
  'service/mcp/|McpServer|McpTool|McpServerPage\.tsx::SUBSYS::## MCP 接入（`service/mcp/`）'
  'service/openapi/|UrlTemplateFiller|OpenApiImportDialog\.tsx::MANUAL::## 改动 OpenAPI 工具导入链路后必须手工验证'
  'service/openapi/|UrlTemplateFiller|OpenApiImportDialog\.tsx::SUBSYS::## OpenAPI 工具导入（`service/openapi/` + `service/tool/UrlTemplateFiller`）'
  'widget/src/core/|^cli/::MANUAL::## 改动 widget 协议层或 CLI 后必须手工验证'
  'service/pkg/|dto/pkg/|ToolPackage|PackageManifest|PackageInstall|PackageStorePage|PlatformPackagePage|api/toolPackage\.ts::MANUAL::## 改动工具商店 / 能力包链路后必须手工验证'
  'service/pkg/|dto/pkg/|ToolPackage|PackageManifest|PackageInstall|PackageStorePage|PlatformPackagePage|api/toolPackage\.ts::SUBSYS::## 工具商店（能力包，`service/pkg/` + `dto/pkg/`）'
  # 安全基线：鉴权 / 守卫 / 五条出站路径 / 上传 / CORS / 异常处理 / 限流额度
  'SsrfGuard|Pinned.*Factory|PinnedAddressResolverGroup|TenantInterceptor|TenantDataAspect|TenantOwnershipGuard|AuthService|Captcha|Turnstile|PlatformConfigController|UserController|ApplicationController|SaTokenConfig|WebConfig|GlobalExceptionHandler|RateLimit|Quota|KnowledgeServiceImpl|TikaTextExtractor|WebhookSignature::BASELINE::# 安全基线'
)

resolve_file() {
  case "$1" in
    MANUAL)   echo "$MANUAL" ;;
    SUBSYS)   echo "$SUBSYS" ;;
    BASELINE) echo "$BASELINE" ;;
    *)        echo "" ;;
  esac
}

for f in "$MANUAL" "$SUBSYS" "$BASELINE"; do
  if [ ! -f "$f" ]; then
    echo "✗ 找不到 $f（当前目录 $REPO_ROOT）" >&2
    exit 1
  fi
done

# 自校验：引用的章节标题必须**整行逐字**存在于对应文件（grep -qxF，不是子串匹配）。
# ⚠️ 用子串匹配不够：给标题加个后缀（「## MCP 接入」→「## MCP 接入（新）」）仍会命中，
# 于是标题漂移抓不到——2026-09-20 拆分时变异验证实测暴露过一次。
# 少了这一步，标题改个字就会让对应规则静默失效——脚本照常 exit 0、什么都不提示，
# 和「本次改动无需手工验证」长得一模一样。这正是本仓库反复出现的那类静默退化。
# 拆分之后它还多守一层：章节被搬到另一个文件而规则没跟着改，同样会在这里转红。
stale=0
for rule in "${RULES[@]}"; do
  key="${rule#*::}"; key="${key%%::*}"
  heading="${rule##*::}"
  file="$(resolve_file "$key")"
  if [ -z "$file" ]; then
    echo "✗ 规则的文件键无法识别：$key" >&2
    stale=1
  elif ! grep -qxF "$heading" "$file"; then
    echo "✗ 规则引用的章节在 $file 里不存在：$heading" >&2
    stale=1
  fi
done
if [ "$stale" -ne 0 ]; then
  echo "" >&2
  echo "  章节标题或所在文件变动过，请同步更新本脚本的 RULES——否则对应提示会静默消失。" >&2
  exit 1
fi

# 收集改动文件
if [ "$#" -gt 0 ]; then
  changed="$(git diff --name-only "$1" 2>/dev/null)"
  scope="$1"
else
  changed="$(
    {
      git diff --name-only HEAD 2>/dev/null
      git diff --name-only --cached 2>/dev/null
      git ls-files --others --exclude-standard 2>/dev/null
    } | sort -u
  )"
  scope="工作区"
fi

if [ -z "$changed" ]; then
  echo "本次改动为空（范围：$scope），无需手工验证。"
  exit 0
fi

echo "改动范围：$scope"
echo ""

hit=0
for rule in "${RULES[@]}"; do
  pattern="${rule%%::*}"
  key="${rule#*::}"; key="${key%%::*}"
  heading="${rule##*::}"
  file="$(resolve_file "$key")"

  matched="$(echo "$changed" | grep -E "$pattern" || true)"
  [ -z "$matched" ] && continue

  hit=1
  echo "▶ $(echo "$heading" | sed 's/^#\+ //')   —— $file"
  echo "  触发文件："
  echo "$matched" | sed 's/^/    /'
  echo ""
done

if [ "$hit" -eq 0 ]; then
  echo "✓ 本次改动没有触及需要额外阅读的章节。"
else
  echo "以上章节请展开读完再改。多数条目对应过一次真实事故，且失效是静默的——"
  echo "自动化测试覆盖不到，所以这里不判红，但漏读的代价由你承担。"
fi
