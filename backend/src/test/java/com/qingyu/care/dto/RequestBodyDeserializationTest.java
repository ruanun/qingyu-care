package com.qingyu.care.dto;

import com.qingyu.care.entity.AiModelConfig;
import com.qingyu.care.entity.AiTool;
import com.qingyu.care.entity.Application;
import com.qingyu.care.entity.ChatStrategy;
import com.qingyu.care.entity.Tenant;
import com.qingyu.care.enums.OwnerScope;
import com.qingyu.care.enums.TenantPlan;
import com.qingyu.care.enums.TenantStatus;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 请求体反序列化回归网：守 Boot 4（Jackson 2→3）暴露的隐式创建器缺陷。
 *
 * <p>Jackson 3 会把 Lombok {@code @AllArgsConstructor} 当成隐式属性创建器（靠 -parameters
 * 取参数名），即使类同时有无参构造。后果有二：请求体里缺省的属性以 null 传入构造器，
 * ① 原始类型直接抛 {@code Cannot map null into type boolean}（Jackson 3 默认开启
 * FAIL_ON_NULL_FOR_PRIMITIVES）；② 字段初值 / {@code @Builder.Default} 被 null 覆盖。
 * 修法是把无参构造显式标为 {@code @JsonCreator}，恢复「无参构造 + setter」填充语义。
 *
 * <p>这类缺陷 MockMvc 之外的单测抓不到，且症状是 400/500 或静默丢默认值，故单独立网。
 *
 * @author ruan
 */
class RequestBodyDeserializationTest {

    /** 与 Spring MVC 消息转换器同源：Jackson 3 的参数名创建器能力已默认启用 */
    private final ObjectMapper om = new ObjectMapper();

    @Test
    void chatRequest_缺省原始类型字段不报错且取字段初值() {
        // openChannel 带 @JsonIgnore，永远无法从请求体提供——不修则任何请求体都必然 400
        ChatRequest r = om.readValue("{\"message\":\"你好\"}", ChatRequest.class);

        assertThat(r.getMessage()).isEqualTo("你好");
        assertThat(r.isStream()).isFalse();
        assertThat(r.isOpenChannel()).isFalse();
    }

    @Test
    void chatRequest_显式传入的字段仍然生效() {
        ChatRequest r = om.readValue(
                "{\"message\":\"hi\",\"sessionId\":\"s-1\",\"userId\":\"u-1\",\"stream\":true}",
                ChatRequest.class);

        assertThat(r.getSessionId()).isEqualTo("s-1");
        assertThat(r.getUserId()).isEqualTo("u-1");
        assertThat(r.isStream()).isTrue();
    }

    @Test
    void application_缺省字段保留BuilderDefault初值() {
        // enabled 是 NOT NULL 列，被 null 覆盖会在落库时炸 500
        Application app = om.readValue("{\"name\":\"冒烟应用\"}", Application.class);

        assertThat(app.getName()).isEqualTo("冒烟应用");
        assertThat(app.getEnabled()).isTrue();
        assertThat(app.getThemeColor()).isEqualTo("#0d9488");
    }

    @Test
    void chatStrategy_缺省字段保留检索参数默认值() {
        // topK / similarityThreshold 丢默认值会直接破坏 RAG 检索
        ChatStrategy s = om.readValue("{\"name\":\"默认策略\"}", ChatStrategy.class);

        assertThat(s.getTopK()).isEqualTo(5);
        assertThat(s.getSimilarityThreshold()).isEqualTo(0.45);
        assertThat(s.getAllowNoKnowledge()).isTrue();
        assertThat(s.getEnabled()).isTrue();
        assertThat(s.getIsDefault()).isFalse();
    }

    @Test
    void aiModelConfig_缺省字段保留场景与归属默认值() {
        AiModelConfig c = om.readValue("{\"name\":\"模型\"}", AiModelConfig.class);

        assertThat(c.getScenario()).isEqualTo("default");
        assertThat(c.getPriority()).isEqualTo(100);
        assertThat(c.getEnabled()).isTrue();
        assertThat(c.getScope()).isEqualTo(OwnerScope.TENANT);
    }

    @Test
    void aiTool_缺省字段保留调用护栏默认值() {
        AiTool t = om.readValue("{\"name\":\"查订单\"}", AiTool.class);

        assertThat(t.getHttpMethod()).isEqualTo("GET");
        assertThat(t.getTimeoutMs()).isEqualTo(5000);
        assertThat(t.getMaxResponseChars()).isEqualTo(4000);
        assertThat(t.getEnabled()).isTrue();
    }

    @Test
    void tenant_缺省字段保留状态与套餐默认值() {
        Tenant t = om.readValue("{\"name\":\"某企业\",\"code\":\"T001\"}", Tenant.class);

        assertThat(t.getStatus()).isEqualTo(TenantStatus.ACTIVE);
        assertThat(t.getPlan()).isEqualTo(TenantPlan.FREE);
    }

    @Test
    void uiPart_无无参构造仍走参数名隐式创建器() {
        // 反向守护：修法不能把 UiPart 这种「只有 @AllArgsConstructor」的类推坏
        UiPart p = om.readValue("{\"kind\":\"text\",\"text\":\"你好\",\"node\":null}", UiPart.class);

        assertThat(p.getKind()).isEqualTo("text");
        assertThat(p.getText()).isEqualTo("你好");
    }
}
