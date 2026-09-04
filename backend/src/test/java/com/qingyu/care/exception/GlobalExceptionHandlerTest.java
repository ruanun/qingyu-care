package com.qingyu.care.exception;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全局异常处理器不得把内部异常文本回给客户端。
 *
 * <p>安全审查 M2（2026-09-03）：{@code handleRuntimeException} 原先拼
 * {@code "服务器内部错误: " + e.getMessage()}，把 SQL 语句、约束名、表名、第三方 SDK 的报错
 * 原样送到公网——CLAUDE.md 里记录的「撞唯一约束 → 500 且回显原始 SQL」正源于此。
 *
 * @author ruan
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(new ObjectMapper());

    @Test
    void 运行时异常不回显异常消息() throws IOException {
        MockHttpServletResponse resp = new MockHttpServletResponse();

        handler.handleRuntimeException(new RuntimeException(
                "ERROR: duplicate key value violates unique constraint \"uk_sys_user_username\""), resp);

        assertThat(resp.getStatus()).isEqualTo(500);
        String body = resp.getContentAsString();
        assertThat(body).doesNotContain("uk_sys_user_username").doesNotContain("duplicate key");
        assertThat(body).contains("\"code\":500");
    }

    /** 参数类异常单独归 400，且同样不回显 JDK/内部异常文本（如 Enum.valueOf 带的类名）。 */
    @Test
    void 非法参数异常归400且不回显异常消息() throws IOException {
        MockHttpServletResponse resp = new MockHttpServletResponse();

        handler.handleIllegalArgumentException(new IllegalArgumentException(
                "No enum constant com.qingyu.care.enums.UserRole.SUPERUSER"), resp);

        assertThat(resp.getStatus()).isEqualTo(400);
        assertThat(resp.getContentAsString()).doesNotContain("com.qingyu.care").contains("\"code\":400");
    }
}
