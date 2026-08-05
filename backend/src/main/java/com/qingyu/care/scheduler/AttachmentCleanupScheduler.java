package com.qingyu.care.scheduler;

import com.qingyu.care.service.media.ChatAttachmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 清理「传了但没发送」的孤儿附件。
 *
 * @author ruan
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AttachmentCleanupScheduler {

    private final ChatAttachmentService chatAttachmentService;

    @Value("${qingyu.care.attachment.orphan-retain-hours:24}")
    private int retainHours;

    /** 每小时扫一次（首次延迟 10 分钟，避开启动高峰）；间隔外部化，仿本仓库其余 @Scheduled 写法 */
    @Scheduled(initialDelayString = "${qingyu.care.attachment.cleanup-initial-delay-ms:600000}",
               fixedDelayString = "${qingyu.care.attachment.cleanup-interval-ms:3600000}")
    public void cleanupOrphanAttachments() {
        try {
            int n = chatAttachmentService.cleanupOrphans(retainHours);
            if (n > 0) {
                log.info("孤儿附件清理完成：{} 个", n);
            }
        } catch (Exception e) {
            log.warn("孤儿附件清理意外失败: {}", e.getMessage());
        }
    }
}
