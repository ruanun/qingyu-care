/**
 * @author ruan
 */
import { describe, it, expect, vi, afterEach } from 'vitest';
import { mountUi } from './ui';
import type { WidgetBrand } from './api';

const brand: WidgetBrand = { appName: '客服', welcomeMessage: '', avatar: '', themeColor: '#6366f1' };

afterEach(() => {
  document.body.innerHTML = '';
});

/** mountUi 把 host 挂到 document.body；jsdom 下同一用例只 mount 一次，取最后一个 host 即可。 */
function lastHost(): HTMLDivElement | null {
  const hosts = document.body.querySelectorAll('div');
  return hosts.length ? (hosts[hosts.length - 1] as HTMLDivElement) : null;
}

function shadowText(): string {
  const host = lastHost();
  return host?.shadowRoot?.querySelector('.qy-msgs')?.textContent ?? '';
}

describe('addBotMessage uiParts 渲染', () => {
  it('有 ui 片段按序渲染卡片，text 片段渲染文本', () => {
    const ui = mountUi(brand, { onSend: () => {}, onHandover: () => {} });
    ui.addBotMessage('原始纯文本', undefined, [
      { kind: 'text', text: '前言' },
      {
        kind: 'ui',
        node: {
          type: 'card',
          props: { title: '订单#1' },
          children: [{ type: 'kv', props: { label: '状态', value: '已发货' } }],
        },
      },
    ]);
    const txt = shadowText();
    expect(txt).toContain('前言');
    expect(txt).toContain('订单#1');
    expect(txt).toContain('已发货');
    // 有 ui 片段时不走纯文本 appendMsg 路径，原始 text 参数不应出现
    expect(txt).not.toContain('原始纯文本');
  });

  it('无 uiParts 回退纯文本 reply（零回归）', () => {
    const ui = mountUi(brand, { onSend: () => {}, onHandover: () => {} });
    ui.addBotMessage('纯文本回复');
    expect(shadowText()).toContain('纯文本回复');
  });

  it('卡片按钮点击触发 onSend(value) 回填', () => {
    const onSend = vi.fn();
    const ui = mountUi(brand, { onSend, onHandover: () => {} });
    ui.addBotMessage('', undefined, [
      { kind: 'ui', node: { type: 'button', props: { label: '查看物流', value: '查询物流' } } },
    ]);
    const host = lastHost();
    const btn = host?.shadowRoot?.querySelector('.qy-card-btn') as HTMLButtonElement;
    btn.dispatchEvent(new Event('click'));
    expect(onSend).toHaveBeenCalledWith('查询物流');
  });

  it('提供 onAction 时卡片按钮走 onAction 而非 onSend', () => {
    const onSend = vi.fn();
    const onAction = vi.fn();
    const ui = mountUi(brand, { onSend, onHandover: () => {}, onAction });
    ui.addBotMessage('', undefined, [
      { kind: 'ui', node: { type: 'button', props: { label: '查看物流', value: '查询物流' } } },
    ]);
    const host = lastHost();
    const btn = host?.shadowRoot?.querySelector('.qy-card-btn') as HTMLButtonElement;
    btn.dispatchEvent(new Event('click'));
    expect(onAction).toHaveBeenCalledWith('查询物流');
    expect(onSend).not.toHaveBeenCalled();
  });

  it('空 uiParts 数组回退纯文本路径（零回归）', () => {
    const ui = mountUi(brand, { onSend: () => {}, onHandover: () => {} });
    ui.addBotMessage('纯文本回复', undefined, []);
    expect(shadowText()).toContain('纯文本回复');
  });
});

describe('markOpened 抑制首开欢迎语', () => {
  const brandWithWelcome: WidgetBrand = {
    appName: '客服',
    welcomeMessage: '你好，欢迎咨询',
    avatar: '',
    themeColor: '#6366f1',
  };

  it('未调用 markOpened 时，首次打开面板照常插入欢迎语（零回归）', () => {
    mountUi(brandWithWelcome, { onSend: () => {}, onHandover: () => {} });
    const host = lastHost();
    const bubble = host?.shadowRoot?.querySelector('.qy-bubble') as HTMLButtonElement;
    bubble.dispatchEvent(new Event('click'));
    expect(shadowText()).toContain('你好，欢迎咨询');
  });

  it('已调用 markOpened（会话恢复出历史）后，首次打开面板不再插入欢迎语', () => {
    const ui = mountUi(brandWithWelcome, { onSend: () => {}, onHandover: () => {} });
    ui.markOpened();
    const host = lastHost();
    const bubble = host?.shadowRoot?.querySelector('.qy-bubble') as HTMLButtonElement;
    bubble.dispatchEvent(new Event('click'));
    expect(shadowText()).not.toContain('你好，欢迎咨询');
  });
});

describe('beginBotMessage 流式渲染', () => {
  it('流式消息按序渲染文本与卡片，卡片后另起文本块', () => {
    const ui = mountUi(brand, { onSend: () => {}, onHandover: () => {} });
    const bot = ui.beginBotMessage();

    bot.appendText('查到订单：');
    bot.appendCard({ type: 'text', props: { value: '订单号 A1' } });
    bot.appendText('还需要什么？');
    bot.finish();

    const row = document.querySelector('div')!.shadowRoot!.querySelector('.qy-msg.bot .qy-text')!;
    expect(row.children.length).toBe(3);          // 文本块 + 卡片 + 新文本块
    expect(row.textContent).toContain('查到订单：');
    expect(row.textContent).toContain('订单号 A1');
    expect(row.textContent).toContain('还需要什么？');
  });

  it('流式文本全程 textContent，HTML 不被解析（XSS）', () => {
    const ui = mountUi(brand, { onSend: () => {}, onHandover: () => {} });
    const bot = ui.beginBotMessage();

    bot.appendText('<img src=x onerror=alert(1)>');
    bot.finish();

    const root = document.querySelector('div')!.shadowRoot!;
    expect(root.querySelector('img')).toBeNull();
    expect(root.querySelector('.qy-msg.bot .qy-text')!.textContent)
      .toContain('<img src=x onerror=alert(1)>');
  });

  it('多次 appendText 累加进同一个文本块', () => {
    const ui = mountUi(brand, { onSend: () => {}, onHandover: () => {} });
    const bot = ui.beginBotMessage();

    bot.appendText('你');
    bot.appendText('好');
    bot.finish();

    const container = document.querySelector('div')!.shadowRoot!
      .querySelector('.qy-msg.bot .qy-text')!;
    expect(container.children.length).toBe(1);
    expect(container.textContent).toBe('你好');
  });
});
