package com.qingyu.care.scheduler;

import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.entity.SysConfig;
import com.qingyu.care.enums.OwnerScope;
import com.qingyu.care.enums.SessionStatus;
import com.qingyu.care.event.WebhookDomainEvent;
import com.qingyu.care.repository.ChatSessionRepository;
import com.qingyu.care.repository.SysConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话超时定时任务
 * 定期检查并关闭超时的会话。
 * 超时阈值支持租户级覆盖（配置中心 tenant.session.timeout.minutes），
 * 未配置/留空/非法值的租户回落全局默认 qingyu.care.session.timeout-minutes。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionTimeoutScheduler {

    /** 租户级会话超时覆盖的配置键（租户配置页可配） */
    static final String TENANT_TIMEOUT_KEY = "tenant.session.timeout.minutes";

    private final ChatSessionRepository sessionRepository;
    private final SysConfigRepository sysConfigRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${qingyu.care.session.timeout-minutes:30}")
    private int timeoutMinutes;

    /**
     * 每 5 分钟检查一次超时会话
     */
    @Scheduled(fixedRateString = "${qingyu.care.session.check-interval-ms:300000}")
    @Transactional
    public void checkTimeoutSessions() {
        LocalDateTime now = LocalDateTime.now();

        List<SessionStatus> activeStatuses = List.of(
                SessionStatus.AI_PROCESSING,
                SessionStatus.AGENT_PROCESSING
        );

        // 收集配置了租户级超时覆盖的租户；调度线程无登录态，仓储查询不受租户过滤器影响
        Map<Long, Integer> overrides = resolveTenantOverrides();

        List<ChatSession> timeoutSessions = new ArrayList<>();
        if (overrides.isEmpty()) {
            timeoutSessions.addAll(sessionRepository.findTimeoutSessions(
                    activeStatuses, now.minusMinutes(timeoutMinutes)));
        } else {
            // 无覆盖的租户走全局默认阈值；有覆盖的租户各按自己的阈值单独扫描
            timeoutSessions.addAll(sessionRepository.findTimeoutSessionsExcludingTenants(
                    activeStatuses, now.minusMinutes(timeoutMinutes), List.copyOf(overrides.keySet())));
            overrides.forEach((tenantId, minutes) -> timeoutSessions.addAll(
                    sessionRepository.findTimeoutSessionsForTenant(
                            activeStatuses, now.minusMinutes(minutes), tenantId)));
        }

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

        // 超时同样是会话结束，与坐席手动结束发同一个事件、用 trigger 区分——接收方订阅一个事件就能
        // 覆盖所有「会话结束」。此前只有手动结束会发，超时的会话在接收方那边永远停在进行中。
        // 本方法带 @Transactional，WebhookDispatcher 的 AFTER_COMMIT 监听在批量更新提交后才落投递行。
        LocalDateTime endedAt = LocalDateTime.now();
        for (ChatSession s : timeoutSessions) {
            eventPublisher.publishEvent(new WebhookDomainEvent(
                    "session.ended", s.getAppId(), s.getTenantId(), s.getSessionId(), "timeout", endedAt));
        }
    }

    /** 租户级超时覆盖表：tenantId → 分钟数。留空/非正数/非法值一律忽略（回落全局默认）。 */
    private Map<Long, Integer> resolveTenantOverrides() {
        Map<Long, Integer> overrides = new LinkedHashMap<>();
        for (SysConfig config : sysConfigRepository.findByScopeAndConfigKey(OwnerScope.TENANT, TENANT_TIMEOUT_KEY)) {
            if (config.getTenantId() == null || config.getConfigValue() == null || config.getConfigValue().isBlank()) {
                continue;
            }
            try {
                int minutes = Integer.parseInt(config.getConfigValue().trim());
                if (minutes > 0) {
                    overrides.put(config.getTenantId(), minutes);
                }
            } catch (NumberFormatException e) {
                log.warn("租户 {} 的会话超时配置非法（{}），回落全局默认 {} 分钟",
                        config.getTenantId(), config.getConfigValue(), timeoutMinutes);
            }
        }
        return overrides;
    }
}
