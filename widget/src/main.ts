import { resolveConfig } from './config';
import { createApi, SessionExpiredError } from './api';
import { loadSession, saveSession, clearSession } from './session';
import { mountUi } from './ui';
import type { StreamingBotMessage } from './ui';
import { connectSse } from './core/sse';
import { restoreHistory } from './core/history';
import type { ChatStreamHandlers } from './core/chatStream';
import type { WidgetAttachment } from './attachments';

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

  // 已读上报所需的两块状态：面板是否展开（点击悬浮气泡切换）、当前已知最新一条坐席/AI消息 id。
  // 上报条件是两者与页面可见性三者同时成立，见 maybeReportRead。
  let isOpen = false;
  let latestAgentMessageId: number | null = null;

  /** fail-soft 上报已读；三个触发点（面板展开/收到新消息/标签页转前台）共用同一道判定。 */
  function maybeReportRead() {
    if (!sessionId || !isOpen || document.visibilityState !== 'visible') return;
    if (latestAgentMessageId == null) return;
    void api.markRead(sessionId, latestAgentMessageId);
  }
  // 触发点三：标签页从后台切回前台时补报（面板可能一直是展开的，只是标签页不在前台）。
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') maybeReportRead();
  });

  // inbox：坐席消息 / 会话状态 / typing / 已读回执实时通道
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
      // 坐席消息目前后端标 role=ASSISTANT，但 MessageRole 枚举本有 AGENT；
      // 两者都当坐席消息渲染，防止后端未来切换标注值时客户端静默丢消息。
      //
      // attachments 必须原样透传：后端 RealtimeNotifier#messageJson 是在推送那一刻现查附件
      // 塞进 payload 的（ChatServiceImpl#agentReply 特意把 attachToMessage 排在 toUserMessage
      // 之前就是为了让这一查查得到），这里丢掉字段等于让坐席发的图只剩文本气泡、且无任何提示，
      // 只有刷新页面走 getSession → restoreHistory 才看得见。
      if (event === 'message' && (p.role === 'ASSISTANT' || p.role === 'AGENT')) {
        ui.addAgentMessage(p.content, p.attachments as WidgetAttachment[] | undefined);
        // 触发点二：收到坐席/AI 新消息，仅当已展开且可见才上报（maybeReportRead 内部判定）。
        if (typeof p.messageId === 'number') {
          latestAgentMessageId = p.messageId;
          maybeReportRead();
        }
      } else if (event === 'session') {
        ui.showSessionState(p.text || sessionStateText(p.kind));
      } else if (event === 'typing' && p.from === 'agent') {
        ui.setTyping(true); // 短暂显示后由下一条消息/超时清除
      } else if (event === 'read') {
        // ⚠️ 独立事件类型，绝不走 showSessionState——那会在访客界面冒出一条莫名提示
        // （session 事件是无条件当文案展示的）。只标已读，不产生任何文案节点。
        if (typeof p.upToMessageId === 'number') ui.markReadUpTo(p.upToMessageId);
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
    onSend: (message, attachments) => void handleSend(message, attachments),
    onHandover: () => void handleHandover(),
    onTyping: () => reportTyping(),
    onAction: (value) => void handleSend(value),
    // sessionId 必填且不能是空串——后端上传端点直接拒绝（空串曾是所有租户共享的
    // 命名空间，两端已统一拒绝）。附件按钮在会话建立前始终禁用（见下方 setAttachEnabled），
    // 这里仍兜底判一次，防止两处状态不同步时打出一个必然 400 的请求。
    onUploadFile: (file) => {
      if (!sessionId) return Promise.reject(new Error('会话尚未建立，无法上传附件'));
      return api.uploadAttachment(sessionId, file);
    },
    // 闭包读取的是外层 let sessionId 的当前值（不是挂载时刻的快照），故会话切换后取到的
    // 始终是最新会话——历史消息里的附件也都属于当前这个会话，语义上是对的。
    resolveAttachmentUrl: (id) => {
      if (!sessionId) return Promise.reject(new Error('会话尚未建立，无法下载附件'));
      return api.downloadAttachment(id, sessionId).then((blob) => URL.createObjectURL(blob));
    },
    // 触发点一：面板从收起变为展开。收起是 widget 自己的内部状态，与页面可见性是两个独立
    // 条件，缺任一判断都会产生假已读——是否上报仍由 maybeReportRead 内部复核可见性。
    onToggle: (open) => {
      isOpen = open;
      if (open) maybeReportRead();
    },
  });

  // 刷新页面后若本地已恢复会话（可能已被坐席接管），先用 getSession 恢复历史消息（含历史卡片），
  // 再建 inbox 长连接，避免用户在再次发消息前收不到坐席实时回复；
  // 会话若已过期或补拉失败，getSession 静默返回 null，照常走 ensureInbox（connectSse 会自行退避重连）。
  if (sessionId) {
    // 本地已有 sessionId 即代表老用户此前交互过，欢迎语此时展示本就不合理：
    // 在 await getSession 网络往返之前就乐观抑制首开欢迎，避免"面板已可点开但 markOpened 未执行"的竞态窗口
    // （用户在等待窗口内点开面板会先插欢迎语、随后历史消息追加其后，导致「先欢迎后历史」错位）。
    ui.markOpened();
    ui.setAttachEnabled(true); // 本地已有 sessionId，附件上传端点可用
    const restored = await api.getSession(sessionId);
    if (restored) {
      if (restored.messages.length) {
        restoreHistory(ui, restored.messages);
        // 补建「当前已知最新一条坐席/AI消息 id」，供刷新后面板展开时的已读上报有值可报。
        for (const m of restored.messages) {
          if ((m.role === 'ASSISTANT' || m.role === 'AGENT') && typeof m.id === 'number') {
            latestAgentMessageId = m.id;
          }
        }
      }
      // 刷新恢复：从坐席的已读水位线还原访客自己消息上的「已读」标记（渲染 A，不涉及上报）。
      if (typeof restored.agentReadUpToMessageId === 'number') {
        ui.markReadUpTo(restored.agentReadUpToMessageId);
      }
    }
    ensureInbox(sessionId);
  }

  async function handleSend(message: string, attachments?: WidgetAttachment[]): Promise<void> {
    const userHandle = ui.addUserMessage(message, attachments);
    ui.setInputEnabled(false);
    ui.setTyping(true);

    let bot: StreamingBotMessage | null = null;
    // 首个正文事件到达时才撤「输入中…」并建消息行，避免空气泡
    const ensureBot = (): StreamingBotMessage => {
      if (!bot) {
        ui.setTyping(false);
        bot = ui.beginBotMessage();
      }
      return bot;
    };

    try {
      await streamWithRetry(message, {
        onStart: (d) => {
          sessionId = d.sessionId;
          saveSession(cfg!.appKey, d.sessionId);
          ensureInbox(d.sessionId);   // 流首即建坐席通道，不必等整条回复结束
          ui.setAttachEnabled(true); // 会话已建立（或确认存续），附件按钮可用
          // START 有就先用（人工接管静默/关键词触发/知识库未命中兜底三条早退路径带真实值），
          // 主问答路径此刻恒为 null，没有就等 DONE（见 chatStream.ts 的字段注释）。
          if (typeof d.userMessageId === 'number') userHandle.setMessageId(d.userMessageId);
        },
        onDelta: (t) => ensureBot().appendText(t),
        onCard: (n) => ensureBot().appendCard(n),
        onDone: (messageId, userMessageId) => {
          const sid = sessionId;
          // DONE 排在落库之后，只要落了库就必然带真实 id——补齐 START 拿不到时的兜底。
          if (typeof userMessageId === 'number') userHandle.setMessageId(userMessageId);
          ensureBot().finish(
            messageId != null && messageId > 0 && sid
              ? { onFeedback: (v) => void api.feedback(sid, messageId, v) }
              : undefined,
          );
        },
        onError: (msg) => ensureBot().appendText(`\n${msg}`),
      }, attachments?.map((a) => a.id));
    } catch (e) {
      if (bot) (bot as StreamingBotMessage).appendText('\n回复中断，请重试。');
      else ui.addBotMessage('抱歉，服务暂时不可用，请稍后再试。');
      console.warn('[Qingyu Widget] 发送失败', e);
    } finally {
      ui.setTyping(false);
      ui.setInputEnabled(true);
    }
  }

  // 会话失效兜底：首次 404 → 清本地会话、以新会话重发一次；用户无感。
  // 404 发生在响应头阶段（尚未开始吐字），故重试不会造成半截内容重复。
  //
  // ⚠️ attachmentIds 只在首次尝试携带，重试时刻意不传：这些附件是绑在已失效的旧
  // sessionId 上的（后端 chatAttachmentService.validateAttachable 按 sessionId 严格
  // 匹配），换成新会话后校验必然判"附件不属于当前会话"直接拒绝——不是丢附件，而是
  // 连文本一起发不出去。宁可丢附件保文本，用户可在新会话下重新选择文件再发一次。
  async function streamWithRetry(
    message: string,
    handlers: ChatStreamHandlers,
    attachmentIds?: number[],
  ): Promise<void> {
    try {
      await api.stream(message, sessionId, handlers, attachmentIds);
    } catch (e) {
      if (e instanceof SessionExpiredError) {
        clearSession(cfg!.appKey);
        sessionId = null;
        ui.setAttachEnabled(false);
        await api.stream(message, null, handlers);
        return;
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
