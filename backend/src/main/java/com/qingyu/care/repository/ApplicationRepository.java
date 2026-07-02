package com.qingyu.care.repository;

import com.qingyu.care.entity.Application;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ApplicationRepository extends JpaRepository<Application, Long> {
    
    List<Application> findByTenantIdOrderByCreatedAtDesc(Long tenantId);
    
    Optional<Application> findByAppKey(String appKey);
    
    boolean existsByAppKey(String appKey);
    
    List<Application> findByTenantIdAndEnabledTrue(Long tenantId);
}
