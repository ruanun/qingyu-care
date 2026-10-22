package com.qingyu.care.it;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.entity.Application;
import com.qingyu.care.entity.ChatStrategy;
import com.qingyu.care.repository.ChatStrategyRepository;
import com.qingyu.care.service.ChatStrategyService;
import com.qingyu.care.service.ModelRouterService;
import com.qingyu.care.support.AbstractPostgresIT;
import com.qingyu.care.support.SpringAiResponses;
import com.qingyu.care.util.SecurityUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 审计：ChatStrategy @PostConstruct 平台种子（tenant_id=NULL）在租户上下文中是否可见 ——
 * 走真实开放对话端点驱动，让 Hibernate 租户过滤器真正生效（与 PromptTemplateFallbackIT 同理）。
 *
 * ChatStrategy 与 Prompt 的差异：即便平台种子被过滤，getActiveStrategy 仍有「运行时硬编码兜底」
 * （allowNoKnowledge=true），不会抛异常。而 @PostConstruct 的 default 种子默认值恰好与硬编码
 * 兜底一致，因此普通场景下二者行为无法区分。为构造可观测差异：本测试把平台 default 种子改成
 * allowNoKnowledge=false + 独特 fallbackMessage（模拟平台管理员自定义了默认策略）。
 *
 * 在知识库空命中（该租户无向量数据）时：
 *   - 修复存在：租户查询被过滤 → 回退 findPlatformDefaultByScenario 命中被改过的平台种子
 *     → allowNoKnowledge=false + 空文档 → 直接返回独特 fallbackMessage（不调用模型）。
 *   - 删掉回退：走硬编码兜底（allowNoKnowledge=true）→ 继续调用（被打桩的）模型 → 返回 MOCK_AI_REPLY。
 * 断言 reply == 独特 fallbackMessage 即可 fail-on-revert（已实测）。
 *
 * @Transactional 保证对平台种子的改动在测试后回滚，不污染同 JVM 其它 IT。
 *
 * @author ruan
 */
@Transactional
class ChatStrategyFallbackIT extends AbstractPostgresIT {

    private static final String 独特兜底语 = "PLATFORM_STRATEGY_FALLBACK_MARKER_90201";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ChatStrategyRepository chatStrategyRepository;

    @Autowired
    private ChatStrategyService chatStrategyService;

    @MockitoBean
    private ModelRouterService modelRouterService;

    @AfterEach
    void 清理租户上下文() {
        TenantContext.clear();
    }

