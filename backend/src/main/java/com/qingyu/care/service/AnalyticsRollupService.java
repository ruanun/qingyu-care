package com.qingyu.care.service;

import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.repository.AnalyticsRollupDao;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 分析 rollup 编排服务：按日「先删后插」（幂等），单日一个事务。
 * 用 TransactionTemplate 而非 @Transactional：backfill 内部逐日调用 rollupDay，
 * 自调用不经代理、注解事务不生效（Spring AOP 自调用陷阱）。
 *
 * @author ruan
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AnalyticsRollupService {

    private final AnalyticsRollupDao rollupDao;
    private final TransactionTemplate transactionTemplate;

    /** 每租户+应用每日热词保留条数 */
    @Value("${qingyu.care.analytics.hot-keyword-top-n:50}")
    private int hotKeywordTopN;

    /**
     * 聚合单日（幂等：先删后插，同一事务）。
     *
     * @return true=本实例完成了聚合；false=别的实例正持有该日期的锁，本轮跳过（不算失败）
     */
    public boolean rollupDay(LocalDate day) {
        return Boolean.TRUE.equals(transactionTemplate.execute(tx -> {
            // 多实例互斥（安全审查 L12）：抢不到就让持锁方做完，重复做只会双写
            if (!rollupDao.tryLockDay(day)) {
                log.info("分析 rollup 跳过：同一日期已有实例在聚合 date={}", day);
                return false;
            }
            rollupDao.deleteDay(day);
            int q = rollupDao.insertQuality(day);
            int k = rollupDao.insertHotKeywords(day, hotKeywordTopN);
            int d = rollupDao.insertDocHits(day);
            log.info("分析 rollup 完成 date={} quality={} keywords={} docHits={}", day, q, k, d);
            return true;
        }));
    }

    /**
     * 区间回填 [from, to]：逐日独立事务执行，单日失败记录后继续，返回成功天数。
     */
    public int backfill(LocalDate from, LocalDate to) {
        if (from == null || to == null || from.isAfter(to)) {
            throw new BusinessException("回填区间非法：from 须早于等于 to");
        }
        // 闭区间天数 = DAYS.between + 1，上限 366 天（含闰年一整年）即 between 最多 365
        if (ChronoUnit.DAYS.between(from, to) > 365) {
            throw new BusinessException("回填区间过长（最多 366 天）");
        }
        int ok = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            try {
                if (rollupDay(d)) {
                    ok++;
                }
            } catch (Exception e) {
                log.error("分析 rollup 失败 date={}: {}", d, e.getMessage(), e);
            }
        }
        return ok;
    }
}
