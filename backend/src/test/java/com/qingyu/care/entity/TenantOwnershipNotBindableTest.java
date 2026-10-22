package com.qingyu.care.entity;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 租户归属字段（tenantId / appId）不得从请求体绑定。
 *
 * <p>守的是一个真实的越权面：五个 create 端点把请求体直接绑到实体，而
 * {@code TenantEntityListener.prePersist} <b>只在字段为 null 时</b>才从 {@code TenantContext}
 * 回填。租户管理员 POST {@code {"tenantId": <他人租户id>}} 时该字段非 null，监听器不覆盖，
 * 行直接建进目标租户。
 *
 * <p>⚠️ 断言的是<b>行为</b>而不是「注解存在」。注解写法可能变（换成 mixin、换成
 * {@code @JsonIgnore}），但「请求体给了也绑不上」这条语义不能变。
 *
 * <p>⚠️ 这里同时用两个实体，是为了证明阻断来自<b>基类</b>而非某个子类的偶然配置——
 * 只测一个的话，把注解从基类挪到那一个子类上，测试照样绿。
 *
 * @author ruan
 */
class TenantOwnershipNotBindableTest {

    /** 与 Spring MVC 消息转换器同源（同 dto/RequestBodyDeserializationTest 的用法） */
    private final ObjectMapper om = new ObjectMapper();

    @Test
    void 工具的租户归属字段不参与反序列化() {
        AiTool tool = om.readValue("""
                {"name":"query_order","description":"查订单",
                 "urlTemplate":"https://e.example.com/o?id={id}",
                 "tenantId":99,"appId":88}
                """, AiTool.class);

        // 普通字段照常绑定——证明这条测试确实解析成功了，不是整体失败导致的空值
        assertThat(tool.getName()).isEqualTo("query_order");
        assertThat(tool.getTenantId()).as("请求体里的 tenantId 必须被丢弃").isNull();
        assertThat(tool.getAppId()).as("请求体里的 appId 必须被丢弃").isNull();
    }

    @Test
    void 技能的租户归属字段不参与反序列化() {
        AiSkill skill = om.readValue("""
                {"name":"退货助手","content":"# 指引","tenantId":99,"appId":88}
                """, AiSkill.class);

        assertThat(skill.getName()).isEqualTo("退货助手");
        assertThat(skill.getTenantId()).isNull();
        assertThat(skill.getAppId()).isNull();
    }
}
