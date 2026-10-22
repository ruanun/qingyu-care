package com.qingyu.care.exception;

/**
 * 非法操作异常 (400)
 * 用于业务逻辑不允许的操作，如对已结束会话执行接管
 */
public class InvalidOperationException extends BusinessException {
    
    public InvalidOperationException(String message) {
        super(400, message);
    }
}
