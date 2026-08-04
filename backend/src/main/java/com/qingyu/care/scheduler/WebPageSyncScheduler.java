package com.qingyu.care.scheduler;

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
 * <p>到期查询走 {@link DocumentRepository#findDueWebPageIds} 的原生 SQL——本线程没有
 * TenantContext，普通 JPA 查询会被租户过滤器静默拒绝（详见该方法的注释）。
 *
 * <p>逐个 fail-soft：一篇失败不影响其余；失败的处理（计数、转手动）在
 * {@link WebPageSyncService#syncDocument} 内部完成，此处只兜住意外异常。
 *
 * @author ruan
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WebPageSyncScheduler {

    private final DocumentRepository documentRepository;
    private final WebPageSyncService webPageSyncService;

    /** 每小时执行一次（首次延迟 5 分钟，避开应用启动高峰）。 */
    @Scheduled(initialDelay = 5 * 60 * 1000L, fixedDelay = 60 * 60 * 1000L)
    public void syncDueWebPages() {
        List<Long> dueIds = documentRepository.findDueWebPageIds(LocalDateTime.now());
        if (dueIds.isEmpty()) {
            return;
        }
        log.info("网页定时同步：{} 篇到期", dueIds.size());
        for (Long id : dueIds) {
            try {
                webPageSyncService.syncDocument(id);
            } catch (Exception e) {
                log.warn("网页定时同步意外失败 id={}: {}", id, e.getMessage());
            }
        }
    }
}
