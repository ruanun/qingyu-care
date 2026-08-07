package com.qingyu.care.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守护「{@code @Async} 方法必须显式归类到两个池之一」这条约定。
 *
 * <p>背景见 {@link AsyncConfig} 的类注释：Boot 默认池是 core 8 + <b>无界队列</b>，
 * 无界队列下 core 占满后 maxPoolSize 永不扩容，于是只要 8 个长任务同时在跑，之后每一次
 * 审计日志落库与 webhook 投递都要排在它们后面。漏加 qualifier 的长任务会掉回默认池、
 * 把这个问题原样带回来，而且<b>不会有任何报错</b>——只在生产上表现为审计与 webhook 莫名延迟。
 *
 * <p>所以这里不是「检查现有那几个方法写对了没」，而是<b>强制新增方法必须做一次归类判断</b>：
 * 扫描全部 {@code @Async} 方法，凡不在下面白名单里的，一律必须带长任务 qualifier。
 * 新加一个长任务忘了写 qualifier → 红；新加一个短任务 → 必须显式加进白名单（红一次，
 * 逼作者想清楚它到底是不是短任务）。
 *
 * @author ruan
 */
class AsyncExecutorRoutingTest {

    /**
     * 短任务白名单——这些走 Boot 默认池，{@code @Async} 不带 qualifier。
     *
     * <p>判据是「毫秒到秒级 + 要求低延迟 + 失败基本无声」。往这里加条目前先确认它真的不会
     * 跑成分钟级：加错地方的代价是把长任务塞进短任务池，正是本测试要防的事。
     */
    private static final Set<String> SHORT_TASK_WHITELIST = Set.of(
            "com.qingyu.care.service.ApiUsageService#record",
            "com.qingyu.care.service.ApiUsageService#recordFallbackTokens",
            "com.qingyu.care.service.AuditLogService#saveAsync",
            "com.qingyu.care.service.webhook.WebhookQueue#dispatch",
            "com.qingyu.care.service.webhook.WebhookSender#deliverAsync"
    );

    @Test
    @DisplayName("每个 @Async 方法都被显式归类：短任务在白名单里且不带 qualifier，其余必须走长任务池")
    void every_async_method_is_explicitly_routed() {
        Set<String> foundShortTasks = new LinkedHashSet<>();
        List<String> misrouted = new ArrayList<>();

        for (Method method : scanAsyncMethods()) {
            String id = method.getDeclaringClass().getName() + "#" + method.getName();
            String qualifier = method.getAnnotation(Async.class).value();

            if (SHORT_TASK_WHITELIST.contains(id)) {
                foundShortTasks.add(id);
                if (!qualifier.isEmpty()) {
                    misrouted.add(id + " 在短任务白名单里，却带了 qualifier '" + qualifier
                            + "'——要么去掉 qualifier，要么把它从白名单移走");
                }
            } else if (!AsyncConfig.LONG_TASK_EXECUTOR.equals(qualifier)) {
                misrouted.add(id + " 未归类：qualifier 是 '" + qualifier
                        + "'，应为 @Async(AsyncConfig.LONG_TASK_EXECUTOR)；"
                        + "若它确实是毫秒级短任务，请显式加进本测试的 SHORT_TASK_WHITELIST");
            }
        }

        assertThat(misrouted).as("存在未按两池约定归类的 @Async 方法").isEmpty();

        // 白名单不能腐烂：列了却已不存在的条目要及时清掉，否则它会掩护一个同名新方法
        assertThat(foundShortTasks)
                .as("白名单里有条目在代码里已不存在（方法被删或改名），请同步清理")
                .containsExactlyInAnyOrderElementsOf(SHORT_TASK_WHITELIST);
    }

    @Test
    @DisplayName("长任务池是有界的：固定线程数 + 有界队列 + 满了就拒绝而不是让调用线程自己跑")
    void long_task_executor_is_bounded() {
        AsyncConfig config = new AsyncConfig();
        ReflectionTestUtils.setField(config, "longTaskPoolSize", 6);
        ReflectionTestUtils.setField(config, "longTaskQueueCapacity", 500);

        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) config.longTaskExecutor();
        try {
            assertThat(executor.getCorePoolSize()).isEqualTo(6);
            // core == max 是有意的：有界队列下 max 只在队列满后才生效，配大于 core 的 max 等于永远用不上
            assertThat(executor.getMaxPoolSize()).isEqualTo(executor.getCorePoolSize());
            // 队列必须有界，否则就退化成默认池那个「无界堆积 + max 永不扩容」的形态
            assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity()).isEqualTo(500);
            // 必须是 Abort 而非 CallerRuns：后者会让 HTTP 请求线程同步跑一个几十分钟的爬取
            assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
                    .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
        } finally {
            executor.shutdown();
        }
    }

    /** 扫描 {@code com.qingyu.care} 下所有 Spring 组件里带 {@code @Async} 的方法。 */
    private static List<Method> scanAsyncMethods() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(true);
        List<Method> methods = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents("com.qingyu.care")) {
            Class<?> type;
            try {
                type = Class.forName(definition.getBeanClassName());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("扫描到的类无法加载: " + definition.getBeanClassName(), e);
            }
            for (Method method : type.getDeclaredMethods()) {
                if (method.isAnnotationPresent(Async.class)) {
                    methods.add(method);
                }
            }
        }
        assertThat(methods)
                .as("一个 @Async 方法都没扫到，说明扫描逻辑本身坏了（这时上面的断言会全部空过）")
                .isNotEmpty();
        return methods;
    }
}
