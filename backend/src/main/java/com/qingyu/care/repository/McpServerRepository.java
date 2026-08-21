package com.qingyu.care.repository;

import com.qingyu.care.entity.McpServer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * MCP server 仓库。全部为 filter-bound 查询——McpServer 继承 BaseTenantEntity，
 * @Filter 自动挡掉别的租户的行，**本期不需要也不应该写 native 查询绕过过滤器**
 * （那正是平台共享工具那条路径反复出安全问题的地方）。
 *
 * @author ruan
 */
public interface McpServerRepository extends JpaRepository<McpServer, Long> {

    List<McpServer> findByAppId(Long appId);

    Optional<McpServer> findByCode(String code);
}
