export function buildStyles(themeColor: string): string {
  return `
    :host { all: initial; }
    * { box-sizing: border-box; font-family: -apple-system, "Segoe UI", "Microsoft YaHei", sans-serif; }
    .qy-bubble {
      position: fixed; right: 20px; bottom: 20px; width: 56px; height: 56px;
      border-radius: 50%; background: ${themeColor}; color: #fff; border: none;
      cursor: pointer; box-shadow: 0 4px 12px rgba(0,0,0,.2); font-size: 24px; z-index: 2147483000;
    }
    .qy-panel {
      position: fixed; right: 20px; bottom: 88px; width: 360px; height: 520px;
      background: #fff; border-radius: 12px; box-shadow: 0 8px 30px rgba(0,0,0,.18);
      display: none; flex-direction: column; overflow: hidden; z-index: 2147483000;
    }
    .qy-panel.open { display: flex; }
    .qy-header {
      background: ${themeColor}; color: #fff; padding: 12px 16px;
      display: flex; align-items: center; justify-content: space-between; font-weight: 600;
    }
    .qy-header .qy-handover {
      background: rgba(255,255,255,.2); color: #fff; border: none; border-radius: 6px;
      padding: 4px 10px; cursor: pointer; font-size: 12px;
    }
    .qy-msgs { flex: 1; overflow-y: auto; padding: 12px; background: #f7f7f8; }
    .qy-msg { margin-bottom: 10px; display: flex; }
    .qy-msg.user { justify-content: flex-end; }
    .qy-msg .qy-text {
      max-width: 78%; padding: 8px 12px; border-radius: 10px; font-size: 14px; line-height: 1.5;
      white-space: pre-wrap; word-break: break-word;
    }
    .qy-msg.bot .qy-text { background: #fff; border: 1px solid #eee; }
    .qy-msg.user .qy-text { background: ${themeColor}; color: #fff; }
    .qy-input { display: flex; border-top: 1px solid #eee; }
    .qy-input textarea {
      flex: 1; border: none; resize: none; padding: 12px; font-size: 14px; outline: none; height: 48px;
    }
    .qy-input button {
      border: none; background: ${themeColor}; color: #fff; padding: 0 16px; cursor: pointer;
    }
    .qy-input button:disabled { opacity: .5; cursor: not-allowed; }
    .qy-msg .qy-feedback { display: flex; gap: 4px; margin-top: 4px; }
    .qy-msg.bot .qy-feedback { justify-content: flex-start; }
    .qy-feedback button { background: none; border: 1px solid #e5e7eb; border-radius: 10px;
      cursor: pointer; font-size: 12px; line-height: 1; padding: 2px 8px; opacity: .7; }
    .qy-feedback button:hover { opacity: 1; }
    .qy-feedback button:disabled { cursor: default; opacity: .4; }
    .qy-feedback button.active { opacity: 1; border-color: currentColor; }
    @media (max-width: 480px) {
      .qy-panel { right: 0; bottom: 0; width: 100vw; height: 100vh; border-radius: 0; }
    }
  `;
}
