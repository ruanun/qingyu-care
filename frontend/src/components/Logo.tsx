import React, { useId } from 'react';

/**
 * 轻语品牌图标
 * 对话气泡（尖角左下）+「轻」字骨架（车字旁两笔 + 右侧两道横）。
 *
 * 实心气泡 + mask 挖空笔画：笔画处透出的是背景本身，因此同一份图形深浅背景通吃，
 * 换 fill 即可——`gradient` 用于浅色背景（侧边栏），`mono` 用于深色背景（登录页），
 * 后者取 currentColor，由父元素的文字色驱动。
 *
 * ⚠️ 同一份路径数据另有三处副本，改形状必须四处同步：
 *    frontend/public/logo.svg（favicon，**基准**）
 *    widget/src/logo.ts（悬浮球）
 *    docs/images/logo.svg（README 的字标锁定形式）
 * 三个独立构建目标 + 一份文档资产，无法共享。刻意不加防漂移哨兵——logo 走形是肉眼
 * 可见的外观问题，不属于本仓库那类「静默失效」，加哨兵是过度工程。
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
                        d="M5 3h14a3 3 0 0 1 3 3v8a3 3 0 0 1-3 3H9.5L5 20.8V17a3 3 0 0 1-3-3V6a3 3 0 0 1 3-3z"
                        fill="#fff"
                    />
                    {/*
                      左三笔＝车字旁（竖 + 两横），右三笔＝圣简化成「工」（横竖横）。
                      ⚠️ 右侧最初只画两横，实测整个图形读作「+=」。中间那一竖是专门用来
                      打断等号读法的，别删。
                    */}
                    <g stroke="#000" strokeWidth="1.4" strokeLinecap="round">
                        <path d="M8 7.4V13.4" />
                        <path d="M6.3 9.4H9.7" />
                        <path d="M6.3 11.9H9.7" />
                        <path d="M12.4 8.6H17.6" />
                        <path d="M15 8.6V12.6" />
                        <path d="M12.4 12.6H17.6" />
                    </g>
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
