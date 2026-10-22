package com.qingyu.care.controller;

import com.qingyu.care.annotation.AdminOnlyWrites;
import com.qingyu.care.annotation.AuditLog;
import com.qingyu.care.dto.AiToolUpdateRequest;
import com.qingyu.care.dto.ApiResponse;
import com.qingyu.care.dto.openapi.OpenApiImportRequest;
import com.qingyu.care.dto.openapi.OpenApiParseRequest;
import com.qingyu.care.dto.openapi.OpenApiParseResponse;
import com.qingyu.care.entity.AiTool;
import com.qingyu.care.enums.OwnerScope;
import com.qingyu.care.service.ChatToolService;
import com.qingyu.care.service.openapi.OpenApiImportService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * AI 工具（HTTP）管理 API
 *
 * @author ruan
 */
@RestController
@AdminOnlyWrites
@RequestMapping("/v1/ai-tools")
@RequiredArgsConstructor
public class AiToolController {

    private final ChatToolService chatToolService;
    private final OpenApiImportService openApiImportService;

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
    public ApiResponse<AiTool> update(@PathVariable Long id, @RequestBody AiToolUpdateRequest tool) {
        AiTool existing = chatToolService.getTool(id);
        if (tool.getName() != null) existing.setName(tool.getName());
        if (tool.getDescription() != null) existing.setDescription(tool.getDescription());
        if (tool.getInputSchema() != null) existing.setInputSchema(tool.getInputSchema());
        if (tool.getHttpMethod() != null) existing.setHttpMethod(tool.getHttpMethod());
        if (tool.getUrlTemplate() != null) existing.setUrlTemplate(tool.getUrlTemplate());
        // isBlank 而非 isEmpty：全空格鉴权头同样无意义，不该被当成「有意修改」写入
        if (tool.getAuthHeader() != null && !tool.getAuthHeader().isBlank()) existing.setAuthHeader(tool.getAuthHeader());
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

    /**
     * 解析 schema 返回候选，**不落库**。刻意不打 @AuditLog：它是只读的，且用户会为调整
     * 配置反复解析同一份 schema，打审计只会把日志刷满无信息量的行。
     */
    @PostMapping("/openapi/parse")
    public ApiResponse<OpenApiParseResponse> parseOpenApi(@RequestBody OpenApiParseRequest request) {
        return ApiResponse.success(openApiImportService.parse(request));
    }

    @AuditLog(action = "TOOL_IMPORT", targetType = "AiTool")
    @PostMapping("/openapi/import")
    public ApiResponse<List<AiTool>> importOpenApi(@RequestBody OpenApiImportRequest request) {
        List<AiTool> created = openApiImportService.importTools(request);
        return ApiResponse.success("已导入 " + created.size() + " 个工具", created);
    }
}
