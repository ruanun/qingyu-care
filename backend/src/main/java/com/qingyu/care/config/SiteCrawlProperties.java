package com.qingyu.care.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 站点爬取配置（qingyu.care.site-crawl.*）。
 *
 * @author ruan
 */
@Component
@ConfigurationProperties(prefix = "qingyu.care.site-crawl")
@Data
public class SiteCrawlProperties {

    /** 单次爬取页数硬顶。服务端按它钳制入参，不能只靠前端。 */
    private int maxPagesLimit = 500;

    /** 链接发现深度硬顶。 */
    private int maxDepthLimit = 5;

    /** 默认抓取间隔（毫秒）；robots.txt 的 Crawl-delay 优先于它。 */
    private long defaultDelayMs = 1000;

    /**
     * 全局并发爬取任务上限。不只是对目标站点礼貌的问题：@Async 池
     * （Spring Boot 默认 applicationTaskExecutor，core 8）是和审计日志异步落库、
     * webhook 直投、文档摄取共享的，几分钟的长任务无上界并发会挤掉那些短任务。
     */
    private int maxConcurrentJobs = 2;
}
