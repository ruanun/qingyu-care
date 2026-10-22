package com.qingyu.care.dto.open;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;

/**
 * 开放 API 统一错误体：{ "error": { "code": 401, "message": "...", "reason": "..."? } }
 * reason 为可空业务标识（如 QUOTA_EXCEEDED），供客户端区分同状态码下的不同原因。
 */
@Data
@RequiredArgsConstructor
public class OpenErrorResponse {
    private final Error error;

    @Data
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Error {
        private int code;
        private String message;
        private String reason;
    }

    public static OpenErrorResponse of(int code, String message) {
        return new OpenErrorResponse(new Error(code, message, null));
    }

    public static OpenErrorResponse of(int code, String message, String reason) {
        return new OpenErrorResponse(new Error(code, message, reason));
    }
}
