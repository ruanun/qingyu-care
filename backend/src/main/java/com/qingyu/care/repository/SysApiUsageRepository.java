package com.qingyu.care.repository;

import com.qingyu.care.entity.SysApiUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * 开放 API 用量仓库：原子累加 + 查询。
 *
 * @author ruan
 */
@Repository
public interface SysApiUsageRepository extends JpaRepository<SysApiUsage, Long> {

    /**
     * 原子累加当天用量，返回受影响行数（0 表示当天尚无记录，需 insert）。
     * {@code @Modifying} 更新必须在事务中执行；调用方 ApiUsageService.record 刻意不加
     * 方法级事务（避免唯一键冲突把整方法标记 rollback-only），故在此让本方法各走独立事务。
     */
    @Modifying
    @Transactional
    @Query(value = "UPDATE sys_api_usage SET call_count = call_count + 1, " +
            "message_count = message_count + :messages, token_count = token_count + :tokens, " +
            "updated_at = CURRENT_TIMESTAMP WHERE app_id = :appId AND usage_date = :date", nativeQuery = true)
    int incrementUsage(@Param("appId") Long appId, @Param("date") LocalDate date,
                       @Param("messages") long messages, @Param("tokens") long tokens);
}
