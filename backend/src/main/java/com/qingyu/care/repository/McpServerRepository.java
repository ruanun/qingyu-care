package com.qingyu.care.repository;

import com.qingyu.care.entity.McpServer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * MCP server 仓库。全部为 filter-bound 查询——McpServer 继承 BaseTenantEntity，
 * @Filter 自动挡掉别的租户的行，**本期不需要也不应该写 native 查询绕过过滤器**
 * （那正是平台共享工具那条路径反复出安全问题的地方）。
 *
 * <p>⚠️ 不要加回 {@code findByCode(String)}：code 只在同一 appId 范围内唯一
 * （见 {@code McpServerService.saveServer}），全局按 code 查天生返回 0~N 行。
 * 若声明为 {@code Optional<McpServer>}，PLATFORM_ADMIN 绕过 {@code @Filter} 时
 * 两个租户凑巧用了同一个 code 就会抛 {@code IncorrectResultSizeDataAccessException} → 500
 * ——同「平台管理员配置解析 NonUnique 500」那个事故同一形状。真要按 code 查，必须写
 * {@code findByAppIdAndCode} 收窄范围。
 *
 * @author ruan
 */
public interface McpServerRepository extends JpaRepository<McpServer, Long> {

    List<McpServer> findByAppId(Long appId);
}
