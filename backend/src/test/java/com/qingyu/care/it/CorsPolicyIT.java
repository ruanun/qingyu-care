package com.qingyu.care.it;

import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CORS 策略必须分两套：开放 API / widget.js 允许任意来源但<b>不带凭据</b>；管理端只允许
 * 配置的前端来源。
 *
 * <p>安全审查 H6（2026-09-03）：此前 {@code /**} 一律 {@code allowedOriginPatterns("*")} +
 * {@code allowCredentials(true)}，叠加 Sa-Token 默认写 cookie，任意网页都能带着受害者的 cookie
 * 读写管理 API（Chrome 靠 SameSite 默认值挡住，Firefox 可直接利用）。管理前端与后端同源
 * （nginx 反代 / Vite 代理），管理端 CORS 默认应当为空。
 *
 * @author ruan
 */
@TestPropertySource(properties = "qingyu.care.cors.allowed-origins=http://admin.example.com")
class CorsPolicyIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 管理端接口拒绝未配置的来源() throws Exception {
        mockMvc.perform(post("/v1/auth/login")
                        .header("Origin", "http://evil.example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void 管理端接口放行配置的来源() throws Exception {
        mockMvc.perform(post("/v1/auth/login")
                        .header("Origin", "http://admin.example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://admin.example.com"));
    }

    @Test
    void 开放API预检放行任意来源但不带凭据() throws Exception {
        mockMvc.perform(options("/open/v1/widget/config")
                        .header("Origin", "http://any-host.example.org")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "x-app-id"))
                .andExpect(status().is2xxSuccessful())
                .andExpect(header().exists("Access-Control-Allow-Origin"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
    }
}
