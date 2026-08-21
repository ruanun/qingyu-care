package com.qingyu.care.service.mcp;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.config.TenantOwnershipGuard;
import com.qingyu.care.entity.McpServer;
import com.qingyu.care.entity.McpTool;
import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.exception.ResourceNotFoundException;
import com.qingyu.care.repository.McpServerRepository;
import com.qingyu.care.repository.McpToolRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * MCP server / tool 的管理面服务：CRUD + 工具清单同步。
 *
 * @author ruan
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class McpServerService {

    /** code 参与 LLM 可见的工具名，必须是合法标识符 */
    private static final Pattern CODE_PATTERN = Pattern.compile("^[a-z][a-z0-9_]*$");

    private final McpServerRepository serverRepository;
    private final McpToolRepository toolRepository;
    private final McpToolExecutor executor;

    /**
     * 按当前应用收窄的 server 列表。
     *
     * <p>TenantDataAspect 对 PLATFORM_ADMIN 整体不启用 @Filter，裸 findAll 会让平台管理员
     * 跨应用读全平台配置，故显式按 appId 收窄（同 ChatToolService.listTools 的处理）。
     */
    public List<McpServer> listServers() {
        Long appId = TenantContext.getAppId();
        return appId == null ? List.of() : serverRepository.findByAppId(appId);
    }

    /**
     * 按 id 取 server。含明文 authHeader，故用写级守卫：越权按「不存在」处理，
     * 防枚举探测与跨租户读取凭据（同 ChatToolService.getTool）。
     */
    public McpServer getServer(Long id) {
        McpServer s = serverRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("MCP 服务", id.toString()));
        if (!TenantOwnershipGuard.mutableInCurrentContext(s.getTenantId(), s.getAppId())) {
            throw new ResourceNotFoundException("MCP 服务", id.toString());
        }
        return s;
    }

    /**
     * 新建/更新 server。
     *
     * <p>⚠️ code 参与 LLM 可见的工具名（{@code {code}__{toolName}}），同一应用内若两个
     * server 用同一个 code，后续全局工具名去重会静默丢掉其中一个 server 的工具——
     * 不报错、不崩溃，运营完全看不出少挂了工具，故必须在保存时挡住。
     */
    @Transactional
    public McpServer saveServer(McpServer server) {
        if (server.getCode() == null || !CODE_PATTERN.matcher(server.getCode()).matches()) {
            throw new BusinessException("标识符只能是小写字母开头的字母、数字、下划线组合");
        }
        Long appId = TenantContext.getAppId();
        if (appId != null) {
            for (McpServer existing : serverRepository.findByAppId(appId)) {
                boolean sameCode = server.getCode().equals(existing.getCode());
                boolean isSelf = existing.getId() != null && existing.getId().equals(server.getId());
                if (sameCode && !isSelf) {
                    throw new BusinessException("标识符「" + server.getCode() + "」在当前应用内已被使用");
                }
            }
        }
        return serverRepository.save(server);
    }

    public List<McpTool> listTools(Long serverId) {
        getServer(serverId); // 守卫：不是自己的 server 直接按不存在处理
        return toolRepository.findByMcpServerId(serverId);
    }

    /**
     * 批量标注只读位，返回实际更新条数。
     *
     * <p>⚠️ 每个 id 都要单独过租户守卫，不能因为是批量就只校验第一个——否则遍历 id
     * 就能改别人的工具（readOnly 是唯一的安全判据，改掉它等于放开可写工具）。
     */
    @Transactional
    public int markReadOnly(List<Long> toolIds, boolean readOnly) {
        int updated = 0;
        for (Long id : toolIds) {
            Optional<McpTool> found = toolRepository.findById(id);
            if (found.isEmpty()) {
                continue;
            }
            McpTool t = found.get();
            if (!TenantOwnershipGuard.mutableInCurrentContext(t.getTenantId(), t.getAppId())) {
                log.warn("跳过越权的只读标注请求，工具 id={}", id);
                continue;
            }
            t.setReadOnly(readOnly);
            toolRepository.save(t);
            updated++;
        }
        return updated;
    }
}
