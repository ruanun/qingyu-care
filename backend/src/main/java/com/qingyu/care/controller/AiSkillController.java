package com.qingyu.care.controller;

import com.qingyu.care.annotation.AuditLog;
import com.qingyu.care.dto.AiSkillUpdateRequest;
import com.qingyu.care.dto.ApiResponse;
import com.qingyu.care.entity.AiSkill;
import com.qingyu.care.service.ChatToolService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * AI Skill（能力包）管理 API
 *
 * @author ruan
 */
@RestController
@RequestMapping("/v1/ai-skills")
@RequiredArgsConstructor
public class AiSkillController {

    private final ChatToolService chatToolService;

    @GetMapping
    public ApiResponse<List<AiSkill>> list() {
        return ApiResponse.success(chatToolService.listSkills());
    }

    @GetMapping("/{id}")
    public ApiResponse<AiSkill> getById(@PathVariable Long id) {
        return ApiResponse.success(chatToolService.getSkill(id));
    }

    @AuditLog(action = "SKILL_CREATE", targetType = "AiSkill")
    @PostMapping
    public ApiResponse<AiSkill> create(@RequestBody AiSkill skill) {
        skill.setId(null);
        return ApiResponse.success("技能已创建", chatToolService.saveSkill(skill));
    }

    @AuditLog(action = "SKILL_UPDATE", targetType = "AiSkill")
    @PutMapping("/{id}")
    public ApiResponse<AiSkill> update(@PathVariable Long id, @RequestBody AiSkillUpdateRequest skill) {
        AiSkill existing = chatToolService.getSkill(id);
        if (skill.getName() != null) existing.setName(skill.getName());
        if (skill.getDescription() != null) existing.setDescription(skill.getDescription());
        if (skill.getContent() != null) existing.setContent(skill.getContent());
        if (skill.getToolIds() != null) existing.setToolIds(skill.getToolIds());
        if (skill.getMcpToolIds() != null) existing.setMcpToolIds(skill.getMcpToolIds());
        if (skill.getEnabled() != null) existing.setEnabled(skill.getEnabled());
        return ApiResponse.success("技能已更新", chatToolService.saveSkill(existing));
    }

    @AuditLog(action = "SKILL_DELETE", targetType = "AiSkill")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        chatToolService.deleteSkill(id);
        return ApiResponse.success("技能已删除", null);
    }
}
