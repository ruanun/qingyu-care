package com.qingyu.care.controller;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import com.qingyu.care.config.TenantContext;
import com.qingyu.care.dto.ApiResponse;
import com.qingyu.care.dto.ApplicationUpdateRequest;
import com.qingyu.care.dto.ApplicationView;
import com.qingyu.care.dto.WebhookTestResult;
import com.qingyu.care.entity.Application;
import com.qingyu.care.entity.Tenant;
import com.qingyu.care.enums.UserRole;
import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.exception.InvalidOperationException;
import com.qingyu.care.repository.ApplicationRepository;
import com.qingyu.care.config.WebhookProperties;
import com.qingyu.care.repository.TenantRepository;
import com.qingyu.care.service.AuthService;
import com.qingyu.care.service.webhook.WebhookPayloadBuilder;
import com.qingyu.care.service.webhook.WebhookSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ApplicationController} 的角色门禁、密钥脱敏与回调地址校验。
 *
 * <p>安全审查 H4/H5（2026-09-03）：此前本控制器没有任何角色判断（{@code checkAppAccess}
 * 只比对租户），坐席能读到 {@code appSecret}/{@code webhookSecret}、改 Origin 白名单与
 * Webhook URL、重置密钥、删应用；{@code webhookUrl} 任填不校验；「测试 Webhook」把连接异常
 * 文本原样回显。
 *
 * <p>角色桩法同 {@code UserControllerTest}；{@code SecurityUtil.getRole()} 先调
 * {@code StpUtil.isLogin()}，不桩它会一律视为未登录（{@code UpdateRequestPropagationTest}
 * 里有同样的提醒）。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class ApplicationControllerTest {

    private static final long TENANT_ID = 9L;

    @Mock
    ApplicationRepository applicationRepository;
    @Mock
    TenantRepository tenantRepository;
    @Mock
    AuthService authService;
    @Mock
    WebhookSender webhookSender;
    @Mock
    WebhookPayloadBuilder webhookPayloadBuilder;
    @org.mockito.Spy
    WebhookProperties webhookProperties = new WebhookProperties();
    @InjectMocks
    ApplicationController controller;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static Application app() {
        return Application.builder().id(1L).name("云舟商城客服")
                .tenant(Tenant.builder().id(TENANT_ID).name("云舟科技").build())
                .appKey("app_abc").appSecret("SECRET_VALUE")
                .webhookUrl("http://8.8.8.8/hook").webhookSecret("WH_SECRET")
                .enabled(true).build();
    }

    private static MockedStatic<StpUtil> stubRole(UserRole role) {
        SaSession session = mock(SaSession.class);
        lenient().when(session.get("role")).thenReturn(role.name());
        MockedStatic<StpUtil> stp = mockStatic(StpUtil.class);
        stp.when(StpUtil::isLogin).thenReturn(true);
        stp.when(StpUtil::getSession).thenReturn(session);
        return stp;
    }

    @Test
    void 成员看应用列表拿不到密钥() {
        TenantContext.setTenantId(TENANT_ID);
        when(applicationRepository.findByTenantIdOrderByCreatedAtDesc(TENANT_ID)).thenReturn(List.of(app()));

        try (MockedStatic<StpUtil> ignored = stubRole(UserRole.TENANT_MEMBER)) {
            ApiResponse<List<ApplicationView>> resp = controller.getAll();

            ApplicationView v = resp.getData().get(0);
            assertThat(v.appKey()).isEqualTo("app_abc");
            assertThat(v.appSecret()).isNull();
            assertThat(v.webhookSecret()).isNull();
        }
    }

    @Test
    void 租户管理员看应用详情含密钥() {
        TenantContext.setTenantId(TENANT_ID);
        when(applicationRepository.findById(1L)).thenReturn(Optional.of(app()));

        try (MockedStatic<StpUtil> ignored = stubRole(UserRole.TENANT_ADMIN)) {
            ApiResponse<ApplicationView> resp = controller.getById(1L);

            assertThat(resp.getData().appSecret()).isEqualTo("SECRET_VALUE");
            assertThat(resp.getData().webhookSecret()).isEqualTo("WH_SECRET");
        }
    }

    /**
     * 变异实验：把任一写端点上的 {@code SecurityUtil.requireTenantAdmin()} 删掉，
     * 对应那一段断言转红。
     */
    @Test
    void 成员对应用的写操作一律403() {
        TenantContext.setTenantId(TENANT_ID);
        lenient().when(applicationRepository.findById(1L)).thenReturn(Optional.of(app()));
        ApplicationUpdateRequest update = new ApplicationUpdateRequest();
        update.setName("改名");

        try (MockedStatic<StpUtil> ignored = stubRole(UserRole.TENANT_MEMBER)) {
            assertThatThrownBy(() -> controller.update(1L, update)).as("update")
                    .isInstanceOf(BusinessException.class).hasMessageContaining("租户管理员");
            assertThatThrownBy(() -> controller.delete(1L)).as("delete")
                    .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> controller.regenerateSecret(1L)).as("regenerateSecret")
                    .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> controller.regenerateWebhookSecret(1L)).as("regenerateWebhookSecret")
                    .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> controller.testWebhook(1L)).as("testWebhook")
                    .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> controller.create(app())).as("create")
                    .isInstanceOf(BusinessException.class);
        }
        verify(applicationRepository, never()).save(any(Application.class));
        verify(applicationRepository, never()).deleteById(any());
    }

    @Test
    void 回调地址指向内网被拒绝() {
        TenantContext.setTenantId(TENANT_ID);
        when(applicationRepository.findById(1L)).thenReturn(Optional.of(app()));
        ApplicationUpdateRequest update = new ApplicationUpdateRequest();
        update.setWebhookUrl("http://127.0.0.1:6379/hook");

        try (MockedStatic<StpUtil> ignored = stubRole(UserRole.TENANT_ADMIN)) {
            assertThatThrownBy(() -> controller.update(1L, update))
                    .isInstanceOf(InvalidOperationException.class)
                    .hasMessageContaining("回调地址");
        }
        verify(applicationRepository, never()).save(any(Application.class));
    }

    @Test
    void 回调地址必须是http或https() {
        TenantContext.setTenantId(TENANT_ID);
        when(applicationRepository.findById(1L)).thenReturn(Optional.of(app()));
        ApplicationUpdateRequest update = new ApplicationUpdateRequest();
        update.setWebhookUrl("ftp://8.8.8.8/hook");

        try (MockedStatic<StpUtil> ignored = stubRole(UserRole.TENANT_ADMIN)) {
            assertThatThrownBy(() -> controller.update(1L, update))
                    .isInstanceOf(InvalidOperationException.class);
        }
        verify(applicationRepository, never()).save(any(Application.class));
    }

    /**
     * 真机冒烟发现（2026-09-03）：{@code allow-private-targets} 原先只作用于<b>出站</b>
     * （{@code RestClientWebhookHttpClient}），而落库校验这一关是硬拦的——开关打开后仍然配不上
     * 本地回调地址，开关等于半失效。集成测试发现不了：它们直接 {@code app.setWebhookUrl(...)}
     * 写库，绕过了控制器这一关。
     */
    @Test
    void 打开私网开关后可以保存本地回调地址但仍拦云元数据() {
        TenantContext.setTenantId(TENANT_ID);
        webhookProperties.setAllowPrivateTargets(true);
        Application existing = app();
        when(applicationRepository.findById(1L)).thenReturn(Optional.of(existing));
        lenient().when(applicationRepository.save(any(Application.class))).thenAnswer(i -> i.getArgument(0));

        ApplicationUpdateRequest local = new ApplicationUpdateRequest();
        local.setWebhookUrl("http://127.0.0.1:9999/hook");
        ApplicationUpdateRequest meta = new ApplicationUpdateRequest();
        meta.setWebhookUrl("http://169.254.169.254/latest/meta-data/");

        try (MockedStatic<StpUtil> ignored = stubRole(UserRole.TENANT_ADMIN)) {
            controller.update(1L, local);
            assertThat(existing.getWebhookUrl()).isEqualTo("http://127.0.0.1:9999/hook");

            assertThatThrownBy(() -> controller.update(1L, meta))
                    .as("云元数据：任何开关都不放行")
                    .isInstanceOf(InvalidOperationException.class);
        }
    }

    @Test
    void 合法的公网回调地址可以保存() {
        TenantContext.setTenantId(TENANT_ID);
        Application existing = app();
        when(applicationRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(applicationRepository.save(any(Application.class))).thenAnswer(i -> i.getArgument(0));
        ApplicationUpdateRequest update = new ApplicationUpdateRequest();
        update.setWebhookUrl("https://8.8.8.8/hook");

        try (MockedStatic<StpUtil> ignored = stubRole(UserRole.TENANT_ADMIN)) {
            controller.update(1L, update);
        }
        assertThat(existing.getWebhookUrl()).isEqualTo("https://8.8.8.8/hook");
    }

    /** 变异实验：把 testWebhook 的 catch 改回 {@code e.getMessage()}，本条转红。 */
    @Test
    void 测试Webhook失败时不回显连接异常文本() {
        TenantContext.setTenantId(TENANT_ID);
        when(applicationRepository.findById(1L)).thenReturn(Optional.of(app()));
        when(webhookPayloadBuilder.build(anyString(), anyString(), any(), any())).thenReturn("{}");
        when(webhookSender.send(anyString(), any(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("Connection refused: /10.0.0.5:6379"));

        try (MockedStatic<StpUtil> ignored = stubRole(UserRole.TENANT_ADMIN)) {
            ApiResponse<WebhookTestResult> resp = controller.testWebhook(1L);

            assertThat(resp.getData().delivered()).isFalse();
            assertThat(resp.getData().error()).isNotBlank().doesNotContain("10.0.0.5");
        }
    }
}
