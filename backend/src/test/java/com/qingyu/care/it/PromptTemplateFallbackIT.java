package com.qingyu.care.it;

import com.qingyu.care.entity.Application;
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
 * 平台级 Prompt 模板回退 —— 走真实开放对话端点驱动，让 Hibernate 租户过滤器真正生效。
 *
 * 关键：在 @SpringBootTest 里直接 set TenantContext 再调 service，过滤器并不会生效
 * （测试线程无请求作用域会话供 TenantDataAspect 绑定过滤器）。必须走 MockMvc 真实请求：
 * OpenApiAuthInterceptor 注入 TenantContext + OSIV 会话 → 过滤器真实作用于同一 Session。
 *
 * 行为：某租户/应用无自有激活模板时，ChatServiceImpl.buildMessages 调
 * promptTemplateService.getActiveTemplateContent()：
 *   - 修复存在：findByIsActiveTrue 被租户过滤挡住 → 回退 findPlatformDefaultActive
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

    /** 打桩模型路由，让对话在到达 buildMessages(prompt) 之后能返回固定回复，不触达真实 LLM。 */
    @MockitoBean
    private ModelRouterService modelRouterService;

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
}
