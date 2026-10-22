package com.qingyu.care.it;

import com.qingyu.care.entity.Application;
import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.enums.SessionStatus;
import com.qingyu.care.repository.ChatSessionRepository;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 用户 inbox SSE 端点会话归属校验（闭合跨租户会话窃听 IDOR）：
 * 持合法 app 凭据者若拿到其他 app/租户的 sessionId，订阅 /open/v1/sessions/{sessionId}/inbox
 * 必须被拒绝，不能建流窃听他人会话的消息/typing/presence 事件。
 *
 * @author ruan
 */
@Transactional
class OpenInboxOwnershipIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ChatSessionRepository sessionRepository;

    @Test
    void 跨租户订阅他人会话inbox返回404_归属校验拦截() throws Exception {
        // 受害者：租户B下的一个会话
        // tenantId/appId 定义在 BaseTenantEntity（父类），@Builder 不含父类字段，改用 setter 赋值
        Application victimApp = seedApp("inboxVictim", "http://victim.example.com");
        ChatSession victimSession = new ChatSession();
        victimSession.setSessionId("sess_victim_" + System.nanoTime());
        victimSession.setTenantId(victimApp.getTenant().getId());
        victimSession.setAppId(victimApp.getId());
        victimSession.setStatus(SessionStatus.AI_PROCESSING);
        victimSession = sessionRepository.save(victimSession);

        // 攻击者：持有租户A自己的合法 app 凭据，但拿到了受害者的 sessionId
        Application attackerApp = seedApp("inboxAttacker", "http://attacker.example.com");

        mockMvc.perform(get("/open/v1/sessions/{sessionId}/inbox", victimSession.getSessionId())
                        .header("X-App-Id", attackerApp.getAppKey())
                        .header("X-Api-Key", attackerApp.getAppSecret()))
                .andExpect(status().isNotFound());
    }
}
