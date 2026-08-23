package com.qingyu.care.dto.openapi;

import lombok.Data;

import java.util.List;

/**
 * 导入请求：候选列表（前端可能已改过 name/description）+ 统一应用到本批的配置。
 *
 * <p>⚠️ 收候选列表而不是「schema + 勾选的 operationId」：后者要在后端重新解析一次，两次
 * 结果可能因 schema 内容变化或解析器版本差异而分叉，用户看到的与实际落库的会悄悄不一致。
 *
 * @author ruan
 */
@Data
public class OpenApiImportRequest {

    private List<OpenApiToolCandidate> candidates;

    /** 统一鉴权头，格式同 AiTool.authHeader（如 "Authorization: Bearer xxx"） */
    private String authHeader;

    private Integer timeoutMs;
    private Integer maxResponseChars;
    private Boolean enabled;
}
