package com.qingyu.care.it;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.entity.AbExperiment;
import com.qingyu.care.entity.AbVariant;
import com.qingyu.care.entity.Application;
import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.entity.ChatStrategy;
import com.qingyu.care.enums.ExperimentStatus;
import com.qingyu.care.repository.AbExperimentRepository;
import com.qingyu.care.repository.ChatSessionRepository;
import com.qingyu.care.repository.ChatStrategyRepository;
import com.qingyu.care.service.AbTestService;
import com.qingyu.care.service.ModelRouterService;
import com.qingyu.care.support.AbstractPostgresIT;
import com.qingyu.care.support.SpringAiResponses;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A/B 实验全链路 IT：开放路径分桶落标 → 对话走变体策略 → 报表聚合 →
 * 结束实验回归默认；多租户隔离；后台入口不分流由单测覆盖（ChatServiceAbWiringTest）。
 * mock ModelRouterService（不 mock VectorStore——会破坏共享表，见 StreamPersistenceIT 教训）。
 * 变体策略 allowNoKnowledge=false + 专属兜底语 → 断言变体生效无需 AI，确定性强。
 *
 * @author ruan
 */
class AbTestFlowIT extends AbstractPostgresIT {

    @Autowired MockMvc mockMvc;
    @Autowired ChatStrategyRepository strategyRepository;
    @Autowired AbExperimentRepository experimentRepository;
    @Autowired ChatSessionRepository sessionRepository;
    @Autowired AbTestService abTestService;

    @MockitoBean
    private ModelRouterService modelRouterService;

    /** 播种策略：显式双设 tenantId+appId（IT 线程无 TenantContext，appFilter 无 NULL 兜底） */
    private ChatStrategy seedStrategy(Application app, String name,
                                      boolean allowNoKnowledge, String fallbackMessage) {
        ChatStrategy s = ChatStrategy.builder()
                .name(name).scenario("default")
                .topK(3).similarityThreshold(0.7)
                .allowNoKnowledge(allowNoKnowledge)
                .fallbackMessage(fallbackMessage)
                .enabled(true).isDefault(false)
                .build();
        s.setTenantId(app.getTenant().getId());
        s.setAppId(app.getId());
        return strategyRepository.saveAndFlush(s);
    }

    /** 播种运行中实验：A 权重 0 / B 权重 100，保证确定性分桶到 B */
    private AbExperiment seedRunningExperiment(Application app, Long strategyA, Long strategyB) {
        AbExperiment exp = AbExperiment.builder()
                .name("IT实验").status(ExperimentStatus.RUNNING)
                .startedAt(LocalDateTime.now())
                .variants(new ArrayList<>(List.of(
                        AbVariant.builder().name("A").strategyId(strategyA).weight(0).build(),
                        AbVariant.builder().name("B").strategyId(strategyB).weight(100).build())))
                .build();
        exp.setTenantId(app.getTenant().getId());
        exp.setAppId(app.getId());
        return experimentRepository.saveAndFlush(exp);
    }

