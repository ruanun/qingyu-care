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
 * ⚠️ 下面的路径数据是 `brand/logo-mark.svg` 的内联副本，全仓共五处同源副本，
 * 由 `frontend/scripts/brandAssetSync.test.ts` 守护——改一处漏其它即红。
 * 别在这里单独调形状，改动步骤见 `brand/README.md`（本文件改完还要重新 build widget）。
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
