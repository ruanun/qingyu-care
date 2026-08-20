/**
 * @author ruan
 */
import { describe, it, expect, vi, afterEach } from 'vitest';
import { mountUi } from './ui';
import { restoreHistory } from './history';
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

/** jsdom 下 input.files 是只读属性，靠 defineProperty 伪造选中的文件，再手动派发 change 事件。 */
function selectFile(input: HTMLInputElement, file: File) {
  Object.defineProperty(input, 'files', { value: [file], configurable: true });
  input.dispatchEvent(new Event('change'));
}

describe('附件：选中即上传 + 待发送预览', () => {
  it('会话建立前附件按钮禁用；setAttachEnabled(true) 后可用', () => {
    const ui = mountUi(brand, { onSend: () => {}, onHandover: () => {} });
    const host = lastHost();
    const attachBtn = host?.shadowRoot?.querySelector('.qy-attach-btn') as HTMLButtonElement;
    expect(attachBtn.disabled).toBe(true);
    ui.setAttachEnabled(true);
    expect(attachBtn.disabled).toBe(false);
    ui.setAttachEnabled(false);
    expect(attachBtn.disabled).toBe(true);
  });

  it('选中文件触发 onUploadFile，成功后预览区出现文件名（textContent，防 XSS）', async () => {
    const onUploadFile = vi.fn().mockResolvedValue({
      id: 1, fileName: '<img onerror=alert(1)>.png', contentType: 'image/png', fileSize: 100,
    });
    const ui = mountUi(brand, { onSend: () => {}, onHandover: () => {}, onUploadFile });
    ui.setAttachEnabled(true);
    const host = lastHost();
    const input = host?.shadowRoot?.querySelector('.qy-attach-file-input') as HTMLInputElement;
    selectFile(input, new File(['x'], 'a.png', { type: 'image/png' }));

    expect(onUploadFile).toHaveBeenCalledTimes(1);
    await Promise.resolve();
    await Promise.resolve();

    const preview = host?.shadowRoot?.querySelector('.qy-attach-preview')!;
    expect(preview.textContent).toContain('<img onerror=alert(1)>.png');
    expect(preview.querySelectorAll('img').length).toBe(0);
  });

  it('点击移除按钮从预览区去掉该附件', async () => {
    const onUploadFile = vi.fn().mockResolvedValue({ id: 1, fileName: 'a.png', contentType: 'image/png', fileSize: 100 });
    const ui = mountUi(brand, { onSend: () => {}, onHandover: () => {}, onUploadFile });
    ui.setAttachEnabled(true);
    const host = lastHost();
    const input = host?.shadowRoot?.querySelector('.qy-attach-file-input') as HTMLInputElement;
    selectFile(input, new File(['x'], 'a.png'));
    await Promise.resolve();
    await Promise.resolve();

    const preview = host?.shadowRoot?.querySelector('.qy-attach-preview')!;
    expect(preview.children.length).toBe(1);
    (preview.querySelector('.qy-attach-chip-remove') as HTMLButtonElement).click();
    expect(preview.children.length).toBe(0);
  });

  it('上传失败不崩，附件按钮恢复此前的启用状态', async () => {
    const onUploadFile = vi.fn().mockRejectedValue(new Error('403'));
    const ui = mountUi(brand, { onSend: () => {}, onHandover: () => {}, onUploadFile });
    ui.setAttachEnabled(true);
    const host = lastHost();
    const input = host?.shadowRoot?.querySelector('.qy-attach-file-input') as HTMLInputElement;
    const attachBtn = host?.shadowRoot?.querySelector('.qy-attach-btn') as HTMLButtonElement;

    expect(() => selectFile(input, new File(['x'], 'a.png'))).not.toThrow();
    // .catch() 与 .finally() 各自额外占一个微任务 tick，比 .then() 链路更深，
    // 用 setTimeout(0) 冲掉整条微任务队列，不去猜确切跳数
    await new Promise((r) => setTimeout(r, 0));

    expect(attachBtn.disabled).toBe(false); // 恢复为 setAttachEnabled(true) 设定的可用态
  });

  it('发送消息时把上传成功的附件一并带上，并清空预览区', async () => {
    const onUploadFile = vi.fn().mockResolvedValue({ id: 9, fileName: 'a.png', contentType: 'image/png', fileSize: 100 });
    const onSend = vi.fn();
    const ui = mountUi(brand, { onSend, onHandover: () => {}, onUploadFile });
    ui.setAttachEnabled(true);
    const host = lastHost();
    const input = host?.shadowRoot?.querySelector('.qy-attach-file-input') as HTMLInputElement;
    selectFile(input, new File(['x'], 'a.png'));
    await Promise.resolve();
    await Promise.resolve();

    const textarea = host?.shadowRoot?.querySelector('textarea') as HTMLTextAreaElement;
    textarea.value = '你看这个';
    const sendBtn = host?.shadowRoot?.querySelector('.qy-input button:not(.qy-attach-btn)') as HTMLButtonElement;
    sendBtn.click();

    expect(onSend).toHaveBeenCalledWith('你看这个', [{ id: 9, fileName: 'a.png', contentType: 'image/png', fileSize: 100 }]);
    const preview = host?.shadowRoot?.querySelector('.qy-attach-preview')!;
    expect(preview.children.length).toBe(0);
  });

  it('没有待发送附件时 onSend 第二个参数为 undefined（零回归）', () => {
    const onSend = vi.fn();
    mountUi(brand, { onSend, onHandover: () => {} });
    const host = lastHost();
    const textarea = host?.shadowRoot?.querySelector('textarea') as HTMLTextAreaElement;
    textarea.value = '普通消息';
    const sendBtn = host?.shadowRoot?.querySelector('.qy-input button:not(.qy-attach-btn)') as HTMLButtonElement;
    sendBtn.click();
    expect(onSend).toHaveBeenCalledWith('普通消息', undefined);
  });
});

