/**
 * 轻语品牌图标（悬浮球用）
 * 两个对话气泡，尾巴方向相反——大泡尾朝左下（用户提问）、小泡尾朝右上（客服回应）。
 *
 * 单色版：`fill="currentColor"` 由 `.qy-bubble` 的 `color:#fff` 驱动，
 * 两泡之间那道切缝透出的是球体底色，即租户自配的 themeColor——因此这里不能写死颜色。
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
      <path d="M4.5 7h8a3 3 0 0 1 3 3v4a3 3 0 0 1-3 3H7.5L3.5 20.8V17a3 3 0 0 1-3-3v-4a3 3 0 0 1 3-3z" fill="#fff"/>
      <path d="M12.5 3h4.5L20.5 0.6L19.5 3a2.5 2.5 0 0 1 2.5 2.5v3.5a2.5 2.5 0 0 1-2.5 2.5H12.5a2.5 2.5 0 0 1-2.5-2.5V5.5a2.5 2.5 0 0 1 2.5-2.5z" fill="#fff"/>
      <path d="M12.5 3h4.5L20.5 0.6L19.5 3a2.5 2.5 0 0 1 2.5 2.5v3.5a2.5 2.5 0 0 1-2.5 2.5H12.5a2.5 2.5 0 0 1-2.5-2.5V5.5a2.5 2.5 0 0 1 2.5-2.5z" fill="none" stroke="#000" stroke-width="1.6"/>
    </mask>
  </defs>
  <rect width="24" height="24" fill="currentColor" mask="url(#qy-bubble-logo-mask)"/>
</svg>`;
