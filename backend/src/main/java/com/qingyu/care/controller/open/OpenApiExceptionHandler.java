package com.qingyu.care.controller.open;

import com.qingyu.care.dto.open.OpenErrorResponse;
import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.exception.OpenApiException;
import com.qingyu.care.exception.ResourceNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 开放 API 异常处理：仅作用于 controller.open 包，返回标准 HTTP 状态码，
 * 优先级最高，避免落到内部 GlobalExceptionHandler 的 body-code 处理。
 */
@RestControllerAdvice(basePackages = "com.qingyu.care.controller.open")
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class OpenApiExceptionHandler {

    @ExceptionHandler(OpenApiException.class)
    public ResponseEntity<OpenErrorResponse> handleOpenApi(OpenApiException e) {
        return ResponseEntity.status(e.getStatus())
                .body(OpenErrorResponse.of(e.getStatus(), e.getMessage()));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<OpenErrorResponse> handleNotFound(ResourceNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(OpenErrorResponse.of(404, e.getMessage()));
    }

    /**
     * 业务校验异常（如反馈值非法）：GlobalExceptionHandler 把它封装成 200+body-code，
     * 但本处理器专为开放 API 走真实 HTTP 状态码——BusinessException 自带的 code 本身
     * 就是合法 HTTP 状态码（见其构造用法，默认 400），直接复用，不落到下方通用 500 兜底。
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<OpenErrorResponse> handleBusiness(BusinessException e) {
        return ResponseEntity.status(e.getCode())
                .body(OpenErrorResponse.of(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, IllegalArgumentException.class})
    public ResponseEntity<OpenErrorResponse> handleBadRequest(Exception e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(OpenErrorResponse.of(400, "请求参数错误"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<OpenErrorResponse> handleGeneric(Exception e) {
        log.error("[OpenAPI] 未捕获异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(OpenErrorResponse.of(500, "服务器内部错误"));
    }
}
