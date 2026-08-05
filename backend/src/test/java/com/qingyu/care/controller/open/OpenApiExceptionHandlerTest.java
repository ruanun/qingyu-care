package com.qingyu.care.controller.open;

import tools.jackson.databind.ObjectMapper;
import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.exception.OpenApiException;
import com.qingyu.care.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⚠️ 真机冒烟发现（MM-9）：这里刻意用 {@link MockHttpServletResponse} 而不是断言返回的
 * {@code ResponseEntity}——本类不再经 Spring 的 HttpMessageConverter 内容协商写响应体，
 * 直接手写 {@code HttpServletResponse}，正是为了绕开 SSE 客户端声明
 * {@code Accept: text/event-stream} 时内容协商失败、连锁兜到 Tomcat 原生错误页的坑
 * （见 OpenApiExceptionHandler 类注释）。断言里额外钉死 contentType 恒为
 * application/json——不因请求方的 Accept 头而改变，这正是本次修复的核心。
 */
class OpenApiExceptionHandlerTest {

    private final OpenApiExceptionHandler handler = new OpenApiExceptionHandler(new ObjectMapper());

    @Test
    void mapsOpenApiExceptionToItsStatus() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handleOpenApi(new OpenApiException(429, "太频繁"), response);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(response.getContentAsString()).contains("\"code\":429").contains("太频繁");
    }

    @Test
    void mapsResourceNotFoundTo404() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handleNotFound(new ResourceNotFoundException("会话", "s1"), response);

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
    }

    @Test
    void mapsBusinessExceptionToItsOwnCode() throws Exception {
        // 回归：曾因未被显式处理落入 handleGeneric 兜底误判为 500（ChatFeedbackService
        // 非法反馈值场景），本处理器须直接复用 BusinessException 自带的 code（默认 400）。
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handleBusiness(new BusinessException("feedback 仅允许 1（赞）或 -1（踩）"), response);

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("\"code\":400")
                .contains("feedback 仅允许 1（赞）或 -1（踩）");
    }

    @Test
    void mapsBadRequestTo400() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handleBadRequest(new IllegalArgumentException("x"), response);

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("\"code\":400");
    }

    @Test
    void mapsGenericTo500() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handleGeneric(new RuntimeException("boom"), response);

        assertThat(response.getStatus()).isEqualTo(500);
    }

    /**
     * 本次修复的核心断言：写响应体不再依赖 HttpMessageConverter 内容协商，
     * 即便 MockHttpServletResponse 上"模拟"了一个 Accept: text/event-stream 请求场景
     * （本类现在压根不读 Accept 头），contentType 依旧恒为 application/json。
     */
    @Test
    void 响应contentType恒为json_不受Accept头影响() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handleNotFound(new ResourceNotFoundException("附件不存在: 999999"), response);

        assertThat(response.getContentType()).isEqualTo("application/json;charset=UTF-8");
    }
}
