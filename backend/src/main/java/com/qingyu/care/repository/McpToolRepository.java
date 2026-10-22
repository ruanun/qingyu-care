package com.qingyu.care.repository;

import com.qingyu.care.entity.McpTool;
import com.qingyu.care.enums.McpToolStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * MCP 工具仓库。同 McpServerRepository，全部 filter-bound。
 *
 * @author ruan
 */
public interface McpToolRepository extends JpaRepository<McpTool, Long> {

    List<McpTool> findByMcpServerId(Long mcpServerId);

    Optional<McpTool> findByMcpServerIdAndName(Long mcpServerId, String name);

    /**
     * 运行时解析用：只取「远端仍存在」且「租户标注为只读」的工具。
     * 只读门槛与 DEPRECATED 过滤都落在这一条查询上，不在界面上拦。
     */
    List<McpTool> findByIdInAndStatusAndReadOnlyTrue(Collection<Long> ids, McpToolStatus status);

    void deleteByMcpServerId(Long mcpServerId);
}
