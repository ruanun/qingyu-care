package com.qingyu.care.scheduler;

import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.entity.SysConfig;
import com.qingyu.care.enums.OwnerScope;
import com.qingyu.care.enums.SessionStatus;
import com.qingyu.care.repository.ChatSessionRepository;
import com.qingyu.care.repository.SysConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import com.qingyu.care.event.WebhookDomainEvent;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话超时调度的租户级阈值覆盖单测（tenant.session.timeout.minutes）。
 * 有覆盖的租户按自身阈值单独扫描；无覆盖/非法值回落全局默认。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class SessionTimeoutSchedulerTest {

    @Mock ChatSessionRepository sessionRepository;
    @Mock SysConfigRepository sysConfigRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    @InjectMocks SessionTimeoutScheduler scheduler;

    @BeforeEach
    void init() {
        ReflectionTestUtils.setField(scheduler, "timeoutMinutes", 30);
    }

    private SysConfig cfg(Long tenantId, String value) {
        return SysConfig.builder()
                .scope(OwnerScope.TENANT)
                .tenantId(tenantId)
                .configKey(SessionTimeoutScheduler.TENANT_TIMEOUT_KEY)
                .configValue(value)
                .build();
    }

    /**
     * 此前超时只改库不发事件，订阅了 session.ended 的接收方那边，超时的会话永远停在「进行中」。
     * 超时与坐席手动结束发同一个事件，用 trigger=timeout 区分。
     */
    @Test
    void 超时会话逐个发布session_ended事件_trigger为timeout() {
        when(sysConfigRepository.findByScopeAndConfigKey(OwnerScope.TENANT, SessionTimeoutScheduler.TENANT_TIMEOUT_KEY))
                .thenReturn(List.of());
        ChatSession s1 = ChatSession.builder().id(1L).sessionId("sid-1").build();
        s1.setTenantId(7L);
        s1.setAppId(70L);
        ChatSession s2 = ChatSession.builder().id(2L).sessionId("sid-2").build();
        s2.setTenantId(8L);
        s2.setAppId(80L);
        when(sessionRepository.findTimeoutSessions(anyList(), any())).thenReturn(List.of(s1, s2));

        scheduler.checkTimeoutSessions();

        ArgumentCaptor<WebhookDomainEvent> captor = ArgumentCaptor.forClass(WebhookDomainEvent.class);
        verify(eventPublisher, org.mockito.Mockito.times(2)).publishEvent(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(e -> {
            assertThat(e.getEventType()).isEqualTo("session.ended");
            assertThat(e.getTrigger()).isEqualTo("timeout");
        });
        // 事件必须带会话自己的租户与应用：调度线程没有 TenantContext，分发器全靠事件上的归属过滤订阅
        assertThat(captor.getAllValues()).extracting(WebhookDomainEvent::getSessionId, WebhookDomainEvent::getAppId, WebhookDomainEvent::getTenantId)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("sid-1", 70L, 7L), org.assertj.core.groups.Tuple.tuple("sid-2", 80L, 8L));
    }

    @Test
    void 无租户覆盖_走全局默认单查询() {
        when(sysConfigRepository.findByScopeAndConfigKey(OwnerScope.TENANT, SessionTimeoutScheduler.TENANT_TIMEOUT_KEY))
                .thenReturn(List.of());
        when(sessionRepository.findTimeoutSessions(anyList(), any())).thenReturn(List.of());

        scheduler.checkTimeoutSessions();

        verify(sessionRepository).findTimeoutSessions(anyList(), any());
        verify(sessionRepository, never()).findTimeoutSessionsExcludingTenants(anyList(), any(), anyList());
    }

    @Test
    void 有租户覆盖_默认批排除覆盖租户_覆盖租户按自身阈值扫描() {
        when(sysConfigRepository.findByScopeAndConfigKey(OwnerScope.TENANT, SessionTimeoutScheduler.TENANT_TIMEOUT_KEY))
                .thenReturn(List.of(cfg(5L, "10")));
        when(sessionRepository.findTimeoutSessionsExcludingTenants(anyList(), any(), eq(List.of(5L))))
                .thenReturn(List.of());
        when(sessionRepository.findTimeoutSessionsForTenant(anyList(), any(), eq(5L)))
                .thenReturn(List.of());

        scheduler.checkTimeoutSessions();

        ArgumentCaptor<LocalDateTime> defaultCutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> tenantCutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(sessionRepository).findTimeoutSessionsExcludingTenants(anyList(), defaultCutoff.capture(), eq(List.of(5L)));
        verify(sessionRepository).findTimeoutSessionsForTenant(anyList(), tenantCutoff.capture(), eq(5L));
        verify(sessionRepository, never()).findTimeoutSessions(anyList(), any());

        // 覆盖租户阈值 10 分钟 vs 全局默认 30 分钟 → 两个 cutoff 相差 20 分钟
        assertThat(Duration.between(defaultCutoff.getValue(), tenantCutoff.getValue()).toMinutes()).isEqualTo(20);
    }

    @Test
    void 非法或空覆盖值_忽略并回落全局默认() {
        when(sysConfigRepository.findByScopeAndConfigKey(OwnerScope.TENANT, SessionTimeoutScheduler.TENANT_TIMEOUT_KEY))
                .thenReturn(List.of(cfg(5L, "abc"), cfg(6L, " "), cfg(7L, "-5"), cfg(null, "10")));
        when(sessionRepository.findTimeoutSessions(anyList(), any())).thenReturn(List.of());

        scheduler.checkTimeoutSessions();

        // 全部覆盖值非法 → 覆盖表为空，仍走单一默认查询
        verify(sessionRepository).findTimeoutSessions(anyList(), any());
        verify(sessionRepository, never()).findTimeoutSessionsForTenant(anyList(), any(), any());
    }

    @Test
    void 命中超时会话_批量置为TIMEOUT() {
        when(sysConfigRepository.findByScopeAndConfigKey(OwnerScope.TENANT, SessionTimeoutScheduler.TENANT_TIMEOUT_KEY))
                .thenReturn(List.of());
        ChatSession session = mock(ChatSession.class);
        when(session.getId()).thenReturn(9L);
        when(sessionRepository.findTimeoutSessions(anyList(), any())).thenReturn(List.of(session));
        when(sessionRepository.batchUpdateStatus(eq(List.of(9L)), eq(SessionStatus.TIMEOUT), any())).thenReturn(1);

        scheduler.checkTimeoutSessions();

        verify(sessionRepository).batchUpdateStatus(eq(List.of(9L)), eq(SessionStatus.TIMEOUT), any());
    }
}
