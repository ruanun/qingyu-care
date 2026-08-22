package com.qingyu.care.service.mcp;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.config.TenantOwnershipGuard;
import com.qingyu.care.entity.AiSkill;
import com.qingyu.care.entity.McpServer;
import com.qingyu.care.entity.McpTool;
import com.qingyu.care.enums.McpToolStatus;
import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.exception.ResourceNotFoundException;
import com.qingyu.care.repository.AiSkillRepository;
import com.qingyu.care.repository.McpServerRepository;
import com.qingyu.care.repository.McpToolRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
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

    /** 与 {@code McpServer.lastSyncError} 的列宽一致，见 {@link #recordSyncFailure} */
    private static final int MAX_SYNC_ERROR_LENGTH = 1000;

    private final McpServerRepository serverRepository;
    private final McpToolRepository toolRepository;
    private final McpToolExecutor executor;
    private final AiSkillRepository skillRepository;

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

    /**
     * 手动同步远端工具清单。请求线程内执行（一次往返、秒级，不进长任务池）。
     *
     * <p>三条分支：
     * <ul>
     *   <li>远端有、库里有 → 更新 description/inputSchema/readOnlyHint，status 置 ACTIVE
     *   <li>远端有、库里无 → 新建，readOnly=false（未知一律当可写）
     *   <li>库里有、远端无 → 标 DEPRECATED，<b>不删行</b>——工具可能已被 skill 绑定，
     *       删行会让绑定悬空（resolve 查不到、界面也看不到，运营无从得知）
     * </ul>
     *
     * <p>⚠️ <b>绝不覆盖 readOnly</b>：它是租户的安全标注，被远端的 schema 更新顺手冲掉，
     * 等于静默把一个可写工具放回只读白名单。
     *
     * <p>⚠️ 失败时只写 lastSyncError、<b>一行工具都不动</b>：一次网络抖动不该让整套工具
     * 集体变 DEPRECATED 进而从对话里消失（语义同 WebPageSyncService 的「抓取失败保留旧内容」）。
     *
     * <p>⚠️ <b>本方法刻意不带 {@code @Transactional}</b>（复核终审 Finding 1）：带上它时，
     * catch 块里 {@code server.setLastSyncError(...); serverRepository.save(server);} 这次
     * 写入会在紧接着的 {@code throw new BusinessException(...)} 后被 Spring 默认回滚规则丢弃
     * ——{@code BusinessException extends RuntimeException}，方法级 {@code @Transactional}
     * 遇到未捕获的 unchecked 异常一律整体回滚，与「保留哪次 save」无关，行内 save 与外层事务
     * 提交与否是两件事。后果是 {@code lastSyncError} 永远落不了库：界面的琥珀色警示条、
     * 服务列表的失败图标全部沦为死代码，运营看不到任何失败原因。三条 repository 调用各自
     * 已有 Spring Data JPA 默认的方法级事务会立即提交，去掉外层 {@code @Transactional} 不影响
     * 单条 save 的原子性；代价是成功路径的多次 save 不再共享同一事务，可接受
     * ——同 {@link com.qingyu.care.service.web.SiteCrawlService#startCrawl} 的取舍。
     * 顺带关闭一条 deferred minor：不再让一次可能长达 {@code timeoutMs} 的出站网络往返占着
     * 数据库连接。
     */
    public List<McpTool> sync(Long serverId) {
        McpServer server = getServer(serverId);

        List<McpToolDescriptor> remote;
        try {
            remote = executor.listTools(server);
        } catch (Exception e) {
            throw recordSyncFailure(server, serverId, e);
        }

        try {
            Set<String> remoteNames = new LinkedHashSet<>();
            for (McpToolDescriptor d : remote) {
                remoteNames.add(d.name());
                McpTool tool = toolRepository.findByMcpServerIdAndName(serverId, d.name())
                        .orElseGet(() -> McpTool.builder()
                                .mcpServerId(serverId)
                                .name(d.name())
                                .readOnly(false) // 未知一律当可写、不挂载
                                .build());
                tool.setDescription(d.description());
                tool.setInputSchema(d.inputSchemaJson());
                tool.setReadOnlyHint(d.readOnlyHint());
                tool.setStatus(McpToolStatus.ACTIVE);
                // ⚠️ 这里刻意不碰 tool.setReadOnly(...)，见方法注释
                toolRepository.save(tool);
            }

            for (McpTool local : toolRepository.findByMcpServerId(serverId)) {
                if (!remoteNames.contains(local.getName()) && local.getStatus() != McpToolStatus.DEPRECATED) {
                    local.setStatus(McpToolStatus.DEPRECATED);
                    toolRepository.save(local);
                }
            }
        } catch (Exception e) {
            throw recordSyncFailure(server, serverId, e);
        }

        server.setLastSyncedAt(LocalDateTime.now());
        server.setLastSyncError(null);
        serverRepository.save(server);
        return toolRepository.findByMcpServerId(serverId);
    }

    /**
     * 记录同步失败原因并构造对外异常。<b>拉取阶段与写库阶段都必须走这里</b>。
     *
     * <p>⚠️ 早先只有拉取阶段被 try 包住，写库阶段的异常直接穿透到 {@code GlobalExceptionHandler}
     * ——真机接入 context7 时实拍到的形态：HTTP 500、原始 SQL 回给客户端，而 {@code lastSyncError}
     * 一片空白，界面的琥珀色警示条与列表失败图标全部不亮，运营只看到一个没有原因的报错。
     *
     * <p>⚠️ <b>消息必须截断</b>：{@code lastSyncError} 自己是 {@code varchar(1000)}，
     * 不截断则这次 save 会再抛一次 {@code DataIntegrityViolationException}，把「已经处理好的
     * 失败」重新变成 500 且连原因都留不下——正是本方法要修的那个形态，换个位置复发。
     *
     * <p>⚠️ <b>数据库异常不把原始消息回给客户端</b>：那是库表结构与 SQL 语句，租户既看不懂也
     * 修不了；全量异常进服务端日志，界面只留可读原因。远端返回的错误则原样保留——那是运营
     * 唯一能自助排查的线索（凭据过期、工具下线等）。
     *
     * <p>⚠️ 写库阶段失败时<b>已写入的工具行不会回滚</b>：{@code sync()} 刻意不带
     * {@code @Transactional}（见方法注释），逐条 save 各自提交。库里因此可能留下半截状态，
     * 重新同步一次即可收敛——这是「失败原因必须落库」与「不长时间占着数据库连接」之间的取舍。
     */
    private BusinessException recordSyncFailure(McpServer server, Long serverId, Exception e) {
        boolean isDataAccess = e instanceof DataAccessException;
        String reason = isDataAccess
                ? "工具清单写入数据库失败，请联系平台管理员查看服务端日志"
                : String.valueOf(e.getMessage());
        server.setLastSyncError(truncateSyncError(reason));
        serverRepository.save(server);
        if (isDataAccess) {
            log.warn("MCP 同步写库失败 serverId={}", serverId, e);
        } else {
            log.warn("MCP 同步失败 serverId={} 原因={}", serverId, e.getClass().getSimpleName());
        }
        return new BusinessException("同步失败：" + reason);
    }

    /** 截到 {@code McpServer.lastSyncError} 的列宽以内。 */
    private String truncateSyncError(String reason) {
        return reason.length() <= MAX_SYNC_ERROR_LENGTH
                ? reason
                : reason.substring(0, MAX_SYNC_ERROR_LENGTH);
    }

    /** 统计有多少 skill 绑定了该 server 的工具，供前端删除确认弹窗显示。 */
    public long countSkillsUsing(Long serverId) {
        Set<Long> owned = ownedToolIds(serverId);
        if (owned.isEmpty()) {
            return 0;
        }
        return skillRepository.findAll().stream()
                .filter(s -> s.getMcpToolIds() != null
                        && s.getMcpToolIds().stream().anyMatch(owned::contains))
                .count();
    }

    /**
     * 删除 server：连带删工具行，<b>并摘掉 skill 里指向它们的绑定</b>。
     *
     * <p>⚠️ 不清理绑定的后果是静默降级：resolve() 的 filter-bound 查询查不到就自然跳过，
     * 不报错也不打日志——skill 在界面上看起来仍绑着 N 个工具，实际挂给 LLM 的少了几个。
     * 同「删站点勾选删文档必须逐篇走 deleteDocument」是同一类问题。
     *
     * <p>⚠️ 遍历 skill 用普通 JPA 查询即可：删除发生在请求线程、TenantContext 齐备，
     * @Filter 会挡掉别的租户；<b>不要为此写 native SQL</b>，那会连别人的 skill 一起扫到。
     */
    @Transactional
    public void deleteServer(Long serverId) {
        McpServer server = getServer(serverId);
        Set<Long> owned = ownedToolIds(serverId);

        if (!owned.isEmpty()) {
            for (AiSkill skill : skillRepository.findAll()) {
                if (skill.getMcpToolIds() == null || skill.getMcpToolIds().isEmpty()) {
                    continue;
                }
                if (skill.getMcpToolIds().removeIf(owned::contains)) {
                    skillRepository.save(skill);
                }
            }
        }
        toolRepository.deleteByMcpServerId(serverId);
        serverRepository.delete(server);
    }

    private Set<Long> ownedToolIds(Long serverId) {
        Set<Long> ids = new LinkedHashSet<>();
        for (McpTool t : toolRepository.findByMcpServerId(serverId)) {
            ids.add(t.getId());
        }
        return ids;
    }
}
