package com.qingyu.care.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code ReadReceiptRequest.upToMessageId} 的 Bean Validation 校验。
 *
 * <p>⚠️ 本类不能靠直调 {@code ChatController.markAgentRead(...)} 来测——Bean Validation 发生在
 * Spring MVC 的参数绑定阶段（{@code @Valid} 触发的 {@code MethodArgumentNotValidException}），
 * 纯 Mockito 直调控制器方法完全绕过这一层，测出来的是「方法体逻辑」而非「请求真的会被挡住」。
 * 故这里直接用 {@link Validator} 校验 DTO 实例——这是能在不起 Spring 容器（不经 MockMvc）的
 * 前提下真正覆盖到这层校验的最简单方式。
 *
 * <p>回归背景：{@code upToMessageId} 为 null 且是该会话读者组合的首次回执时，请求若绕过校验
 * 直接进 service，会在 {@code ReadReceiptService.doMarkRead} 里一路走到
 * {@code currentOrDefault} 兜底也返回 null，而 {@code ChatServiceImpl.markAgentRead} 返回类型是
 * 原始 {@code long}——拆箱 NPE，对客户端表现为 500 而非语义正确的 400。{@code @NotNull} +
 * 控制器方法参数上的 {@code @Valid} 让这类请求在进 service 之前就被挡下。
 *
 * @author ruan
 */
class ReadReceiptRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @Test
    @DisplayName("upToMessageId 为 null 时校验产生违规，非 null 时通过")
    void upToMessageId为null时产生校验违规_非null时通过() {
        ReadReceiptRequest missing = new ReadReceiptRequest();
        // 不设置 upToMessageId，字段无初始化器，此刻应为 null

        Set<ConstraintViolation<ReadReceiptRequest>> violations = validator.validate(missing);

        assertThat(violations).isNotEmpty();
        assertThat(violations)
                .extracting(ConstraintViolation::getPropertyPath)
                .extracting(Object::toString)
                .contains("upToMessageId");

        ReadReceiptRequest present = new ReadReceiptRequest();
        present.setUpToMessageId(42L);

        assertThat(validator.validate(present)).isEmpty();
    }
}
