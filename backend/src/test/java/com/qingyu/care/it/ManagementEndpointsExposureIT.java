package com.qingyu.care.it;

import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 基线配置下 Swagger 与 actuator/metrics 不可匿名访问（安全审查 M9，2026-09-03）。
 * 它们在 Sa-Token 的登录豁免名单里，所以「关掉」是唯一可靠的手段：基线 application.yml
 * 关闭，只有 dev profile 再打开。test profile 不含 dev，故这里看到的就是生产形态。
 *
 * @author ruan
 */
class ManagementEndpointsExposureIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 基线下Swagger文档不可访问() throws Exception {
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound());
        mockMvc.perform(get("/swagger-ui.html")).andExpect(status().isNotFound());
    }

    @Test
    void 基线下只暴露health() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/metrics")).andExpect(status().isNotFound());
    }
}
