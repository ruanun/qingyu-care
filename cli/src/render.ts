/**
 * 终端渲染。全部是纯函数（收数据、返回字符串），不碰 stdout——这样能直接断言输出内容。
 *
 * @author ruan
 */

export interface KnowledgeHit {
  content: string;
  score: number;
  source: string;
}

const fmtScore = (s: number): string => (typeof s === 'number' && Number.isFinite(s) ? s.toFixed(2) : '—');

export function formatHits(hits: KnowledgeHit[]): string {
  const scores = hits.map((h) => fmtScore(h.score)).join(' / ');
  return hits.length ? `⋯ 命中 ${hits.length} 条  ${scores}` : '⋯ 命中 0 条';
}

/** `/hits` 展开时用：逐条列出来源与正文。 */
export function formatHitDetails(hits: KnowledgeHit[]): string {
  if (!hits.length) return '（本轮没有检索命中）';
  return hits
    .map((h, i) => `[${i + 1}] ${fmtScore(h.score)}  ${h.source}\n${h.content}`)
    .join('\n\n');
}

export function formatDone(meta: { messageId?: number; suggestHandover?: boolean }): string {
  return `✓ messageId=${meta.messageId ?? '—'}  建议转人工=${meta.suggestHandover ? '是' : '否'}`;
}

export function formatCard(node: unknown): string {
  return `[卡片 ui_spec]\n${JSON.stringify(node, null, 2)}`;
}
