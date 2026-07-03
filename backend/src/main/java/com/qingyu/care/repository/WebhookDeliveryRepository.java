package com.qingyu.care.repository;

import com.qingyu.care.entity.WebhookDelivery;
import com.qingyu.care.enums.WebhookDeliveryStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Webhook 投递仓库：条件认领 + 到期扫描。
 *
 * @author ruan
 */
@Repository
public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, Long> {

    /**
     * 认领一条待投递记录：仅当仍为 PENDING 时置 PROCESSING。
     * 返回受影响行数（1=认领成功，0=已被他人领走）。自带事务，立即提交，
     * 让其它线程/实例看到 PROCESSING，避免重复投递。
     */
    @Transactional
    @Modifying
    @Query(value = "UPDATE sys_webhook_delivery SET status = 'PROCESSING', updated_at = CURRENT_TIMESTAMP " +
            "WHERE id = :id AND status = 'PENDING'", nativeQuery = true)
    int claim(@Param("id") Long id);

    /** 扫描到期待重试的投递（PENDING 且 next_attempt_at 已到）。 */
    List<WebhookDelivery> findByStatusAndNextAttemptAtLessThanEqual(
            WebhookDeliveryStatus status, LocalDateTime now, Pageable pageable);
}
