import type { CliOptions } from './args.js';

/**
 * 计算本地会话存储的分键。
 *
 * 分键必须同时覆盖「接入点」与「身份」两个维度，缺一都会导致会话被错误地续上：
 *
 * 1. **接入点**（apiBase + appKey）：同一台机器调试多个应用/环境时互不覆盖，这是最初就有的设计。
 * 2. **身份**（服务端 / 访客，含访客的 origin）：⚠️ 这是本函数存在的直接原因——服务端与访客两条
 *    鉴权路径能看到的知识库范围不同（`RetrievalScope`：服务端 INTERNAL 全见，访客仅见 PUBLIC
 *    文档）。分键若不含身份，用 `--as-visitor` 启动会**续上服务端那次留下的会话**（真机实测复现：
 *    启动后打印「续上会话 fe4e8095…」），运维以为自己在看访客视角、实际却是服务端视角的历史上下文，
 *    拿错视角的检索结果去归因正是这个调试工具最该避免的事。
 *    访客身份内再按 origin 细分：不同 origin 可能对应不同的白名单场景（如预发域名 vs 生产域名），
 *    混用同一把键会把这些场景的对话历史串在一起。
 *
 * @author ruan
 */
export function computeSessionKey(opts: CliOptions): string {
  const identity = opts.apiSecret ? 'server' : `visitor:${opts.origin ?? ''}`;
  return `${opts.apiBase}|${opts.appKey}|${identity}`;
}
