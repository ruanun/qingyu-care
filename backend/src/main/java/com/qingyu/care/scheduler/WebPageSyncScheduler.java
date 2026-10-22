package com.qingyu.care.scheduler;

import com.qingyu.care.config.SiteCrawlProperties;
import com.qingyu.care.repository.DocumentRepository;
import com.qingyu.care.service.web.WebPageSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 网页文档定时同步：每小时扫描一次到期文档，逐个重抓。
 *
 * <p>到期查询走 {@link DocumentRepository#findDueWebPageIds} 的原生 SQL 绕过租户过滤器——
 * 本线程没有 TenantContext 也没有 Sa-Token 会话，是纵深防御而非唯一活路（详见该方法注释）。
 *
 * <p>逐个 fail-soft：一篇失败不影响其余；失败的处理（计数、转手动）在
 * {@link WebPageSyncService#syncDocument} 内部完成，此处只兜住意外异常。
 *
 * <p>⚠️ <b>逐篇之间必须限速</b>：{@link WebPageSyncService#syncDocument} 无论 hash 变没变
 * 都先发一次 HTTP 抓取，请求量与内容有没有变无关。站点爬取（{@code SiteCrawlService}）
 * 一次产出的几十篇文档的 {@code lastSyncedAt} 落在同一个几分钟窗口内，会同时到期——本方法
 * 一轮 {@code LIMIT 50}（见 {@code findDueWebPageIds}）对同一个 host 背靠背发 50 个请求，
 * 间隔只有网络 RTT，与爬取本身老老实实 ≥1s/页、遵守 robots {@code Crawl-delay} 的礼貌
 * 完全对不上——这条路径不讲礼貌地把爬取节流的效果又抵消掉了。复用
 * {@link SiteCrawlProperties#getDefaultDelayMs()}（同一套「对被抓站点的礼貌」配置，
 * 不必另起一份）而非 robots 声明值：本方法一轮里混着不同站点的到期文档，不像
 * {@code SiteCrawlService} 那样有单一站点的 robots 规则可用。
 *
 * @author ruan
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WebPageSyncScheduler {

    private final DocumentRepository documentRepository;
    private final WebPageSyncService webPageSyncService;
    private final SiteCrawlProperties siteCrawlProperties;

    /** 每小时执行一次（首次延迟 5 分钟，避开应用启动高峰）；间隔外部化，仿本仓库其余 5 处 @Scheduled 写法。 */
    @Scheduled(initialDelayString = "${qingyu.care.web-sync.initial-delay-ms:300000}",
               fixedDelayString = "${qingyu.care.web-sync.interval-ms:3600000}")
    public void syncDueWebPages() {
        List<Long> dueIds = documentRepository.findDueWebPageIds(LocalDateTime.now());
        if (dueIds.isEmpty()) {
            return;
        }
        log.info("网页定时同步：{} 篇到期", dueIds.size());
        boolean first = true;
        for (Long id : dueIds) {
            // 第一篇之前不用睡；睡眠期间被中断则恢复中断标志并提前结束本轮，不吞掉中断
            if (!first && !sleepQuietly(siteCrawlProperties.getDefaultDelayMs())) {
                log.warn("网页定时同步被中断，提前结束本轮");
                break;
            }
            first = false;
            try {
                webPageSyncService.syncDocument(id);
            } catch (Exception e) {
                log.warn("网页定时同步意外失败 id={}: {}", id, e.getMessage());
            }
        }
    }

    /** @return {@code false} 表示睡眠期间被中断，调用方应停止后续循环。 */
    private static boolean sleepQuietly(long millis) {
        if (millis <= 0) {
            return true;
        }
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
