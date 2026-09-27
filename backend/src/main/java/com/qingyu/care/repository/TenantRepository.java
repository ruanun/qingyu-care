package com.qingyu.care.repository;

import com.qingyu.care.entity.Tenant;
import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import com.qingyu.care.enums.TenantStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TenantRepository extends JpaRepository<Tenant, Long> {
    
    Optional<Tenant> findByCode(String code);
    
    List<Tenant> findByStatus(TenantStatus status);
    
    boolean existsByCode(String code);

    /** 到期扫描：状态仍是 ACTIVE、到期时间已过的租户 */
    List<Tenant> findByStatusAndExpireTimeBefore(TenantStatus status, LocalDateTime time);

    /**
     * 条件更新状态（CAS）：只有当前状态等于 {@code from} 才改，返回受影响行数。
     * 多实例同时扫描、或平台管理员恰好在扫描时改了状态，只有真正改成功的那一方继续做后续动作。
     * 方法级事务让每个租户单独提交，一个失败不回滚其它。
     */
    @Transactional
    @Modifying
    @Query("UPDATE Tenant t SET t.status = :to WHERE t.id = :id AND t.status = :from")
    int transitionStatus(@Param("id") Long id, @Param("from") TenantStatus from, @Param("to") TenantStatus to);
}
