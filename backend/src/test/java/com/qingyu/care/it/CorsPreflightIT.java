package com.qingyu.care.it;

import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 浏览器跨域预检 OPTIONS 必须 2xx，否则开放 API 全被拦。 */
class CorsPreflightIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 开放API的CORS预检返回2xx() throws Exception {
        mockMvc.perform(options("/open/v1/widget/config")
                        .header("Origin", "http://it.example.com")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "x-app-id"))
                .andExpect(status().is2xxSuccessful());
    }
}
