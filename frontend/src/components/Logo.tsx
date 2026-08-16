import React, { useId } from 'react';

/**
 * 轻语品牌图标（管理端）
 * 两个对话气泡，尾巴方向相反——大泡尾朝左下（用户提问）、小泡尾朝右上（客服回应）。
 *
 * 实心气泡 + mask：两泡先并成一个白色区域，再用小泡轮廓的黑色描边把它们切开。
 * 切缝处透出的是背景本身，因此同一份图形深浅背景通吃，换 fill 即可——`gradient`
 * 用于浅色背景（侧边栏），`mono` 用于深色背景（登录页），后者取 currentColor，
 * 由父元素的文字色驱动。
 *
 * ⚠️ 下面的路径数据是 `brand/logo-mark.svg` 的内联副本，全仓共五处同源副本，
 * 由 `scripts/brandAssetSync.test.ts` 守护——改一处漏其它即红。
 * 别在这里单独调形状，改动步骤见 `brand/README.md`。
 */
const Logo: React.FC<{
    /** gradient=靛蓝渐变实心（浅底）；mono=currentColor（深底） */
    variant?: 'gradient' | 'mono';
    className?: string;
}> = ({ variant = 'gradient', className = 'h-8 w-8' }) => {
    // 同一页面可能渲染多个实例，mask/gradient 的 id 必须唯一，否则后者的定义会串到前者。
    // useId 产出的 ":r0:" 带冒号，放进 url(#...) 在部分浏览器下解析失败，故剥掉。
    const uid = useId().replace(/:/g, '');
    const gradientId = `qy-logo-gradient-${uid}`;
    const maskId = `qy-logo-mask-${uid}`;

    return (
        <svg
            viewBox="0 0 24 24"
            className={className}
            role="img"
            aria-label="轻语 QingYu Care"
        >
            <defs>
                <linearGradient id={gradientId} x1="0" y1="0" x2="1" y2="1">
                    <stop offset="0" stopColor="#6366f1" />
                    <stop offset="1" stopColor="#8b5cf6" />
                </linearGradient>
                <mask id={maskId}>
                    <path
                        d="M4.5 7h8a3 3 0 0 1 3 3v4a3 3 0 0 1-3 3H7.5L3.5 20.8V17a3 3 0 0 1-3-3v-4a3 3 0 0 1 3-3z"
                        fill="#fff"
                    />
                    <path
                        d="M12.5 3h4.5L20.5 0.6L19.5 3a2.5 2.5 0 0 1 2.5 2.5v3.5a2.5 2.5 0 0 1-2.5 2.5H12.5a2.5 2.5 0 0 1-2.5-2.5V5.5a2.5 2.5 0 0 1 2.5-2.5z"
                        fill="#fff"
                    />
                    {/* ⚠️ 这条描边就是两泡之间的切缝，1.6 换算到 16px 约 1.07px，再窄会糊成一块 */}
                    <path
                        d="M12.5 3h4.5L20.5 0.6L19.5 3a2.5 2.5 0 0 1 2.5 2.5v3.5a2.5 2.5 0 0 1-2.5 2.5H12.5a2.5 2.5 0 0 1-2.5-2.5V5.5a2.5 2.5 0 0 1 2.5-2.5z"
                        fill="none"
                        stroke="#000"
                        strokeWidth="1.6"
                    />
                </mask>
            </defs>
            <rect
                width="24"
                height="24"
                fill={variant === 'gradient' ? `url(#${gradientId})` : 'currentColor'}
                mask={`url(#${maskId})`}
            />
        </svg>
    );
};

export default Logo;
