package com.qingyu.care.config;

import com.qingyu.care.entity.AiTool;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 共享行凭据脱敏助手单测。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class SharedCredentialMaskerTest {

    @Mock
    private EntityManager entityManager;

    @InjectMocks
    private SharedCredentialMasker masker;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private AiTool tool(Long tenantId, Long appId, String authHeader) {
        AiTool t = AiTool.builder().name("t").description("d").urlTemplate("http://x").build();
        t.setTenantId(tenantId);
        t.setAppId(appId);
        t.setAuthHeader(authHeader);
        return t;
    }

    @Test
    void 租户上下文读平台共享行时凭据被清空() {
        TenantContext.setTenantId(7L);
        AiTool shared = tool(null, null, "Bearer platform-secret");

        masker.mask(List.of(shared), AiTool::getTenantId, AiTool::getAppId, t -> t.setAuthHeader(null));

        assertThat(shared.getAuthHeader()).isNull();
    }

    @Test
    void 租户上下文读自有行时凭据保留() {
        TenantContext.setTenantId(7L);
        AiTool own = tool(7L, null, "Bearer my-secret");

        masker.mask(List.of(own), AiTool::getTenantId, AiTool::getAppId, t -> t.setAuthHeader(null));

        assertThat(own.getAuthHeader()).isEqualTo("Bearer my-secret");
    }

    @Test
    void 平台上下文读共享行时凭据保留() {
        // 平台管理员登录后 TenantContext.tenantId 恒为 null
        AiTool shared = tool(null, null, "Bearer platform-secret");

        masker.mask(List.of(shared), AiTool::getTenantId, AiTool::getAppId, t -> t.setAuthHeader(null));

        assertThat(shared.getAuthHeader()).isEqualTo("Bearer platform-secret");
    }

    @Test
    void 脱敏前必须先detach否则脏检查会把null刷回库() {
        TenantContext.setTenantId(7L);
        AiTool shared = tool(null, null, "Bearer platform-secret");

        masker.mask(List.of(shared), AiTool::getTenantId, AiTool::getAppId, t -> t.setAuthHeader(null));

        verify(entityManager).detach(shared);
    }

    @Test
    void 不脱敏的行不应被detach() {
        TenantContext.setTenantId(7L);
        AiTool own = tool(7L, null, "Bearer my-secret");

        masker.mask(List.of(own), AiTool::getTenantId, AiTool::getAppId, t -> t.setAuthHeader(null));

        verify(entityManager, never()).detach(own);
    }
}
