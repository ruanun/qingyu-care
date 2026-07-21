package com.qingyu.care.service;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.entity.ChatMessage;
import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.enums.MessageRole;
import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.exception.ResourceNotFoundException;
import com.qingyu.care.repository.ChatMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 消息级反馈（赞/踩）。单一职责小服务，不并入已复杂的 ChatService。
 *
 * @author ruan
 */
@Service
@RequiredArgsConstructor
public class ChatFeedbackService {

    private final ChatMessageRepository messageRepository;

    /**
     * 提交反馈（1=赞 / -1=踩，重复提交覆盖）。
     * 归属校验：消息须为 AI 回复且属于给定会话；有应用上下文时会话须属于当前应用——
     * ChatMessage 非租户过滤实体，findById 不受过滤器保护，显式校验闭合跨会话/跨租户投毒。
     * 越权一律按 404 处理（不泄露消息是否存在）。
     */
    @Transactional
    public void submitFeedback(String sessionId, Long messageId, int feedback) {
        if (feedback != 1 && feedback != -1) {
            throw new BusinessException("feedback 仅允许 1（赞）或 -1（踩）");
        }
        ChatMessage msg = messageRepository.findById(messageId)
                .orElseThrow(() -> new ResourceNotFoundException("消息", String.valueOf(messageId)));
        ChatSession session = msg.getSession();
        if (session == null || !session.getSessionId().equals(sessionId)) {
            throw new ResourceNotFoundException("消息", String.valueOf(messageId));
        }
        Long ctxAppId = TenantContext.getAppId();
        if (ctxAppId != null && session.getAppId() != null && !ctxAppId.equals(session.getAppId())) {
            throw new ResourceNotFoundException("消息", String.valueOf(messageId));
        }
        if (msg.getRole() != MessageRole.ASSISTANT) {
            throw new BusinessException("仅可评价 AI 回复消息");
        }
        msg.setFeedback(feedback);
        messageRepository.save(msg);
    }
}
