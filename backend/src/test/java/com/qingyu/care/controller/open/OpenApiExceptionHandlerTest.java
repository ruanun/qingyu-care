package com.qingyu.care.controller.open;

import com.qingyu.care.dto.open.OpenErrorResponse;
import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.exception.OpenApiException;
import com.qingyu.care.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiExceptionHandlerTest {

    private final OpenApiExceptionHandler handler = new OpenApiExceptionHandler();

    @Test
    void mapsOpenApiExceptionToItsStatus() {
        ResponseEntity<OpenErrorResponse> resp =
                handler.handleOpenApi(new OpenApiException(429, "太频繁"));
        assertThat(resp.getStatusCode().value()).isEqualTo(429);
        assertThat(resp.getBody().getError().getCode()).isEqualTo(429);
        assertThat(resp.getBody().getError().getMessage()).isEqualTo("太频繁");
    }

    @Test
    void mapsResourceNotFoundTo404() {
        ResponseEntity<OpenErrorResponse> resp =
                handler.handleNotFound(new ResourceNotFoundException("会话", "s1"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void mapsBusinessExceptionToItsOwnCode() {
        // 回归：曾因未被显式处理落入 handleGeneric 兜底误判为 500（ChatFeedbackService
        // 非法反馈值场景），本处理器须直接复用 BusinessException 自带的 code（默认 400）。
        ResponseEntity<OpenErrorResponse> resp =
                handler.handleBusiness(new BusinessException("feedback 仅允许 1（赞）或 -1（踩）"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().getError().getCode()).isEqualTo(400);
        assertThat(resp.getBody().getError().getMessage()).isEqualTo("feedback 仅允许 1（赞）或 -1（踩）");
    }

    @Test
    void mapsBadRequestTo400() {
        ResponseEntity<OpenErrorResponse> resp =
                handler.handleBadRequest(new IllegalArgumentException("x"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().getError().getCode()).isEqualTo(400);
    }

    @Test
    void mapsGenericTo500() {
        ResponseEntity<OpenErrorResponse> resp =
                handler.handleGeneric(new RuntimeException("boom"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
