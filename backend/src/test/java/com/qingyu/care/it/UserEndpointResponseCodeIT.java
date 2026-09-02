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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code UserController} 的失败响应必须是「HTTP 200 + 有语义的 body.code」。
 *
 * <p>这些失败是正常 {@code return} 而非抛异常，不经 {@code GlobalExceptionHandler}，code 得在
 * 调用点自己给。图省事用 {@code ApiResponse.error(String)} 就会拿到默认的 <b>500</b>——而本类
 * 覆盖的三种失败没有一处是服务器内部错误。12 处曾经全是 500，2026-09-02 订正。
 *
 * <p>⚠️ 每条都同时断言 {@code status().isOk()}：HTTP 状态码是前端 axios 的分流依据，一旦有人
 * 把这些 {@code return} 改成抛异常，4xx 会让 axios 直接 reject，调用方那句
 * {@code if (res.code !== 200)} 根本走不到——而 body.code 断言对此毫无感知，只钉 code 会漏掉
 * 这半边。
 *
 * @author ruan
 */
class UserEndpointResponseCodeIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    @DisplayName("非管理员建用户返回 403 而不是 500")
    void 权限不足返回403() throws Exception {
        seed("code_member", "code_member_user", UserRole.TENANT_MEMBER);

        mockMvc.perform(post("/v1/users")
                        .header("satoken", login("code_member_user"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"whoever\",\"password\":\"pwd123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @DisplayName("查不存在的用户返回 404 而不是 500")
    void 资源不存在返回404() throws Exception {
        seed("code_admin404", "code_admin404_user", UserRole.TENANT_ADMIN);

        mockMvc.perform(get("/v1/users/99999999")
                        .header("satoken", login("code_admin404_user")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @DisplayName("缺必填字段返回 400 而不是 500")
    void 输入非法返回400() throws Exception {
        seed("code_admin400", "code_admin400_user", UserRole.TENANT_ADMIN);

        mockMvc.perform(post("/v1/users")
                        .header("satoken", login("code_admin400_user"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\",\"password\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    private void seed(String tenantSuffix, String username, UserRole role) {
        Tenant tenant = tenantRepository.save(Tenant.builder()
                .name("IT租户-" + tenantSuffix).code("IT_" + tenantSuffix)
                .status(TenantStatus.ACTIVE).plan(TenantPlan.FREE).build());
        userRepository.save(User.builder()
                .username(username)
                .password(BCrypt.hashpw("pwd123456"))
                .nickname(username)
                .role(role)
                .tenant(tenant)
                .enabled(true)
                .build());
    }

    private String login(String username) throws Exception {
        String body = mockMvc.perform(post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"pwd123456\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        int at = body.indexOf("\"token\":\"") + 9;
        return body.substring(at, body.indexOf('"', at));
    }
}
