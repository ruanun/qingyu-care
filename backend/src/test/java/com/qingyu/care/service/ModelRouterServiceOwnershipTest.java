package com.qingyu.care.service;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.entity.AiModelConfig;
import com.qingyu.care.enums.OwnerScope;
import com.qingyu.care.exception.ResourceNotFoundException;
import com.qingyu.care.repository.AiModelConfigRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * ModelRouterService.getById 的租户归属校验单测。
 * getById 走 findById（不受 @Filter 保护），若不校验归属，已登录租户可枚举 id
 * 跨租户读到他人模型配置——含明文 apiKey，是最高危泄露点。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class ModelRouterServiceOwnershipTest {

    @Mock AiModelConfigRepository repository;
    @InjectMocks ModelRouterService service;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private AiModelConfig ownedBy(Long tenantId) {
        AiModelConfig config = AiModelConfig.builder()
                .name("他租户模型").provider("openai").modelId("gpt-4o")
                .apiKey("sk-secret").scenario("default").enabled(true)
                .scope(OwnerScope.TENANT).build();
        config.setTenantId(tenantId);
        return config;
    }

    @Test
    void getById_他租户模型_按不存在处理() {
        when(repository.findById(1L)).thenReturn(Optional.of(ownedBy(9L)));
        TenantContext.setTenantId(2L);

        assertThatThrownBy(() -> service.getById(1L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getById_本租户模型_正常返回() {
        when(repository.findById(1L)).thenReturn(Optional.of(ownedBy(2L)));
        TenantContext.setTenantId(2L);

        assertThat(service.getById(1L).getApiKey()).isEqualTo("sk-secret");
    }

    @Test
    void getById_平台共享模型_租户按id读取拒绝_防apiKey泄露() {
        // 实体含明文 apiKey，读路径也用写级守卫：租户对共享模型的继承走场景查询，
        // 不需要按 id 读平台行；放行即等于把平台凭据交给租户
        when(repository.findById(1L)).thenReturn(Optional.of(ownedBy(null)));
        TenantContext.setTenantId(2L);

        assertThatThrownBy(() -> service.getById(1L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getById_平台共享模型_平台管理员可读() {
        // 平台管理员上下文 tenantId 恒为 null，管理共享模型池（PlatformController）需经此路径
        when(repository.findById(1L)).thenReturn(Optional.of(ownedBy(null)));

        assertThat(service.getById(1L).getApiKey()).isEqualTo("sk-secret");
    }

    @Test
    void deleteModel_他租户模型_按不存在处理_不落删除() {
        // deleteById 直连主键同样绕 @Filter，删除路径必须经归属校验
        when(repository.findById(1L)).thenReturn(Optional.of(ownedBy(9L)));
        TenantContext.setTenantId(2L);

        assertThatThrownBy(() -> service.deleteModel(1L))
                .isInstanceOf(ResourceNotFoundException.class);
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never())
                .delete(org.mockito.ArgumentMatchers.any());
    }
}
