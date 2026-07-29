import { buildStyles } from './styles';
import { renderNode } from './render';
import type { WidgetBrand, UiPart } from './api';

export interface UiHandlers {
  onSend: (message: string) => void;
  onHandover: () => void;
  /** 用户在输入框中输入（未节流，节流由调用方控制）；可选 */
  onTyping?: () => void;
  /** 卡片按钮点击回填；未提供时复用 onSend */
  onAction?: (value: string) => void;
}

export interface BotFeedback {
  onFeedback: (value: 1 | -1) => void;
}

export interface WidgetUi {
  addUserMessage: (text: string) => void;
  addBotMessage: (text: string, feedback?: BotFeedback, uiParts?: UiPart[]) => void;
  /** 渲染坐席（真人）消息，带「客服」标记以区分 AI 回复 */
  addAgentMessage: (text: string) => void;
  /** 消息区插入一条居中系统状态提示（如「客服已接入」） */
  showSessionState: (text: string) => void;
  setTyping: (on: boolean) => void;
  setInputEnabled: (on: boolean) => void;
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
  bubble.textContent = '💬';
  bubble.setAttribute('aria-label', '打开客服');

  const panel = document.createElement('div');
  panel.className = 'qy-panel';
  panel.innerHTML = `
    <div class="qy-header">
      <span>${escapeHtml(brand.appName || '在线客服')}</span>
      <button class="qy-handover" type="button">转人工</button>
    </div>
    <div class="qy-msgs"></div>
    <div class="qy-input">
      <textarea placeholder="输入消息…"></textarea>
      <button type="button">发送</button>
    </div>
  `;

  root.appendChild(bubble);
  root.appendChild(panel);

  const msgs = panel.querySelector<HTMLDivElement>('.qy-msgs')!;
  const textarea = panel.querySelector<HTMLTextAreaElement>('textarea')!;
  const sendBtn = panel.querySelector<HTMLButtonElement>('.qy-input button')!;
  const handoverBtn = panel.querySelector<HTMLButtonElement>('.qy-handover')!;

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
    if (!text) return;
    textarea.value = '';
    handlers.onSend(text);
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

  function addUserMessage(text: string) {
    appendMsg(text, 'user');
  }
  function addBotMessage(text: string, feedback?: BotFeedback, uiParts?: UiPart[]) {
    const hasUi = Array.isArray(uiParts) && uiParts.some((p) => p.kind === 'ui' && p.node);
    const row = hasUi ? appendUiMsg(uiParts!) : appendMsg(text, 'bot');
    if (feedback) {
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
  }

  function addAgentMessage(text: string) {
    const row = appendMsg(text, 'bot');
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

  return { addUserMessage, addBotMessage, addAgentMessage, showSessionState, setTyping, setInputEnabled };
}

function escapeHtml(s: string): string {
  const d = document.createElement('div');
  d.textContent = s;
  return d.innerHTML;
}
