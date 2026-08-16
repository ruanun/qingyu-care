import { buildStyles } from './styles';
import { BUBBLE_LOGO_SVG } from './logo';
import { renderNode } from './render';
import type { WidgetBrand, UiPart, UiNode } from './api';
import { renderAttachments, type WidgetAttachment, type ResolveAttachmentUrl } from './attachments';

export interface UiHandlers {
  onSend: (message: string, attachments?: WidgetAttachment[]) => void;
  onHandover: () => void;
  /** 用户在输入框中输入（未节流，节流由调用方控制）；可选 */
  onTyping?: () => void;
  /** 卡片按钮点击回填；未提供时复用 onSend */
  onAction?: (value: string) => void;
  /**
   * 上传一个附件（选中即上传，不等发送），返回附件元数据供组进待发送预览。
   * 可选——未提供时附件按钮点击选中文件后静默忽略（不崩，仅不生效），
   * 与 onAction 缺省回退 onSend 是同一种"可选能力"处理惯例。
   */
  onUploadFile?: (file: File) => Promise<WidgetAttachment>;
  /** 取附件二进制的 blob URL，供消息里的图片预览/文件下载换源；可选，缺省时只渲染骨架不换源 */
  resolveAttachmentUrl?: ResolveAttachmentUrl;
}

export interface BotFeedback {
  onFeedback: (value: 1 | -1) => void;
}

/** 流式 AI 消息句柄：由 beginBotMessage 返回，供调用方逐步喂入文本增量/卡片，最后 finish 收尾。 */
export interface StreamingBotMessage {
  appendText: (text: string) => void;
  appendCard: (node: UiNode) => void;
  finish: (feedback?: BotFeedback) => void;
}

export interface WidgetUi {
  addUserMessage: (text: string, attachments?: WidgetAttachment[]) => void;
  addBotMessage: (text: string, feedback?: BotFeedback, uiParts?: UiPart[], attachments?: WidgetAttachment[]) => void;
  /** 渲染坐席（真人）消息，带「客服」标记以区分 AI 回复 */
  addAgentMessage: (text: string, attachments?: WidgetAttachment[]) => void;
  /** 消息区插入一条居中系统状态提示（如「客服已接入」） */
  showSessionState: (text: string) => void;
  setTyping: (on: boolean) => void;
  setInputEnabled: (on: boolean) => void;
  /**
   * 附件按钮是否可用。sessionId 不存在时后端上传端点直接拒绝（空串是所有租户共享的
   * 命名空间，两端已统一拒绝），故首条消息发出、会话建立前必须禁用——由调用方
   * （main.ts）按 sessionId 是否存在来驱动。
   */
  setAttachEnabled: (on: boolean) => void;
  /** 标记「已开场」，抑制首次打开面板时的欢迎语插入（用于会话恢复出历史消息后，避免与历史重复问候）。 */
  markOpened: () => void;
  /** 开始一条流式 AI 消息，返回增量渲染句柄（文本追加/插卡片/收尾）。 */
  beginBotMessage: () => StreamingBotMessage;
}

/**
 * 在 Shadow DOM 内构建挂件 UI，返回操作句柄。
 * 所有样式与节点都在 shadow root 内，不污染宿主页面、也不受其影响。
 */
