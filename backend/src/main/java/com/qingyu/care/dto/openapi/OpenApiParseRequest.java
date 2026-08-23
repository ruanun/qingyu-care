package com.qingyu.care.dto.openapi;

import lombok.Data;

/**
 * 解析请求：{@code schemaText} 与 {@code schemaUrl} 二选一。
 *
 * @author ruan
 */
@Data
public class OpenApiParseRequest {

    /** 直接粘贴的 schema 文本（JSON 或 YAML） */
    private String schemaText;

    /** 或者给一个可拉取的地址。⚠️ 走 SsrfGuard 与禁重定向，见 OpenApiSchemaFetcher */
    private String schemaUrl;

    /**
     * 服务器地址。留空时取 schema 的 {@code servers[0].url}，若那也不是绝对地址则由
     * mapper 记全局 warning 要求用户填。⚠️ **绝不猜成 schemaUrl 的 origin**：schema 托管
     * 地址与 API 实际地址不同是常态，猜测会静默生成一批指向错误主机的工具。
     */
    private String baseUrl;
}
