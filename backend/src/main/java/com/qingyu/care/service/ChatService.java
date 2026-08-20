package com.qingyu.care.service;

import com.qingyu.care.dto.AgentSessionDTO;
import com.qingyu.care.dto.ChatRequest;
import com.qingyu.care.dto.ChatResponse;
import com.qingyu.care.dto.ChatStreamEvent;
import com.qingyu.care.entity.ChatMessage;
import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.enums.SessionStatus;
import com.qingyu.care.enums.SessionTag;
import org.springframework.ai.document.Document;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 聊天服务接口
 */
public interface ChatService {
    
    /**
     * 发送消息并获取AI回复
     */
    ChatResponse chat(ChatRequest request);
    
    /**
     * 流式聊天（内部调用，不计量）。返回结构化事件流，协议见
     * docs/superpowers/specs/2026-08-04-streaming-chat-design.md §3。
     */
    Flux<ChatStreamEvent> streamChat(ChatRequest request);

    /**
     * 流式聊天（开放 API：带 token 计量）。事件序列：START → (DELTA|CARD)* → DONE；失败时以 ERROR 收尾。
     *
     * @param onTokensUsed         本轮"一次调用"的用量回调，流正常结束/取消/未回退的失败均恰好回调一次
     *                             （含 messages/tokens，即 {@code ApiUsageService.record} 语义）。
     * @param onFallbackTokensUsed 流式失败并成功回退非流式后触发：仅补记本轮真实 token 用量，不计入
     *                             调用/消息次数（onTokensUsed 此前已以 0 token 记过这一次调用）。
     *                             未触发回退、或回退本身也失败时不会被调用。
     */
    Flux<ChatStreamEvent> streamChat(ChatRequest request, java.util.function.IntConsumer onTokensUsed,
                                     java.util.function.IntConsumer onFallbackTokensUsed);

    /**
     * 获取会话信息
     */
    ChatSession getSession(String sessionId);

    /**
     * 判断会话是否存在（按当前上下文可见）。开放 API 用于对失效 sessionId 显式报错。
     */
    boolean sessionExists(String sessionId);
    
    /**
     * 结束会话
     */
    void endSession(String sessionId);
    
    /**
     * 请求人工接管
     */
    void requestHandover(String sessionId);

    /**
     * 知识检索：按当前租户上下文 + 激活的 default 场景策略，检索相关知识（混合检索开关由策略
     * hybridEnabled 决定，null 视为开启）。fail-soft：任何异常均返回空列表，不阻断对话。
     * chat()/streamChat() 内部复用同一实现（传入已取的 strategy，避免重复查询）；
     * 本方法额外暴露给外部（如集成测试）按 query 直接验证检索链路。
     */
    List<Document> searchKnowledge(String query);

    // ===== 新增方法 =====
    
    /**
     * 获取用户的历史会话列表（分页）
     */
    Page<ChatSession> getUserSessions(String userId, Pageable pageable);
    
    /**
     * 获取所有会话列表（分页，带状态过滤）
     */
    Page<ChatSession> getAllSessions(SessionStatus status, Pageable pageable);
    
    /**
     * 添加会话标签
     */
    ChatSession addSessionTag(String sessionId, SessionTag tag);

    /**
     * 移除会话标签
     */
    ChatSession removeSessionTag(String sessionId, SessionTag tag);
    
    /**
     * 更新会话摘要
     */
    ChatSession updateSessionSummary(String sessionId, String summary);
    
    // ===== 人工客服相关 =====
    
    /**
     * 客服接管会话
     */
    ChatSession acceptSession(String sessionId, Long agentId);

    /**
     * 坐席上报已读水位线。
     *
     * @param agentId       当前坐席的 user id，<b>由控制器取好传入</b>（同 {@code acceptSession}
     *                      的既有范式：{@code StpUtil.getLoginIdAsLong()} 在控制器层取）。service 层
     *                      不碰 Sa-Token，单测因此不需要静态桩。
     * @param upToMessageId 坐席端当前<b>已渲染</b>的最新一条消息 id。服务端不替客户端推断
     *                      「最新是哪条」——坐席实际看到的截止位置只有客户端知道，服务端若取库里
     *                      最新的一条，会把「消息刚到、还没渲染出来」也算成已读。
     * @return 生效后的水位线
     */
    long markAgentRead(String sessionId, Long agentId, Long upToMessageId);

    /**
     * 访客上报已读水位线（开放 API，无登录态）。
     *
     * <p>开放 API 的身份是「这个 app」：调用方额外校验会话属于当前 {@code TenantContext.getAppId()}，
     * 与附件下载端点要求 {@code ?sessionId=} 是同一套定级——{@code sessionId}(UUID) 本身即第二重
     * 能力凭证。
     *
     * <p>readerId 用该会话的 {@code chat_session.id}：访客没有 user id，一个会话只有一个访客，
     * 用会话主键即可唯一标识它；绝不能传 null（PG 唯一约束里 {@code NULL != NULL}，留 null 会让
     * 同一会话的多条访客回执各自插入，约束形同虚设且不报错）。
     *
     * @param upToMessageId 访客端当前已渲染的最新一条消息 id
     * @return 生效后的水位线
     */
    long markVisitorRead(String sessionId, Long upToMessageId);

    /**
     * 客服发送消息（绕过 AI）
     *
     * @return 落库后这条坐席消息的真实 id——调用方（管理端）应改用它替代本地占位 id
     *         做乐观渲染/已读上报，见 {@code AgentPage.tsx} 与 {@code ChatServiceImpl.clampToMaxMessageId} 注释
     */
    Long agentReply(String sessionId, String message, Long agentId);

    /**
     * 客服发送消息（绕过 AI），可携带附件 id（先经 /v1/chat/files 上传拿到）。
     * 附件落点是这条坐席消息本身（同用户消息路径同语义：落点是真正被发送的那条消息）。
     *
     * @return 落库后这条坐席消息的真实 id，语义同上
     */
    Long agentReply(String sessionId, String message, Long agentId, List<Long> attachmentIds);
    
    /**
     * 获取待接管会话列表
     */
    Page<ChatSession> getWaitingAgentSessions(Pageable pageable);
    
    /**
     * 获取会话消息历史
     */
    List<ChatMessage> getSessionMessages(String sessionId);

    /**
     * 坐席把会话交还 AI 续答（仅 AGENT_PROCESSING 状态允许）
     */
    void returnToAI(String sessionId);

    /**
     * 获取当前坐席接管中的会话列表，含每个会话的未读消息数，按最后消息时间倒序。
     *
     * @param agentId 当前坐席的 user id，<b>由控制器取好传入</b>（同 {@link #markAgentRead}
     *                的既有范式：{@code StpUtil.getLoginIdAsLong()} 在控制器层取）。service 层
     *                不碰 Sa-Token，单测因此不需要静态桩。
     * @param page    页码（从 0 开始）
     * @param size    页大小
     */
    List<AgentSessionDTO> getMySessions(Long agentId, int page, int size);
}

