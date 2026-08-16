/**
 * 轻语品牌图标（悬浮球用）
 * 对话气泡（尖角左下）+「轻」字骨架（车字旁两笔 + 右侧两道横）。
 *
 * 单色版：`fill="currentColor"` 由 `.qy-bubble` 的 `color:#fff` 驱动，
 * 挖空的笔画处透出的是球体底色，即租户自配的 themeColor——因此这里不能写死颜色。
 *
 * 常量字符串、无任何用户输入，故 `innerHTML` 注入是安全的（同 ui.ts 里 panel 的用法）。
 * 图标位于 Shadow DOM 内，mask 的 id 不会与宿主页面冲突。
 *
 * ⚠️ 同一份路径数据另有三处副本，改形状必须四处同步：
 *    frontend/public/logo.svg（favicon，**基准**）
 *    frontend/src/components/Logo.tsx（管理端）
 *    docs/images/logo.svg（README 的字标锁定形式）
 * 三个独立构建目标 + 一份文档资产，无法共享。刻意不加防漂移哨兵——logo 走形是肉眼
 * 可见的外观问题。
 */
export const BUBBLE_LOGO_SVG = `<svg viewBox="0 0 24 24" width="28" height="28" aria-hidden="true" focusable="false">
  <defs>
    <mask id="qy-bubble-logo-mask">
      <path d="M5 3h14a3 3 0 0 1 3 3v8a3 3 0 0 1-3 3H9.5L5 20.8V17a3 3 0 0 1-3-3V6a3 3 0 0 1 3-3z" fill="#fff"/>
      <g stroke="#000" stroke-width="1.4" stroke-linecap="round">
        <path d="M8 7.4V13.4"/>
        <path d="M6.3 9.4H9.7"/>
        <path d="M6.3 11.9H9.7"/>
        <path d="M12.4 8.6H17.6"/>
        <path d="M15 8.6V12.6"/>
        <path d="M12.4 12.6H17.6"/>
      </g>
    </mask>
  </defs>
  <rect width="24" height="24" fill="currentColor" mask="url(#qy-bubble-logo-mask)"/>
</svg>`;
