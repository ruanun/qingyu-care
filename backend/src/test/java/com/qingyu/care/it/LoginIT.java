package com.qingyu.care.it;

import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 未登录态下 findByUsername 不得被租户过滤挡掉（否则谁都登不上）。 */
class LoginIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mockMvc;

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
}
