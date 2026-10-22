package com.qingyu.care.scheduler;

import com.qingyu.care.entity.Tenant;
import com.qingyu.care.enums.TenantStatus;
import com.qingyu.care.repository.TenantRepository;
import com.qingyu.care.service.SessionRevocationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 租户套餐到期后自动停用：状态置为 EXPIRED，并踢掉该租户全部已登录用户。
 *
 * <p>登录与开放 API 鉴权本就会检查 {@code expireTime}，但已签发的 token 不受影响——套餐自然到期没有任何触发点，
 * 已登录的用户能一直用到 token 过期（7 天）。平台管理员手动冻结、改到期时间都会吊销登录态
 * （见 {@code TenantController}），这里补上「时间自己走过去」这一条（2026-09-25 安全扫描 M1 的遗留缺口）。
 *
 * <p>互斥靠条件更新：{@link TenantRepository#transitionStatus} 只在状态仍是 ACTIVE 时才改，
 * 只有改成功的那一方去踢人，多实例同时扫描或重复执行都无副作用（同安全基线第 19 条的归类）。
 * 调度线程没有登录态，仓储查询不受租户过滤器影响（同 SessionTimeoutScheduler）。
 *
 * @author ruan
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TenantExpiryScheduler {

    private final TenantRepository tenantRepository;
    private final SessionRevocationService sessionRevocationService;

    /** 默认每 10 分钟扫一次，首次延迟 1 分钟避开启动高峰 */
    @Scheduled(initialDelayString = "${qingyu.care.tenant.expiry-check-initial-delay-ms:60000}",
               fixedDelayString = "${qingyu.care.tenant.expiry-check-interval-ms:600000}")
    public void expireOverdueTenants() {
        List<Tenant> overdue = tenantRepository.findByStatusAndExpireTimeBefore(TenantStatus.ACTIVE, LocalDateTime.now());
        for (Tenant t : overdue) {
            try {
                if (tenantRepository.transitionStatus(t.getId(), TenantStatus.ACTIVE, TenantStatus.EXPIRED) == 1) {
                    log.info("租户 {}（{}）套餐已于 {} 到期，已停用并下线其全部用户", t.getId(), t.getName(), t.getExpireTime());
                    sessionRevocationService.revokeTenant(t.getId(), "租户套餐到期");
                }
            } catch (Exception e) {
                // 单个租户失败不影响其余，下一轮会重试（状态没改成功，下次仍会被扫到）
                log.warn("停用到期租户 {} 失败: {}", t.getId(), e.getMessage());
            }
        }
    }
}
