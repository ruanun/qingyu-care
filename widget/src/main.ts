import { resolveConfig } from './config';
import { createApi, SessionExpiredError } from './api';
import { loadSession, saveSession, clearSession } from './session';
import { mountUi } from './ui';
import { connectSse } from './sse';

/** 会话状态事件 kind → 展示文案兜底（后端通常已带 text，此处仅作兜底） */
function sessionStateText(kind: string): string {
  switch (kind) {
    case 'accepted':
      return '客服已接入';
    case 'returned_to_ai':
      return '已切回智能助手';
    case 'ended':
      return '会话已结束';
    case 'waiting':
      return '正在为您转接人工…';
    default:
      return '';
  }
}

async function init(): Promise<void> {
  const cfg = resolveConfig();
  if (!cfg) return; // 缺 appKey 已在 resolveConfig 内告警

  const api = createApi(cfg);

  let brand;
  try {
    brand = await api.getConfig();
  } catch (e) {
    console.warn('[Qingyu Widget] 拉取配置失败，停止渲染', e);
    return; // fail silently，不干扰宿主页面
  }

  let sessionId: string | null = loadSession(cfg.appKey);

  // inbox：坐席消息 / 会话状态 / typing 实时通道
  let inbox: { close: () => void } | null = null;
  let inboxSid: string | null = null;
  function ensureInbox(sid: string) {
    if (inboxSid === sid && inbox) return;
    inbox?.close();
    inboxSid = sid;
    inbox = connectSse(api.inboxUrl(sid), cfg!.appKey, (event, data) => {
      let p: any;
      try {
        p = JSON.parse(data);
      } catch {
        return; // 非法负载忽略，不影响对话
      }
      if (event === 'message' && p.role === 'ASSISTANT') {
        ui.addAgentMessage(p.content);
      } else if (event === 'session') {
        ui.showSessionState(p.text || sessionStateText(p.kind));
      } else if (event === 'typing' && p.from === 'agent') {
        ui.setTyping(true); // 短暂显示后由下一条消息/超时清除
      }
    });
  }
  // 卸载/页面离开时释放长连接，避免泄漏
  window.addEventListener('pagehide', () => inbox?.close());

  // 用户 typing 节流上报（≥3s 一次），仅在已有会话时上报
  let lastTypingReport = 0;
  const TYPING_THROTTLE_MS = 3000;
  function reportTyping() {
    if (!sessionId) return;
    const now = Date.now();
    if (now - lastTypingReport < TYPING_THROTTLE_MS) return;
    lastTypingReport = now;
    void api.sendTyping(sessionId);
  }

  const ui = mountUi(brand, {
    onSend: (message) => void handleSend(message),
    onHandover: () => void handleHandover(),
    onTyping: () => reportTyping(),
  });

  async function handleSend(message: string): Promise<void> {
    ui.addUserMessage(message);
    ui.setInputEnabled(false);
    ui.setTyping(true);
    try {
      const reply = await sendWithRetry(message);
      ensureInbox(reply.sessionId);
      ui.setTyping(false);
      const mid = reply.messageId;
      ui.addBotMessage(
        reply.reply,
        mid
          ? { onFeedback: (v) => void api.feedback(reply.sessionId, mid, v) }
          : undefined,
      );
    } catch (e) {
      ui.setTyping(false);
      ui.addBotMessage('抱歉，服务暂时不可用，请稍后再试。');
      console.warn('[Qingyu Widget] 发送失败', e);
    } finally {
      ui.setInputEnabled(true);
    }
  }

  // 会话失效兜底：首次 404 → 清本地会话、以新会话重发一次；用户无感
  async function sendWithRetry(message: string) {
    try {
      const reply = await api.send(message, sessionId);
      sessionId = reply.sessionId;
      saveSession(cfg!.appKey, sessionId);
      return reply;
    } catch (e) {
      if (e instanceof SessionExpiredError) {
        clearSession(cfg!.appKey);
        sessionId = null;
        const reply = await api.send(message, null);
        sessionId = reply.sessionId;
        saveSession(cfg!.appKey, sessionId);
        return reply;
      }
      throw e;
    }
  }

  async function handleHandover(): Promise<void> {
    if (!sessionId) {
      ui.addBotMessage('请先发送消息开始会话，再申请转人工。');
      return;
    }
    try {
      await api.handover(sessionId);
      ui.addBotMessage('已为你转接人工，请稍候。');
    } catch (e) {
      console.warn('[Qingyu Widget] 转人工失败', e);
    }
  }
}

// DOM 就绪后初始化，避免 body 尚未可用
if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', () => void init());
} else {
  void init();
}
