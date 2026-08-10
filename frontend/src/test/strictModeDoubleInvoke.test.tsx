import { StrictMode, useEffect } from 'react';
import { render } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

/**
 * 工具链前提哨兵——不测任何产品代码。
 *
 * `CrawlSiteList.test.tsx` 里那条「StrictMode 下轮询照常工作」的回归测试，
 * 成立的前提是：vitest 下 react-dom 走 development 构建，<StrictMode> 会把
 * effect 跑成 setup → cleanup → setup。这个前提由工具链决定、不由我们的代码决定。
 *
 * 一旦升级 React / vitest / Vite 让双调用不再发生，那条回归测试会**静默退化成
 * 永真**而不是转红：isMountedRef 的初值本来就是 true，不双调用就永远测不出
 * 「effect 的 setup 阶段缺少复位」这个缺陷。所以把前提本身钉成一条测试——
 * 它先红，那条才可信。
 */
describe('工具链前提：StrictMode 双调用', () => {
    it('挂载时 effect 跑成 setup → cleanup → setup', () => {
        const calls: string[] = [];

        function Probe() {
            useEffect(() => {
                calls.push('setup');
                // 花括号丢弃 Array.push 的返回值（number）：
                // EffectCallback 的 Destructor 类型要求返回 void，隐式返回表达式值会被 tsc -b 拒绝。
                return () => { calls.push('cleanup'); };
            }, []);
            return null;
        }

        render(<StrictMode><Probe /></StrictMode>);

        expect(calls).toEqual(['setup', 'cleanup', 'setup']);
    });
});
