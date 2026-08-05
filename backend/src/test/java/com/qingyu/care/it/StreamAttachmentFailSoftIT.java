package com.qingyu.care.it;

import com.qingyu.care.entity.Application;
import com.qingyu.care.entity.ChatAttachment;
import com.qingyu.care.entity.ChatMessage;
import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.enums.MessageRole;
import com.qingyu.care.enums.SessionStatus;
import com.qingyu.care.repository.ChatAttachmentRepository;
import com.qingyu.care.repository.ChatMessageRepository;
import com.qingyu.care.repository.ChatSessionRepository;
import com.qingyu.care.service.ModelRouterService;
import com.qingyu.care.support.AbstractPostgresIT;
import com.qingyu.care.support.SpringAiResponses;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * C-1（复核发现）：真实事务下验证附件关联失败不连累整轮流式对话落库。
 *
 * <p>纯 Mockito 单测测不出这条坑——{@code ChatAttachmentService#attachToMessage} 自带
 * {@code @Transactional}（REQUIRED），在 {@code persistStreamedMessages} 的
 * {@code transactionTemplate.execute} 内直接调用会作为**参与型事务**加入外层事务；
 * 抛异常时 Spring 的事务拦截器会把外层事务标记 rollback-only 后再重新抛出——就地
 * try/catch 只是不让异常继续往外冒，救不回已经被标记的事务，{@code execute} 返回时仍会
 * 因 rollback-only 而回滚并抛 {@code UnexpectedRollbackException}，现象与 C-1 原始症状
 * 完全相同（用户消息 + AI 消息一起消失，客户端却收到正常的 DONE）。这个机制只有真实
 * {@code JpaTransactionManager} 才会触发；纯 Mockito 测的 {@code TransactionTemplate}
 * 只是直通桩，无法感知"参与型事务被标记 rollback-only"这件事，之前那版单测因此给出了
 * 虚假的绿色（只验证了"catch 住了"，没验证"事务真的能提交"）。
 *
 * <p>修法是把附件关联挪到独立的 {@code REQUIRES_NEW} 事务里执行
 * （{@code ChatServiceImpl#attachInNewTransaction}，经 {@code self} 代理调用），
 * 失败时只回滚它自己的新事务，不触碰被挂起、之后照常提交的外层消息落库事务。
 *
 * @author ruan
 */
class StreamAttachmentFailSoftIT extends AbstractPostgresIT {

    @Autowired MockMvc mockMvc;
    @Autowired ChatSessionRepository sessionRepository;
    @Autowired ChatMessageRepository messageRepository;
    @Autowired ChatAttachmentRepository attachmentRepository;

    @MockitoBean
    private ModelRouterService modelRouterService;

    private final List<Long> cleanupAttachmentIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        cleanupAttachmentIds.forEach(id -> attachmentRepository.findById(id).ifPresent(attachmentRepository::delete));
        cleanupAttachmentIds.clear();
    }

    private ChatSession seedSession(String sessionId, Application app) {
        ChatSession session = new ChatSession();
        session.setSessionId(sessionId);
        session.setStatus(SessionStatus.AI_PROCESSING);
        session.setTenantId(app.getTenant().getId());
        session.setAppId(app.getId());
        return sessionRepository.saveAndFlush(session);
    }

    @Test
    void 附件关联失败时对话仍正常落库_不因参与型事务被标记rollback_only而整轮丢失() throws Exception {
        Application app = seedApp("attfs" + System.nanoTime(), "http://attfs.example.com");
        String sessionId = "attfs-sess-" + System.nanoTime();
        seedSession(sessionId, app);

        // 已被占用的附件：sessionId 与本轮请求匹配（预校验 validateAttachable 会放行，
        // 它刻意不查占用状态），但 messageId 已非空——真正 attachToMessage 时会抛
        // 「附件已被使用」，用来模拟并发重复提交这类预校验消灭不了的残余竞态
        ChatAttachment occupied = ChatAttachment.builder()
                .sessionId(sessionId).fileName("a.png").fileSize(1L).contentType("image/png")
                .storageKey("k-" + System.nanoTime()).uploaderRole(MessageRole.USER)
                .messageId(999999L).build();
        occupied.setTenantId(app.getTenant().getId());
        occupied.setAppId(app.getId());
        occupied = attachmentRepository.saveAndFlush(occupied);
        cleanupAttachmentIds.add(occupied.getId());

        Mockito.when(modelRouterService.streamWithFallback(
                        ArgumentMatchers.eq("default"), ArgumentMatchers.any()))
                .thenReturn(Flux.just(SpringAiResponses.of("已收到", 5)));

        MvcResult mvcResult = mockMvc.perform(post("/open/v1/chat/stream")
                        .header("X-App-Id", app.getAppKey())
                        .header("X-Api-Key", app.getAppSecret())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"" + sessionId + "\",\"message\":\"图在这\","
                                + "\"attachmentIds\":[" + occupied.getId() + "]}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        String body = mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        // C-1 症状的直接证据：DONE 事件必须带真实 messageId，不是丢失整轮后的 null
        assertThat(body).contains("event:done");
        assertThat(body).doesNotContain("\"messageId\":null");

        // 两条消息（用户提问 + AI 回复）都必须真的落库——不能被参与型事务的
        // rollback-only 标记拖累回滚掉
        await().atMost(5, SECONDS).untilAsserted(() -> {
            List<ChatMessage> saved = messageRepository.findAll().stream()
                    .filter(m -> "图在这".equals(m.getContent()) || "已收到".equals(m.getContent()))
                    .toList();
            assertThat(saved).hasSize(2);
        });

        // 附件关联本身失败了，不应该被静默"修复成功"——占用状态原样保留在别的消息上
        ChatAttachment stillOccupied = attachmentRepository.findById(occupied.getId()).orElseThrow();
        assertThat(stillOccupied.getMessageId()).isEqualTo(999999L);
    }
}