    /** IT 线程内以指定应用的租户上下文执行（服务层/JPA 查询需过滤器上下文） */
    private <T> T withTenantContext(Application app, Supplier<T> action) {
        try {
            TenantContext.setTenantId(app.getTenant().getId());
            TenantContext.setAppId(app.getId());
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }

    private String completions(Application app, String message) throws Exception {
        return mockMvc.perform(post("/open/v1/chat/completions")
                        .header("X-App-Id", app.getAppKey())
                        .header("X-Api-Key", app.getAppSecret())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"" + message + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void 全链路_分桶走变体策略_报表聚合正确() throws Exception {
        Application app = seedApp("ab" + System.nanoTime(), "http://ab.example.com");
        ChatStrategy sA = seedStrategy(app, "变体A策略", true, null);
        ChatStrategy sB = seedStrategy(app, "变体B策略", false, "变体B专属兜底");
        AbExperiment exp = seedRunningExperiment(app, sA.getId(), sB.getId());

        // 开放对话：无知识库命中 + B 策略不允许自由发挥 → 回复即 B 的兜底语（证明变体策略生效）
        String body = completions(app, "问一个问题");
        assertThat((String) JsonPath.read(body, "$.reply")).isEqualTo("变体B专属兜底");

        // 会话三字段落库
        String sessionId = JsonPath.read(body, "$.sessionId");
        ChatSession session = withTenantContext(app, () ->
                sessionRepository.findBySessionId(sessionId).orElseThrow());
        assertThat(session.getExperimentId()).isEqualTo(exp.getId());
        assertThat(session.getVariantName()).isEqualTo("B");
        assertThat(session.getVariantStrategyId()).isEqualTo(sB.getId());

        // 反馈一个赞（兜底消息也有 messageId）。JsonPath 数值类型按 Number 读取再转换，
        // 避免因 json-smart 视值域返回 Integer/Long 不一致导致的拆箱 ClassCastException。
        Number messageId = JsonPath.read(body, "$.messageId");
        mockMvc.perform(post("/open/v1/chat/feedback")
                        .header("X-App-Id", app.getAppKey())
                        .header("X-Api-Key", app.getAppSecret())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"" + sessionId + "\",\"messageId\":" + messageId.longValue()
                                + ",\"feedback\":1}"))
                .andExpect(status().isOk());

        // 报表：B 有数据，A 全 0
        var report = withTenantContext(app, () -> abTestService.getReport(exp.getId()));
        var variantB = report.variants().stream()
                .filter(v -> v.variantName().equals("B")).findFirst().orElseThrow();
        assertThat(variantB.sessionCount()).isEqualTo(1);
        assertThat(variantB.questionCount()).isEqualTo(1);
        assertThat(variantB.aiMsgCount()).isEqualTo(1);
        assertThat(variantB.fallbackRate()).isEqualTo(1.0);
        assertThat(variantB.feedbackUp()).isEqualTo(1);
        assertThat(variantB.likeRate()).isEqualTo(1.0);
        var variantA = report.variants().stream()
                .filter(v -> v.variantName().equals("A")).findFirst().orElseThrow();
        assertThat(variantA.sessionCount()).isZero();
    }

    @Test
    void 结束实验后_新会话回归默认策略() throws Exception {
        Application app = seedApp("abend" + System.nanoTime(), "http://abe.example.com");
        ChatStrategy sA = seedStrategy(app, "A策略", true, null);
        ChatStrategy sB = seedStrategy(app, "B策略", false, "结束前兜底");
        AbExperiment exp = seedRunningExperiment(app, sA.getId(), sB.getId());

        withTenantContext(app, () -> abTestService.end(exp.getId()));

        // 默认策略（平台种子）allowNoKnowledge=true → 走 AI，需 mock
        Mockito.when(modelRouterService.callWithFallback(
                        ArgumentMatchers.eq("default"), ArgumentMatchers.any()))
                .thenReturn(SpringAiResponses.of("默认AI回复", 10));

        String body = completions(app, "结束后的提问");
        assertThat((String) JsonPath.read(body, "$.reply")).isEqualTo("默认AI回复");

        String sessionId = JsonPath.read(body, "$.sessionId");
        ChatSession session = withTenantContext(app, () ->
                sessionRepository.findBySessionId(sessionId).orElseThrow());
        assertThat(session.getExperimentId()).isNull();
        assertThat(session.getVariantName()).isNull();
    }

    @Test
    void 多租户隔离_他租户实验不分流() throws Exception {
        Application appA = seedApp("abiso1" + System.nanoTime(), "http://i1.example.com");
        Application appB = seedApp("abiso2" + System.nanoTime(), "http://i2.example.com");
        ChatStrategy sA = seedStrategy(appA, "A策略", true, null);
        ChatStrategy sB = seedStrategy(appA, "B策略", false, "A租户兜底");
        seedRunningExperiment(appA, sA.getId(), sB.getId());

        Mockito.when(modelRouterService.callWithFallback(
                        ArgumentMatchers.eq("default"), ArgumentMatchers.any()))
                .thenReturn(SpringAiResponses.of("B租户默认回复", 10));

        String body = completions(appB, "B租户提问");
        assertThat((String) JsonPath.read(body, "$.reply")).isEqualTo("B租户默认回复");

        String sessionId = JsonPath.read(body, "$.sessionId");
        ChatSession session = withTenantContext(appB, () ->
                sessionRepository.findBySessionId(sessionId).orElseThrow());
        assertThat(session.getExperimentId()).isNull();
    }
}
