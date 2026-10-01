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
    @Autowired
    private com.qingyu.care.scheduler.TenantExpiryScheduler tenantExpiryScheduler;

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

    /**
     * 2026-09-25 安全扫描 H2：10 个应用级配置控制器对坐席完全放开。单测里切面是手工织入的，
     * 这里证明真实 Spring 容器里 {@code @AdminOnlyWrites} 确实作用在控制器上。
     * 每一类都用「同一请求换管理员就成功」作对照——否则 403 可能只是请求本身不合法。
     */
    @Test
    void 成员改不动应用级配置_同一请求管理员可以() throws Exception {
        String memberToken = login(MEMBER);
        String adminToken = login(ADMIN);
        String tool = "{\"name\":\"priv_tool_" + SUF + "\",\"description\":\"d\",\"httpMethod\":\"GET\","
                + "\"urlTemplate\":\"https://8.8.8.8/x\",\"authHeader\":\"Authorization: Bearer TOOL_SECRET\"}";
        String[][] writes = {
                {"/v1/ai-tools", tool},
                {"/v1/prompts", "{\"name\":\"priv_prompt_" + SUF + "\",\"content\":\"你是客服 {context}\"}"},
                {"/v1/knowledge", "{\"name\":\"priv_kb_" + SUF + "\"}"},
        };
        for (String[] w : writes) {
            mockMvc.perform(post(w[0]).header("satoken", memberToken).header("X-App-Id", appId)
                            .contentType(MediaType.APPLICATION_JSON).content(w[1]))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(403));
            mockMvc.perform(post(w[0]).header("satoken", adminToken).header("X-App-Id", appId)
                            .contentType(MediaType.APPLICATION_JSON).content(w[1]))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }
        mockMvc.perform(post("/v1/mcp/servers").header("satoken", memberToken).header("X-App-Id", appId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"m\",\"code\":\"m" + SUF + "\",\"url\":\"https://8.8.8.8/mcp\"}"))
                .andExpect(jsonPath("$.code").value(403));
        mockMvc.perform(post("/v1/ai-models").header("satoken", memberToken).header("X-App-Id", appId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"m\",\"provider\":\"openai\",\"modelName\":\"x\",\"apiKey\":\"sk-x\"}"))
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void 成员仍能读配置但拿不到凭据_只读POST不被拦() throws Exception {
        String adminToken = login(ADMIN);
        mockMvc.perform(post("/v1/ai-tools").header("satoken", adminToken).header("X-App-Id", appId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"priv_read_" + SUF + "\",\"description\":\"d\",\"httpMethod\":\"GET\","
                                + "\"urlTemplate\":\"https://8.8.8.8/x\",\"authHeader\":\"Authorization: Bearer READ_SECRET\"}"))
                .andExpect(jsonPath("$.code").value(200))
                // 创建者自己拿到的响应里也不回显凭据
                .andExpect(jsonPath("$.data.authHeader").doesNotExist());

        String memberToken = login(MEMBER);
        String list = mockMvc.perform(get("/v1/ai-tools").header("satoken", memberToken).header("X-App-Id", appId))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[*].name").value(hasItem("priv_read_" + SUF)))
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(list).doesNotContain("READ_SECRET").doesNotContain("authHeader");

        mockMvc.perform(post("/v1/knowledge/documents/status/batch").header("satoken", memberToken).header("X-App-Id", appId)
                        .contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(jsonPath("$.code").value(200));
    }

    /**
     * 2026-09-25 安全扫描 M1：停用 / 降级后旧 token 此前照用不误（角色存在会话里，降级要等对方自己重登）。
     * 单测里吊销服务是 mock，这里证明 Sa-Token 的注销真的让那个 token 失效。
     * 用独立的临时账号：MEMBER 被其他用例共用，停用它会连坐。
     */
    @Test
    void 停用或降级后旧token立即失效() throws Exception {
        Tenant tenant = tenantRepository.findByCode(TENANT_CODE).orElseThrow();
        String adminToken = login(ADMIN);
        for (String action : new String[]{"disable", "demote"}) {
            String name = "priv_victim_" + action + "_" + SUF;
            UserRole role = action.equals("demote") ? UserRole.TENANT_ADMIN : UserRole.TENANT_MEMBER;
            Long id = userRepository.save(user(tenant, name, role)).getId();
            String victimToken = login(name);
            mockMvc.perform(get("/v1/auth/me").header("satoken", victimToken))
                    .andExpect(jsonPath("$.code").value(200));

            String body = action.equals("disable") ? "{\"enabled\":false}" : "{\"role\":\"TENANT_MEMBER\"}";
            mockMvc.perform(put("/v1/users/" + id).header("satoken", adminToken)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(jsonPath("$.code").value(200));

            mockMvc.perform(get("/v1/auth/me").header("satoken", victimToken))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void 冻结租户后该租户用户的旧token立即失效() throws Exception {
        Tenant t = tenantRepository.save(Tenant.builder().name("IT租户-freeze").code("IT_freeze_" + SUF)
                .status(TenantStatus.ACTIVE).plan(TenantPlan.FREE).build());
        String name = "priv_frozen_" + SUF;
        userRepository.save(user(t, name, UserRole.TENANT_MEMBER));
        String token = login(name);

        String platformToken = login("admin", "admin123");
        mockMvc.perform(post("/v1/tenants/" + t.getId() + "/toggle-status").header("satoken", platformToken))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("FROZEN"));

        mockMvc.perform(get("/v1/auth/me").header("satoken", token))
                .andExpect(status().isUnauthorized());
    }

    /** 登录页未登录就要读它；漏进 Sa-Token 免登录名单的话登录页一加载就被 401 踢走 */
    /**
     * 套餐自然到期没有任何操作触发，此前已登录用户能一直用到 token 过期。到期任务把它补上。
     * 单测里仓储是 mock，这里验证真实的 JPQL 条件更新与仓储方法上的事务确实落库，且旧 token 真的失效。
     */
    @Test
    void 套餐到期后定时任务停用租户并让旧token失效() throws Exception {
        Tenant t = tenantRepository.save(Tenant.builder().name("IT租户-expiry").code("IT_expiry_" + SUF)
                .status(TenantStatus.ACTIVE).plan(TenantPlan.FREE).build());
        String name = "priv_expiry_" + SUF;
        userRepository.save(user(t, name, UserRole.TENANT_MEMBER));
        String token = login(name); // 先登录，再让套餐「自然」到期——登录时的到期检查拦不到这个 token
        t.setExpireTime(java.time.LocalDateTime.now().minusMinutes(1));
        tenantRepository.save(t);

        tenantExpiryScheduler.expireOverdueTenants();

        org.assertj.core.api.Assertions.assertThat(tenantRepository.findById(t.getId()).orElseThrow().getStatus())
                .isEqualTo(TenantStatus.EXPIRED);
        mockMvc.perform(get("/v1/auth/me").header("satoken", token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 登录页公开配置免登录可读() throws Exception {
        mockMvc.perform(get("/v1/auth/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.selfRegisterEnabled").value(true)); // test profile 打开
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

    /**
     * 2026-09-30 安全审查 M3：本人改密必须验证当前密码，走真实请求（TenantContext 的 userId 由拦截器填入）。
     * 旧密码错 → 400 且密码不变；对 → 新密码能登、旧密码登不上；经通用更新端点改自己的密码 → 400。
     */
    @Test
    void 本人改密必须验证当前密码() throws Exception {
        String name = "priv_selfpwd_" + SUF;
        Tenant tenant = tenantRepository.findByCode(TENANT_CODE).orElseThrow();
        Long id = userRepository.save(user(tenant, name, UserRole.TENANT_MEMBER)).getId();
        String token = login(name);

        mockMvc.perform(put("/v1/users/" + id).header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"NoOldPass1\"}"))
                .andExpect(jsonPath("$.code").value(400));
        mockMvc.perform(post("/v1/users/me/password").header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"wrong-guess\",\"newPassword\":\"NewPass456\"}"))
                .andExpect(jsonPath("$.code").value(400));
        login(name); // 原密码仍然有效

        mockMvc.perform(post("/v1/users/me/password").header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"pwd123456\",\"newPassword\":\"NewPass456\"}"))
                .andExpect(jsonPath("$.code").value(200));
        login(name, "NewPass456");
        mockMvc.perform(post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + name + "\",\"password\":\"pwd123456\"}"))
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(200)));
    }

    private String login(String username) throws Exception {
        return login(username, "pwd123456");
    }

    private String login(String username, String password) throws Exception {
        String body = mockMvc.perform(post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();
        int at = body.indexOf("\"token\":\"") + 9;
        return body.substring(at, body.indexOf('"', at));
    }
}
