package com.qingyu.care.controller;

import com.qingyu.care.enums.OwnerScope;
import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.service.ConfigService;
import com.qingyu.care.service.captcha.CaptchaService;
import com.qingyu.care.service.captcha.TurnstileVerifier;
import com.qingyu.care.util.SecurityUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 平台配置里的凭据（Turnstile Secret Key）只写不读，以及保存前的验证码配置校验。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class PlatformConfigControllerTest {

    @Mock ConfigService configService;
    @Mock CaptchaService captchaService;
    @InjectMocks PlatformConfigController controller;

    MockedStatic<SecurityUtil> security;

    @BeforeEach
    void setUp() {
        security = mockStatic(SecurityUtil.class);
    }

    @AfterEach
    void tearDown() {
        security.close();
    }

    @Test
    void 读取时Secret打码_未设置返回空串_其余原样() {
        Map<String, String> db = new LinkedHashMap<>();
        db.put(CaptchaService.TURNSTILE_SECRET_KEY, "0x4AAA-real-secret");
        db.put(CaptchaService.TURNSTILE_SITE_KEY, "0x4AAA-site");
        when(configService.getAll(OwnerScope.PLATFORM, null)).thenReturn(db);

        Map<String, String> out = controller.get().getData();

        assertThat(out).containsEntry(CaptchaService.TURNSTILE_SECRET_KEY, PlatformConfigController.SECRET_MASK)
                .containsEntry(CaptchaService.TURNSTILE_SITE_KEY, "0x4AAA-site");
        assertThat(out.values()).doesNotContain("0x4AAA-real-secret");

        db.put(CaptchaService.TURNSTILE_SECRET_KEY, "");
        assertThat(controller.get().getData()).containsEntry(CaptchaService.TURNSTILE_SECRET_KEY, "");
    }

    /**
     * 编辑表单不回显凭据：留空或原样回传掩码都必须当「不修改」，否则保存一次别的配置就把 secret 清掉或改成 ******。
     */
    @Test
    @SuppressWarnings("unchecked")
    void 保存时Secret留空或回传掩码都不覆盖() {
        for (String untouched : new String[]{"", "  ", PlatformConfigController.SECRET_MASK}) {
            Map<String, String> in = new LinkedHashMap<>();
            in.put(CaptchaService.TURNSTILE_SECRET_KEY, untouched);
            in.put("platform.announcement", "hi");

            controller.update(in);

            ArgumentCaptor<Map<String, String>> saved = ArgumentCaptor.forClass(Map.class);
            verify(configService, org.mockito.Mockito.atLeastOnce()).setAll(eq(OwnerScope.PLATFORM), eq(null), saved.capture());
            assertThat(saved.getValue()).doesNotContainKey(CaptchaService.TURNSTILE_SECRET_KEY)
                    .containsEntry("platform.announcement", "hi");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void 保存时填了新Secret才写入() {
        Map<String, String> in = new LinkedHashMap<>(Map.of(CaptchaService.TURNSTILE_SECRET_KEY, "new-secret"));

        controller.update(in);

        ArgumentCaptor<Map<String, String>> saved = ArgumentCaptor.forClass(Map.class);
        verify(configService).setAll(eq(OwnerScope.PLATFORM), eq(null), saved.capture());
        assertThat(saved.getValue()).containsEntry(CaptchaService.TURNSTILE_SECRET_KEY, "new-secret");
    }

    @Test
    void 验证码配置校验不通过时整批不落库() {
        doThrow(new BusinessException(400, "启用 Cloudflare Turnstile 需先填写 Site Key 与 Secret Key"))
                .when(captchaService).validateConfigUpdate(anyMap());

        assertThatThrownBy(() -> controller.update(new LinkedHashMap<>(Map.of(CaptchaService.MODE_KEY, "TURNSTILE"))))
                .isInstanceOf(BusinessException.class);
        verify(configService, never()).setAll(any(), any(), anyMap());
    }

    /** 前端表单里的 secret 若带着掩码提交上来，不能把掩码当成 secret 发给 Cloudflare */
    @Test
    void 测试Turnstile时掩码当作未填() {
        MockHttpServletRequest http = new MockHttpServletRequest();
        http.setRemoteAddr("203.0.113.7");
        when(captchaService.testTurnstile("tok", null, "203.0.113.7"))
                .thenReturn(new TurnstileVerifier.Result(true, List.of(), "a.com"));

        var r = controller.testTurnstile(Map.of("token", "tok", "secretKey", PlatformConfigController.SECRET_MASK), http);

        assertThat(r.getData().success()).isTrue();
    }
}
