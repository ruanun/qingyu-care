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
     * 硬顶就是 40 多小时，全程占着 {@code AsyncConfig.LONG_TASK_EXECUTOR} 的一个线程
     * （固定 6 个，与文档摄取、QA 生成、批量重嵌、网页同步共用）和全局并发额度的一格。
     */
    private long maxDelayMs = 10_000;

    /**
     * 全局并发爬取任务上限，<b>不区分租户</b>。既是对目标站点礼貌，也是对执行器的保护：
     * 爬取跑在 {@code AsyncConfig.LONG_TASK_EXECUTOR} 这个<b>固定 6 线程</b>的长任务池上，
     * 与文档摄取、QA 生成、批量重嵌、网页同步共用它。不设上限时几个爬取就能把 6 个线程
     * 全占满，同池的摄取与重嵌只能排队，等待时长最坏可达 {@code maxPagesLimit} ×
     * {@code maxDelayMs}（500 页 × 10s ≈ 83 分钟）。
     *
     * <p>审计日志落库、webhook 直投等短任务<b>不在这个池里</b>（走 Boot 默认的
     * {@code applicationTaskExecutor}），所以爬取占满长任务池不会拖慢它们——这是
     * {@code AsyncConfig} 分池之前没有的保证，当时全靠这条配额硬撑。
     *
     * <p>⚠️ 这条上限必须与 {@link #maxConcurrentJobsPerTenant} 一起判——只留后者会让
     * 总量变成「租户数 × 每租户上限」、彻底失去对长任务池的保护（真实教训：曾经改成
     * 纯按租户分桶，总量随租户数线性增长，几个租户各起 2 个爬取就能占满整池）。
     */
    private int maxConcurrentJobs = 2;

    /**
     * 单租户并发爬取任务上限，在 {@link #maxConcurrentJobs} 之内再按
     * {@code TenantContext.getTenantId()} 细分。只有全局上限时，一个租户占满会连带
     * 拒绝同实例其他所有租户点「爬取」——跨租户可用性耦合。两层上限一起判：
     * {@link com.qingyu.care.service.web.SiteCrawlService#launch} 先判全局（保护共享
     * 线程池），再判租户桶（保护跨租户公平），任一超限都拒绝。
     */
    private int maxConcurrentJobsPerTenant = 2;
}
