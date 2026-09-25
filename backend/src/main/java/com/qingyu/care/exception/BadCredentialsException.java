package com.qingyu.care.exception;

/**
 * 用户名或密码错误。对外响应与 {@link InvalidOperationException} 完全一致（400「用户名或密码错误」），
 * 单独成类只为让登录限流能区分「凭据错」与「凭据对但账号停用 / 租户冻结」——后者不该计入失败次数。
 * 靠匹配报错文案来区分太脆弱，改一个字就静默失效。
 *
 * @author ruan
 */
public class BadCredentialsException extends InvalidOperationException {

    public BadCredentialsException() {
        super("用户名或密码错误");
    }
}
