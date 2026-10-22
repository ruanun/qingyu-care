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

    @Test
    void masksAuthHeaderField() {
        String raw = "AiTool(name=query_order, authHeader=Authorization: Bearer abc123, timeoutMs=5000)";
        String out = AuditLogAspect.sanitize(raw);
        assertThat(out).contains("authHeader=***");
        assertThat(out).doesNotContain("Bearer abc123");
        assertThat(out).doesNotContain("abc123");
    }

    /** 2026-09-30 安全审查：值里带逗号时此前只遮到第一个逗号；secretKey= / token= 这类键名此前不匹配 */
    @Test
    void 值含逗号与变体键名一并遮住() {
        String out = AuditLogAspect.sanitize("{username=a, password=ab,cd, role=X}");
        assertThat(out).doesNotContain("cd").contains("role=X").contains("username=a");

        String out2 = AuditLogAspect.sanitize("Req(secretKey=s1, accessToken=t1, oldPassword=p1, name=n)");
        assertThat(out2).doesNotContain("s1").doesNotContain("t1").doesNotContain("p1").contains("name=n");
    }
}
