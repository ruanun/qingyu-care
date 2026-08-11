import { describe, expect, it } from 'vitest';
import { getErrorMessage, throwIfFailed } from './apiResult';

/**
 * `lib/apiResult` 的单元测试。
 *
 * 这两个函数是技术债第 9 项（36 处写操作把业务错误静默吞掉）的收敛点，全站
 * 写操作都靠它们把「HTTP 200 + body.code ≠ 200」变成一次可见的报错。它们本身
 * 写错的话，那 36 处会一起静默回去——所以这里的断言粒度要精确到「抛的是什么」，
 * 不能只断言「抛了」。
 */
describe('throwIfFailed', () => {
    it('code 为 200 时什么都不做', () => {
        expect(() => throwIfFailed({ code: 200, message: 'success' })).not.toThrow();
    });

    it('code 不为 200 时抛出 Error，且 message 用的是后端那句', () => {
        // 断言到 message 内容而不只是「抛了」：调用点的 catch 会把它显示给用户，
        // 若这里改成 throw new Error('操作失败') 之类的固定文案，用户就看不到
        // 「入口地址重复」「已被别的坐席接管」这类真正有用的原因了。
        expect(() => throwIfFailed({ code: 400, message: '入口地址重复' }))
            .toThrowError(new Error('入口地址重复'));
    });

    it('对 500 之类的其它非 200 码同样抛出', () => {
        expect(() => throwIfFailed({ code: 500, message: '服务异常' })).toThrow('服务异常');
    });
});

describe('getErrorMessage', () => {
    it('Error 实例取 message', () => {
        expect(getErrorMessage(new Error('入口地址重复'))).toBe('入口地址重复');
    });

    it('非 Error 值退化成 String()', () => {
        // 有库直接 throw 字符串的先例，所以不能假设 catch 到的一定是 Error
        expect(getErrorMessage('炸了')).toBe('炸了');
        expect(getErrorMessage(undefined)).toBe('undefined');
    });

    it('自定义 Error 子类也取到 message', () => {
        class SessionExpiredError extends Error {}
        expect(getErrorMessage(new SessionExpiredError('会话已过期'))).toBe('会话已过期');
    });
});
