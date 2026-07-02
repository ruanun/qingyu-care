package com.qingyu.care.scheduler;

import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.enums.SessionStatus;
import com.qingyu.care.repository.ChatSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话超时定时任务
 * 定期检查并关闭超时的会话
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionTimeoutScheduler {
    
    private final ChatSessionRepository sessionRepository;
    
    @Value("${qingyu.care.session.timeout-minutes:30}")
    private int timeoutMinutes;
    
    /**
     * 每 5 分钟检查一次超时会话
     */
    @Scheduled(fixedRateString = "${qingyu.care.session.check-interval-ms:300000}")
    @Transactional
    public void checkTimeoutSessions() {
        LocalDateTime timeout = LocalDateTime.now().minusMinutes(timeoutMinutes);
        
        // 查找所有活跃但超时的会话
        List<SessionStatus> activeStatuses = List.of(
                SessionStatus.AI_PROCESSING,
                SessionStatus.AGENT_PROCESSING
        );
        
        List<ChatSession> timeoutSessions = sessionRepository.findTimeoutSessions(activeStatuses, timeout);
        
        if (timeoutSessions.isEmpty()) {
            return;
        }
        
        log.info("Found {} timeout sessions, closing them...", timeoutSessions.size());
        
        // 批量更新状态
        List<Long> ids = timeoutSessions.stream()
                .map(ChatSession::getId)
                .toList();
        
        int updated = sessionRepository.batchUpdateStatus(
                ids,
                SessionStatus.TIMEOUT,
                LocalDateTime.now()
        );
        
        log.info("Closed {} timeout sessions", updated);
    }
}
