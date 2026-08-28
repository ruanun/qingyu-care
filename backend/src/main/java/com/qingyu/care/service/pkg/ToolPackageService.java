package com.qingyu.care.service.pkg;

import com.qingyu.care.dto.pkg.PackageDetailDTO;
import com.qingyu.care.dto.pkg.PackageExportRequest;
import com.qingyu.care.dto.pkg.PackageExportResult;
import com.qingyu.care.dto.pkg.PackageManifest;
import com.qingyu.care.dto.pkg.ToolPackageUpdateRequest;
import com.qingyu.care.entity.AiSkill;
import com.qingyu.care.entity.AiTool;
import com.qingyu.care.entity.McpServer;
import com.qingyu.care.entity.McpTool;
import com.qingyu.care.entity.ToolPackage;
import com.qingyu.care.enums.PackageStatus;
import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.exception.ResourceNotFoundException;
import com.qingyu.care.repository.McpToolRepository;
import com.qingyu.care.repository.ToolPackageRepository;
import com.qingyu.care.service.ChatToolService;
import com.qingyu.care.service.mcp.McpServerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 能力包的平台侧编排：CRUD、从现成配置导出、发布/下架。
 *
 * <p>规则都在三个纯函数类里（{@link PackageManifestBuilder} / {@link PackageManifestParser} /
 * {@link PackageVariableApplier}），本类只做取数与落库——范式同 {@code OpenApiImportService}。
 *
 * @author ruan
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ToolPackageService {

    /** 包 code 的合法形状，与 MCP server 的 code 同口径（都是要被人在 URL 里读写的标识符）。 */
    private static final Pattern CODE_PATTERN = Pattern.compile("^[a-z][a-z0-9_]*$");

    /** 一次导出的条目上限，防误操作把整个应用的配置灌成一个包。 */
    private static final int MAX_ITEMS = 50;

    private final ToolPackageRepository packageRepository;
    private final ChatToolService chatToolService;
    private final McpServerService mcpServerService;
    private final McpToolRepository mcpToolRepository;
    private final PackageManifestBuilder manifestBuilder;
    private final PackageManifestParser manifestParser;

    // ===== 查询 =====

    /** 平台侧列表：三种状态全给。 */
    public List<ToolPackage> listAll() {
        return packageRepository.findAllByOrderByCreatedAtDesc();
    }

    /** 租户侧列表：只给已发布的。 */
    public List<ToolPackage> listPublished() {
        return packageRepository.findByStatusOrderByNameAsc(PackageStatus.PUBLISHED);
    }

    /**
     * 租户侧商店列表：逐个转成详情。
     *
     * <p>⚠️ <b>单个包解析失败时跳过并记日志，不让整个列表 500</b>：manifest 是平台管理员可以
     * 手工编辑的文本，一个包被改坏不该让所有租户的商店页面打不开。这条 fail-soft 与
     * {@code ChatServiceImpl} 检索失败时返回空上下文同源——降级到「少一个包」，好过降级到
     * 「整页打不开」。
     */
    public List<PackageDetailDTO> listPublishedDetails() {
        List<PackageDetailDTO> details = new ArrayList<>();
        for (ToolPackage toolPackage : listPublished()) {
            try {
                details.add(detailOf(toolPackage));
            } catch (RuntimeException e) {
                log.warn("能力包 {} 的 manifest 解析失败，已从商店列表中跳过", toolPackage.getCode(), e);
            }
        }
        return details;
    }

    public ToolPackage getById(Long id) {
        return packageRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("能力包", String.valueOf(id)));
    }

    /**
     * 租户按 code 取包。
     *
     * <p>⚠️ <b>非 {@code PUBLISHED} 一律按「不存在」处理</b>，不是「无权访问」：草稿包的存在
     * 本身就不该被租户探测到（同本仓库对越权一律按资源不存在处理的既有口径）。
     */
    public ToolPackage getPublished(String code) {
        return packageRepository.findByCode(code)
                .filter(p -> p.getStatus() == PackageStatus.PUBLISHED)
                .orElseThrow(() -> new ResourceNotFoundException("能力包", code));
    }

    /** 把包转成租户看得到的详情（不含 manifest 全文，见 {@link PackageDetailDTO} 的注释）。 */
    public PackageDetailDTO detailOf(ToolPackage toolPackage) {
        PackageManifest manifest = manifestParser.parse(toolPackage.getManifest());
        return new PackageDetailDTO(
                toolPackage.getCode(),
                toolPackage.getName(),
                toolPackage.getDescription(),
                PackageManifest.nullSafe(manifest.variables()),
                PackageManifest.nullSafe(manifest.tools()).stream()
                        .map(PackageManifest.ToolSpec::name).toList(),
                PackageManifest.nullSafe(manifest.mcpServers()).stream()
                        .map(PackageManifest.McpServerSpec::name).toList(),
                PackageManifest.nullSafe(manifest.skills()).stream()
                        .map(PackageManifest.SkillSpec::name).toList());
    }

    // ===== 导出 =====

    /**
     * 从平台管理员勾选的现成配置导出成一个 {@code DRAFT} 包。
     *
     * <p>⚠️ <b>只导出「平台自己的行」（{@code tenantId == null}）</b>。
     * {@code TenantOwnershipGuard.mutableInCurrentContext} 在上下文 tenantId 为空时
     * <b>无条件放行</b>——而平台管理员登录后 tenantId 恒为 null，于是
     * {@code chatToolService.getTool(任意 id)} 都能取到<b>任意租户</b>的工具。不在这里拦住，
     * 平台管理员切到某个租户的应用再导出，就会把该租户私有的工具配置（name / description /
     * urlTemplate）随包发给所有租户。判据取 {@code tenantId == null} 而非应用范围：平台管理员
     * 在任何应用下建的行 {@code tenant_id} 都是 null（{@code TenantEntityListener} 从空上下文
     * 回填），所以这条精确等于「只导出平台管理员自己建的配置」。
     */
    @Transactional
    public PackageExportResult export(PackageExportRequest request) {
        List<Long> toolIds = nullSafe(request.getToolIds());
        List<Long> serverIds = nullSafe(request.getMcpServerIds());
        List<Long> skillIds = nullSafe(request.getSkillIds());
        if (toolIds.isEmpty() && serverIds.isEmpty() && skillIds.isEmpty()) {
            throw new BusinessException("至少要勾选一个工具、MCP 服务或技能");
        }
        if (toolIds.size() + serverIds.size() + skillIds.size() > MAX_ITEMS) {
            throw new BusinessException("一个包最多包含 " + MAX_ITEMS + " 个条目，请拆分");
        }

        List<AiTool> tools = new ArrayList<>();
        for (Long id : toolIds) {
            AiTool tool = chatToolService.getTool(id);
            requirePlatformOwned(tool.getTenantId(), "工具", tool.getName());
            tools.add(tool);
        }
        List<McpServer> servers = new ArrayList<>();
        for (Long id : serverIds) {
            McpServer server = mcpServerService.getServer(id);
            requirePlatformOwned(server.getTenantId(), "MCP 服务", server.getName());
            servers.add(server);
        }
        List<AiSkill> skills = new ArrayList<>();
        for (Long id : skillIds) {
            AiSkill skill = chatToolService.getSkill(id);
            requirePlatformOwned(skill.getTenantId(), "技能", skill.getName());
            skills.add(skill);
        }

        // code 校验放在取数之后：先让「勾了别人的行」这类更严重的问题先报出来
        String code = request.getCode();
        if (code == null || !CODE_PATTERN.matcher(code).matches()) {
            throw new BusinessException("包标识只能是小写字母开头的字母、数字、下划线组合");
        }
        if (packageRepository.existsByCode(code)) {
            throw new BusinessException("包标识「" + code + "」已被占用");
        }
        if (request.getName() == null || request.getName().isBlank()) {
            throw new BusinessException("请填写包名称");
        }

        PackageManifestBuilder.BuildResult built = manifestBuilder.build(
                new PackageManifestBuilder.BuildInput(tools, servers, skills,
                        toolNamesById(), mcpToolsById(skills)));

        ToolPackage saved = packageRepository.save(ToolPackage.builder()
                .code(code)
                .name(request.getName())
                .description(request.getDescription())
                .status(PackageStatus.DRAFT)
                .manifest(manifestParser.toJson(built.manifest()))
                .build());
        return new PackageExportResult(saved, built.warnings());
    }

    private void requirePlatformOwned(Long tenantId, String what, String name) {
        if (tenantId != null) {
            throw new BusinessException(what + "「" + name + "」属于某个租户，不能导出成平台能力包。"
                    + "请先在平台管理员自己的配置里把它调通，再导出");
        }
    }

    /** 全部可见工具的 id → name，只为把「集合外引用」的 id 翻译成人能看懂的名字。 */
    private Map<Long, String> toolNamesById() {
        Map<Long, String> names = new LinkedHashMap<>();
        chatToolService.listTools().forEach(t -> names.put(t.getId(), t.getName()));
        return names;
    }

    /**
     * 被这批 skill 引用到的 MCP 工具 id → (所属 server id, 远端工具名)。
     *
     * <p>只查引用到的那些，不整表扫。{@code findAllById} 是 filter-bound 的派生查询，
     * 别的租户的 id 天然查不出来。
     */
    private Map<Long, PackageManifestBuilder.McpToolRef> mcpToolsById(List<AiSkill> skills) {
        Set<Long> ids = new LinkedHashSet<>();
        for (AiSkill skill : skills) {
            if (skill.getMcpToolIds() != null) {
                ids.addAll(skill.getMcpToolIds());
            }
        }
        Map<Long, PackageManifestBuilder.McpToolRef> refs = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return refs;
        }
        for (McpTool tool : mcpToolRepository.findAllById(ids)) {
            refs.put(tool.getId(),
                    new PackageManifestBuilder.McpToolRef(tool.getMcpServerId(), tool.getName()));
        }
        return refs;
    }

    // ===== 生命周期 =====

    @Transactional
    public ToolPackage update(Long id, ToolPackageUpdateRequest request) {
        ToolPackage existing = getById(id);
        if (request.getName() != null) {
            existing.setName(request.getName());
        }
        if (request.getDescription() != null) {
            existing.setDescription(request.getDescription());
        }
        if (request.getManifest() != null) {
            // 解析不过就整体拒绝：存进去一份坏 manifest，症状要等到某个租户点安装才现形
            manifestParser.parse(request.getManifest());
            existing.setManifest(request.getManifest());
        }
        return packageRepository.save(existing);
    }

    /**
     * 发布。
     *
     * <p>⚠️ 发布前<b>必须</b>重新解析一次 manifest：它可能被上一次 update 之后的手工编辑改坏，
     * 也可能是从别处导入的。发布出去就等于宣告「这个包装得下去」，把解析失败留到租户点安装时
     * 才炸，报错会落在租户头上而平台侧毫不知情。
     */
    @Transactional
    public ToolPackage publish(Long id) {
        ToolPackage existing = getById(id);
        manifestParser.parse(existing.getManifest());
        existing.setStatus(PackageStatus.PUBLISHED);
        return packageRepository.save(existing);
    }

    /**
     * 下架。
     *
     * <p>⚠️ <b>已经装出去的副本不受影响</b>（spec §2.3）：安装是一次性复制，装完与包断开。
     * 下架只是让它从租户的商店列表里消失。
     */
    @Transactional
    public ToolPackage archive(Long id) {
        ToolPackage existing = getById(id);
        existing.setStatus(PackageStatus.ARCHIVED);
        return packageRepository.save(existing);
    }

    @Transactional
    public void delete(Long id) {
        packageRepository.delete(getById(id));
    }

    private <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }
}
