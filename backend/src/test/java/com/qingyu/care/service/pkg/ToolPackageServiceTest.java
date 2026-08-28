package com.qingyu.care.service.pkg;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.dto.pkg.PackageExportRequest;
import com.qingyu.care.dto.pkg.PackageExportResult;
import com.qingyu.care.dto.pkg.ToolPackageUpdateRequest;
import com.qingyu.care.entity.AiSkill;
import com.qingyu.care.entity.AiTool;
import com.qingyu.care.entity.McpServer;
import com.qingyu.care.entity.ToolPackage;
import com.qingyu.care.enums.PackageStatus;
import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.exception.ResourceNotFoundException;
import com.qingyu.care.repository.McpToolRepository;
import com.qingyu.care.repository.ToolPackageRepository;
import com.qingyu.care.service.ChatToolService;
import com.qingyu.care.service.mcp.McpServerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ToolPackageService} 的编排规则测试。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ToolPackageServiceTest {

    @Mock ToolPackageRepository packageRepository;
    @Mock ChatToolService chatToolService;
    @Mock McpServerService mcpServerService;
    @Mock McpToolRepository mcpToolRepository;

    // 三个纯函数类用真货而不是 mock：它们没有 IO，mock 掉等于把本类要验的编排结果抹平
    @Spy PackageManifestBuilder manifestBuilder = new PackageManifestBuilder();
    @Spy PackageManifestParser manifestParser = new PackageManifestParser(new ObjectMapper());

    @InjectMocks ToolPackageService service;

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private static AiTool platformTool(Long id, String name) {
        AiTool t = AiTool.builder()
                .name(name).description("描述").httpMethod("GET")
                .urlTemplate("https://api.example.com/" + name)
                .timeoutMs(5000).maxResponseChars(4000).enabled(true).build();
        t.setId(id);
        t.setTenantId(null);   // 平台管理员建的行
        return t;
    }

    private static PackageExportRequest exportRequest(Long... toolIds) {
        PackageExportRequest r = new PackageExportRequest();
        r.setCode("weather_pack");
        r.setName("天气能力包");
        r.setDescription("查天气");
        r.setToolIds(List.of(toolIds));
        return r;
    }

    @Test
    void 导出产出DRAFT状态的包并落库() {
        when(chatToolService.getTool(1L)).thenReturn(platformTool(1L, "weather_query"));
        when(packageRepository.existsByCode("weather_pack")).thenReturn(false);
        when(packageRepository.save(any(ToolPackage.class))).thenAnswer(i -> i.getArgument(0));

        PackageExportResult result = service.export(exportRequest(1L));

        assertThat(result.toolPackage().getStatus()).isEqualTo(PackageStatus.DRAFT);
        assertThat(result.toolPackage().getCode()).isEqualTo("weather_pack");
        assertThat(result.toolPackage().getManifest()).contains("weather_query");
        verify(packageRepository).save(any(ToolPackage.class));
    }

    /**
     * ⚠️ 核心安全断言：{@code TenantOwnershipGuard.mutableInCurrentContext} 对平台管理员
     * （上下文 tenantId 为 null）<b>无条件放行</b>，所以 {@code chatToolService.getTool} 能取到
     * 任意租户的工具。不在这里拦住，平台管理员切到某个租户的应用后，导出就会把该租户私有的
     * 工具配置随包发给所有租户。
     */
    @Test
    @DisplayName("勾中了属于某个租户的行 → 整个导出被拒绝，不能把租户配置发成平台包")
    void 拒绝导出租户私有的行() {
        AiTool tenantOwned = platformTool(1L, "tenant_private");
        tenantOwned.setTenantId(42L);
        when(chatToolService.getTool(1L)).thenReturn(tenantOwned);

        assertThatThrownBy(() -> service.export(exportRequest(1L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("tenant_private");
        verify(packageRepository, never()).save(any());
    }

    @Test
    @DisplayName("勾中属于某个租户的 MCP 服务 → 同样整体拒绝（三个分支结构相同，最易复制粘贴改错字段）")
    void 拒绝导出租户私有的MCP服务() {
        McpServer tenantOwned = McpServer.builder()
                .name("tenant_private_mcp").code("tenant_mcp")
                .url("https://mcp.example.com/x")
                .timeoutMs(10000).maxResponseChars(4000).enabled(true).build();
        tenantOwned.setId(1L);
        tenantOwned.setTenantId(42L);
        when(mcpServerService.getServer(1L)).thenReturn(tenantOwned);

        PackageExportRequest request = new PackageExportRequest();
        request.setCode("mcp_pack");
        request.setName("MCP 包");
        request.setMcpServerIds(List.of(1L));

        assertThatThrownBy(() -> service.export(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("tenant_private_mcp");
        verify(packageRepository, never()).save(any());
    }

    @Test
    @DisplayName("勾中属于某个租户的技能 → 同样整体拒绝")
    void 拒绝导出租户私有的技能() {
        AiSkill tenantOwned = AiSkill.builder()
                .name("tenant_private_skill").content("技能正文").enabled(true).build();
        tenantOwned.setId(1L);
        tenantOwned.setTenantId(42L);
        when(chatToolService.getSkill(1L)).thenReturn(tenantOwned);

        PackageExportRequest request = new PackageExportRequest();
        request.setCode("skill_pack");
        request.setName("技能包");
        request.setSkillIds(List.of(1L));

        assertThatThrownBy(() -> service.export(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("tenant_private_skill");
        verify(packageRepository, never()).save(any());
    }

    @Test
    void code重复被拒绝() {
        when(packageRepository.existsByCode("weather_pack")).thenReturn(true);
        when(chatToolService.getTool(1L)).thenReturn(platformTool(1L, "weather_query"));

        assertThatThrownBy(() -> service.export(exportRequest(1L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("weather_pack");
    }

    @Test
    void code格式非法被拒绝() {
        PackageExportRequest r = exportRequest(1L);
        r.setCode("Weather-Pack");
        when(chatToolService.getTool(1L)).thenReturn(platformTool(1L, "weather_query"));

        assertThatThrownBy(() -> service.export(r))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("标识");
    }

    @Test
    void 一个都没勾被拒绝() {
        PackageExportRequest r = new PackageExportRequest();
        r.setCode("empty_pack");
        r.setName("空包");

        assertThatThrownBy(() -> service.export(r))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("至少");
    }

    @Test
    @DisplayName("发布前 manifest 必须能解析通过——发布出去就装得下去，不能留到租户点安装才炸")
    void 发布时校验manifest() {
        ToolPackage broken = ToolPackage.builder().id(1L).code("p").name("包")
                .status(PackageStatus.DRAFT).manifest("{ 这不是 JSON").build();
        when(packageRepository.findById(1L)).thenReturn(Optional.of(broken));

        assertThatThrownBy(() -> service.publish(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("manifest");
        verify(packageRepository, never()).save(any());
    }

    @Test
    void 发布成功后状态变PUBLISHED() {
        ToolPackage draft = ToolPackage.builder().id(1L).code("p").name("包")
                .status(PackageStatus.DRAFT)
                .manifest("""
                        {"manifestVersion":1,"tools":[{"key":"t","name":"t","description":"d",
                        "httpMethod":"GET","urlTemplate":"https://example.com/x",
                        "timeoutMs":5000,"maxResponseChars":4000}],
                        "mcpServers":[],"skills":[],"variables":[]}""")
                .build();
        when(packageRepository.findById(1L)).thenReturn(Optional.of(draft));
        when(packageRepository.save(any(ToolPackage.class))).thenAnswer(i -> i.getArgument(0));

        assertThat(service.publish(1L).getStatus()).isEqualTo(PackageStatus.PUBLISHED);
    }

    @Test
    @DisplayName("租户按 code 取包时，非 PUBLISHED 一律按「不存在」处理")
    void 未发布的包对租户不存在() {
        ToolPackage draft = ToolPackage.builder().id(1L).code("draft_pkg").name("草稿")
                .status(PackageStatus.DRAFT).manifest("{}").build();
        when(packageRepository.findByCode("draft_pkg")).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.getPublished("draft_pkg"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void 更新时没给的字段不被覆盖() {
        ToolPackage existing = ToolPackage.builder().id(1L).code("p").name("原名")
                .description("原描述").status(PackageStatus.DRAFT).manifest("{\"a\":1}").build();
        when(packageRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(packageRepository.save(any(ToolPackage.class))).thenAnswer(i -> i.getArgument(0));

        ToolPackageUpdateRequest request = new ToolPackageUpdateRequest();
        request.setName("新名字");

        ToolPackage updated = service.update(1L, request);

        assertThat(updated.getName()).isEqualTo("新名字");
        assertThat(updated.getDescription()).isEqualTo("原描述");
        assertThat(updated.getManifest()).isEqualTo("{\"a\":1}");
    }

    @Test
    void 更新时给的manifest解析不过整体被拒() {
        ToolPackage existing = ToolPackage.builder().id(1L).code("p").name("原名")
                .status(PackageStatus.DRAFT).manifest("{\"a\":1}").build();
        when(packageRepository.findById(1L)).thenReturn(Optional.of(existing));

        ToolPackageUpdateRequest request = new ToolPackageUpdateRequest();
        request.setManifest("{ 坏的 }");

        assertThatThrownBy(() -> service.update(1L, request)).isInstanceOf(BusinessException.class);
        verify(packageRepository, never()).save(any());
    }
}
