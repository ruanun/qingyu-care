package com.qingyu.care.controller;

import com.qingyu.care.annotation.AuditLog;
import com.qingyu.care.dto.ApiResponse;
import com.qingyu.care.dto.McpServerUpdateRequest;
import com.qingyu.care.dto.McpToolReadOnlyRequest;
import com.qingyu.care.entity.McpServer;
import com.qingyu.care.entity.McpTool;
import com.qingyu.care.enums.OwnerScope;
import com.qingyu.care.service.mcp.McpServerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * MCP 服务管理端点。
 *
 * @author ruan
 */
@RestController
@RequestMapping("/v1/mcp")
@RequiredArgsConstructor
@Tag(name = "MCP 服务", description = "远端 MCP server 与其工具清单")
public class McpServerController {

    private final McpServerService mcpServerService;

    @GetMapping("/servers")
    @Operation(summary = "服务器列表")
    public ApiResponse<List<McpServer>> listServers() {
        return ApiResponse.success(mcpServerService.listServers());
    }

    @PostMapping("/servers")
    @Operation(summary = "新建服务器")
    @AuditLog(action = "创建MCP服务", targetType = "McpServer")
    public ApiResponse<McpServer> createServer(@RequestBody McpServer server) {
        // 不信任请求体里的归属字段：BaseTenantEntity 的 tenantId/appId 是 READ_ONLY，
        // 由 TenantEntityListener 回填
        server.setId(null);
        // 不信任请求体里的 scope：McpServer.scope 无 @JsonProperty(READ_ONLY) 保护，
        // 租户可在请求体里自封 PLATFORM（AiToolController.create 那一行同源，见
        // docs/dev/子系统要点.md「平台共享工具」第 1 条记载的真实事故——租户自封 PLATFORM 后，
        // 工具带着攻击者的 authHeader 出现在所有租户的共享列表里，被别的租户绑进 skill 调用）
        server.setScope(OwnerScope.TENANT);
        return ApiResponse.success("创建成功", mcpServerService.saveServer(server));
    }

    @PutMapping("/servers/{id}")
    @Operation(summary = "更新服务器")
    @AuditLog(action = "更新MCP服务", targetType = "McpServer")
    public ApiResponse<McpServer> updateServer(@PathVariable Long id,
                                               @RequestBody McpServerUpdateRequest request) {
        McpServer server = mcpServerService.getServer(id);
        if (request.getName() != null) {
            server.setName(request.getName());
        }
        if (request.getCode() != null) {
            server.setCode(request.getCode());
        }
        if (request.getDescription() != null) {
            server.setDescription(request.getDescription());
        }
        if (request.getTransport() != null) {
            server.setTransport(request.getTransport());
        }
        if (request.getUrl() != null) {
            server.setUrl(request.getUrl());
        }
        // ⚠️ 凭据字段额外判 isBlank：前端不回显凭据、编辑弹窗把该字段初始化为空串
        // （或粘贴带来的全空格）却照样提交，只判 != null 挡不住，会把已存的凭据清空
        if (request.getAuthHeader() != null && !request.getAuthHeader().isBlank()) {
            server.setAuthHeader(request.getAuthHeader());
        }
        if (request.getTimeoutMs() != null) {
            server.setTimeoutMs(request.getTimeoutMs());
        }
        if (request.getMaxResponseChars() != null) {
            server.setMaxResponseChars(request.getMaxResponseChars());
        }
        if (request.getEnabled() != null) {
            server.setEnabled(request.getEnabled());
        }
        return ApiResponse.success("更新成功", mcpServerService.saveServer(server));
    }

    @DeleteMapping("/servers/{id}")
    @Operation(summary = "删除服务器", description = "连带删除其工具行并摘掉 skill 绑定")
    @AuditLog(action = "删除MCP服务", targetType = "McpServer")
    public ApiResponse<Void> deleteServer(@PathVariable Long id) {
        mcpServerService.deleteServer(id);
        return ApiResponse.success("删除成功", null);
    }

    @GetMapping("/servers/{id}/usage")
    @Operation(summary = "统计有多少 skill 在用该服务的工具", description = "供删除确认弹窗显示")
    public ApiResponse<Map<String, Long>> usage(@PathVariable Long id) {
        return ApiResponse.success(Map.of("skillCount", mcpServerService.countSkillsUsing(id)));
    }

    @PostMapping("/servers/{id}/sync")
    @Operation(summary = "同步工具清单")
    @AuditLog(action = "同步MCP工具", targetType = "McpServer")
    public ApiResponse<List<McpTool>> sync(@PathVariable Long id) {
        return ApiResponse.success("同步成功", mcpServerService.sync(id));
    }

    @GetMapping("/servers/{id}/tools")
    @Operation(summary = "该服务的工具清单")
    public ApiResponse<List<McpTool>> listTools(@PathVariable Long id) {
        return ApiResponse.success(mcpServerService.listTools(id));
    }

    @PutMapping("/tools/read-only")
    @Operation(summary = "批量标注只读位")
    @AuditLog(action = "标注MCP工具只读", targetType = "McpTool")
    public ApiResponse<Integer> markReadOnly(@Valid @RequestBody McpToolReadOnlyRequest request) {
        return ApiResponse.success("标注成功",
                mcpServerService.markReadOnly(request.getToolIds(), request.getReadOnly()));
    }
}
