package com.qingyu.care.service;

import com.qingyu.care.entity.Application;
import com.qingyu.care.entity.Tenant;
import com.qingyu.care.exception.OpenApiException;
import com.qingyu.care.repository.ApplicationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.qingyu.care.enums.TenantStatus;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpenApiAuthServiceTest {

    @Mock ApplicationRepository applicationRepository;
    @InjectMocks OpenApiAuthService service;

    private Application app(String key, String secret, boolean enabled) {
        return Application.builder()
                .id(1L).name("测试应用").appKey(key).appSecret(secret).enabled(enabled)
                .tenant(Tenant.builder().id(9L).build())
                .build();
    }

    @Test
    void authenticate_returnsApp_whenCredentialsValid() {
        when(applicationRepository.findByAppKey("app_x")).thenReturn(Optional.of(app("app_x", "sec", true)));

        Application result = service.authenticate("app_x", "sec");

        assertThat(result.getId()).isEqualTo(1L);
    }

    @Test
    void authenticate_401_whenHeaderMissing() {
        assertThatThrownBy(() -> service.authenticate(null, "sec"))
                .isInstanceOf(OpenApiException.class)
                .satisfies(e -> assertThat(((OpenApiException) e).getStatus()).isEqualTo(401));
    }

    @Test
    void authenticate_401_whenHeaderBlank() {
        assertThatThrownBy(() -> service.authenticate("  ", "sec"))
                .isInstanceOf(OpenApiException.class)
                .satisfies(e -> assertThat(((OpenApiException) e).getStatus()).isEqualTo(401));
    }

    @Test
    void authenticate_401_whenAppKeyNotFound() {
        when(applicationRepository.findByAppKey("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.authenticate("nope", "sec"))
                .isInstanceOf(OpenApiException.class)
                .satisfies(e -> assertThat(((OpenApiException) e).getStatus()).isEqualTo(401));
    }

    @Test
    void authenticate_401_whenSecretMismatch() {
        when(applicationRepository.findByAppKey("app_x")).thenReturn(Optional.of(app("app_x", "right", true)));

        assertThatThrownBy(() -> service.authenticate("app_x", "wrong"))
                .isInstanceOf(OpenApiException.class)
                .satisfies(e -> assertThat(((OpenApiException) e).getStatus()).isEqualTo(401));
    }

    @Test
    void authenticate_403_whenAppDisabled() {
        when(applicationRepository.findByAppKey("app_x")).thenReturn(Optional.of(app("app_x", "sec", false)));

        assertThatThrownBy(() -> service.authenticate("app_x", "sec"))
                .isInstanceOf(OpenApiException.class)
                .satisfies(e -> assertThat(((OpenApiException) e).getStatus()).isEqualTo(403));
    }

    private Application appWithOrigins(String origins, boolean enabled) {
        return Application.builder()
                .id(1L).name("测试应用").appKey("app_x").appSecret("sec").enabled(enabled)
                .allowedOrigins(origins)
                .tenant(Tenant.builder().id(9L).build())
                .build();
    }

    @Test
    void authenticateByOrigin_returnsApp_whenOriginWhitelisted() {
        when(applicationRepository.findByAppKey("app_x"))
                .thenReturn(Optional.of(appWithOrigins("https://a.com, https://www.a.com", true)));

        Application result = service.authenticateByOrigin("app_x", "https://www.a.com");

        assertThat(result.getId()).isEqualTo(1L);
    }

    @Test
    void authenticateByOrigin_401_whenAppIdMissing() {
        assertThatThrownBy(() -> service.authenticateByOrigin(null, "https://a.com"))
                .isInstanceOf(OpenApiException.class)
                .satisfies(e -> assertThat(((OpenApiException) e).getStatus()).isEqualTo(401));
    }

    @Test
    void authenticateByOrigin_401_whenAppKeyNotFound() {
        when(applicationRepository.findByAppKey("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.authenticateByOrigin("nope", "https://a.com"))
                .isInstanceOf(OpenApiException.class)
                .satisfies(e -> assertThat(((OpenApiException) e).getStatus()).isEqualTo(401));
    }

    @Test
    void authenticateByOrigin_403_whenAppDisabled() {
        when(applicationRepository.findByAppKey("app_x"))
                .thenReturn(Optional.of(appWithOrigins("https://a.com", false)));

        assertThatThrownBy(() -> service.authenticateByOrigin("app_x", "https://a.com"))
                .isInstanceOf(OpenApiException.class)
                .satisfies(e -> assertThat(((OpenApiException) e).getStatus()).isEqualTo(403));
    }

    @Test
    void authenticateByOrigin_403_whenOriginsEmpty() {
        when(applicationRepository.findByAppKey("app_x"))
                .thenReturn(Optional.of(appWithOrigins("  ", true)));

        assertThatThrownBy(() -> service.authenticateByOrigin("app_x", "https://a.com"))
                .isInstanceOf(OpenApiException.class)
                .satisfies(e -> assertThat(((OpenApiException) e).getStatus()).isEqualTo(403));
    }

    @Test
    void authenticateByOrigin_403_whenOriginNotWhitelisted() {
        when(applicationRepository.findByAppKey("app_x"))
                .thenReturn(Optional.of(appWithOrigins("https://a.com", true)));

        assertThatThrownBy(() -> service.authenticateByOrigin("app_x", "https://evil.com"))
                .isInstanceOf(OpenApiException.class)
                .satisfies(e -> assertThat(((OpenApiException) e).getStatus()).isEqualTo(403));
    }

    @Test
    void authenticateByOrigin_403_whenOriginNull() {
        when(applicationRepository.findByAppKey("app_x"))
                .thenReturn(Optional.of(appWithOrigins("https://a.com", true)));

        assertThatThrownBy(() -> service.authenticateByOrigin("app_x", null))
                .isInstanceOf(OpenApiException.class)
                .satisfies(e -> assertThat(((OpenApiException) e).getStatus()).isEqualTo(403));
    }
    // ===== 安全审查 M6（2026-09-03）：开放 API 必须校验租户状态与到期时间 =====

    private Application appOf(Tenant tenant) {
        return Application.builder().id(1L).name("测试应用").appKey("app_x").appSecret("sec")
                .enabled(true).allowedOrigins("https://a.com").tenant(tenant).build();
    }

    @Test
    void authenticate_403_whenTenantFrozen() {
        Tenant frozen = Tenant.builder().id(9L).status(TenantStatus.FROZEN).build();
        when(applicationRepository.findByAppKey("app_x")).thenReturn(Optional.of(appOf(frozen)));

        assertThatThrownBy(() -> service.authenticate("app_x", "sec"))
                .isInstanceOf(OpenApiException.class)
                .satisfies(e -> assertThat(((OpenApiException) e).getStatus()).isEqualTo(403));
    }

    @Test
    void authenticateByOrigin_403_whenTenantExpired() {
        Tenant expired = Tenant.builder().id(9L).status(TenantStatus.ACTIVE)
                .expireTime(LocalDateTime.now().minusMinutes(1)).build();
        when(applicationRepository.findByAppKey("app_x")).thenReturn(Optional.of(appOf(expired)));

        assertThatThrownBy(() -> service.authenticateByOrigin("app_x", "https://a.com"))
                .isInstanceOf(OpenApiException.class)
                .satisfies(e -> assertThat(((OpenApiException) e).getStatus()).isEqualTo(403));
    }

    @Test
    void authenticateByOrigin_passes_whenTenantActiveAndNotExpired() {
        Tenant active = Tenant.builder().id(9L).status(TenantStatus.ACTIVE)
                .expireTime(LocalDateTime.now().plusDays(30)).build();
        when(applicationRepository.findByAppKey("app_x")).thenReturn(Optional.of(appOf(active)));

        assertThat(service.authenticateByOrigin("app_x", "https://a.com").getId()).isEqualTo(1L);
    }
}
