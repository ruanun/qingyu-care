package com.qingyu.care.it;

import com.qingyu.care.entity.Application;
import com.qingyu.care.entity.ChatMessage;
import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.enums.MessageRole;
import com.qingyu.care.enums.SessionStatus;
import com.qingyu.care.repository.ChatMessageRepository;
import com.qingyu.care.repository.ChatSessionRepository;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 开放反馈端点：双路径鉴权（API Key / Origin）+ 跨会话拒绝 + 跨应用拒绝 + 非法值拒绝。
 * 断言的 HTTP 状态按实际异常映射调整（Step 0 已核对，且核对中发现 controller.open 包
 * 由 {@link com.qingyu.care.controller.open.OpenApiExceptionHandler} 以
 * @Order(HIGHEST_PRECEDENCE) 优先于 GlobalExceptionHandler 生效，走真实 HTTP 状态码而非
 * 200+body-code）：ResourceNotFoundException → 真 404；BusinessException 原先未被
 * OpenApiExceptionHandler 显式处理、落入通用 Exception 兜底误判为 500——已在本任务中补上
 * 专属 handler（直接复用 BusinessException 自带的 code，默认 400）纠正为真 400。
 *
 * @author ruan
 */
class ChatFeedbackIT extends AbstractPostgresIT {

    @Autowired MockMvc mockMvc;
    @Autowired ChatSessionRepository sessionRepository;
    @Autowired ChatMessageRepository messageRepository;

    private Application app;
    private ChatSession session;
    private ChatMessage aiMsg;

    @BeforeEach
    void seed() {
        app = seedApp("fb" + System.nanoTime(), "http://fb.example.com");
        session = ChatSession.builder()
                .sessionId("fb-session-" + System.nanoTime())
                .userId("u").status(SessionStatus.AI_PROCESSING).build();
        session.setTenantId(app.getTenant().getId());
        session.setAppId(app.getId());
        session = sessionRepository.saveAndFlush(session);
        aiMsg = messageRepository.saveAndFlush(ChatMessage.builder()
                .session(session).role(MessageRole.ASSISTANT)
                .content("答复").confidenceScore(0.8).build());
    }

    @AfterEach
    void cleanup() {
        messageRepository.deleteById(aiMsg.getId());
        sessionRepository.deleteById(session.getId());
    }

    private String body(String sessionId, Long messageId, int feedback) {
        return "{\"sessionId\":\"" + sessionId + "\",\"messageId\":" + messageId
                + ",\"feedback\":" + feedback + "}";
    }

    @Test
    void ApiKey路径_提交反馈成功() throws Exception {
        mockMvc.perform(post("/open/v1/chat/feedback")
                        .header("X-App-Id", app.getAppKey())
                        .header("X-Api-Key", app.getAppSecret())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(session.getSessionId(), aiMsg.getId(), 1)))
                .andExpect(status().isOk());
        assertThat(messageRepository.findById(aiMsg.getId()).orElseThrow().getFeedback())
                .isEqualTo(1);
    }

    @Test
    void Origin路径_提交反馈成功() throws Exception {
        mockMvc.perform(post("/open/v1/chat/feedback")
                        .header("X-App-Id", app.getAppKey())
                        .header("Origin", "http://fb.example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(session.getSessionId(), aiMsg.getId(), -1)))
                .andExpect(status().isOk());
        assertThat(messageRepository.findById(aiMsg.getId()).orElseThrow().getFeedback())
                .isEqualTo(-1);
    }

    @Test
    void 跨会话messageId_拒绝() throws Exception {
        mockMvc.perform(post("/open/v1/chat/feedback")
                        .header("X-App-Id", app.getAppKey())
                        .header("X-Api-Key", app.getAppSecret())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("其他会话id", aiMsg.getId(), 1)))
                .andExpect(status().isNotFound());
        assertThat(messageRepository.findById(aiMsg.getId()).orElseThrow().getFeedback()).isNull();
    }

    /**
     * 跨应用凭据拒绝：应用 B 的合法凭据提交应用 A 会话消息的反馈（sessionId/messageId 均正确）。
     * ChatMessage 不继承 BaseTenantEntity，findById 不受租户过滤器保护，
     * 归属须靠 ChatFeedbackService 显式 appId 比对闭合——验证真实 Hibernate 环境下
     * 「懒加载会话 + 租户过滤器」不会把越权误判为 500，而是正确按 404 拒绝。
     */
    @Test
    void 跨应用凭据_拒绝() throws Exception {
        Application appB = seedApp("fbOther" + System.nanoTime(), "http://fb-other.example.com");

        mockMvc.perform(post("/open/v1/chat/feedback")
                        .header("X-App-Id", appB.getAppKey())
                        .header("X-Api-Key", appB.getAppSecret())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(session.getSessionId(), aiMsg.getId(), 1)))
                .andExpect(status().isNotFound());
        assertThat(messageRepository.findById(aiMsg.getId()).orElseThrow().getFeedback()).isNull();
    }

    @Test
    void 非法feedback值_拒绝() throws Exception {
        mockMvc.perform(post("/open/v1/chat/feedback")
                        .header("X-App-Id", app.getAppKey())
                        .header("X-Api-Key", app.getAppSecret())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(session.getSessionId(), aiMsg.getId(), 5)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(400));
        assertThat(messageRepository.findById(aiMsg.getId()).orElseThrow().getFeedback()).isNull();
    }
}
