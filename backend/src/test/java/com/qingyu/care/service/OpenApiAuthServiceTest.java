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
}
