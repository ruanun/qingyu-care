package com.qingyu.care.service;

import com.qingyu.care.entity.ChatStrategy;
import com.qingyu.care.repository.AbExperimentRepository;
import com.qingyu.care.repository.ChatStrategyRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * getActiveStrategy 的作用域解析：经 {@link ConfigScopeResolver} 显式按有效 (tenantId, appId)
 * 收窄取自有默认策略，避免平台管理员绕过 Hibernate @Filter 时 NonUniqueResultException。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class ChatStrategyServiceTest {

    @Mock
    private ChatStrategyRepository repository;

    @Mock
    private AbExperimentRepository abExperimentRepository;

    @Mock
    private ConfigScopeResolver configScopeResolver;

    @InjectMocks
    private ChatStrategyService service;

    @Test
    void getActiveStrategy_有有效作用域_按tenantId_appId取自有默认策略() {
        when(configScopeResolver.resolve())
                .thenReturn(new ConfigScopeResolver.Scope(4L, 3L));
        ChatStrategy own = ChatStrategy.builder().name("租户4自有").scenario("default").build();
        when(repository.findActiveDefaultScoped("default", 4L, 3L))
                .thenReturn(java.util.List.of(own));

        ChatStrategy result = service.getActiveStrategy("default");

        assertThat(result.getName()).isEqualTo("租户4自有");
        verify(repository, never()).findPlatformDefaultByScenario(anyString());
    }

    @Test
    void getActiveStrategy_作用域无租户_跳过自有直落平台默认() {
        when(configScopeResolver.resolve())
                .thenReturn(new ConfigScopeResolver.Scope(null, 3L));
        ChatStrategy platform = ChatStrategy.builder().name("平台默认").scenario("default").build();
        when(repository.findPlatformDefaultByScenario("default"))
                .thenReturn(java.util.Optional.of(platform));

        ChatStrategy result = service.getActiveStrategy("default");

        assertThat(result.getName()).isEqualTo("平台默认");
        verify(repository, never()).findActiveDefaultScoped(anyString(), any(), any());
    }

    @Test
    void getActiveStrategy_自有多行_findFirst不抛NonUnique() {
        when(configScopeResolver.resolve())
                .thenReturn(new ConfigScopeResolver.Scope(4L, 3L));
        when(repository.findActiveDefaultScoped("default", 4L, 3L))
                .thenReturn(java.util.List.of(
                        ChatStrategy.builder().name("第一").scenario("default").build(),
                        ChatStrategy.builder().name("第二").scenario("default").build()));

        ChatStrategy result = service.getActiveStrategy("default");

        assertThat(result.getName()).isEqualTo("第一");   // ORDER BY id → findFirst
    }
}
