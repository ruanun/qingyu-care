package com.qingyu.care.entity;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 凭据字段只读入、不写出（2026-09-25 安全扫描 H2）。
 *
 * <p>模型、工具、MCP 的控制器直接把实体当响应体，此前 {@code apiKey} / {@code authHeader} 明文随列表发给
 * 全体登录用户，坐席也读得到。两个方向都要钉：写出方向是本次修复本身；读入方向是 create 端点
 * 直接用实体收请求体，改成 {@code @JsonIgnore} 会让「新建时填的密钥」静默丢失而写出方向照样绿。
 *
 * @author ruan
 */
class CredentialNotSerializedTest {

    /** 与 Spring MVC 消息转换器同源（同 TenantOwnershipNotBindableTest 的用法） */
    private final ObjectMapper om = new ObjectMapper();

    @Test
    void 模型apiKey不写出但照常读入() {
        AiModelConfig m = om.readValue("{\"name\":\"qwen\",\"apiKey\":\"sk-SECRET\"}", AiModelConfig.class);
        assertThat(m.getApiKey()).as("create 端点靠它收密钥").isEqualTo("sk-SECRET");

        String json = om.writeValueAsString(m);
        assertThat(json).contains("\"name\":\"qwen\"").doesNotContain("sk-SECRET").doesNotContain("apiKey");
    }

    @Test
    void 工具authHeader不写出但照常读入() {
        AiTool t = om.readValue("{\"name\":\"q\",\"authHeader\":\"Authorization: Bearer SECRET\"}", AiTool.class);
        assertThat(t.getAuthHeader()).isEqualTo("Authorization: Bearer SECRET");
        assertThat(om.writeValueAsString(t)).contains("\"name\":\"q\"").doesNotContain("SECRET").doesNotContain("authHeader");
    }

    @Test
    void MCP服务authHeader不写出但照常读入() {
        McpServer s = om.readValue("{\"name\":\"m\",\"authHeader\":\"X-Token: SECRET\"}", McpServer.class);
        assertThat(s.getAuthHeader()).isEqualTo("X-Token: SECRET");
        assertThat(om.writeValueAsString(s)).contains("\"name\":\"m\"").doesNotContain("SECRET").doesNotContain("authHeader");
    }
}
