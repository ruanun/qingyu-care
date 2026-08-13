package com.qingyu.care.it;

import cn.dev33.satoken.secure.BCrypt;
import com.qingyu.care.entity.AiTool;
import com.qingyu.care.entity.Application;
import com.qingyu.care.entity.User;
import com.qingyu.care.enums.UserRole;
import com.qingyu.care.repository.AiToolRepository;
import com.qingyu.care.repository.UserRepository;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 租户不能通过请求体把资源建进别人租户。
 *
 * <p>⚠️ 必须走真实 MockMvc 而不是直调 service 或手工 {@code ObjectMapper}：
 * <ul>
 *   <li>手工 mapper 可能与 Spring 实际配置的那个不一致，断言会<b>静默失去意义</b>；
 *   <li>{@code TenantContext} 只在请求流经 {@code TenantInterceptor} 时才有值，
 *       而这条测试的核心正是「回填用的是上下文、不是请求体」。
 * </ul>
 *
 * <p>注：越权尝试<b>不报错</b>，行会正常建进发起方自己的租户（见 spec §5，
 * 静默丢弃而非显式拒绝）。所以断言看的是<b>落库归属</b>，不是响应状态码。
 *
 * @author ruan
 */
class CreateTenantInjectionIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AiToolRepository toolRepository;

    @Autowired
    private UserRepository userRepository;

    private final ObjectMapper om = new ObjectMapper();

    private String login(String username, String password) throws Exception {
        MvcResult r = mockMvc.perform(post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return om.readTree(r.getResponse().getContentAsString()).path("data").path("token").asString();
    }

    @Test
    void 租户在请求体里指定他人租户_行仍落进自己租户() throws Exception {
        Application appA = seedApp("INJECT_A", "http://localhost");
        Application appB = seedApp("INJECT_B", "http://localhost");

        userRepository.saveAndFlush(User.builder()
                .tenant(appA.getTenant())
                .username("inject_a_admin").password(BCrypt.hashpw("pwd123456"))
                .role(UserRole.TENANT_ADMIN).enabled(true).build());

        String tokenA = login("inject_a_admin", "pwd123456");
        Long victimTenantId = appB.getTenant().getId();

        MvcResult r = mockMvc.perform(post("/v1/ai-tools")
                        .header("satoken", tokenA)
                        .header("X-App-Id", appA.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"injected_tool\",\"description\":\"越权尝试\","
                                + "\"urlTemplate\":\"http://evil.example.com/{q}\","
                                + "\"tenantId\":" + victimTenantId + ","
                                + "\"appId\":" + appB.getId() + "}"))
                .andExpect(status().isOk()).andReturn();

        var data = om.readTree(r.getResponse().getContentAsString()).path("data");
        long createdId = data.path("id").asLong();

        // READ_ONLY 是单向的（只写出、不读入）：这条断言防的是有人把它误改成 @JsonIgnore
        // 一类的双向屏蔽——反序列化方向的守护（Task 1 单测）不会变红，但响应契约会静默改变，
        // 「响应里照常返回 tenantId」这条设计承诺（spec §5）不再成立。
        assertThat(data.path("tenantId").asLong())
                .as("响应体必须照常带出 tenantId（序列化方向不受影响），且值是发起方租户 A")
                .isEqualTo(appA.getTenant().getId());

        AiTool created = toolRepository.findById(createdId).orElseThrow();

        assertThat(created.getTenantId())
                .as("必须落进发起方租户 A，而不是请求体里指定的租户 B")
                .isEqualTo(appA.getTenant().getId());
        assertThat(created.getAppId())
                .as("appId 同样由 X-App-Id 头决定，不由请求体决定")
                .isEqualTo(appA.getId());
    }
}
