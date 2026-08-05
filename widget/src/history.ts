/**
 * @author ruan
 * 会话历史恢复：把 getSession 拉回的历史消息按角色回放到 UI。
 * 独立成模块（而非留在 main.ts 入口）是为了让入口保持「无导出的纯副作用 IIFE」，
 * 避免 vite lib 构建把 export 提升为全局变量，污染宿主页面（widget.js 不应暴露任何全局符号）。
 */
import type { OpenSessionResponse } from './api';
import type { WidgetUi } from './ui';

/**
 * 恢复历史消息到 UI（各角色分派；ASSISTANT 带历史卡片 uiParts，USER/AGENT 带历史附件）。
 * 历史消息不带反馈（避免重复计量/messageId 缺失）。
 */
export function restoreHistory(ui: WidgetUi, messages: OpenSessionResponse['messages']): void {
  for (const m of messages) {
    switch (m.role) {
      case 'USER':
        ui.addUserMessage(m.content, m.attachments);
        break;
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