export function mountUi(brand: WidgetBrand, handlers: UiHandlers): WidgetUi {
  const host = document.createElement('div');
  document.body.appendChild(host);
  const root = host.attachShadow({ mode: 'open' });

  const style = document.createElement('style');
  style.textContent = buildStyles(brand.themeColor || '#6366f1');
  root.appendChild(style);

  const bubble = document.createElement('button');
  bubble.className = 'qy-bubble';
  bubble.innerHTML = BUBBLE_LOGO_SVG; // 模块内常量、无用户输入，同下方 panel 的用法
  bubble.setAttribute('aria-label', '打开客服');

  const panel = document.createElement('div');
  panel.className = 'qy-panel';
  panel.innerHTML = `
    <div class="qy-header">
      <span>${escapeHtml(brand.appName || '在线客服')}</span>
      <button class="qy-handover" type="button">转人工</button>
    </div>
    <div class="qy-msgs"></div>
    <div class="qy-attach-preview"></div>
    <div class="qy-input">
      <button type="button" class="qy-attach-btn" aria-label="添加附件" disabled>📎</button>
      <input type="file" class="qy-attach-file-input" hidden />
      <textarea placeholder="输入消息…"></textarea>
      <button type="button">发送</button>
    </div>
  `;

  root.appendChild(bubble);
  root.appendChild(panel);

  const msgs = panel.querySelector<HTMLDivElement>('.qy-msgs')!;
  const textarea = panel.querySelector<HTMLTextAreaElement>('textarea')!;
  const sendBtn = panel.querySelector<HTMLButtonElement>('.qy-input button:not(.qy-attach-btn)')!;
  const handoverBtn = panel.querySelector<HTMLButtonElement>('.qy-handover')!;
  const attachBtn = panel.querySelector<HTMLButtonElement>('.qy-attach-btn')!;
  const attachFileInput = panel.querySelector<HTMLInputElement>('.qy-attach-file-input')!;
  const attachPreview = panel.querySelector<HTMLDivElement>('.qy-attach-preview')!;

  // 待发送附件：选中即上传（见 handlers.onUploadFile），成功后进这里，随下一次发送一并提交。
  let pendingAttachments: WidgetAttachment[] = [];
  let attachEnabled = false;

  function renderPendingAttachments() {
    attachPreview.replaceChildren();
    for (const a of pendingAttachments) {
      const chip = document.createElement('div');
      chip.className = 'qy-attach-chip';
      const name = document.createElement('span');
      name.className = 'qy-attach-chip-name';
      name.textContent = a.fileName; // 用户输入，必须 textContent
      const remove = document.createElement('button');
      remove.type = 'button';
      remove.className = 'qy-attach-chip-remove';
      remove.textContent = '×';
      remove.setAttribute('aria-label', `移除附件 ${a.fileName}`);
      remove.addEventListener('click', () => {
        pendingAttachments = pendingAttachments.filter((p) => p.id !== a.id);
        renderPendingAttachments();
      });
      chip.appendChild(name);
      chip.appendChild(remove);
      attachPreview.appendChild(chip);
    }
  }

  attachBtn.addEventListener('click', () => attachFileInput.click());
  attachFileInput.addEventListener('change', () => {
    const file = attachFileInput.files?.[0];
    attachFileInput.value = ''; // 允许连续选择同一个文件
    if (!file || !handlers.onUploadFile) return;
    attachBtn.disabled = true;
    handlers
      .onUploadFile(file)
      .then((a) => {
        pendingAttachments.push(a);
        renderPendingAttachments();
      })
      .catch((e) => {
        console.warn('[Qingyu Widget] 附件上传失败', e);
        showSessionState('附件上传失败，请重试');
      })
      .finally(() => {
        attachBtn.disabled = !attachEnabled;
      });
  });

  let firstOpen = true;
  bubble.addEventListener('click', () => {
    panel.classList.toggle('open');
    if (firstOpen && panel.classList.contains('open')) {
      firstOpen = false;
      if (brand.welcomeMessage) addBotMessage(brand.welcomeMessage);
    }
  });

  const doSend = () => {
    const text = textarea.value.trim();
    // 后端 message 字段 @NotBlank：附件必须搭配文字一起发（与管理端 ChatPage 同语义），
    // 纯图片消息不支持——即便挂了待发附件，没有文字也不允许发送。
    if (!text) return;
    textarea.value = '';
    const attachments = pendingAttachments;
    pendingAttachments = [];
    renderPendingAttachments();
    handlers.onSend(text, attachments.length ? attachments : undefined);
  };
  sendBtn.addEventListener('click', doSend);
  textarea.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      doSend();
    }
  });
  textarea.addEventListener('input', () => handlers.onTyping?.());
  handoverBtn.addEventListener('click', () => handlers.onHandover());

  function appendMsg(text: string, who: 'user' | 'bot'): HTMLDivElement {
    const row = document.createElement('div');
    row.className = `qy-msg ${who}`;
    const bubbleEl = document.createElement('div');
    bubbleEl.className = 'qy-text';
    bubbleEl.textContent = text;
    row.appendChild(bubbleEl);
    msgs.appendChild(row);
    msgs.scrollTop = msgs.scrollHeight;
    return row;
  }

  /** 消息行有附件时追加渲染（图片走鉴权 fetch 换 blob URL，见 attachments.ts）。 */
  function appendAttachmentsIfAny(row: HTMLDivElement, attachments?: WidgetAttachment[]) {
    const el = renderAttachments(attachments, handlers.resolveAttachmentUrl);
    if (el) {
      row.appendChild(el);
      msgs.scrollTop = msgs.scrollHeight;
    }
  }

  /** 按序渲染 uiParts（text 片段渲染为文本节点，ui 片段经 renderNode 渲染），供 addBotMessage 复用消息行/反馈逻辑。 */
  function appendUiMsg(uiParts: UiPart[]): HTMLDivElement {
    const row = document.createElement('div');
    row.className = 'qy-msg bot';
    const container = document.createElement('div');
    container.className = 'qy-text';
    const onAction = handlers.onAction ?? handlers.onSend;
    for (const part of uiParts) {
      if (part.kind === 'text') {
        const str = part.text ?? '';
        if (str) {
          const t = document.createElement('div');
          t.className = 'qy-node-text';
          t.textContent = str;
          container.appendChild(t);
        }
      } else if (part.kind === 'ui' && part.node) {
        const c = renderNode(part.node, onAction);
        if (c) container.appendChild(c);
      }
    }
    row.appendChild(container);
    msgs.appendChild(row);
    msgs.scrollTop = msgs.scrollHeight;
    return row;
  }

  /** 给消息行挂上赞/踩按钮（addBotMessage 与流式 finish 共用）。 */
  function attachFeedback(row: HTMLDivElement, feedback: BotFeedback) {
    // 竖排：气泡在上、反馈按钮在下方左对齐；不影响无反馈消息的横排布局
    row.classList.add('has-feedback');
    const bar = document.createElement('div');
    bar.className = 'qy-feedback';
    const up = document.createElement('button');
    up.type = 'button';
    up.textContent = '👍';
    up.setAttribute('aria-label', '有帮助');
    const down = document.createElement('button');
    down.type = 'button';
    down.textContent = '👎';
    down.setAttribute('aria-label', '没帮助');
    const pick = (value: 1 | -1, chosen: HTMLButtonElement) => {
      feedback.onFeedback(value);
      up.disabled = true;
      down.disabled = true;
      chosen.classList.add('active');
    };
    up.addEventListener('click', () => pick(1, up));
    down.addEventListener('click', () => pick(-1, down));
    bar.appendChild(up);
    bar.appendChild(down);
    row.appendChild(bar);
    msgs.scrollTop = msgs.scrollHeight;
  }

  /**
   * 开始一条流式 AI 消息，返回增量渲染句柄。
   * 文本全程 textContent（绝不 innerHTML）；卡片复用 renderNode（内含 safeUrl 白名单与结构守卫）。
   */
  function beginBotMessage(): StreamingBotMessage {
    const row = document.createElement('div');
    row.className = 'qy-msg bot';
    const container = document.createElement('div');
    container.className = 'qy-text';
    row.appendChild(container);
    msgs.appendChild(row);
    msgs.scrollTop = msgs.scrollHeight;

    // 当前文本块；插入卡片后置空，使卡片之后的文本另起一块（与后端 uiParts 的分块语义一致）
    let textEl: HTMLDivElement | null = null;
    const onAction = handlers.onAction ?? handlers.onSend;

    return {
      appendText(text: string) {
        if (!text) return;
        if (!textEl) {
          textEl = document.createElement('div');
          textEl.className = 'qy-node-text';
          container.appendChild(textEl);
        }
        textEl.textContent = (textEl.textContent ?? '') + text;
        msgs.scrollTop = msgs.scrollHeight;
      },
      appendCard(node: UiNode) {
        const el = renderNode(node, onAction);
        if (el) container.appendChild(el);
        textEl = null;
        msgs.scrollTop = msgs.scrollHeight;
      },
      finish(feedback?: BotFeedback) {
        if (feedback) attachFeedback(row, feedback);
      },
    };
  }

  function addUserMessage(text: string, attachments?: WidgetAttachment[]) {
    const row = appendMsg(text, 'user');
    appendAttachmentsIfAny(row, attachments);
  }
  function addBotMessage(text: string, feedback?: BotFeedback, uiParts?: UiPart[], attachments?: WidgetAttachment[]) {
    const hasUi = Array.isArray(uiParts) && uiParts.some((p) => p.kind === 'ui' && p.node);
    const row = hasUi ? appendUiMsg(uiParts!) : appendMsg(text, 'bot');
    appendAttachmentsIfAny(row, attachments);
    if (feedback) attachFeedback(row, feedback);
  }

  function addAgentMessage(text: string, attachments?: WidgetAttachment[]) {
    const row = appendMsg(text, 'bot');
    appendAttachmentsIfAny(row, attachments);
    const label = document.createElement('div');
    label.textContent = '客服';
    label.style.cssText = 'font-size:11px;color:#999;margin-bottom:2px;';
    row.insertBefore(label, row.firstChild);
  }

  function showSessionState(text: string) {
    const row = document.createElement('div');
    row.style.cssText = 'text-align:center;color:#999;font-size:12px;margin:8px 0;';
    row.textContent = text;
    msgs.appendChild(row);
    msgs.scrollTop = msgs.scrollHeight;
  }

  let typingRow: HTMLDivElement | null = null;
  function setTyping(on: boolean) {
    if (on && !typingRow) {
      typingRow = appendMsg('输入中…', 'bot');
    } else if (!on && typingRow) {
      typingRow.remove();
      typingRow = null;
    }
  }

  function setInputEnabled(on: boolean) {
    textarea.disabled = !on;
    sendBtn.disabled = !on;
  }

  function setAttachEnabled(on: boolean) {
    attachEnabled = on;
    attachBtn.disabled = !on;
  }

  function markOpened() {
    firstOpen = false;
  }

  return {
    addUserMessage,
    addBotMessage,
    addAgentMessage,
    showSessionState,
    setTyping,
    setInputEnabled,
    setAttachEnabled,
    markOpened,
    beginBotMessage,
  };
}

function escapeHtml(s: string): string {
  const d = document.createElement('div');
  d.textContent = s;
  return d.innerHTML;
}
