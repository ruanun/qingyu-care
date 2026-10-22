/** 仅允 http/https 且长度受限，其余返回空串（防 javascript:/data: 注入）。 */
export function safeUrl(u: unknown): string {
  if (typeof u !== 'string' || u.length > 2048) return '';
  try {
    const p = new URL(u);
    return (p.protocol === 'http:' || p.protocol === 'https:') ? u : '';
  } catch {
    return '';
  }
}
