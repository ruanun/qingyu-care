/**
 * @author ruan
 */
import { describe, it, expect, vi } from 'vitest';
import { restoreHistory } from './history';
import type { WidgetUi } from './ui';

function fakeUi() {
  return {
    addUserMessage: vi.fn(),
    addBotMessage: vi.fn(),
    addAgentMessage: vi.fn(),
    showSessionState: vi.fn(),
    setTyping: vi.fn(),
    setInputEnabled: vi.fn(),
    markOpened: vi.fn(),
  } as unknown as WidgetUi & Record<string, ReturnType<typeof vi.fn>>;
}

describe('restoreHistory 会话恢复', () => {
  it('按角色渲染历史，ASSISTANT 带 uiParts', () => {
    const ui = fakeUi();
    restoreHistory(ui as unknown as WidgetUi, [
      { role: 'USER', content: '你好', createdAt: '' },
      { role: 'ASSISTANT', content: '卡片回复', createdAt: '', uiParts: [{ kind: 'ui', node: { type: 'card' } }] },
      { role: 'AGENT', content: '客服接入', createdAt: '' },
      { role: 'SYSTEM', content: '会话结束', createdAt: '' },
    ]);
    expect(ui.addUserMessage).toHaveBeenCalledWith('你好', undefined);
    expect(ui.addBotMessage).toHaveBeenCalledWith(
      '卡片回复', undefined, [{ kind: 'ui', node: { type: 'card' } }], undefined,
    );
    expect(ui.addAgentMessage).toHaveBeenCalledWith('客服接入', undefined);
    expect(ui.showSessionState).toHaveBeenCalledWith('会话结束');
  });

  it('USER/AGENT 历史消息带 attachments 时原样透传给 ui', () => {
    const ui = fakeUi();
    const atts = [{ id: 1, fileName: 'a.png', contentType: 'image/png', fileSize: 100 }];
    restoreHistory(ui as unknown as WidgetUi, [
      { role: 'USER', content: '看图', createdAt: '', attachments: atts },
      { role: 'AGENT', content: '收到', createdAt: '', attachments: atts },
    ]);
    expect(ui.addUserMessage).toHaveBeenCalledWith('看图', atts);
    expect(ui.addAgentMessage).toHaveBeenCalledWith('收到', atts);
  });

  it('空/未知角色不崩', () => {
    const ui = fakeUi();
    expect(() => restoreHistory(ui as unknown as WidgetUi, [
      { role: 'WEIRD', content: 'x', createdAt: '' },
    ])).not.toThrow();
  });
});
