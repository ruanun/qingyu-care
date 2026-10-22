package com.qingyu.care.repository;

import com.qingyu.care.entity.User;
import com.qingyu.care.enums.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * 按用户名查用户，<b>绕过租户过滤器</b>。
     *
     * <p>⚠️ 必须是 native 查询，且刻意<b>不提供</b>派生的 {@code findByUsername}——
     * {@code User} 上标着 {@code @Filter(tenantFilter)}，派生查询会被
     * {@code TenantDataAspect} 加上 {@code tenant_id = :tenantId}。而 username 是
     * <b>全局唯一键</b>（{@code @Column(unique = true)}），按它查找天然跨租户，被过滤就是错的。
     *
     * <p>{@code TenantDataAspect} 为此做过豁免，但判据是「未登录」——那是个间接代理量，
     * 假设「登录/注册总发生在未登录态」。上一个账号的 satoken 还在时（浏览器 cookie 残留最
     * 常见，{@code localStorage.clear()} 清不掉它）该假设不成立，过滤器照常生效，症状是
     * 登录报「用户名或密码错误」而密码其实是对的。由 {@code LoginIT} 守护。
     */
    @Query(value = "SELECT * FROM sys_user WHERE username = :username", nativeQuery = true)
    Optional<User> findByUsernameAcrossTenants(@Param("username") String username);

    /**
     * 用户名是否已被占用，<b>绕过租户过滤器</b>。理由同
     * {@link #findByUsernameAcrossTenants}：唯一性检查若只在当前租户内查，重名会被放行到
     * INSERT 撞唯一约束——HTTP 500 且把原始 SQL 与约束名回给客户端，而正确响应是
     * 400「用户名已存在」。
     */
    @Query(value = "SELECT count(*) > 0 FROM sys_user WHERE username = :username", nativeQuery = true)
    boolean existsByUsernameAcrossTenants(@Param("username") String username);


    List<User> findByTenantIdOrderByCreatedAtDesc(Long tenantId);
    
    List<User> findByTenantId(Long tenantId);
    
    List<User> findByRole(UserRole role);
    
    List<User> findByTenantIdAndEnabledTrue(Long tenantId);
}
