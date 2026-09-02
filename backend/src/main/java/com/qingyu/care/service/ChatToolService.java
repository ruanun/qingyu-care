package com.qingyu.care.service;

import tools.jackson.databind.ObjectMapper;
import com.qingyu.care.config.SharedCredentialMasker;
import com.qingyu.care.config.TenantContext;
import com.qingyu.care.config.TenantOwnershipGuard;
import com.qingyu.care.entity.AiSkill;
import com.qingyu.care.entity.AiTool;
import com.qingyu.care.entity.McpServer;
import com.qingyu.care.entity.McpTool;
import com.qingyu.care.enums.McpToolStatus;
import com.qingyu.care.enums.OwnerScope;
import com.qingyu.care.exception.ResourceNotFoundException;
import com.qingyu.care.repository.AiSkillRepository;
import com.qingyu.care.repository.AiToolRepository;
import com.qingyu.care.repository.McpServerRepository;
import com.qingyu.care.repository.McpToolRepository;
import com.qingyu.care.service.mcp.McpToolCallback;
import com.qingyu.care.service.mcp.McpToolExecutor;
import com.qingyu.care.service.tool.HttpToolCallback;
import com.qingyu.care.service.tool.HttpToolExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工具/Skill 运行时解析 + AiTool 管理面 CRUD。resolve() 须在请求线程调用（依赖 TenantContext 的应用隔离）。
 *
 * @author ruan
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChatToolService {

    private final AiToolRepository toolRepository;
    private final AiSkillRepository skillRepository;
    private final HttpToolExecutor httpToolExecutor;
    private final ObjectMapper objectMapper;
    private final SharedCredentialMasker credentialMasker;
    private final McpToolRepository mcpToolRepository;
    private final McpServerRepository mcpServerRepository;
    private final McpToolExecutor mcpToolExecutor;

    /** 运行时解析包：待注入系统提示的 skill 正文 + 待挂载的工具回调。 */
    public record ToolBundle(List<String> skillMarkdowns, List<ToolCallback> toolCallbacks) {
        public static ToolBundle empty() {
            return new ToolBundle(List.of(), List.of());
        }

        public boolean isEmpty() {
            return skillMarkdowns.isEmpty() && toolCallbacks.isEmpty();
        }
    }

    /**
     * 取当前应用 enabled 的 skill，收集正文 + 其绑定工具的并集，组装为 ToolBundle。
     * 工具只经 skill 激活；无 skill 时返回 empty（chat() 据此保持零回归）。
     */
    public ToolBundle resolve() {
        List<AiSkill> skills = skillRepository.findByEnabledTrue();
        if (skills.isEmpty()) {
            return ToolBundle.empty();
        }
        List<String> markdowns = new ArrayList<>();
        Set<Long> toolIds = new LinkedHashSet<>();
        Set<Long> mcpToolIds = new LinkedHashSet<>();
        for (AiSkill s : skills) {
            if (s.getContent() != null && !s.getContent().isBlank()) {
                markdowns.add(s.getContent());
            }
            if (s.getToolIds() != null) {
                toolIds.addAll(s.getToolIds());
            }
            if (s.getMcpToolIds() != null) {
                mcpToolIds.addAll(s.getMcpToolIds());
            }
        }
        List<ToolCallback> callbacks = new ArrayList<>();
        Set<String> exposedNames = new LinkedHashSet<>();
        if (!toolIds.isEmpty()) {
            for (AiTool t : resolveTools(toolIds)) {
                if (!exposedNames.add(t.getName())) {
                    log.warn("工具名重复，跳过 HTTP 工具 {}", t.getName());
                    continue;
                }
                callbacks.add(new HttpToolCallback(t, httpToolExecutor, objectMapper));
            }
        }
        if (!mcpToolIds.isEmpty()) {
            callbacks.addAll(resolveMcpCallbacks(mcpToolIds, exposedNames));
        }
        return new ToolBundle(markdowns, callbacks);
    }

    /**
     * 解析 MCP 工具为 callback。
     *
     * <p><b>只读门槛与 DEPRECATED 过滤都落在这条查询上</b>，不在界面上拦——界面拦得住的
     * 东西，接口层拦不住。
     *
     * <p>查询是 filter-bound 的普通 JPA 查询：McpTool 继承 BaseTenantEntity，@Filter 会
     * 挡掉别的租户的 id。⚠️ <b>不要改成 native</b>，本期没有平台共享，绕过过滤器只会引入
     * 跨租户越权（那正是共享工具那条路径反复出问题的地方）。
     *
     * <p>⚠️ <b>取 server 用的 {@code findById} 不受 @Filter 保护</b>（Hibernate 6 的
     * {@code @Filter} 只拦截查询，不拦截主键加载）。这条路径的安全性本来只靠一个隐式不变量
     * ——「本租户的 McpTool.mcpServerId 只可能指向本租户的 McpServer」（由 Task 6 的同步逻辑
     * 建立）——是传递信任而非显式防御。故取到 server 后必须再用
     * {@link TenantOwnershipGuard#mutableInCurrentContext} 显式校验一次归属。
     *
     * <p>⚠️ <b>必须用写级守卫，不能用读级的 {@code belongsToCurrentContext}</b>——本仓库对两者的
     * 取舍标准不是「读操作还是写操作」，而是「这个实体带不带密钥」：{@code getTool}（本文件）与
     * {@code McpServerService.getServer} 对同样含明文 authHeader 的实体都用
     * {@code mutableInCurrentContext}，只有无密钥的 {@code getSkill} 才用读级守卫。原因是
     * {@code belongsToCurrentContext} 对 {@code entityTenantId == null} 无条件放行（那条豁免是
     * 为「平台默认配置=有意共享」设计的），而本期 MCP 没有平台共享（{@code McpServer.scope} 恒为
     * {@code TENANT}）——库里任何 {@code tenantId} 为 null 的 {@code McpServer} 行只可能是
     * {@code TenantContext} 为空时误建（本仓库反复出过的「tenantId 静默落 NULL」事故模式），
     * 用读级守卫会让这类误建行的明文 authHeader 被任意租户的对话链路取用去发起出站请求。
     */
    private List<ToolCallback> resolveMcpCallbacks(Set<Long> mcpToolIds, Set<String> exposedNames) {
        List<McpTool> tools =
                mcpToolRepository.findByIdInAndStatusAndReadOnlyTrue(mcpToolIds, McpToolStatus.ACTIVE);
        List<ToolCallback> out = new ArrayList<>();
        Map<Long, McpServer> serverCache = new LinkedHashMap<>();
        for (McpTool t : tools) {
            McpServer server = serverCache.computeIfAbsent(t.getMcpServerId(),
                    id -> mcpServerRepository.findById(id).orElse(null));
            if (server == null) {
                log.warn("MCP server 不存在，跳过工具 mcpToolId={}", t.getId());
                continue;
            }
            if (!Boolean.TRUE.equals(server.getEnabled())) {
                log.warn("MCP server {} 已停用，跳过工具 {}", server.getCode(), t.getName());
                continue;
            }
            if (!TenantOwnershipGuard.mutableInCurrentContext(server.getTenantId(), server.getAppId())) {
                log.warn("MCP server {} 不属于当前租户上下文，跳过工具 mcpToolId={}", server.getId(), t.getId());
                continue;
            }
            String exposed = McpToolCallback.exposedName(server, t);
            if (!exposedNames.add(exposed)) {
                log.warn("工具名重复，跳过 MCP 工具 {}", exposed);
                continue;
            }
            out.add(new McpToolCallback(server, t, mcpToolExecutor, objectMapper));
        }
        return out;
    }

    /**
     * 按 id 集合解析工具：当前应用自有 ∪ 平台共享。
     *
     * <p>先查自有（filter-bound，天然只看得到本租户本应用的行），剩下没命中的 id 再去查平台
     * 共享（native，钉死 {@code scope='PLATFORM'}）。同一个 id 不可能既是自有又是共享，
     * 所以顺序只影响查询次数不影响结果；自有全部命中时省掉第二条查询——绝大多数租户没绑
     * 共享工具，不该为此多打一次库。
     *
     * <p><b>安全</b>：别的租户的工具 id 在两条查询里都覆盖不到——自有查询有 @Filter 挡着，
     * 共享查询有 {@code scope='PLATFORM'} 挡着。少任何一道都会变成跨租户越权调用。
     *
     * <p><b>这里拿到的必须是未脱敏的原始实体</b>：{@code HttpToolCallback} 要用 authHeader
     * 真的发出站请求，绝不能在这条路径上套 {@code SharedCredentialMasker}。
     */
    private List<AiTool> resolveTools(Set<Long> toolIds) {
        List<AiTool> tools = new ArrayList<>(toolRepository.findByIdInAndEnabledTrue(toolIds));
        Set<Long> found = new LinkedHashSet<>();
        for (AiTool t : tools) {
            found.add(t.getId());
        }
        Set<Long> rest = new LinkedHashSet<>(toolIds);
        rest.removeAll(found);
        if (!rest.isEmpty()) {
            tools.addAll(toolRepository.findSharedByIdInAndEnabledTrue(rest));
        }
        return tools;
    }

    // ===== AiTool CRUD =====

    /**
     * 按当前应用收窄的工具列表 ∪ 平台共享工具。
     *
     * <p>TenantDataAspect 对 PLATFORM_ADMIN 整体不启用 @Filter，裸 findAll 会让平台管理员
     * 跨应用读全平台工具；显式按 appId 收窄，无 appId（未选应用）时自有部分为空，但平台共享
     * 工具仍须可见——租户要能在 skill 里勾选它们。
     *
     * <p>返回前经 {@link SharedCredentialMasker} 脱敏：平台共享工具的 authHeader 是平台的
     * 凭据，租户可以<b>使用</b>但不能<b>看到</b>。
     */
    public List<AiTool> listTools() {
        Long appId = TenantContext.getAppId();
        List<AiTool> own = appId == null ? List.of() : toolRepository.findByAppId(appId);
        List<AiTool> shared = toolRepository.findAllSharedTools();
        List<AiTool> merged = mergeById(own, shared);
        return credentialMasker.mask(merged, AiTool::getTenantId, AiTool::getAppId,
                t -> t.setAuthHeader(null));
    }

    /** 自有优先、按 id 去重合并（范式同 ModelRouterService.mergeOwnAndSharedById）。 */
    private List<AiTool> mergeById(List<AiTool> own, List<AiTool> shared) {
        java.util.LinkedHashMap<Long, AiTool> byId = new java.util.LinkedHashMap<>();
        for (AiTool t : own) byId.putIfAbsent(t.getId(), t);
        for (AiTool t : shared) byId.putIfAbsent(t.getId(), t);
        return new ArrayList<>(byId.values());
    }

    /**
     * 按 id 取工具。含明文 authHeader，用写级守卫（同 ModelRouterService.getById 对 apiKey 的处理）：
     * 越权按「不存在」处理，防枚举探测与跨租户读取凭据。
     */
    public AiTool getTool(Long id) {
        AiTool t = toolRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("工具", id.toString()));
        if (!TenantOwnershipGuard.mutableInCurrentContext(t.getTenantId(), t.getAppId())) {
            throw new ResourceNotFoundException("工具", id.toString());
        }
        return t;
    }

    @Transactional
    public AiTool saveTool(AiTool t) {
        return toolRepository.save(t);
    }

    @Transactional
    public void deleteTool(Long id) {
        AiTool t = getTool(id);
        toolRepository.delete(t);
    }

    // ===== 平台共享工具（仅平台管理员，端点侧已 requirePlatformAdmin） =====

    /** 列出全部平台共享工具。不脱敏——本方法只被平台管理员调用，维护时必须能看到并回填凭据。 */
    public List<AiTool> listSharedTools() {
        return toolRepository.findAllSharedTools();
    }

    /** 保存平台共享工具（强制平台归属，避免被 TenantEntityListener 回填租户，也不信任请求体）。 */
    @Transactional
    public AiTool saveSharedTool(AiTool t) {
        t.setScope(OwnerScope.PLATFORM);
        t.setTenantId(null);
        t.setAppId(null);
        return toolRepository.save(t);
    }

    /** 删除平台共享工具。只允许删 PLATFORM 行，避免拿租户私有工具的 id 越权删除。 */
    @Transactional
    public void deleteSharedTool(Long id) {
        AiTool t = toolRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("共享工具", id.toString()));
        if (t.getScope() != OwnerScope.PLATFORM) {
            throw new ResourceNotFoundException("共享工具", id.toString());
        }
        toolRepository.delete(t);
    }

    // ===== AiSkill CRUD =====

    /** 按当前应用收窄的 skill 列表，语义同 {@link #listTools()}。 */
    public List<AiSkill> listSkills() {
        Long appId = TenantContext.getAppId();
        return appId == null ? List.of() : skillRepository.findByAppId(appId);
    }

    /**
     * 按 id 取 skill。
     *
     * <p>⚠️ <b>用写级守卫</b>。此前这里用读级守卫，理由是「skill 无密钥」——但那是个代理量，
     * 它代理的是「值不值得保护」，而 skill 的 {@code content} 是注入每一轮系统提示的提示词，
     * 本身就是资产；更直接的是本仓库<b>刻意不共享 skill</b>（共享它等于静默改掉所有租户的回答
     * 行为），所以 {@code tenant_id IS NULL} 的 skill 行<b>不存在共享意图</b>，只可能是平台
     * 管理员无上下文时建的。判据见 {@link TenantOwnershipGuard} 类注释。
     */
    public AiSkill getSkill(Long id) {
        AiSkill s = skillRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("技能", id.toString()));
        if (!TenantOwnershipGuard.mutableInCurrentContext(s.getTenantId(), s.getAppId())) {
            throw new ResourceNotFoundException("技能", id.toString());
        }
        return s;
    }

    @Transactional
    public AiSkill saveSkill(AiSkill s) {
        return skillRepository.save(s);
    }

    @Transactional
    public void deleteSkill(Long id) {
        AiSkill s = getSkill(id);
        skillRepository.delete(s);
    }
}
