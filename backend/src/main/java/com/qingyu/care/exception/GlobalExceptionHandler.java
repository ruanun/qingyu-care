package com.qingyu.care.exception;

import tools.jackson.databind.ObjectMapper;
import com.qingyu.care.dto.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;

import java.io.IOException;
import java.util.stream.Collectors;

/**
 * 全局异常处理器
 *
 * <p>⚠️ 真机冒烟发现（MM-9）：本类原先各处理器直接返回 {@code ApiResponse<Void>} POJO，
 * 交给 Spring 走 {@code HttpMessageConverter} 内容协商写响应体——但 {@code /v1/chat/stream}
 * 同样是 SSE 端点，真实客户端一律声明 {@code Accept: text/event-stream}，而 POJO 只有
 * Jackson 的 JSON 转换器能处理，与该 Accept 头不匹配，写响应体这一步本身会抛
 * {@code HttpMediaTypeNotAcceptableException}。管理端这条路径没有 OpenApiExceptionHandler
 * 那种"二次失败又撞上 Sa-Token 未豁免的 /error 转发"的连锁（请求本身已登录），但结果同样
 * 糟糕：Spring 找不到任何可用表示，最终响应是**空 body + 500**，比开放 API 那边的
 * "HTML+堆栈+错误状态码"更难排查（客户端连错误原因都看不到）。
 * <p>修法：所有处理器改为 {@code void} + 直接用 {@code HttpServletResponse} 手写响应体
 * （同 {@code OpenApiExceptionHandler}/{@code OpenApiAuthInterceptor#writeError} 既有模式），
 * 彻底绕开内容协商。HTTP 状态码与响应体 {@code code} 字段的既有语义保持不变——业务异常
 * （BusinessException）沿用本仓库"HTTP 200 + body.code"的既定约定，其余按各自原 @ResponseStatus
 * 语义写真实状态码。
 */
@RestControllerAdvice
@RequiredArgsConstructor
@Slf4j
public class GlobalExceptionHandler {

    private final ObjectMapper objectMapper;

    /**
     * 处理业务异常。本仓库既定约定：HTTP 200（不改状态码）+ body.code 携带真实业务码，
     * 与 OpenApiExceptionHandler#handleBusiness 的"真实 HTTP 状态码"路线刻意不同——
     * 管理端前端 axios 拦截器按 body.code 分流，不看 HTTP 状态码。
     */
    @ExceptionHandler(BusinessException.class)
    public void handleBusinessException(BusinessException e, HttpServletResponse response) throws IOException {
        log.warn("业务异常: code={}, message={}", e.getCode(), e.getMessage());
        writeError(response, 200, e.getCode(), e.getMessage());
    }

    /**
     * 处理资源不存在异常 (404)
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public void handleResourceNotFoundException(ResourceNotFoundException e, HttpServletResponse response)
            throws IOException {
        log.warn("资源不存在: {}", e.getMessage());
        writeError(response, 404, 404, e.getMessage());
    }

    /**
     * 处理非法操作异常 (400)
     */
    @ExceptionHandler(InvalidOperationException.class)
    public void handleInvalidOperationException(InvalidOperationException e, HttpServletResponse response)
            throws IOException {
        log.warn("非法操作: {}", e.getMessage());
        writeError(response, 400, 400, e.getMessage());
    }

    /**
     * 处理参数校验异常 (@Valid)
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public void handleValidationException(MethodArgumentNotValidException e, HttpServletResponse response)
            throws IOException {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        log.warn("参数校验失败: {}", message);
        writeError(response, 400, 400, message);
    }

    /**
     * 处理请求参数缺失
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public void handleMissingParamException(MissingServletRequestParameterException e, HttpServletResponse response)
            throws IOException {
        String message = "缺少必需参数: " + e.getParameterName();
        log.warn(message);
        writeError(response, 400, 400, message);
    }

    /**
     * 处理参数类型不匹配
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public void handleTypeMismatchException(MethodArgumentTypeMismatchException e, HttpServletResponse response)
            throws IOException {
        String message = "参数类型错误: " + e.getName();
        log.warn(message);
        writeError(response, 400, 400, message);
    }

    /**
     * 处理 JSON 解析错误
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public void handleJsonParseException(HttpMessageNotReadableException e, HttpServletResponse response)
            throws IOException {
        log.warn("JSON 解析失败: {}", e.getMessage());
        writeError(response, 400, 400, "请求体格式错误");
    }

    /**
     * 处理 Sa-Token 未登录异常
     */
    @ExceptionHandler(NotLoginException.class)
    public void handleNotLoginException(NotLoginException e, HttpServletResponse response) throws IOException {
        log.warn("未登录或 Token 失效: {}", e.getMessage());
        writeError(response, 401, 401, "请重新登录: " + e.getMessage());
    }

    /**
     * 处理 Sa-Token 无权限异常
     */
    @ExceptionHandler({NotPermissionException.class, NotRoleException.class})
    public void handleForbiddenException(Exception e, HttpServletResponse response) throws IOException {
        log.warn("权限不足: {}", e.getMessage());
        writeError(response, 403, 403, "权限不足");
    }

    /**
     * 处理所有未捕获的 RuntimeException
     */
    @ExceptionHandler(RuntimeException.class)
    public void handleRuntimeException(RuntimeException e, HttpServletResponse response) throws IOException {
        log.error("运行时异常: ", e);
        writeError(response, 500, 500, "服务器内部错误: " + e.getMessage());
    }

    /**
     * 兜底：处理所有其他异常
     */
    @ExceptionHandler(Exception.class)
    public void handleException(Exception e, HttpServletResponse response) throws IOException {
        log.error("未知异常: ", e);
        writeError(response, 500, 500, "服务器内部错误");
    }

    /** 绕开 HttpMessageConverter 内容协商，直接写 JSON——见类注释。 */
    private void writeError(HttpServletResponse response, int httpStatus, int bodyCode, String message)
            throws IOException {
        response.setStatus(httpStatus);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.error(bodyCode, message)));
    }
}
