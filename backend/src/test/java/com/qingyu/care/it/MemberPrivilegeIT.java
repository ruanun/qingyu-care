package com.qingyu.care.it;

import cn.dev33.satoken.secure.BCrypt;
import com.qingyu.care.entity.Application;
import com.qingyu.care.entity.Tenant;
import com.qingyu.care.entity.User;
import com.qingyu.care.enums.TenantPlan;
import com.qingyu.care.enums.TenantStatus;
import com.qingyu.care.enums.UserRole;
import com.qingyu.care.repository.UserRepository;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 租户内的角色边界：坐席（{@code TENANT_MEMBER}）不能越过租户管理员。
 *
 * <p>安全审查 H2/H3/H4/M1（2026-09-03）走真实 HTTP 的那一半：单测已经各自钉住控制器行为，
 * 这里串起登录态 → 拦截器 → 控制器 → 序列化整条链，防止某一环（如 {@code TenantContext.userId}
 * 由拦截器写入）在单测里被桩掉后静默失效。
 *
 * @author ruan
 */
class MemberPrivilegeIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;

    /** 同一 JVM 内固定后缀：@BeforeEach 每条用例都会进来，播种必须幂等（用户名是全局唯一键）。 */
    private static final String SUF = Long.toString(System.nanoTime(), 36);
    private static final String ADMIN = "priv_admin_" + SUF;
    private static final String MEMBER = "priv_member_" + SUF;
    private static final String TENANT_CODE = "IT_priv_" + SUF;
    private static final String APP_KEY = "app_it_priv_" + SUF;

    private Long adminId;
    private Long appId;

    @BeforeEach
    void seed() {
        if (!userRepository.existsByUsernameAcrossTenants(ADMIN)) {
            Tenant tenant = tenantRepository.save(Tenant.builder()
                    .name("IT租户-priv").code(TENANT_CODE)
                    .status(TenantStatus.ACTIVE).plan(TenantPlan.FREE).build());
            userRepository.save(user(tenant, ADMIN, UserRole.TENANT_ADMIN));
            userRepository.save(user(tenant, MEMBER, UserRole.TENANT_MEMBER));

            Application app = new Application();
            app.setName("IT应用-priv");
            app.setTenant(tenant);
            app.setAppKey(APP_KEY);
            app.setAppSecret("sec_it_priv_SECRET");
            app.setEnabled(true);
            applicationRepository.save(app);
        }
        adminId = userRepository.findByUsernameAcrossTenants(ADMIN).orElseThrow().getId();
        appId = applicationRepository.findByAppKey(APP_KEY).orElseThrow().getId();
    }

    private static User user(Tenant tenant, String username, UserRole role) {
        return User.builder().username(username).password(BCrypt.hashpw("pwd123456"))
                .nickname(username).role(role).tenant(tenant).enabled(true).build();
    }

    @Test
    void 成员不能重置租户管理员的密码() throws Exception {
        String memberToken = login(MEMBER);

        mockMvc.perform(put("/v1/users/" + adminId)
                        .header("satoken", memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"hacked123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));

        // 管理员仍能用原口令登录：密码没有被改动
        login(ADMIN);
    }

    @Test
    void 成员看应用列表拿不到密钥且改不动应用() throws Exception {
        String memberToken = login(MEMBER);

        mockMvc.perform(get("/v1/applications").header("satoken", memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[*].appKey").value(hasItem(APP_KEY)))
                .andExpect(jsonPath("$.data[*].appSecret").value(not(hasItem("sec_it_priv_SECRET"))));

        mockMvc.perform(put("/v1/applications/" + appId)
                        .header("satoken", memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allowedOrigins\":\"https://evil.example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void 用户列表不含密码哈希() throws Exception {
        String memberToken = login(MEMBER);

        mockMvc.perform(get("/v1/users").header("satoken", memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].password").doesNotExist());
    }

    @Test
    void 凭租户编码自助注册被拒绝() throws Exception {
        mockMvc.perform(post("/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"priv_intruder\",\"password\":\"pwd123456\","
                                + "\"nickname\":\"入侵者\",\"tenantCode\":\"" + TENANT_CODE + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));
    }

    private String login(String username) throws Exception {
        String body = mockMvc.perform(post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"pwd123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();
        int at = body.indexOf("\"token\":\"") + 9;
        return body.substring(at, body.indexOf('"', at));
    }
}
