package com.qingyu.care.repository;

import com.qingyu.care.entity.User;
import com.qingyu.care.enums.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    
    Optional<User> findByUsername(String username);
    
    boolean existsByUsername(String username);
    
    List<User> findByTenantIdOrderByCreatedAtDesc(Long tenantId);
    
    List<User> findByTenantId(Long tenantId);
    
    List<User> findByRole(UserRole role);
    
    List<User> findByTenantIdAndEnabledTrue(Long tenantId);
}
