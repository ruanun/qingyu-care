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

    /**
     * N-1（复核终审发现）：上面那条只覆盖失败分支。全仓库唯一断言"附件真的挂上了"的是
     * 纯 Mockito 的 StreamChatEventPipelineTest，那里 {@code self == service}（同一个 POJO，
     * 直接 {@code setSelf(service)}），对"代理是否生效"一无所知——若 {@code self} 链哪天断掉，
     * 流式路径下附件会**永远**挂不上：{@code self} 为 null 时 {@code self.attachInNewTransaction}
     * 抛 NPE，被 {@code saveMessages} 的 fail-soft catch 兜住记一条 warn（**会**打日志，只是
     * 没人看），对话照常落库、请求照常返回 200；调用被短路成空跑（方法体什么都不做）时连
     * warn 都没有。两种坏法的后果一样：附件停留在 {@code messageId IS NULL}，24 小时后被孤儿
     * 清理任务连同 S3 对象一起删掉——现象是"图发出去了、过一天没了"，而 {@code mvn test} +
     * {@code mvn verify} 全绿。
     * <p>本用例用一个干净附件（{@code messageId} 为 null）走正常流式一轮，断言跑完后它的
     * {@code messageId} 被正确回填为**用户消息**的 id（不是 AI 消息的 id）——这是唯一在真实
     * Spring 容器 + 真实事务代理链下断言"正向路径确实生效"的用例。
     * <p>⚠️ 变异自证的口径修正（复核建议 vs 实测）：复核原本建议的变异是"把
     * {@code self.attachInNewTransaction(...)} 改成直调 {@code attachInNewTransaction(...)}
     * （绕过 self 代理）"。实测这条变异**只会让上面那条失败路径用例转红**（REQUIRES_NEW 退化
     * 为参与型事务，复现 C-1 的 rollback-only 机制），**不会**让本用例转红——happy path 下
     * 没有任何异常抛出，参与型事务与独立事务提交的最终结果（附件 messageId 正确回填）完全一致，
     * Mockito 测不出的"事务隔离"这件事，在无失败发生时对可观测结果没有影响。
     * 真正能单独抓住"self 链断掉、attach 变成静默空转"这一具体失效模式的变异是把
     * {@code attachInNewTransaction} 方法体清空（模拟调用链在代理层之外被短路，什么都没发生、
     * 也不抛异常）——该变异下本用例转红（{@code messageId} 停留 null 超时），而上面那条失败路径
     * 用例反而保持绿（没有异常可捕获，DONE 正常带 id）。两条用例因此对"事务隔离被破坏"与
     * "关联调用被短路"两种不同的坏法分别具有排他的判别力，合起来才不留死角。
     */
    @Test
    void 附件关联成功时messageId被回填为用户消息id() throws Exception {
        Application app = seedApp("attok" + System.nanoTime(), "http://attok.example.com");
        String sessionId = "attok-sess-" + System.nanoTime();
        seedSession(sessionId, app);

        ChatAttachment clean = ChatAttachment.builder()
                .sessionId(sessionId).fileName("clean.png").fileSize(1L).contentType("image/png")
                .storageKey("k-" + System.nanoTime()).uploaderRole(MessageRole.USER).build();
        clean.setTenantId(app.getTenant().getId());
        clean.setAppId(app.getId());
        clean = attachmentRepository.saveAndFlush(clean);
        Long cleanId = clean.getId();
        cleanupAttachmentIds.add(cleanId);

        Mockito.when(modelRouterService.streamWithFallback(
                        ArgumentMatchers.eq("default"), ArgumentMatchers.any()))
                .thenReturn(Flux.just(SpringAiResponses.of("收到，马上处理", 5)));

        MvcResult mvcResult = mockMvc.perform(post("/open/v1/chat/stream")
                        .header("X-App-Id", app.getAppKey())
                        .header("X-Api-Key", app.getAppSecret())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"" + sessionId + "\",\"message\":\"这是报错截图\","
                                + "\"attachmentIds\":[" + cleanId + "]}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(mvcResult)).andExpect(status().isOk());

        await().atMost(5, SECONDS).untilAsserted(() -> {
            ChatMessage userMsg = messageRepository.findAll().stream()
                    .filter(m -> "这是报错截图".equals(m.getContent()))
                    .findFirst().orElseThrow();
            ChatAttachment reloaded = attachmentRepository.findById(cleanId).orElseThrow();
            assertThat(reloaded.getMessageId()).isEqualTo(userMsg.getId());
        });
    }
}
