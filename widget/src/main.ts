import { resolveConfig } from './config';
import { createApi, SessionExpiredError } from './api';
import { loadSession, saveSession, clearSession } from './session';
import { mountUi } from './ui';

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

  const ui = mountUi(brand, {
    onSend: (message) => void handleSend(message),
    onHandover: () => void handleHandover(),
  });

  async function handleSend(message: string): Promise<void> {
    ui.addUserMessage(message);
    ui.setInputEnabled(false);
    ui.setTyping(true);
    try {
      const reply = await sendWithRetry(message);
      ui.setTyping(false);
      ui.addBotMessage(reply.reply);
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
