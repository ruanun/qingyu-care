#!/usr/bin/env bash
#
# 改到哪弹哪条：按本次改动的文件，列出 CLAUDE.md 里对应的手工验证清单章节。
#
# 为什么需要它：CLAUDE.md 里那几节「必须手工验证」是散文，只有被完整读到才生效，
# 而改一行附件代码不该要背下站点爬取的 13 条。这个脚本把它变成「改了哪些文件，
# 只弹哪几节」。
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

readonly CLAUDE_MD="CLAUDE.md"

# 「路径匹配正则 :: CLAUDE.md 章节标题」。
#
# 刻意只存标题、不复述条目内容——复述就会产生第二份文案，改一处忘另一处正是本仓库
# 反复踩的坑（卡片协议白名单分裂三处就是同一个病）。脚本负责告诉你「该读哪一节」，
# 内容永远以 CLAUDE.md 为唯一来源。
readonly RULES=(
  'ChatServiceImpl|controller/.*[Cc]hat|chatStream|widget/src/core/sse\.ts|ChatStreamEvent::### 改动流式对话链路后必须手工验证'
  'CARD_PROMPT|actionUri|[Uu]iSpec|CardErrorBoundary::### 改动卡片 Action URI 协议后必须同步三处'
  'service/web/|WebPageSync|WebPageFetcher|SiteCrawl|LinkExtractor|RobotsChecker|CrawlSite::### 改动网站知识库同步 / 站点爬取链路后必须手工验证'
  'service/media/|ChatAttachment|Attachment|widget/src/attachments\.ts::### 改动聊天附件链路后必须手工验证'
  # ChatStrategy / 混合检索 / 检索范围也归到这一节：该节第 1 条「向量检索活性（命中且
  # score > 0）」正是它们共同决定的东西。相似度阈值改错的症状与换模型没标定完全一样——
  # 知识库有内容却问什么都答不知道，且检索返回空是 fail-soft 正常路径，日志里什么都没有。
  'pom\.xml|AiModelConfig|ModelRouterService|EmbeddingModel|application.*\.ya?ml|ChatStrategy|HybridRetrieval|RetrievalScope|KeywordSearchDao::### 升级 Spring AI / Spring Boot / 更换 embedding 模型后必须手工验证的三件事'
  'AiTool|ChatToolService|SharedCredentialMasker|AiToolRepository::### 平台共享工具（`AiTool.scope`）'
  'ReadReceipt|read_receipt|AgentPage|widget/src/main\.ts::### 改动已读回执链路后必须手工验证'
  'service/mcp/|McpServer|McpTool|McpServerPage\.tsx::### 改动 MCP 接入链路后必须手工验证'
  'service/openapi/|UrlTemplateFiller|OpenApiImportDialog\.tsx::### 改动 OpenAPI 工具导入链路后必须手工验证'
  'widget/src/core/|^cli/::### 改动 widget 协议层或 CLI 后必须手工验证'
)

if [ ! -f "$CLAUDE_MD" ]; then
  echo "✗ 找不到 $CLAUDE_MD（当前目录 $REPO_ROOT）" >&2
  exit 1
fi

# 自校验：引用的章节标题必须真的存在。
# 少了这一步，CLAUDE.md 改个标题就会让对应规则静默失效——脚本照常 exit 0、什么都不提示，
# 和「本次改动无需手工验证」长得一模一样。这正是本仓库反复出现的那类静默退化。
stale=0
for rule in "${RULES[@]}"; do
  heading="${rule##*::}"
  if ! grep -qF "$heading" "$CLAUDE_MD"; then
    echo "✗ 规则引用的章节在 $CLAUDE_MD 里不存在：$heading" >&2
    stale=1
  fi
done
if [ "$stale" -ne 0 ]; then
  echo "" >&2
  echo "  章节标题被改动过，请同步更新本脚本的 RULES——否则对应提示会静默消失。" >&2
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
  heading="${rule##*::}"

  matched="$(echo "$changed" | grep -E "$pattern" || true)"
  [ -z "$matched" ] && continue

  hit=1
  echo "▶ ${heading#\#\#\# }"
  echo "  触发文件："
  echo "$matched" | sed 's/^/    /'
  echo "  → 展开读 $CLAUDE_MD 的「${heading#\#\#\# }」一节，逐条真机走一遍。"
  echo ""
done

if [ "$hit" -eq 0 ]; then
  echo "✓ 本次改动没有触及需要手工验证的链路。"
else
  echo "以上条目自动化测试覆盖不到，只能真机验证——这不是形式，每一条都对应过一次真实事故。"
fi
