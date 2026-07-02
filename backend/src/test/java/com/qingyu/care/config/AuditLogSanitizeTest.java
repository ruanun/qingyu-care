package com.qingyu.care.config;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审计日志脱敏逻辑单元测试（不加载 Spring 上下文）。
 */
class AuditLogSanitizeTest {

    @Test
    void masksApiKeyAndPassword() {
        String detail = AuditLogAspect.sanitize(
                "{name=x, apiKey=sk-secret-123, password=abc, priority=1}");
        assertThat(detail).doesNotContain("sk-secret-123");
        assertThat(detail).doesNotContain("abc");
        assertThat(detail).contains("name=x");
        assertThat(detail).contains("priority=1");
    }

    @Test
    void truncatesToMaxLength() {
        String big = "x".repeat(2000);
        assertThat(AuditLogAspect.sanitize(big).length()).isLessThanOrEqualTo(1000);
    }

    @Test
    void handlesNull() {
        assertThat(AuditLogAspect.sanitize(null)).isEqualTo("");
    }

    @Test
    void masksMultipleSensitiveFields() {
        String detail = AuditLogAspect.sanitize("password=abc, secret=xyz");
        assertThat(detail).doesNotContain("abc");
        assertThat(detail).doesNotContain("xyz");
        assertThat(detail).contains("password=***");
        assertThat(detail).contains("secret=***");
    }

    @Test
    void masksSensitiveFieldBeforeClosingParen() {
        String detail = AuditLogAspect.sanitize("AiModelConfig(name=x, apiKey=sk-9)");
        assertThat(detail).doesNotContain("sk-9");
        assertThat(detail).contains("apiKey=***");
        // 结尾的右括号必须保留（验证正则终止符含 ) 的修复）
        assertThat(detail).endsWith(")");
    }
}
