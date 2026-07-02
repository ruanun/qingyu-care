package com.qingyu.care.repository;

import com.qingyu.care.entity.Tenant;
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
}
