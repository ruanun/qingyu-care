package com.qingyu.care.service;

import tools.jackson.databind.ObjectMapper;
import com.qingyu.care.config.TenantContext;
import com.qingyu.care.config.TenantOwnershipGuard;
import com.qingyu.care.entity.AiSkill;
import com.qingyu.care.entity.AiTool;
import com.qingyu.care.exception.ResourceNotFoundException;
import com.qingyu.care.repository.AiSkillRepository;
import com.qingyu.care.repository.AiToolRepository;
import com.qingyu.care.service.tool.HttpToolCallback;
import com.qingyu.care.service.tool.HttpToolExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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
        for (AiSkill s : skills) {
            if (s.getContent() != null && !s.getContent().isBlank()) {
                markdowns.add(s.getContent());
            }
            if (s.getToolIds() != null) {
                toolIds.addAll(s.getToolIds());
            }
        }
        List<ToolCallback> callbacks = new ArrayList<>();
        if (!toolIds.isEmpty()) {
            for (AiTool t : resolveTools(toolIds)) {
                callbacks.add(new HttpToolCallback(t, httpToolExecutor, objectMapper));
            }
        }
        return new ToolBundle(markdowns, callbacks);
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
     * 按当前应用收窄的工具列表。TenantDataAspect 对 PLATFORM_ADMIN 整体不启用 @Filter，
     * 裸 findAll 会让平台管理员跨应用读全平台工具；显式按 appId 收窄，无 appId（未选应用）返空。
     */
    public List<AiTool> listTools() {
        Long appId = TenantContext.getAppId();
        return appId == null ? List.of() : toolRepository.findByAppId(appId);
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

    // ===== AiSkill CRUD =====

    /** 按当前应用收窄的 skill 列表，语义同 {@link #listTools()}。 */
    public List<AiSkill> listSkills() {
        Long appId = TenantContext.getAppId();
        return appId == null ? List.of() : skillRepository.findByAppId(appId);
    }

    /** 按 id 取 skill（无密钥，用读级守卫 belongsToCurrentContext）。 */
    public AiSkill getSkill(Long id) {
        AiSkill s = skillRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("技能", id.toString()));
        if (!TenantOwnershipGuard.belongsToCurrentContext(s.getTenantId(), s.getAppId())) {
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
