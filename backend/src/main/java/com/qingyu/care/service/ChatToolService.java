package com.qingyu.care.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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
            for (AiTool t : toolRepository.findByIdInAndEnabledTrue(toolIds)) {
                callbacks.add(new HttpToolCallback(t, httpToolExecutor, objectMapper));
            }
        }
        return new ToolBundle(markdowns, callbacks);
    }

    // ===== AiTool CRUD =====

    public List<AiTool> listTools() {
        return toolRepository.findAll();
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

    public List<AiSkill> listSkills() {
        return skillRepository.findAll();
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
