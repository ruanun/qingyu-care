package com.qingyu.care.dto;

import com.qingyu.care.enums.McpTransport;
import lombok.Data;

/**
 * MCP server 局部更新请求。
 *
 * <p>⚠️ <b>所有字段都不能写初始化器</b>（含 @Builder.Default）：实体字段的初始化器在
 * Jackson 走无参构造反序列化时照样生效，请求体没给的字段会拿到类默认值而非 null，
 * {@code if (x != null)} 恒真，局部 PUT 时把库里的值静默覆盖回默认值。
 * UpdateRequestNoInitializerTest 会反射扫描强制这一条。
 *
 * @author ruan
 */
@Data
public class McpServerUpdateRequest {

    private String name;
    private String code;
    private String description;
    private McpTransport transport;
    private String url;
    /** ⚠️ 凭据字段，判空必须是 != null && !isBlank()，见控制器 */
    private String authHeader;
    private Integer timeoutMs;
    private Integer maxResponseChars;
    private Boolean enabled;
}
