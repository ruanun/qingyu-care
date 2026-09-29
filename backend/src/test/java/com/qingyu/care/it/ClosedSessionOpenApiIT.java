package com.qingyu.care.it;

import com.qingyu.care.entity.Application;
import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.enums.SessionStatus;
import com.qingyu.care.repository.ChatSessionRepository;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 「一次咨询一个会话」（2026-09-29 定）：会话被坐席结束（ENDED）或超时关闭（TIMEOUT）后，开放 API 对它的
 * 发消息 / 转人工 / 上传附件一律 404，Widget 据此丢掉本地会话、下一条消息新开一个。
 *
 * <p>此前只判「会话存在」：访客能一直往已结束的会话里发消息（状态停在 ENDED 却不断冒出新消息与 token 消耗），
 * 转人工还会让它从 ENDED 直接跳成 WAITING_AGENT。发消息的 404 在调用模型之前返回，本测试不产生模型调用。
 *
 * @author ruan
 */
@Transactional
class ClosedSessionOpenApiIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ChatSessionRepository sessionRepository;

    private ChatSession session(Application app, SessionStatus status) {
        ChatSession s = new ChatSession();
        s.setSessionId("sess_closed_" + status + "_" + System.nanoTime());
        s.setTenantId(app.getTenant().getId());
        s.setAppId(app.getId());
        s.setStatus(status);
        return sessionRepository.save(s);
    }

    /** Spring 7 里 multipart 构造器不再继承 MockHttpServletRequestBuilder，二者共用这个泛型基类 */
    private static <B extends AbstractMockHttpServletRequestBuilder<B>> B auth(B b, Application app) {
        return b.header("X-App-Id", app.getAppKey()).header("X-Api-Key", app.getAppSecret());
    }

    @Test
    void 已结束或已超时的会话_发消息转人工传附件都返回404() throws Exception {
        Application app = seedApp("closedSess", "http://closed.example.com");
        for (SessionStatus closed : new SessionStatus[]{SessionStatus.ENDED, SessionStatus.TIMEOUT}) {
            String sid = session(app, closed).getSessionId();
            String body = "{\"message\":\"还在吗\",\"sessionId\":\"" + sid + "\"}";

            mockMvc.perform(auth(post("/open/v1/chat/completions"), app)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.message").value("会话不存在或已结束"));
            mockMvc.perform(auth(post("/open/v1/chat/stream"), app)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isNotFound());
            mockMvc.perform(auth(post("/open/v1/sessions/{sid}/handover", sid), app))
                    .andExpect(status().isNotFound());
            mockMvc.perform(auth(multipart("/open/v1/chat/files")
                            .file(new MockMultipartFile("file", "a.txt", "text/plain", "hi".getBytes()))
                            .param("sessionId", sid), app))
                    .andExpect(status().isNotFound());

            // 转人工被拒之后，状态必须原样保持——此前会被改成 WAITING_AGENT
            assertThat(sessionRepository.findBySessionId(sid).orElseThrow().getStatus()).as(closed.name()).isEqualTo(closed);
        }
    }

    /** 对照：同样的请求打到进行中的会话照常成功，证明上面的 404 来自「会话已关闭」而不是请求本身不合法 */
    @Test
    void 进行中的会话照常转人工() throws Exception {
        Application app = seedApp("openSess", "http://open.example.com");
        String sid = session(app, SessionStatus.AI_PROCESSING).getSessionId();

        mockMvc.perform(auth(post("/open/v1/sessions/{sid}/handover", sid), app))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("handover_requested"));
        assertThat(sessionRepository.findBySessionId(sid).orElseThrow().getStatus()).isEqualTo(SessionStatus.WAITING_AGENT);
    }
}
