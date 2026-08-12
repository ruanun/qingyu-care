package com.qingyu.care.controller;

import com.qingyu.care.annotation.AuditLog;
import com.qingyu.care.dto.ApiResponse;
import com.qingyu.care.entity.AiTool;
import com.qingyu.care.enums.OwnerScope;
import com.qingyu.care.service.ChatToolService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * AI 工具（HTTP）管理 API
 *
 * @author ruan
 */
@RestController
@RequestMapping("/v1/ai-tools")
@RequiredArgsConstructor
public class AiToolController {

    private final ChatToolService chatToolService;

    @GetMapping
    public ApiResponse<List<AiTool>> list() {
        return ApiResponse.success(chatToolService.listTools());
    }

    @GetMapping("/{id}")
    public ApiResponse<AiTool> getById(@PathVariable Long id) {
        return ApiResponse.success(chatToolService.getTool(id));
    }

    @AuditLog(action = "TOOL_CREATE", targetType = "AiTool")
    @PostMapping
    public ApiResponse<AiTool> create(@RequestBody AiTool tool) {
        tool.setId(null);
        // 租户侧建的工具一律是租户级：scope 是客户端可控字段，不清掉的话租户可以在请求体里
        // 自封 PLATFORM，让自己的工具（连同自己的 authHeader）出现在所有租户的列表里。
        // 平台共享工具只能走 /v1/platform/tools（那边强制 tenantId=null）。
        tool.setScope(OwnerScope.TENANT);
        return ApiResponse.success("工具已创建", chatToolService.saveTool(tool));
    }

    @AuditLog(action = "TOOL_UPDATE", targetType = "AiTool")
    @PutMapping("/{id}")
    public ApiResponse<AiTool> update(@PathVariable Long id, @RequestBody AiTool tool) {
        AiTool existing = chatToolService.getTool(id);
        if (tool.getName() != null) existing.setName(tool.getName());
        if (tool.getDescription() != null) existing.setDescription(tool.getDescription());
        if (tool.getInputSchema() != null) existing.setInputSchema(tool.getInputSchema());
        if (tool.getHttpMethod() != null) existing.setHttpMethod(tool.getHttpMethod());
        if (tool.getUrlTemplate() != null) existing.setUrlTemplate(tool.getUrlTemplate());
        if (tool.getAuthHeader() != null && !tool.getAuthHeader().isEmpty()) existing.setAuthHeader(tool.getAuthHeader());
        if (tool.getTimeoutMs() != null) existing.setTimeoutMs(tool.getTimeoutMs());
        if (tool.getMaxResponseChars() != null) existing.setMaxResponseChars(tool.getMaxResponseChars());
        if (tool.getEnabled() != null) existing.setEnabled(tool.getEnabled());
        return ApiResponse.success("工具已更新", chatToolService.saveTool(existing));
    }

    @AuditLog(action = "TOOL_DELETE", targetType = "AiTool")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        chatToolService.deleteTool(id);
        return ApiResponse.success("工具已删除", null);
    }
}
