package com.qingyu.care.scheduler;

import com.qingyu.care.repository.DocumentRepository;
import com.qingyu.care.service.web.WebPageSyncService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
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
    @InjectMocks WebPageSyncScheduler scheduler;

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
}
