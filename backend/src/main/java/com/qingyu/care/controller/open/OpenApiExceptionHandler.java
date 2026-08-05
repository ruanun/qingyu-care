package com.qingyu.care.controller.open;

import tools.jackson.databind.ObjectMapper;
import com.qingyu.care.dto.open.OpenErrorResponse;
import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.exception.OpenApiException;
import com.qingyu.care.exception.ResourceNotFoundException;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;

/**
 * 开放 API 异常处理：仅作用于 controller.open 包，返回标准 HTTP 状态码，
 * 优先级最高，避免落到内部 GlobalExceptionHandler 的 body-code 处理。
 *
 * <p>⚠️ 真机冒烟发现（MM-9）：本类原先返回 {@code ResponseEntity<OpenErrorResponse>}，交给 Spring
 * 走 {@code HttpMessageConverter} 内容协商——但 {@code /open/v1/chat/stream} 的真实客户端
 * （浏览器 fetch 手动实现 SSE 解析，同 EventSource 惯例）一律声明 {@code Accept: text/event-stream}，
 * 而 {@code OpenErrorResponse} 只有 Jackson 的 JSON 转换器能处理、产出 {@code application/json}，
 * 与该 Accept 头不匹配——写响应体这一步本身会抛 {@code HttpMediaTypeNotAcceptableException}。
 * 这是"处理异常时处理过程自己又抛异常"的二次失败：Spring 见本 advice 处理失败，放弃它、
 * 继续尝试下一个 resolver，最终把请求兜到 Tomcat 的 {@code /error} 内部转发——而这条内部路径
 * **不在** Sa-Token 的登录豁免名单里（豁免名单只列了 {@code /open/v1/**} 等业务路径），
 * {@code SaInterceptor} 对它照常执行登录校验、抛出 {@code NotLoginException}；这第二个异常
 * 同样撞上一模一样的内容协商失败，一路兜到 Tomcat 原生错误页——响应变成 HTML + 完整 Java 堆栈
 * + 一个跟真正原因（附件不存在/会话过期）毫无关系的 401（源自 {@code NotLoginException}
 * 处理器的 {@code @ResponseStatus}，只是恰好是这条失败链条里最后一次被尝试的状态码）。
 * <p>修法：不再经 {@code ResponseEntity} + {@code HttpMessageConverter}，直接用
 * {@code HttpServletResponse} 手写响应体（同 {@code OpenApiAuthInterceptor#writeError} 既有
 * 模式）——彻底绕开内容协商，任何 {@code Accept} 头下都能正确写出 JSON，不会触发上述连锁失败。
 */
@RestControllerAdvice(basePackages = "com.qingyu.care.controller.open")
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
@Slf4j
public class OpenApiExceptionHandler {

    private final ObjectMapper objectMapper;

    @ExceptionHandler(OpenApiException.class)
    public void handleOpenApi(OpenApiException e, HttpServletResponse response) throws IOException {
        writeError(response, e.getStatus(), e.getMessage());
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public void handleNotFound(ResourceNotFoundException e, HttpServletResponse response) throws IOException {
        writeError(response, HttpStatus.NOT_FOUND.value(), e.getMessage());
    }

    /**
     * 业务校验异常（如反馈值非法）：GlobalExceptionHandler 把它封装成 200+body-code，
     * 但本处理器专为开放 API 走真实 HTTP 状态码——BusinessException 自带的 code 本身
     * 就是合法 HTTP 状态码（见其构造用法，默认 400），直接复用，不落到下方通用 500 兜底。
     */
    @ExceptionHandler(BusinessException.class)
    public void handleBusiness(BusinessException e, HttpServletResponse response) throws IOException {
        writeError(response, e.getCode(), e.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, IllegalArgumentException.class})
    public void handleBadRequest(Exception e, HttpServletResponse response) throws IOException {
        writeError(response, HttpStatus.BAD_REQUEST.value(), "请求参数错误");
    }

    @ExceptionHandler(Exception.class)
    public void handleGeneric(Exception e, HttpServletResponse response) throws IOException {
        log.error("[OpenAPI] 未捕获异常", e);
        writeError(response, HttpStatus.INTERNAL_SERVER_ERROR.value(), "服务器内部错误");
    }

    /** 绕开 HttpMessageConverter 内容协商，直接写 JSON——见类注释。 */
    private void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(OpenErrorResponse.of(status, message)));
    }
}
