/**
 * @author ruan
 * 会话历史恢复：把 getSession 拉回的历史消息按角色回放到 UI。
 * 独立成模块（而非留在 main.ts 入口）是为了让入口保持「无导出的纯副作用 IIFE」，
 * 避免 vite lib 构建把 export 提升为全局变量，污染宿主页面（widget.js 不应暴露任何全局符号）。
 */
import type { OpenSessionResponse, UiPart } from './api';
import type { WidgetAttachment } from './attachments';

/** 供事后回填服务端生成的真实 messageId（镜像 widget/src/ui.ts 的 UserMessageHandle，形状相同）。 */
export interface HistoryMessageHandle {
  setMessageId: (id: number) => void;
}

/** 消息反馈回调（镜像 widget/src/ui.ts 的 BotFeedback，仅供下方签名匹配，restoreHistory 本身不传它）。 */
export interface HistoryFeedback {
  onFeedback: (value: 1 | -1) => void;
}

/**
 * restoreHistory 所需的最小 UI 能力集合。⚠️ core 不得依赖 widget/src/ui.ts——那里有大量
 * DOM API（document/HTMLElement 等），依赖它会把整个 widget UI 层拖进 CLI 的编译图（CLI
 * 只需要协议层，不需要也不该编译渲染代码）。widget 的 WidgetUi 结构上是这个接口的超集，
 * 直接把 mountUi() 的返回值传进来即可，不需要显式 implements。
 */
export interface HistoryUi {
  addUserMessage: (text: string, attachments?: WidgetAttachment[]) => HistoryMessageHandle;
  addBotMessage: (text: string, feedback?: HistoryFeedback, uiParts?: UiPart[], attachments?: WidgetAttachment[]) => void;
  /** 渲染坐席（真人）消息，带「客服」标记以区分 AI 回复 */
  addAgentMessage: (text: string, attachments?: WidgetAttachment[]) => void;
  /** 消息区插入一条居中系统状态提示（如「客服已接入」） */
  showSessionState: (text: string) => void;
}

/**
 * 恢复历史消息到 UI（各角色分派；ASSISTANT 带历史卡片 uiParts，USER/AGENT 带历史附件）。
 * 历史消息不带反馈（避免重复计量/messageId 缺失）。
 */
export function restoreHistory(ui: HistoryUi, messages: OpenSessionResponse['messages']): void {
  for (const m of messages) {
    switch (m.role) {
      case 'USER': {
        // 必须回填 id：已读标记（markReadUpTo）按 data-message-id 定位气泡，不回填则刷新
        // 页面后历史访客消息永远无处可标——「刷新后已读状态保持」正是这个特性最该稳的东西。
        const handle = ui.addUserMessage(m.content, m.attachments);
        if (typeof m.id === 'number') handle.setMessageId(m.id);
        break;
      }
      case 'ASSISTANT':
        ui.addBotMessage(m.content, undefined, m.uiParts, m.attachments);
        break;
      case 'AGENT':
        ui.addAgentMessage(m.content, m.attachments);
        break;
      case 'SYSTEM':
        ui.showSessionState(m.content);
        break;
      default:
        break; // 未知角色忽略
    }
  }
}
