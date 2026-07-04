package com.qingyu.care.it;

import com.qingyu.care.entity.Application;
import com.qingyu.care.entity.ChatStrategy;
import com.qingyu.care.repository.ChatStrategyRepository;
import com.qingyu.care.service.ModelRouterService;
import com.qingyu.care.support.AbstractPostgresIT;
import com.qingyu.care.support.SpringAiResponses;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

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

    @MockitoBean
    private ModelRouterService modelRouterService;

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
}
