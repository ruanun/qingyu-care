package com.qingyu.care.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 坐席端点角色门禁：未登录 401（Sa-Token 拦截，未过滤器链即挡下）；非坐席角色（平台管理员）
 * requireAgent 抛 BusinessException(403,...)。
 * 注：本仓库 GlobalExceptionHandler#handleBusinessException 未标 @ResponseStatus，
 * 故 BusinessException 走的是 HTTP 200 + 响应体 code=403 的"业务态 403"语义（与
 * requirePlatformAdmin/requireTenantAdmin 等既有守卫完全一致），并非真实 HTTP 403 状态码；
 * 这里按该既有约定断言，而非字面 HTTP 状态。
 * 平台管理员账号由 AuthService#init 在应用启动时兜底播种（admin/admin123），无需本类自行播种。
 *
 * @author ruan
 */
class AgentEndpointAuthIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper om = new ObjectMapper();

    private String login(String username, String password) throws Exception {
        MvcResult r = mockMvc.perform(post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return om.readTree(r.getResponse().getContentAsString()).path("data").path("token").asText();
    }

    @Test
    void 未登录访问坐席端点返回401() throws Exception {
        mockMvc.perform(get("/v1/chat/agents/online"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 平台管理员非坐席身份访问坐席端点返回业务态403() throws Exception {
        String token = login("admin", "admin123");

        mockMvc.perform(get("/v1/chat/agents/online").header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("无权限：该操作仅限本租户坐席"));
    }
}
