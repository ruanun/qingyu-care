package com.qingyu.care.it;

import com.qingyu.care.entity.Application;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 开放 API 双路径鉴权：服务端 Api-Key、浏览器 Origin 白名单。 */
@Transactional
class OpenApiAuthIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void widget配置_白名单Origin通过_非白名单403_未知appKey401() throws Exception {
        Application app = seedApp("auth", "http://it.example.com");

        mockMvc.perform(get("/open/v1/widget/config")
                        .header("X-App-Id", app.getAppKey())
                        .header("Origin", "http://it.example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appName").value(app.getName()));

        mockMvc.perform(get("/open/v1/widget/config")
                        .header("X-App-Id", app.getAppKey())
                        .header("Origin", "http://evil.example.com"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/open/v1/widget/config")
                        .header("X-App-Id", "app_不存在")
                        .header("Origin", "http://it.example.com"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 对话端点_无凭据401_错secret401() throws Exception {
        Application app = seedApp("auth2", "http://it.example.com");

        mockMvc.perform(post("/open/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"hi\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/open/v1/chat/completions")
                        .header("X-App-Id", app.getAppKey()).header("X-Api-Key", "错的")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"hi\"}"))
                .andExpect(status().isUnauthorized());
    }
}
