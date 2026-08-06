package com.qingyu.care.scheduler;

import com.qingyu.care.config.SiteCrawlProperties;
import com.qingyu.care.repository.DocumentRepository;
import com.qingyu.care.service.web.WebPageSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 单元测试：调度器逐个同步到期文档，且单个失败不中断其余。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class WebPageSyncSchedulerTest {

    @Mock DocumentRepository documentRepository;
    @Mock WebPageSyncService webPageSyncService;
    SiteCrawlProperties siteCrawlProperties;
    WebPageSyncScheduler scheduler;

    @BeforeEach
    void setUp() {
        siteCrawlProperties = new SiteCrawlProperties();
        siteCrawlProperties.setDefaultDelayMs(0);   // 默认不真的睡；只有时序相关用例显式调大
        scheduler = new WebPageSyncScheduler(documentRepository, webPageSyncService, siteCrawlProperties);
    }

    @Test
    void 逐个同步到期文档() {
        when(documentRepository.findDueWebPageIds(any(LocalDateTime.class))).thenReturn(List.of(1L, 2L, 3L));

        scheduler.syncDueWebPages();

        verify(webPageSyncService).syncDocument(1L);
        verify(webPageSyncService).syncDocument(2L);
        verify(webPageSyncService).syncDocument(3L);
    }

    @Test
    void 单个文档抛异常不中断其余() {
        when(documentRepository.findDueWebPageIds(any(LocalDateTime.class))).thenReturn(List.of(1L, 2L, 3L));
        doThrow(new RuntimeException("数据库连接断了")).when(webPageSyncService).syncDocument(2L);

        scheduler.syncDueWebPages();   // 不应抛穿

        verify(webPageSyncService, times(1)).syncDocument(1L);
        verify(webPageSyncService, times(1)).syncDocument(3L);   // 第 2 个失败后第 3 个仍执行
    }

    @Test
    void 无到期文档时不调用同步() {
        when(documentRepository.findDueWebPageIds(any(LocalDateTime.class))).thenReturn(List.of());

        scheduler.syncDueWebPages();

        verify(webPageSyncService, times(0)).syncDocument(any());
    }

    /**
     * 逐篇之间必须按 {@code SiteCrawlProperties.defaultDelayMs} 休眠，否则爬取产出的一批
     * 文档会同时到期，本调度器对同一 host 背靠背发请求，把爬取本身老老实实做到的限速
     * 抵消掉。3 篇文档、2 次间隔（第一篇之前不睡）：断言用严格的下限而非精确值——
     * 既能抓住"根本没睡"（0ms 必然落空）这个回归，又不会因系统调度的几毫秒抖动变 flaky。
     */
    @Test
    void 逐篇之间按平台配置间隔休眠() {
        siteCrawlProperties.setDefaultDelayMs(80);
        when(documentRepository.findDueWebPageIds(any(LocalDateTime.class))).thenReturn(List.of(1L, 2L, 3L));

        long start = System.currentTimeMillis();
        scheduler.syncDueWebPages();
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isGreaterThanOrEqualTo(150);   // 2 × 80ms 打了充足的下探容差
    }

    /**
     * 睡眠期间被中断：必须提前结束本轮（不吞掉中断、不把剩余文档硬跑完），
     * 且恢复线程的中断标志（不能吞掉中断本身，否则调用方/线程池感知不到）。
     */
    @Test
    void 睡眠期间被中断则提前结束且恢复中断标志() throws InterruptedException {
        siteCrawlProperties.setDefaultDelayMs(5000);
        when(documentRepository.findDueWebPageIds(any(LocalDateTime.class))).thenReturn(List.of(1L, 2L, 3L));

        AtomicBoolean interruptedAfterReturn = new AtomicBoolean();
        Thread worker = new Thread(() -> {
            scheduler.syncDueWebPages();
            interruptedAfterReturn.set(Thread.currentThread().isInterrupted());
        });
        worker.start();
        Thread.sleep(300);   // 确保 worker 已处理完第 1 篇、正睡在第 2 篇之前
        worker.interrupt();
        worker.join(2000);

        assertThat(worker.isAlive()).isFalse();
        verify(webPageSyncService, times(1)).syncDocument(1L);
        verify(webPageSyncService, never()).syncDocument(2L);
        verify(webPageSyncService, never()).syncDocument(3L);
        assertThat(interruptedAfterReturn.get()).isTrue();
    }
}
