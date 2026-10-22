package com.qingyu.care.service;

import com.qingyu.care.entity.SysApiUsage;
import com.qingyu.care.repository.SysApiUsageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * 开放 API 用量计量：按 (appId, 当天) 原子累加。
 * 异步执行，异常只记日志不外抛，不影响主调用链。
 * <p>
 * 刻意不加方法级 {@code @Transactional}：若整方法包在一个事务里，
 * save() 唯一键冲突会把事务标记为 rollback-only，导致 catch 中的重试
 * incrementUsage 也随之回滚、计量静默丢失。这里让每个仓库方法各自走
 * 独立事务，冲突后重试的累加才能真正提交。
 *
 * @author ruan
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ApiUsageService {

    private final SysApiUsageRepository repository;

    /**
     * 记录一次 API 用量。
     *
     * @param tenantId 租户 ID
     * @param appId    应用 ID
     * @param messages 本次消息数（通常为 1）
     * @param tokens   本次 token 用量（来自 ChatResponse.tokenUsage，可能为 0）
     */
    @Async
    public void record(Long tenantId, Long appId, long messages, long tokens) {
        try {
            LocalDate today = LocalDate.now();
            // 先尝试累加已有行
            int updated = repository.incrementUsage(appId, today, messages, tokens);
            if (updated == 0) {
                // 当天尚无记录，插入新行
                try {
                    repository.save(SysApiUsage.builder()
                            .tenantId(tenantId)
                            .appId(appId)
                            .usageDate(today)
                            .callCount(1)
                            .messageCount(messages)
                            .tokenCount(tokens)
                            .build());
                } catch (DataIntegrityViolationException insertConflict) {
                    // 并发场景：另一线程已抢先插入唯一键，重试一次累加。
                    // 未加 @Transactional，increment/save 各走独立事务，此重试能真正提交。
                    log.debug("并发插入冲突，重试累加 appId={}", appId);
                    repository.incrementUsage(appId, today, messages, tokens);
                }
            }
        } catch (Exception e) {
            log.warn("写开放 API 用量失败 appId={}: {}", appId, e.getMessage());
        }
    }

    /**
     * 仅补记 token 用量，不计入调用/消息次数。
     * <p>用于流式对话降级轮：{@code streamChat} 首次 {@code doFinally} 已按「一次调用」记过账
     * （此时 tokens 恒为 0，因为流式失败在 usage 到达之前）；随后非流式回退成功后拿到真实 token，
     * 此处只补 token 差值——不能复用 {@link #record}，它的 SQL 无条件把 call_count + 1，会把这
     * 一轮重复计成两次调用。
     * <p>fail-soft：与 {@link #record} 同惯例，异常只记日志不外抛；当天用量行不存在（理论上不会
     * 发生，正常情形下主计量路径必然已先插入过）时放弃补记，不插入一条 callCount=0 的畸形行。
     *
     * @param appId  应用 ID
     * @param tokens 需要补记的 token 差值；非正数直接跳过
     */
    @Async
    public void recordFallbackTokens(Long appId, long tokens) {
        if (tokens <= 0) {
            return;
        }
        try {
            int updated = repository.incrementTokensOnly(appId, LocalDate.now(), tokens);
            if (updated == 0) {
                log.warn("补记降级轮 token 失败：当天用量行不存在 appId={}", appId);
            }
        } catch (Exception e) {
            log.warn("补记降级轮 token 失败 appId={}: {}", appId, e.getMessage());
        }
    }
}
