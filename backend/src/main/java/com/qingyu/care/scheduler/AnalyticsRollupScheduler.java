package com.qingyu.care.scheduler;

import com.qingyu.care.service.AnalyticsRollupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 分析 rollup 定时任务：每日凌晨聚合近 2 天（昨日 + 前日）。
 * 覆盖前日是为吸收跑批后才提交的迟到反馈——幂等先删后插，重跑无副作用。
 *
 * @author ruan
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AnalyticsRollupScheduler {

    private final AnalyticsRollupService rollupService;

    @Scheduled(cron = "${qingyu.care.analytics.rollup-cron:0 30 2 * * *}")
    public void rollupDaily() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        int ok = rollupService.backfill(yesterday.minusDays(1), yesterday);
        log.info("分析 rollup 定时任务完成，成功 {}/2 天", ok);
    }
}
