package com.qingyu.care.dto.pkg;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PackageInstallRequest#toString()} 绝不能吐出变量取值。
 *
 * <p>它会流经 {@code AuditLogAspect} 落进 {@code sys_audit_log.detail}，而那里的脱敏是<b>按字段名</b>
 * 匹配的、认不出自定义变量名。改回 Lombok 默认 toString 是完全静默的——没有任何人会注意到租户的
 * 第三方密钥被记进了平台管理员可全局查询的审计表。
 *
 * @author ruan
 */
class PackageInstallRequestTest {

    @Test
    @DisplayName("toString 只输出变量名，绝不输出取值")
    void toString不泄漏变量取值() {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("WEATHER_HOST", "api.qweather.com");
        variables.put("WEATHER_AUTH", "Authorization: Bearer sk-super-secret-value");

        PackageInstallRequest request = new PackageInstallRequest();
        request.setVariables(variables);

        String text = request.toString();
        assertThat(text).contains("WEATHER_HOST").contains("WEATHER_AUTH");
        assertThat(text)
                .as("变量取值绝不能出现在 toString 里——它会原样落进审计表")
                .doesNotContain("sk-super-secret-value")
                .doesNotContain("api.qweather.com");
    }

    @Test
    void variables为null时toString不抛() {
        assertThat(new PackageInstallRequest().toString()).contains("[]");
    }
}
