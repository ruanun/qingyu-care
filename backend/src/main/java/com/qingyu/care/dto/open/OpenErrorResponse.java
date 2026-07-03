package com.qingyu.care.dto.open;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;

/**
 * 开放 API 统一错误体：{ "error": { "code": 401, "message": "..." } }
 */
@Data
@RequiredArgsConstructor
public class OpenErrorResponse {
    private final Error error;

    @Data
    @AllArgsConstructor
    public static class Error {
        private int code;
        private String message;
    }

    public static OpenErrorResponse of(int code, String message) {
        return new OpenErrorResponse(new Error(code, message));
    }
}