describe('消息行渲染附件', () => {
  it('addUserMessage 带 attachments 时消息行内出现附件容器', () => {
    const ui = mountUi(brand, { onSend: () => {}, onHandover: () => {} });
    ui.addUserMessage('你看', [{ id: 1, fileName: 'a.png', contentType: 'image/png', fileSize: 100 }]);
    const host = lastHost();
    const row = host?.shadowRoot?.querySelector('.qy-msg.user')!;
    expect(row.querySelector('.qy-attachments')).not.toBeNull();
  });

  it('无 attachments 时不产生附件容器（零回归）', () => {
    const ui = mountUi(brand, { onSend: () => {}, onHandover: () => {} });
    ui.addUserMessage('普通消息');
    const host = lastHost();
    const row = host?.shadowRoot?.querySelector('.qy-msg.user')!;
    expect(row.querySelector('.qy-attachments')).toBeNull();
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

describe('markReadUpTo 已读标记', () => {
  it('按 upToMessageId 只标最后一条已读的访客消息', () => {
    const ui = mountUi(brand, { onSend: () => {}, onHandover: () => {} });
    ui.addUserMessage('第一条').setMessageId(1);
    ui.addUserMessage('第二条').setMessageId(2);
    ui.addUserMessage('第三条').setMessageId(3);

    ui.markReadUpTo(2);

    const root = lastHost()!.shadowRoot!;
    // 只标最后一条已读的：id=1 虽然也已读，但水位线语义下它被 id=2 蕴含
    expect(root.querySelectorAll('[data-read-mark]')).toHaveLength(1);
    expect(root.querySelector('[data-message-id="2"] [data-read-mark]')).not.toBeNull();
    expect(root.querySelector('[data-message-id="3"] [data-read-mark]')).toBeNull();
  });

  it('水位线前移后旧标记被清除，不会同时出现两个已读', () => {
    const ui = mountUi(brand, { onSend: () => {}, onHandover: () => {} });
    ui.addUserMessage('第一条').setMessageId(1);
    ui.addUserMessage('第二条').setMessageId(2);

    ui.markReadUpTo(1);
    ui.markReadUpTo(2);

    const root = lastHost()!.shadowRoot!;
    expect(root.querySelectorAll('[data-read-mark]')).toHaveLength(1);
    expect(root.querySelector('[data-message-id="2"] [data-read-mark]')).not.toBeNull();
  });

  it('upToMessageId 小于所有已知 id 时不标记任何消息（零回归）', () => {
    const ui = mountUi(brand, { onSend: () => {}, onHandover: () => {} });
    ui.addUserMessage('第一条').setMessageId(5);

    ui.markReadUpTo(1);

    const root = lastHost()!.shadowRoot!;
    expect(root.querySelectorAll('[data-read-mark]')).toHaveLength(0);
  });

  it('未 setMessageId 的乐观渲染气泡不参与已读判定，不崩', () => {
    const ui = mountUi(brand, { onSend: () => {}, onHandover: () => {} });
    ui.addUserMessage('还没拿到 id'); // 未调用 setMessageId：id 尚未从服务端响应回填

    expect(() => ui.markReadUpTo(999)).not.toThrow();
    const root = lastHost()!.shadowRoot!;
    expect(root.querySelectorAll('[data-read-mark]')).toHaveLength(0);
  });

  /**
   * 裁定二：会话恢复出的历史访客消息也必须能被标记已读——history.ts 的 restoreHistory
   * 必须把消息 id 回填进 addUserMessage 返回的句柄，否则刷新页面后已读标记无处可标，
   * 而「刷新后已读状态保持」正是这个特性最该稳定的东西。
   */
  it('会话恢复后的历史访客消息能被 markReadUpTo 标记', () => {
    const ui = mountUi(brand, { onSend: () => {}, onHandover: () => {} });
    restoreHistory(ui, [
      { id: 101, role: 'USER', content: '第一条历史消息', createdAt: '' },
      { id: 102, role: 'USER', content: '第二条历史消息', createdAt: '' },
    ]);

    ui.markReadUpTo(101);

    const root = lastHost()!.shadowRoot!;
    expect(root.querySelector('[data-message-id="101"] [data-read-mark]')).not.toBeNull();
    expect(root.querySelector('[data-message-id="102"] [data-read-mark]')).toBeNull();
  });
});

describe('onToggle：面板展开/收起回调（main.ts 已读上报的真实触发入口）', () => {
  it('点击悬浮气泡展开时 onToggle(true)，再次点击收起时 onToggle(false)', () => {
    const onToggle = vi.fn();
    mountUi(brand, { onSend: () => {}, onHandover: () => {}, onToggle });
    const host = lastHost();
    const bubble = host?.shadowRoot?.querySelector('.qy-bubble') as HTMLButtonElement;

    bubble.dispatchEvent(new Event('click'));
    expect(onToggle).toHaveBeenLastCalledWith(true);

    bubble.dispatchEvent(new Event('click'));
    expect(onToggle).toHaveBeenLastCalledWith(false);
  });
});
