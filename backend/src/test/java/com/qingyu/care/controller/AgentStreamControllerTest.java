package com.qingyu.care.controller;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import com.qingyu.care.exception.ResourceNotFoundException;
import com.qingyu.care.service.ChatService;
import com.qingyu.care.service.realtime.RealtimeHub;
import com.qingyu.care.service.realtime.RealtimeNotifier;
import com.qingyu.care.service.realtime.RealtimePresenceRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 坐席「正在输入」事件必须先校验会话归属（安全审查 L8，2026-09-03）：此前任何坐席拿到
 * 会话 UUID 就能往任意租户的访客端推 typing。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class AgentStreamControllerTest {

    @Mock
    RealtimeHub realtimeHub;
    @Mock
    RealtimeNotifier realtimeNotifier;
    @Mock
    ObjectProvider<RealtimePresenceRegistry> presenceRegistryProvider;
    @Mock
    ChatService chatService;
    @InjectMocks
    AgentStreamController controller;

    private static MockedStatic<StpUtil> stubAgent() {
        SaSession session = mock(SaSession.class);
        when(session.get("role")).thenReturn("TENANT_MEMBER");
        MockedStatic<StpUtil> stp = mockStatic(StpUtil.class);
        stp.when(StpUtil::isLogin).thenReturn(true);
        stp.when(StpUtil::getSession).thenReturn(session);
        return stp;
    }

    @Test
    void 他租户的会话不推typing() {
        when(chatService.getSession("s-foreign")).thenThrow(new ResourceNotFoundException("会话", "s-foreign"));

        try (MockedStatic<StpUtil> ignored = stubAgent()) {
            assertThatThrownBy(() -> controller.agentTyping("s-foreign"))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
        verify(realtimeNotifier, never()).typing(anyString(), anyString(), anyString());
    }

    @Test
    void 本租户的会话正常推typing() {
        when(chatService.getSession("s-mine")).thenReturn(null);

        try (MockedStatic<StpUtil> ignored = stubAgent()) {
            controller.agentTyping("s-mine");
        }
        verify(realtimeNotifier).typing(any(), any(), any());
    }
}
