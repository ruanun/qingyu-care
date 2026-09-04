package com.qingyu.care.dto;

import com.qingyu.care.dto.open.OpenChatRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 单条消息长度上限（安全审查 L7，2026-09-03）：{@code message} 此前只有 {@code @NotBlank}，
 * 一条超长消息会整段进 prompt、整段落库。
 *
 * @author ruan
 */
class ChatMessageLengthValidationTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void 开放对话消息超过8000字被拒绝() {
        OpenChatRequest req = new OpenChatRequest();
        req.setMessage("a".repeat(8001));

        assertThat(VALIDATOR.validate(req)).isNotEmpty();
    }

    @Test
    void 开放对话消息8000字以内通过() {
        OpenChatRequest req = new OpenChatRequest();
        req.setMessage("a".repeat(8000));

        assertThat(VALIDATOR.validate(req)).isEmpty();
    }

    @Test
    void 管理端对话消息超过8000字被拒绝() {
        ChatRequest req = ChatRequest.builder().message("a".repeat(8001)).build();

        assertThat(VALIDATOR.validate(req)).isNotEmpty();
    }
}
