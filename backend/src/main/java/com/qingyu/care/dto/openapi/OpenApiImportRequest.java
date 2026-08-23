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

    private Boolean enabled;

    // ⚠️ timeoutMs / maxResponseChars 曾在这里，已删除：导入面板从未提供对应输入框
    // （唯一调用方 OpenApiImportDialog.handleImport 只发 candidates/authHeader/enabled），
    // 是文档与实现漂移出的死字段。本期统一用 OpenApiImportService.DEFAULT_TIMEOUT_MS /
    // DEFAULT_MAX_RESPONSE_CHARS（与既有 create 端点默认值一致），见设计文档 §3.1/§7.2。
}
