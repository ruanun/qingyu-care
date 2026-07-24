package com.qingyu.care.service.tool;

/**
 * HTTP 执行缝：把「发请求」与 {@link HttpToolCallback} 的参数拼装/截断逻辑解耦，
 * 便于对 callback 做纯 Mockito 单测（stub 本接口即可，无需真发请求）。
 */
public interface HttpToolExecutor {

    /**
     * @param method     GET / POST
     * @param url        已填充占位符的完整 URL
     * @param authHeader 形如 "Authorization: Bearer xxx" 的整行头，可空
     * @param body       请求体（POST 用），可空
     * @param timeoutMs  超时
     * @return 响应体文本
     */
    String execute(String method, String url, String authHeader, String body, int timeoutMs);
}
