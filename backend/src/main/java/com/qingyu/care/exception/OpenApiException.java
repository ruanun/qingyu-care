package com.qingyu.care.exception;

/**
 * 开放 API 专用异常，携带标准 HTTP 状态码。
 */
public class OpenApiException extends RuntimeException {
    private final int status;

    public OpenApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
