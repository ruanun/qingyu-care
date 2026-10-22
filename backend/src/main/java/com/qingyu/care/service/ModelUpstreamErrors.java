package com.qingyu.care.service;

import com.qingyu.care.config.ModelEndpointGuardInterceptor;

/**
 * 模型上游调用失败的原因分类：给「测试连接」与向量模型保存前探测用。
 *
 * <p><b>只给分类、不回显上游原文</b>（2026-09-30 安全审查 M1）：此前直接拼 {@code e.getMessage()}，openai-java 的消息是
 * 「状态码: 完整错误 JSON」——租户把 endpoint 指向会重定向 / rebinding 到内网的服务器时，这里就成了读取内网响应的
 * 回显通道，各类失败文案的差异还能用来探测端口。守卫拒绝的消息只含主机名，可以原样给。
 *
 * @author ruan
 */
public final class ModelUpstreamErrors {

    private ModelUpstreamErrors() {
    }

    public static String describe(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ModelEndpointGuardInterceptor.EndpointRejectedException) {
                return t.getMessage();
            }
            if (t instanceof com.openai.errors.OpenAIServiceException se) {
                return "上游返回 HTTP " + se.statusCode() + "（请检查端点地址、模型 ID 与 API Key）";
            }
        }
        return "无法连接上游（" + e.getClass().getSimpleName() + "）";
    }
}
