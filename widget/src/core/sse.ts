// widget 版 SSE（fetch+ReadableStream）：X-App-Id header 鉴权（EventSource 不能带 header），自动重连。

export function parseSseChunk(raw: string): { event: string; data: string | null } {
  let event = 'message';
  const dataLines: string[] = [];
  for (const line of raw.split('\n')) {
    if (line === '' || line.startsWith(':')) continue;
    if (line.startsWith('event:')) event = line.slice(6).trim();
    else if (line.startsWith('data:')) dataLines.push(line.slice(5).replace(/^ /, ''));
  }
  return { event, data: dataLines.length ? dataLines.join('\n') : null };
}

export function connectSse(
  url: string,
  appKey: string,
  onEvent: (event: string, data: string) => void,
): { close: () => void } {
  let closed = false;
  let controller: AbortController | null = null;
  let retry = 0;

  const loop = async () => {
    while (!closed) {
      controller = new AbortController();
      try {
        const resp = await fetch(url, {
          headers: { 'X-App-Id': appKey, Accept: 'text/event-stream' },
          signal: controller.signal,
        });
        if (resp.status === 401 || resp.status === 403) {
          closed = true;                 // 鉴权失败不可恢复,停止重连(widget 无错误回调,静默停)
          return;
        }
        if (!resp.ok || !resp.body) throw new Error('SSE ' + resp.status);
        retry = 0;
        const reader = resp.body.getReader();
        const decoder = new TextDecoder();
        let buf = '';
        for (;;) {
          const { done, value } = await reader.read();
          if (done || closed) break;
          buf += decoder.decode(value, { stream: true });
          let idx: number;
          while ((idx = buf.indexOf('\n\n')) >= 0) {
            const chunk = buf.slice(0, idx);
            buf = buf.slice(idx + 2);
            const { event, data } = parseSseChunk(chunk);
            if (data !== null) onEvent(event, data);
          }
        }
      } catch {
        if (closed) return;
      }
      if (closed) return;
      retry += 1;
      await new Promise((r) => setTimeout(r, Math.min(15000, 500 * 2 ** Math.min(retry, 5))));
    }
  };
  void loop();
  return { close: () => { closed = true; controller?.abort(); } };
}