    @Test
    void 租户无自有策略时走对话端点应回退平台默认策略() throws Exception {
        // 无租户上下文时（测试线程未登录）过滤器不生效，可直接取到 @PostConstruct 的 default 平台种子
        ChatStrategy platformDefault = chatStrategyRepository
                .findByScenarioAndEnabledTrueAndIsDefaultTrue("default")
                .orElseThrow(() -> new IllegalStateException("@PostConstruct 应已播种 default 平台策略"));
        // 改成可观测：不允许无知识自由发挥 + 独特兜底语（tenant_id 保持 NULL）
        platformDefault.setAllowNoKnowledge(false);
        platformDefault.setFallbackMessage(独特兜底语);
        chatStrategyRepository.saveAndFlush(platformDefault);

        Application app = seedApp("strategy_fb", "http://it.example.com");

        // 若走硬编码兜底（无修复），对话会调用模型；打桩返回带 usage 的 Spring AI ChatResponse（类型一致，避免 CCE）
        Mockito.when(modelRouterService.callWithFallback(
                        ArgumentMatchers.eq("default"), ArgumentMatchers.any()))
                .thenReturn(SpringAiResponses.of("MOCK_AI_REPLY", 7));

        // 知识库空命中（该租户无向量数据）→ 命中平台策略时 allowNoKnowledge=false 分支返回 fallbackMessage
        mockMvc.perform(post("/open/v1/chat/completions")
                        .header("X-App-Id", app.getAppKey())
                        .header("X-Api-Key", app.getAppSecret())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"你好\"}"))
                .andExpect(status().isOk())
                // 命中平台种子策略 → 返回独特兜底语；若回退被删 → 返回 MOCK_AI_REPLY（断言失败）
                .andExpect(jsonPath("$.reply").value(独特兜底语));
    }

    /**
     * Task 6：混合检索配置字段（hybridEnabled/keywordTopK/rrfK）也须随「租户自有→平台默认」
     * 回退链路一起可见。租户上下文直接用 TenantContext 设置（与 ModelRouterDefaultIT /
     * HandoverRuleFallbackIT 同一手法），无需真实 HTTP 请求即可让 TenantDataAspect 生效。
     */
    @Test
    void 平台默认hybrid字段_对租户可见_经native回退() {
        // 租户 90601 / 应用 90602 没有自己的 default 场景策略
        TenantContext.setTenantId(90601L);
        TenantContext.setAppId(90602L);

        ChatStrategy strategy = chatStrategyService.getActiveStrategy("default");

        // 租户自有查询被过滤器挡住 → 回退 findPlatformDefaultByScenario（原生 SQL 绕过过滤器）
        // 命中 @PostConstruct 播种的平台默认行，三个混合检索字段应随之带出
        assertThat(strategy.getHybridEnabled()).isTrue();
        assertThat(strategy.getKeywordTopK()).isEqualTo(10);
        assertThat(strategy.getRrfK()).isEqualTo(60);
    }

    /**
     * 平台管理员场景（无过滤器）：ChatStrategy 表内同键（scenario='default', enabled=true,
     * isDefault=true）存在多个租户各一行 + 一行平台默认，裸 findByScenarioAndEnabledTrueAndIsDefaultTrue
     * 会因过滤器缺失同时看到全部行而 NonUniqueResultException。修复后经 ConfigScopeResolver
     * 按 X-App-Id 反查出应用 A 归属的租户 T，用 findActiveDefaultScoped 显式收窄，必须解析到
     * 租户 T 自己的那一行——而非另一租户 T2 的同名行，也不是平台默认（验证「解析到正确租户」
     * 而非仅「不抛异常」，避免重蹈转人工回退修复前的教训）。
     */
    @Test
    void 平台管理员无过滤器_多租户同键行_精确解析到应用归属租户() {
        Application appA = seedApp("chatstrategy_scope_a", "http://it.example.com");
        Long tenantT = appA.getTenant().getId();

        // 另一租户 T2，同 scenario/enabled/isDefault 键值，制造 NonUnique 触发条件
        Application appB = seedApp("chatstrategy_scope_b", "http://it.example.com");
        Long tenantT2 = appB.getTenant().getId();

        ChatStrategy ownT = new ChatStrategy();
        ownT.setTenantId(tenantT);
        ownT.setAppId(appA.getId());
        ownT.setName("租户T自有默认策略");
        ownT.setScenario("default");
        ownT.setEnabled(true);
        ownT.setIsDefault(true);
        chatStrategyRepository.saveAndFlush(ownT);

        ChatStrategy ownT2 = new ChatStrategy();
        ownT2.setTenantId(tenantT2);
        ownT2.setAppId(appB.getId());
        ownT2.setName("租户T2自有默认策略");
        ownT2.setScenario("default");
        ownT2.setEnabled(true);
        ownT2.setIsDefault(true);
        chatStrategyRepository.saveAndFlush(ownT2);

        // 平台管理员：无租户上下文，仅带 X-App-Id（模拟平台管理员选中应用 A 后的会话态）
        TenantContext.setTenantId(null);
        TenantContext.setAppId(appA.getId());

        // ConfigScopeResolver.resolve() 新增了 SecurityUtil.isPlatformAdmin() 守卫，只有真正
        // 平台管理员登录（Sa-Token 会话）才会触发按 X-App-Id 反查归属租户；本用例未真实登录，
        // 用 mockStatic 忠实模拟"平台管理员"这一前提，让反查得以发生。
        try (MockedStatic<SecurityUtil> secMock = mockStatic(SecurityUtil.class)) {
            secMock.when(SecurityUtil::isPlatformAdmin).thenReturn(true);

            ChatStrategy result = chatStrategyService.getActiveStrategy("default");

            assertThat(result.getTenantId()).isEqualTo(tenantT);
            assertThat(result.getName()).isEqualTo("租户T自有默认策略");
            assertThat(result.getName()).isNotEqualTo("租户T2自有默认策略");
        }
    }
}
