package com.qingyu.care.it;

import cn.dev33.satoken.secure.BCrypt;
import com.qingyu.care.entity.Tenant;
import com.qingyu.care.entity.User;
import com.qingyu.care.enums.TenantPlan;
import com.qingyu.care.enums.TenantStatus;
import com.qingyu.care.enums.UserRole;
import com.qingyu.care.repository.TenantRepository;
import com.qingyu.care.repository.UserRepository;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 用户名是<b>全局唯一键</b>，按它查找必须跨租户，任何时候都不能被 {@code tenantFilter} 挡掉。
 *
 * <p>⚠️ {@code TenantDataAspect} 为此做过豁免，但判据是「未登录」（`!isLoggedIn()`）——
 * 那是个<b>间接代理量</b>，它假设「登录/注册总发生在未登录态」。该假设在**上一个账号的
 * satoken 还在**时不成立（浏览器 cookie 残留最常见，`localStorage.clear()` 清不掉它）：
 * 此时 {@code TenantInterceptor} 已把上一个租户的 tenantId 写进上下文，过滤器照常生效，
 * 于是三条唯一键查找全部错位。三种症状各不相同、都指向错误的排查方向：
 * <ul>
 *   <li>登录 → 查不到别租户的账号 → 「用户名或密码错误」，让人一路去查密码；</li>
 *   <li>注册 → 重名查不出来 → 放行到 INSERT 撞唯一约束 → HTTP 500 且把原始 SQL
 *       与约束名回给客户端，而正确响应是 400「用户名已存在」；</li>
 *   <li>租户管理员建用户 → 同注册。</li>
 * </ul>
 *
 * <p>故这四条测试分两半：前两条守「未登录态」（原有覆盖），后三条守「带着别人的登录态」
 * ——缺陷正藏在后者这一半里。
 *
 * @author ruan
 */
class LoginIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    void 平台管理员可登录并拿到token() throws Exception {
        mockMvc.perform(post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.role").value("PLATFORM_ADMIN"));
    }

    @Test
    void 未登录访问受保护接口返回401() throws Exception {
        mockMvc.perform(get("/v1/applications"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 带着甲租户的登录态仍能登录乙租户的账号() throws Exception {
        seedTenantAdmin("xlogin_a", "xlogin_a_admin");
        seedTenantAdmin("xlogin_b", "xlogin_b_admin");

        String tokenOfA = loginAndGetToken("xlogin_a_admin");

        // 关键：带着甲的 satoken 去登录乙的账号。过滤器若生效，findByUsername 会被加上
        // 甲的 tenant_id，查不到乙的账号，最终报「用户名或密码错误」——密码其实是对的。
        mockMvc.perform(post("/v1/auth/login")
                        .header("satoken", tokenOfA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"xlogin_b_admin\",\"password\":\"pwd123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.username").value("xlogin_b_admin"));
    }

    @Test
    void 带着甲租户的登录态注册乙租户已占用的用户名应报重名而非500() throws Exception {
        seedTenantAdmin("xreg_a", "xreg_a_admin");
        seedTenantAdmin("xreg_b", "xreg_b_admin");

        String tokenOfA = loginAndGetToken("xreg_a_admin");

        // 重名检查若被过滤掉，会放行到 INSERT 撞唯一约束：500 + 原始 SQL 泄漏给客户端。
        //
        // ⚠️ 这里断言 HTTP 400 而下面那条建用户断言 HTTP 200，不是笔误：本路径抛
        // InvalidOperationException，GlobalExceptionHandler 给它「真实 HTTP 状态码」（400）；
        // 而 UserController 是正常返回 ApiResponse.error(400, ...)（HTTP 200 + body.code）。
        // 两条路线在 GlobalExceptionHandler 的类注释里是刻意分开的，body.code 一致即可。
        mockMvc.perform(post("/v1/auth/register")
                        .header("satoken", tokenOfA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"xreg_b_admin\",\"password\":\"pwd123456\","
                                + "\"nickname\":\"重名\",\"tenantName\":\"重名企业\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("用户名已存在"));
    }

    @Test
    void 租户管理员建用户时重名检查须跨租户() throws Exception {
        seedTenantAdmin("xcreate_a", "xcreate_a_admin");
        seedTenantAdmin("xcreate_b", "xcreate_b_admin");

        String tokenOfA = loginAndGetToken("xcreate_a_admin");

        // 甲租户管理员在自己上下文里建用户，用户名却已被乙租户占用。
        mockMvc.perform(post("/v1/users")
                        .header("satoken", tokenOfA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"xcreate_b_admin\",\"password\":\"pwd123456\","
                                + "\"nickname\":\"重名\",\"role\":\"TENANT_MEMBER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("用户名已存在"));
    }

    private void seedTenantAdmin(String tenantSuffix, String username) {
        Tenant tenant = tenantRepository.save(Tenant.builder()
                .name("IT租户-" + tenantSuffix).code("IT_" + tenantSuffix)
                .status(TenantStatus.ACTIVE).plan(TenantPlan.FREE).build());
        userRepository.save(User.builder()
                .username(username)
                .password(BCrypt.hashpw("pwd123456"))
                .nickname(username)
                .role(UserRole.TENANT_ADMIN)
                .tenant(tenant)
                .enabled(true)
                .build());
    }

    private String loginAndGetToken(String username) throws Exception {
        String body = mockMvc.perform(post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"pwd123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();
        int at = body.indexOf("\"token\":\"") + 9;
        return body.substring(at, body.indexOf('"', at));
    }
}
