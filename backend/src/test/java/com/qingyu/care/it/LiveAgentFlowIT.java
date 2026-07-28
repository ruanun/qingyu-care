package com.qingyu.care.it;

import cn.dev33.satoken.secure.BCrypt;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qingyu.care.entity.Application;
import com.qingyu.care.entity.ChatMessage;
import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.entity.Tenant;
import com.qingyu.care.entity.User;
import com.qingyu.care.enums.MessageRole;
import com.qingyu.care.enums.SessionStatus;
import com.qingyu.care.enums.UserRole;
import com.qingyu.care.repository.ChatMessageRepository;
import com.qingyu.care.repository.ChatSessionRepository;
import com.qingyu.care.repository.UserRepository;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真人坐席端到端全流程 IT：坐席登录态下依次走 handover → accept → agent/reply →
 * return-to-ai 真实 HTTP 请求链，断言会话状态在每一步的流转，以及最终消息序列。
 *
 * <p>全走 {@code /v1/chat/**}（MockMvc 路径无 {@code /api} 前缀）。SSE 收发的验证由
 * {@link RealtimeSseIT} 在 bean 层完成——本类不对无限流端点发起 MockMvc 请求，避免挂起
 * 测试进程。
 *
 * <p>类级 {@code @Transactional}：MockMvc 请求在同一测试线程内同步执行，各请求内部的
 * {@code @Transactional} 服务方法以 REQUIRED 传播加入外层测试事务，测试结束整体回滚，
 * 免手工清理（范式同 {@link OpenInboxOwnershipIT}）。
 *
 * @author ruan
 */
@Transactional
class LiveAgentFlowIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ChatSessionRepository sessionRepository;
    @Autowired
    private ChatMessageRepository messageRepository;

    private final ObjectMapper om = new ObjectMapper();

    private String login(String username, String password) throws Exception {
        MvcResult r = mockMvc.perform(post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return om.readTree(r.getResponse().getContentAsString()).path("data").path("token").asText();
    }

    @Test
    void 坐席全流程handover接管回复交还AI状态流转与消息序列() throws Exception {
        Application app = seedApp("liveAgentFlow", "http://liveagentflow.example.com");
        Tenant tenant = app.getTenant();
        User agent = userRepository.save(User.builder()
                .username("live_agent_flow_it").password(BCrypt.hashpw("pw123456"))
                .nickname("坐席流转IT").role(UserRole.TENANT_MEMBER).tenant(tenant).enabled(true).build());

        String sessionId = "sess-live-agent-flow-it-" + System.nanoTime();
        ChatSession session = new ChatSession();
        session.setSessionId(sessionId);
        session.setTenantId(tenant.getId());
        session.setAppId(app.getId());
        session.setStatus(SessionStatus.AI_PROCESSING);
        session = sessionRepository.save(session);

        // 预置一条用户消息，作为消息序列的第一环
        ChatMessage userMsg = ChatMessage.builder()
                .session(session).role(MessageRole.USER).content("用户提问：能帮我转人工吗？").build();
        messageRepository.save(userMsg);

        String token = login("live_agent_flow_it", "pw123456");

        // 1. handover：AI_PROCESSING → WAITING_AGENT
        mockMvc.perform(post("/v1/chat/session/" + sessionId + "/handover").header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(get("/v1/chat/session/" + sessionId).header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("WAITING_AGENT"));

        // 2. accept：WAITING_AGENT → AGENT_PROCESSING，agentId=登录坐席
        mockMvc.perform(post("/v1/chat/session/" + sessionId + "/accept").header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("AGENT_PROCESSING"))
                .andExpect(jsonPath("$.data.agentId").value(agent.getId()));

        // 3. agent/reply：落一条 ASSISTANT 消息（人工回复，绕过 AI）
        String agentReplyContent = "人工客服回复：已为您处理，请稍等";
        String body = "{\"sessionId\":\"" + sessionId + "\",\"message\":\"" + agentReplyContent + "\"}";
        mockMvc.perform(post("/v1/chat/agent/reply").header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 4. return-to-ai：AGENT_PROCESSING → AI_PROCESSING，agentId 清空
        mockMvc.perform(post("/v1/chat/session/" + sessionId + "/return-to-ai").header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(get("/v1/chat/session/" + sessionId).header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("AI_PROCESSING"))
                .andExpect(jsonPath("$.data.agentId").value(nullValue()));

        // 5. 消息序列：[0] 预置的用户提问，[1] 人工回复（agentReply 落库，角色为 ASSISTANT）
        mockMvc.perform(get("/v1/chat/session/" + sessionId + "/messages").header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].role").value("USER"))
                .andExpect(jsonPath("$.data[0].content").value("用户提问：能帮我转人工吗？"))
                .andExpect(jsonPath("$.data[1].role").value("ASSISTANT"))
                .andExpect(jsonPath("$.data[1].content").value(agentReplyContent));
    }
}
