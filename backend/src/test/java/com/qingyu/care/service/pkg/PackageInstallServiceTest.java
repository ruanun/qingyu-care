package com.qingyu.care.service.pkg;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.dto.pkg.PackageInstallResult;
import com.qingyu.care.entity.AiSkill;
import com.qingyu.care.entity.AiTool;
import com.qingyu.care.entity.McpServer;
import com.qingyu.care.entity.ToolPackage;
import com.qingyu.care.enums.OwnerScope;
import com.qingyu.care.enums.PackageStatus;
import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.repository.AiSkillRepository;
import com.qingyu.care.repository.AiToolRepository;
import com.qingyu.care.repository.McpServerRepository;
import com.qingyu.care.repository.McpToolRepository;
import com.qingyu.care.service.mcp.McpServerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PackageInstallService} 的安装编排测试（段一：HTTP 工具 + 技能）。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PackageInstallServiceTest {

    private static final Long TENANT_ID = 7L;
    private static final Long APP_ID = 70L;

    @Mock ToolPackageService packageService;
    @Mock AiToolRepository toolRepository;
    @Mock AiSkillRepository skillRepository;
    @Mock McpServerRepository mcpServerRepository;
    @Mock McpServerService mcpServerService;
    @Mock McpToolRepository mcpToolRepository;
    @Mock TransactionTemplate transactionTemplate;

    @Spy PackageManifestParser manifestParser = new PackageManifestParser(new ObjectMapper());
    @Spy PackageVariableApplier variableApplier = new PackageVariableApplier();

    @InjectMocks PackageInstallService service;

    /** 一个只含 HTTP 工具 + 技能的包，工具的鉴权头是变量。 */
    private static final String MANIFEST = """
            {
              "manifestVersion": 1,
              "tools": [{
                "key": "weather_query", "name": "weather_query", "description": "查天气",
                "inputSchema": "{\\"type\\":\\"object\\"}",
                "httpMethod": "GET",
                "urlTemplate": "https://${WEATHER_HOST}/v1/weather?city={city}",
                "timeoutMs": 5000, "maxResponseChars": 4000,
                "authHeaderVar": "WEATHER_AUTH"
              }],
              "mcpServers": [],
              "skills": [{
                "key": "weather_skill", "name": "天气助手", "description": "回答天气问题",
                "content": "# 天气助手\\n用户问天气时调用 weather_query。",
                "toolKeys": ["weather_query"], "mcpRefs": []
              }],
              "variables": [
                {"name": "WEATHER_HOST", "label": "服务地址", "required": true, "secret": false, "help": "如 api.qweather.com"},
                {"name": "WEATHER_AUTH", "label": "鉴权头", "required": true, "secret": true, "help": "Authorization: Bearer xxx"}
              ]
            }
            """;

    private static final Map<String, String> VALUES = Map.of(
            "WEATHER_HOST", "api.qweather.com",
            "WEATHER_AUTH", "Authorization: Bearer sk-tenant-own");

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(TENANT_ID);
        TenantContext.setAppId(APP_ID);

        when(packageService.getPublished("weather_pack")).thenReturn(
                ToolPackage.builder().id(1L).code("weather_pack").name("天气包")
                        .status(PackageStatus.PUBLISHED).manifest(MANIFEST).build());

        // TransactionTemplate 直接执行回调：本类验的是编排顺序与落库内容，不是事务边界
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });

        // 落库时补一个自增 id，供 skill 回填工具绑定
        AtomicLong toolIdSeq = new AtomicLong(100);
        when(toolRepository.save(any(AiTool.class))).thenAnswer(invocation -> {
            AiTool t = invocation.getArgument(0);
            t.setId(toolIdSeq.incrementAndGet());
            return t;
        });
        when(skillRepository.save(any(AiSkill.class))).thenAnswer(invocation -> {
            AiSkill s = invocation.getArgument(0);
            s.setId(200L);
            return s;
        });
        when(toolRepository.findByAppId(APP_ID)).thenReturn(List.of());
        when(toolRepository.findAllSharedTools()).thenReturn(List.of());
        when(skillRepository.findByAppId(APP_ID)).thenReturn(List.of());
        when(mcpServerRepository.findByAppId(APP_ID)).thenReturn(List.of());
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void 安装建出工具与技能并返回摘要() {
        PackageInstallResult result = service.install("weather_pack", VALUES);

        assertThat(result.toolNames()).containsExactly("weather_query");
        assertThat(result.skillNames()).containsExactly("天气助手");
        assertThat(result.mcpServerNames()).isEmpty();
    }

    @Test
    @DisplayName("落库的工具：变量已替换、租户填的凭据已写入、scope 强制 TENANT")
    void 工具字段与归属正确() {
        service.install("weather_pack", VALUES);

        ArgumentCaptor<AiTool> captor = ArgumentCaptor.forClass(AiTool.class);
        verify(toolRepository).save(captor.capture());
        AiTool saved = captor.getValue();

        assertThat(saved.getName()).isEqualTo("weather_query");
        assertThat(saved.getUrlTemplate())
                .isEqualTo("https://api.qweather.com/v1/weather?city={city}");
        assertThat(saved.getAuthHeader()).isEqualTo("Authorization: Bearer sk-tenant-own");
        assertThat(saved.getHttpMethod()).isEqualTo("GET");
        assertThat(saved.getTimeoutMs()).isEqualTo(5000);
        assertThat(saved.getMaxResponseChars()).isEqualTo(4000);
        assertThat(saved.getEnabled()).isTrue();
        // ⚠️ scope 必须是服务端强制的 TENANT——manifest 里压根没有这个字段，
        // 但 AiTool.scope 有 @Builder.Default = TENANT，靠默认值是「碰巧对」而不是「强制对」
        assertThat(saved.getScope()).isEqualTo(OwnerScope.TENANT);
        // 归属交给 TenantEntityListener 从上下文回填，这里不该手工设（也不该被 manifest 影响）
        assertThat(saved.getId()).isNotNull();
    }

    @Test
    @DisplayName("skill 的 toolIds 指向新建行的 id，不是 manifest 里的任何东西")
    void skill绑定回填成新建工具的id() {
        service.install("weather_pack", VALUES);

        ArgumentCaptor<AiSkill> captor = ArgumentCaptor.forClass(AiSkill.class);
        verify(skillRepository).save(captor.capture());
        AiSkill saved = captor.getValue();

        assertThat(saved.getName()).isEqualTo("天气助手");
        assertThat(saved.getContent()).contains("weather_query");
        assertThat(saved.getToolIds()).containsExactly(101L);   // toolIdSeq 的第一个值
        assertThat(saved.getEnabled()).isTrue();
    }

    /** ⚠️ appId 为空时建出来的行对任何应用都检索不到，且完全静默——必须在入口拒绝。 */
    @Test
    @DisplayName("没选应用（appId 为空）直接拒绝安装")
    void 未选应用被拒绝() {
        TenantContext.setAppId(null);

        assertThatThrownBy(() -> service.install("weather_pack", VALUES))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("应用");
        verify(toolRepository, never()).save(any());
    }

    /** ⚠️ requireTenantAdmin 放行平台管理员，而他没有 tenantId——落库会是 tenant_id IS NULL。 */
    @Test
    @DisplayName("没有租户上下文（平台管理员）直接拒绝安装")
    void 无租户上下文被拒绝() {
        TenantContext.setTenantId(null);

        assertThatThrownBy(() -> service.install("weather_pack", VALUES))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("租户");
        verify(toolRepository, never()).save(any());
    }

    @Test
    @DisplayName("工具重名 → 整体拒绝并列出冲突项，不自动改名")
    void 工具重名整体拒绝() {
        AiTool existing = AiTool.builder().name("weather_query").description("已有的")
                .urlTemplate("https://x.example.com").build();
        when(toolRepository.findByAppId(APP_ID)).thenReturn(List.of(existing));

        assertThatThrownBy(() -> service.install("weather_pack", VALUES))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("weather_query");
        verify(toolRepository, never()).save(any());
    }

    @Test
    @DisplayName("与平台共享工具重名同样拒绝——resolve() 遇到重名会静默跳过其中一个")
    void 与共享工具重名也拒绝() {
        AiTool shared = AiTool.builder().name("weather_query").description("平台共享")
                .urlTemplate("https://x.example.com").scope(OwnerScope.PLATFORM).build();
        when(toolRepository.findAllSharedTools()).thenReturn(List.of(shared));

        assertThatThrownBy(() -> service.install("weather_pack", VALUES))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("weather_query");
    }

    @Test
    void 技能重名整体拒绝() {
        AiSkill existing = AiSkill.builder().name("天气助手").content("已有").build();
        when(skillRepository.findByAppId(APP_ID)).thenReturn(List.of(existing));

        assertThatThrownBy(() -> service.install("weather_pack", VALUES))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("天气助手");
    }

    @Test
    @DisplayName("多个冲突一次列全，别让人改一个装一次")
    void 多个冲突一次列全() {
        when(toolRepository.findByAppId(APP_ID)).thenReturn(List.of(
                AiTool.builder().name("weather_query").description("x")
                        .urlTemplate("https://x.example.com").build()));
        when(skillRepository.findByAppId(APP_ID)).thenReturn(List.of(
                AiSkill.builder().name("天气助手").content("x").build()));

        assertThatThrownBy(() -> service.install("weather_pack", VALUES))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("weather_query")
                .hasMessageContaining("天气助手");
    }

    @Test
    void 必填变量没填时在落库之前就被拒绝() {
        assertThatThrownBy(() -> service.install("weather_pack", Map.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("WEATHER_HOST");
        verify(toolRepository, never()).save(any());
    }

    @Test
    @DisplayName("MCP 服务的 code 在当前应用内重名同样拒绝（它是 LLM 工具名前缀）")
    void MCP服务code重名拒绝() {
        String mcpManifest = """
                {
                  "manifestVersion": 1, "tools": [],
                  "mcpServers": [{
                    "key": "ctx7", "name": "Context7", "code": "ctx7",
                    "transport": "STREAMABLE_HTTP", "url": "https://mcp.example.com/mcp",
                    "timeoutMs": 10000, "maxResponseChars": 4000
                  }],
                  "skills": [], "variables": []
                }
                """;
        when(packageService.getPublished("mcp_pack")).thenReturn(
                ToolPackage.builder().id(2L).code("mcp_pack").name("MCP 包")
                        .status(PackageStatus.PUBLISHED).manifest(mcpManifest).build());
        when(mcpServerRepository.findByAppId(APP_ID)).thenReturn(List.of(
                McpServer.builder().name("别的服务").code("ctx7")
                        .url("https://other.example.com/mcp").build()));

        assertThatThrownBy(() -> service.install("mcp_pack", Map.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ctx7");
        verify(mcpServerService, never()).saveServer(any());
    }
}
