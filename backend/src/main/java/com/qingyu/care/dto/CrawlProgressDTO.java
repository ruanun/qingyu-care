package com.qingyu.care.dto;

import com.qingyu.care.service.web.SiteCrawlService;

import java.time.LocalDateTime;

/**
 * 爬取进度响应。{@code total} 刻意不存在——BFS 边爬边发现，总数是动态的
 * （这与 {@code ReembedService} 不同，重嵌开始时就知道总数）。前端显示成
 * 「已爬 12 / 上限 50 页」。
 *
 * @author ruan
 */
public record CrawlProgressDTO(String jobId, Long siteId, String status,
                               int crawled, int created, int updated, int failed,
                               int maxPages, String errorMessage,
                               LocalDateTime startedAt, LocalDateTime finishedAt) {

    public static CrawlProgressDTO from(SiteCrawlService.Progress p) {
        return new CrawlProgressDTO(p.jobId, p.siteId, p.status,
                p.crawled, p.created, p.updated, p.failed,
                p.maxPages, p.errorMessage, p.startedAt, p.finishedAt);
    }
}
