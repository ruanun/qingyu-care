package com.qingyu.care.it;

import cn.dev33.satoken.secure.BCrypt;
import com.qingyu.care.entity.Application;
import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.entity.SysApiUsage;
import com.qingyu.care.entity.Tenant;
import com.qingyu.care.entity.User;
import com.qingyu.care.enums.SessionStatus;
import com.qingyu.care.enums.UserRole;
import com.qingyu.care.repository.ChatSessionRepository;
import com.qingyu.care.repository.SysApiUsageRepository;
import com.qingyu.care.repository.UserRepository;
import com.qingyu.care.support.AbstractPostgresIT;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 用量看板端到端：平台管理员跨租户 / 租户只见本租户 / 带 X-App-Id 时会话聚合仍跨应用（appFilter 未挡）。
 * 不加 @Transactional：MockMvc 请求经真实拦截器链，需 seed 已提交可见；结尾手动清理。
 *
 * @author ruan
 */
class AnalyticsUsageIT extends AbstractPostgresIT {

    @Autowired MockMvc mockMvc;
    @Autowired SysApiUsageRepository usageRepository;
    @Autowired ChatSessionRepository sessionRepository;
    @Autowired UserRepository userRepository;

    private final ObjectMapper om = new ObjectMapper();

    private String login(String username, String password) throws Exception {
        MvcResult r = mockMvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return om.readTree(r.getResponse().getContentAsString()).path("data").path("token").asString();
    }

    private void seedUsage(Long tenant, Long app, long calls, long tokens) {
        usageRepository.save(SysApiUsage.builder()
                .tenantId(tenant).appId(app).usageDate(LocalDate.now())
                .callCount(calls).messageCount(calls).tokenCount(tokens).build());
    }

    private void seedSession(Long tenant, Long app) {
        ChatSession s = ChatSession.builder()
                .sessionId("it-usage-" + tenant + "-" + app + "-" + System.nanoTime())
                .userId("u").status(SessionStatus.ENDED).build();
        s.setTenantId(tenant);
        s.setAppId(app);
        sessionRepository.save(s);
    }

    @Test
    void 平台管理员看到跨租户_租户只看本租户_且带AppId会话仍跨应用() throws Exception {
        Tenant tenantA = tenantRepository.save(Tenant.builder()
                .name("用量IT租户A").code("USAGE_IT_A")
                .status(com.qingyu.care.enums.TenantStatus.ACTIVE)
                .plan(com.qingyu.care.enums.TenantPlan.FREE).build());
        Long TA = tenantA.getId();

        Application appA1 = new Application();
        appA1.setName("A1"); appA1.setTenant(tenantA);
        appA1.setAppKey("usage_it_a1"); appA1.setAppSecret("sec_a1");
        appA1.setEnabled(true);
        appA1 = applicationRepository.save(appA1);
        Long A1 = appA1.getId();

        Application appA2 = new Application();
        appA2.setName("A2"); appA2.setTenant(tenantA);
        appA2.setAppKey("usage_it_a2"); appA2.setAppSecret("sec_a2");
        appA2.setEnabled(true);
        appA2 = applicationRepository.save(appA2);
        Long A2 = appA2.getId();

        Tenant tenantB = tenantRepository.save(Tenant.builder()
                .name("用量IT租户B").code("USAGE_IT_B")
                .status(com.qingyu.care.enums.TenantStatus.ACTIVE)
                .plan(com.qingyu.care.enums.TenantPlan.FREE).build());
        Long TB = tenantB.getId();

        User adminA = userRepository.save(User.builder()
                .username("usage_it_admin_a").password(BCrypt.hashpw("pw123456"))
                .nickname("A管理员").role(UserRole.TENANT_ADMIN).tenant(tenantA).enabled(true).build());

        seedUsage(TA, A1, 5, 50);
        seedUsage(TA, A2, 3, 30);
        seedUsage(TB, 999001L, 8, 80);
        seedSession(TA, A1);
        seedSession(TA, A2);
        seedSession(TB, 999001L);

        try {
            // —— 平台管理员：全平台 ——
            String platToken = login("admin", "admin123");
            mockMvc.perform(get("/v1/analytics/usage?days=30").header("satoken", platToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.scope").value("PLATFORM"))
                    .andExpect(jsonPath("$.data.summary.totalCalls").value(16)); // 5+3+8

            // —— 租户 A 管理员：只见本租户（TA=8，不含 TB 的 8）——
            String aToken = login("usage_it_admin_a", "pw123456");
            mockMvc.perform(get("/v1/analytics/usage?days=30").header("satoken", aToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.scope").value("TENANT"))
                    .andExpect(jsonPath("$.data.summary.totalCalls").value(8));

            // —— 关键：租户 A 管理员带 X-App-Id=A1 时，会话聚合仍跨应用（A1+A2=2），不被 appFilter 挡 ——
            MvcResult withApp = mockMvc.perform(get("/v1/analytics/usage?days=30")
                            .header("satoken", aToken)
                            .header("X-App-Id", String.valueOf(A1)))
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode data = om.readTree(withApp.getResponse().getContentAsString()).path("data");
            assertThat(data.path("summary").path("totalSessions").asLong()).isEqualTo(2L);
        } finally {
            usageRepository.findAll().stream()
                    .filter(u -> TA.equals(u.getTenantId()) || TB.equals(u.getTenantId()))
                    .forEach(usageRepository::delete);
            sessionRepository.findAll().stream()
                    .filter(s -> TA.equals(s.getTenantId()) || TB.equals(s.getTenantId()))
                    .forEach(sessionRepository::delete);
            userRepository.delete(adminA);
            applicationRepository.deleteAll(List.of(appA1, appA2));
            tenantRepository.deleteAll(List.of(tenantA, tenantB));
        }
    }
}
