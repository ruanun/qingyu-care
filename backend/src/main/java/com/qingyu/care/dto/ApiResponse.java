package com.qingyu.care.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 通用响应包装类。
 *
 * <p>⚠️ <b>{@link #error(int, String)} 是唯一的失败构造入口，{@code code} 必须显式给。
 * 不要再加 {@code error(String)} 那种「默认 500」的便捷重载</b>——它存在过，2026-09-02 删掉。
 *
 * <p>删它的原因不是洁癖：控制器里正常 {@code return} 的失败响应不经
 * {@code GlobalExceptionHandler}，{@code code} 只能在调用点自己给，而那个重载让「不给」变成
 * 了最省事的写法。后果是 {@code UserController} 里 13 处业务校验失败（无权限、用户名已存在、
 * 用户不存在）全被标成「服务器内部错误」，一直没人发现——因为它不报错，只是把码写错。
 *
 * <p>订正那 13 处时是手工列的映射表，<b>还漏了一处</b>，靠 grep 扫全仓才找出来。所以最终
 * 选择删掉重载而不是「加条注释提醒别用」：<b>编译器强制每个调用点写出第二个参数</b>，
 * 逼人停下来想一秒「这到底是几」，比注释和测试哨兵都硬，且零维护。
 *
 * <p>取值口径与 {@code GlobalExceptionHandler} 对齐：403 权限不足 / 404 资源不存在 /
 * 400 客户端输入或上下文问题 / 502 代发出站请求但上游无有效响应 / 500 才是真的服务端内部错误。
 * ⚠️ 别用 401——前端那条「清登录态并跳 /login」判的是 HTTP 状态码，而这些响应恒为 HTTP 200，
 * 写 401 只会被当成一条普通错误消息静默显示（详见 {@code UserController} 类注释）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponse<T> {
    
    private int code;
    private String message;
    private T data;
    private long timestamp;
    
    public static <T> ApiResponse<T> success(T data) {
        return ApiResponse.<T>builder()
                .code(200)
                .message("success")
                .data(data)
                .timestamp(System.currentTimeMillis())
                .build();
    }
    
    public static <T> ApiResponse<T> success(String message, T data) {
        return ApiResponse.<T>builder()
                .code(200)
                .message(message)
                .data(data)
                .timestamp(System.currentTimeMillis())
                .build();
    }
    
    public static <T> ApiResponse<T> error(int code, String message) {
        return ApiResponse.<T>builder()
                .code(code)
                .message(message)
                .timestamp(System.currentTimeMillis())
                .build();
    }
}
