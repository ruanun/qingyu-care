package com.qingyu.care.it;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.entity.Application;
import com.qingyu.care.entity.PromptTemplate;
import com.qingyu.care.repository.PromptTemplateRepository;
import com.qingyu.care.service.ModelRouterService;
import com.qingyu.care.service.PromptTemplateService;
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
 * 平台级 Prompt 模板回退 —— 走真实开放对话端点驱动，让 Hibernate 租户过滤器真正生效。
 *
 * 关键：在 @SpringBootTest 里直接 set TenantContext 再调 service，过滤器并不会生效
 * （测试线程无请求作用域会话供 TenantDataAspect 绑定过滤器）。必须走 MockMvc 真实请求：
 * OpenApiAuthInterceptor 注入 TenantContext + OSIV 会话 → 过滤器真实作用于同一 Session。
 *
 * 行为：某租户/应用无自有激活模板时，ChatServiceImpl.buildMessages 调
 * promptTemplateService.getActiveTemplateContent()：
 *   - 修复存在：findActiveScoped 被有效作用域收窄挡空 → 回退 findPlatformDefaultActive
 *     命中 @PostConstruct 平台默认模板 → 对话成功（200）。
 *   - 删掉回退（.or(findPlatformDefaultActive)）：getActiveTemplate 抛
 *     ResourceNotFoundException「没有激活的 Prompt 模板」→ 开放 API 映射为 404 → IT 变红。
 * 即行为级 revert-proof fail-on-revert（已实测）。
 *
 * ModelRouterService 用 @MockitoBean 打桩，避免真实 LLM 调用。
 *
 * @author ruan
 */
@Transactional
class PromptTemplateFallbackIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PromptTemplateRepository promptTemplateRepository;

    @Autowired
    private PromptTemplateService promptTemplateService;

    /** 打桩模型路由，让对话在到达 buildMessages(prompt) 之后能返回固定回复，不触达真实 LLM。 */
    @MockitoBean
    private ModelRouterService modelRouterService;

    @AfterEach
    void 清理租户上下文() {
        TenantContext.clear();
    }

    @Test
    void 租户无自有模板时走对话端点应回退平台默认并成功() throws Exception {
        // 该租户应用不建自有 prompt/strategy，只留 @PostConstruct 的平台默认（tenant_id=NULL）
        Application app = seedApp("prompt_fb", "http://it.example.com");

        // 对话最终会调用 callWithFallback；打桩返回带 usage 的 Spring AI ChatResponse，避免真实模型
        Mockito.when(modelRouterService.callWithFallback(
                        ArgumentMatchers.eq("default"), ArgumentMatchers.any()))
                .thenReturn(SpringAiResponses.of("MOCK_AI_REPLY", 7));

        mockMvc.perform(post("/open/v1/chat/completions")
                        .header("X-App-Id", app.getAppKey())
                        .header("X-Api-Key", app.getAppSecret())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"你好\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("MOCK_AI_REPLY"));
    }

    /**
     * 平台管理员场景（无过滤器）：prompt_templates 表内同键（is_active=true）存在多个租户各
     * 一行 + 一行平台默认，若不做作用域收窄裸查 is_active=true 会因过滤器缺失同时看到全部行而
     * NonUniqueResultException。修复后经 ConfigScopeResolver 按 X-App-Id 反查出应用 A 归属的
     * 租户 T，用 findActiveScoped 显式收窄，必须解析到租户 T 自己的那一行——而非另一租户 T2
     * 的同名行，也不是平台默认（验证「解析到正确租户」而非仅「不抛异常」，避免重蹈转人工
     * 回退修复前的教训）。
     */
    @Test
    void 平台管理员无过滤器_多租户同键行_精确解析到应用归属租户() {
        Application appA = seedApp("prompt_scope_a", "http://it.example.com");
        Long tenantT = appA.getTenant().getId();

        // 另一租户 T2，同 is_active=true 键值，制造 NonUnique 触发条件
        Application appB = seedApp("prompt_scope_b", "http://it.example.com");
        Long tenantT2 = appB.getTenant().getId();

        PromptTemplate ownT = PromptTemplate.builder()
                .name("租户T自有模板")
                .content("C-T")
                .version(1)
                .isActive(true)
                .build();
        ownT.setTenantId(tenantT);
        ownT.setAppId(appA.getId());
        promptTemplateRepository.saveAndFlush(ownT);

        PromptTemplate ownT2 = PromptTemplate.builder()
                .name("租户T2自有模板")
                .content("C-T2")
                .version(1)
                .isActive(true)
                .build();
        ownT2.setTenantId(tenantT2);
        ownT2.setAppId(appB.getId());
        promptTemplateRepository.saveAndFlush(ownT2);

        // 平台管理员：无租户上下文，仅带 X-App-Id（模拟平台管理员选中应用 A 后的会话态）
        TenantContext.setTenantId(null);
        TenantContext.setAppId(appA.getId());

        // ConfigScopeResolver.resolve() 新增了 SecurityUtil.isPlatformAdmin() 守卫，只有真正
        // 平台管理员登录（Sa-Token 会话）才会触发按 X-App-Id 反查归属租户；本用例未真实登录，
        // 用 mockStatic 忠实模拟"平台管理员"这一前提，让反查得以发生（与 ReembedServiceTest 同款用法）。
        try (MockedStatic<SecurityUtil> secMock = mockStatic(SecurityUtil.class)) {
            secMock.when(SecurityUtil::isPlatformAdmin).thenReturn(true);

            PromptTemplate result = promptTemplateService.getActiveTemplate();

            assertThat(result.getTenantId()).isEqualTo(tenantT);
            assertThat(result.getName()).isEqualTo("租户T自有模板");
            assertThat(result.getName()).isNotEqualTo("租户T2自有模板");
        }
    }
}
