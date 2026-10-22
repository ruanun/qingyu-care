package com.qingyu.care.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步执行器配置。本项目把 {@code @Async} 任务分成<b>两类、两个池</b>，不要混用。
 *
 * <p><b>短任务 → 默认池</b>（Spring Boot 自动装配的 {@code applicationTaskExecutor}，不写
 * qualifier 就走它）：用量计量落库、审计日志落库、webhook 入队与直投。它们都是毫秒到秒级、
 * 要求低延迟，且失败基本无声（审计日志丢一条不影响业务）。
 *
 * <p><b>长任务 → {@link #LONG_TASK_EXECUTOR}</b>（本类定义的有界池，必须写
 * {@code @Async(AsyncConfig.LONG_TASK_EXECUTOR)}）：文档摄取、QA 生成、向量模型迁移、
 * 网页同步、站点爬取。它们是分钟到小时级（站点爬取按 500 页硬顶 × 最长 10s/页可达 80 多分钟）。
 *
 * <p>⚠️ <b>为什么必须分池</b>：Boot 默认的 {@code applicationTaskExecutor} 是 core 8 +
 * <b>无界队列</b>。无界队列意味着 core 线程占满后 maxPoolSize 永远不会扩容——后来的任务
 * 一律排进那条无界 FIFO。于是只要 8 个长任务同时在跑，之后<b>每一次</b>审计日志落库、
 * webhook 投递都要排在它们后面，等待时长等于最慢那个长任务的剩余时间。站点爬取上线前
 * 这个风险是靠业务层配额（{@code SiteCrawlProperties} 的两层并发上限）压住的，那是
 * 用业务配额补偿基础设施缺陷；分池才是正解。
 *
 * <p>⚠️ <b>新增 {@code @Async} 方法时必须显式归类</b>：长任务加 qualifier，短任务不加。
 * 漏加 qualifier 的长任务会掉回默认池、把上面那个问题原样带回来，而且<b>不会有任何报错</b>，
 * 只在生产上表现为审计与 webhook 莫名延迟。{@code AsyncExecutorRoutingTest} 会强制这个
 * 约定——它扫描全部 {@code @Async} 方法，凡不在短任务白名单里的都必须带长任务 qualifier。
 *
 * @author ruan
 */
@Configuration
@EnableAsync
@Slf4j
public class AsyncConfig {

    /** 长任务执行器的 bean 名。用常量而非字面量，拼错在编译期就暴露。 */
    public static final String LONG_TASK_EXECUTOR = "longTaskExecutor";

    /**
     * 长任务池的固定线程数。
     *
     * <p>{@code core == max} 是有意的：Java 线程池在<b>有界</b>队列下，只有队列满了才会把
     * 线程数从 core 扩到 max，所以配一个大于 core 的 max 在 500 容量的队列前面等于永远用不上，
     * 徒增理解成本。要调整并发度就直接调这个值。
     */
    @Value("${qingyu.care.async.long-task.pool-size:6}")
    private int longTaskPoolSize;

    /**
     * 长任务队列容量。取 500 是为了「实际排不满、但不至于无界堆积」：
     * 最大压力来自用户一次性上传上百个文档（每篇一个摄取任务），而站点爬取自身有全局上限。
     * 真排满说明系统已严重过载，此时抛 {@code TaskRejectedException} 让调用方感知，
     * 比默默堆积到 OOM 好——各调用方都有兜底（摄取失败文档停 PENDING 可重新处理、
     * 网页同步靠调度器捡回、爬取的 launch 会回滚互斥）。
     */
    @Value("${qingyu.care.async.long-task.queue-capacity:500}")
    private int longTaskQueueCapacity;

    /**
     * 长任务专用池。短任务继续走 Boot 默认的 {@code applicationTaskExecutor}——
     * 本类刻意不覆盖它，免得动到 webhook/审计日志那些既有行为。
     */
    @Bean(name = LONG_TASK_EXECUTOR)
    public Executor longTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(longTaskPoolSize);
        executor.setMaxPoolSize(longTaskPoolSize);
        executor.setQueueCapacity(longTaskQueueCapacity);
        executor.setThreadNamePrefix("long-task-");
        // 队列满即拒绝并抛 TaskRejectedException。不用 CallerRunsPolicy——那会让调用线程
        // （可能是 HTTP 请求线程）同步跑一个几十分钟的爬取，比拒绝糟得多
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        // 关闭时不等长任务跑完：站点爬取可能还有几十分钟，等它会把部署卡死。
        // 中断的后果各链路已有兜底（爬取进度是内存态、重跑幂等；摄取有咨询锁与状态机）
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        log.info("长任务执行器已就绪：线程数={} 队列容量={}", longTaskPoolSize, longTaskQueueCapacity);
        return executor;
    }
}
