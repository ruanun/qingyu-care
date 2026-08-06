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
     * robots.txt {@code Crawl-delay} 的上限（毫秒）。这是第三方（被爬站点）可控的值，
     * 不设上限时对方写一句 {@code Crawl-delay: 300} 就能让每页睡 5 分钟——乘以 500 页
     * 硬顶就是 40 多小时，全程占着共享的 {@code applicationTaskExecutor} 一个线程
     * （与审计日志落库、webhook 直投、文档摄取共享）和全局并发额度的一格。
     */
    private long maxDelayMs = 10_000;

    /**
     * 全局并发爬取任务上限。不只是对目标站点礼貌的问题：@Async 池
     * （Spring Boot 默认 applicationTaskExecutor，core 8）是和审计日志异步落库、
     * webhook 直投、文档摄取共享的，几分钟的长任务无上界并发会挤掉那些短任务。
     */
    private int maxConcurrentJobs = 2;
}
