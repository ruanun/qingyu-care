package com.qingyu.care.service.pkg;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.dto.pkg.PackageInstallResult;
import com.qingyu.care.dto.pkg.PackageManifest;
import com.qingyu.care.entity.AiSkill;
import com.qingyu.care.entity.AiTool;
import com.qingyu.care.entity.McpServer;
import com.qingyu.care.entity.ToolPackage;
import com.qingyu.care.enums.OwnerScope;
import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.repository.AiSkillRepository;
import com.qingyu.care.repository.AiToolRepository;
import com.qingyu.care.repository.McpServerRepository;
import com.qingyu.care.repository.McpToolRepository;
import com.qingyu.care.service.mcp.McpServerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 能力包安装编排：冲突检测 + 两段式落库。
 *
 * <p><b>为什么必须分两段</b>（spec §4.3）：{@code McpServerService.sync()} 是<b>网络出站 +
 * 自己写库</b>，且<b>刻意不带 {@code @Transactional}</b>（它的半截状态靠重新同步收敛，
 * CLAUDE.md「MCP 接入」第 9 条）。把它包进安装事务有两重问题：长事务占着数据库连接做网络
 * 往返；以及跟它自己的非事务语义直接打架。
 *
 * <p><b>段二失败不回滚段一</b>，这是<b>有意的部分成功</b>：MCP 服务已经装好了，界面明确提示
 * 「同步失败，请手动同步后到技能里勾选工具」。整个回滚对租户更糟——工具和技能全白装，而它们
 * 跟 MCP 同步失败没有任何关系。
 *
 * <p>⚠️ <b>用 {@link TransactionTemplate} 而不是 {@code @Transactional} + 自注入代理</b>：
 * 段一要在事务里、段二要在事务外，同一个方法里做不到；而 {@code @Lazy} 自注入那条路
 * （{@code WebPageSyncService} 的写法）在纯 Mockito 单测里构造不出代理，会让本类最需要测的
 * 那段编排逻辑失去单测覆盖。范式同 {@code AnalyticsRollupService} 与 {@code ChatServiceImpl}。
 *
 * @author ruan
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PackageInstallService {

    private final ToolPackageService packageService;
    private final PackageManifestParser manifestParser;
    private final PackageVariableApplier variableApplier;
    private final AiToolRepository toolRepository;
    private final AiSkillRepository skillRepository;
    private final McpServerRepository mcpServerRepository;
    private final McpServerService mcpServerService;
    private final McpToolRepository mcpToolRepository;
    private final TransactionTemplate transactionTemplate;

    /** 段一的产物，供段二回填 skill 绑定用。 */
    private record Stage1(Map<String, Long> serverIdByKey, Map<String, Long> skillIdByKey,
                          List<String> toolNames, List<String> serverNames, List<String> skillNames) {
    }

    public PackageInstallResult install(String code, Map<String, String> values) {
        requireInstallContext();

        ToolPackage toolPackage = packageService.getPublished(code);
        PackageManifest manifest = manifestParser.parse(toolPackage.getManifest());
        PackageVariableApplier.AppliedPackage applied = variableApplier.apply(manifest, values);

        requireNoConflict(applied.manifest());

        List<String> warnings = new ArrayList<>();
        Stage1 stage1 = transactionTemplate.execute(status -> installStage1(applied));

        // 段二：MCP 工具清单同步 + skill 绑定回填。事务外，best-effort。
        syncMcpAndBindSkills(applied.manifest(), stage1, warnings);

        log.info("能力包安装完成 code={} 工具={} MCP={} 技能={}",
                code, stage1.toolNames().size(), stage1.serverNames().size(),
                stage1.skillNames().size());
        return new PackageInstallResult(stage1.toolNames(), stage1.serverNames(),
                stage1.skillNames(), warnings);
    }

    /**
     * 安装上下文的硬前置。
     *
     * <p>⚠️ <b>appId 为空</b>：建出来的行 {@code app_id} 落 null，而 {@code ChatToolService.resolve()}
     * 走 filter-bound 查询（{@code appFilter} 按 {@code app_id} 等值匹配），{@code NULL} 对任何
     * 应用都匹配不上——<b>装了等于没装，且完全静默</b>。
     *
     * <p>⚠️ <b>tenantId 为空</b>：{@code SecurityUtil.requireTenantAdmin()} 是<b>放行平台管理员</b>
     * 的，而平台管理员登录后 {@code tenantId} 恒为 null，落库会写成 {@code tenant_id IS NULL}
     * ——那是「平台共享」的语义位置，本仓库反复在这里出事（CLAUDE.md 里那几条「静默落 NULL」）。
     */
    private void requireInstallContext() {
        if (TenantContext.getTenantId() == null) {
            throw new BusinessException("请用租户管理员账号安装：平台管理员没有租户归属，"
                    + "装出来的配置不属于任何租户");
        }
        if (TenantContext.getAppId() == null) {
            throw new BusinessException("请先在左上角选择要安装到的应用");
        }
    }

    /**
     * 冲突检测：<b>整体拒绝，不自动改名</b>（spec §4.2）。
     *
     * <p>⚠️ 静默改名会让人以为装好了却在列表里找不到自己认识的名字。
     *
     * <p>⚠️ 工具名的已占用集合<b>必须算上平台共享工具</b>：{@code ChatToolService.resolve()}
     * 遇到重名会静默跳过其中一个（只 {@code log.warn}），所以与共享工具重名的工具即便装进去了
     * 也挂不上 LLM——同 {@code OpenApiImportService.existingNames()} 的理由。
     */
    private void requireNoConflict(PackageManifest manifest) {
        Long appId = TenantContext.getAppId();

        Set<String> takenToolNames = new LinkedHashSet<>();
        toolRepository.findByAppId(appId).forEach(t -> takenToolNames.add(t.getName()));
        toolRepository.findAllSharedTools().forEach(t -> takenToolNames.add(t.getName()));

        Set<String> takenSkillNames = new LinkedHashSet<>();
        skillRepository.findByAppId(appId).forEach(s -> takenSkillNames.add(s.getName()));

        Set<String> takenServerNames = new LinkedHashSet<>();
        Set<String> takenServerCodes = new LinkedHashSet<>();
        mcpServerRepository.findByAppId(appId).forEach(s -> {
            takenServerNames.add(s.getName());
            takenServerCodes.add(s.getCode());
        });

        List<String> conflicts = new ArrayList<>();
        for (PackageManifest.ToolSpec tool : PackageManifest.nullSafe(manifest.tools())) {
            if (takenToolNames.contains(tool.name())) {
                conflicts.add("工具「" + tool.name() + "」");
            }
        }
        for (PackageManifest.SkillSpec skill : PackageManifest.nullSafe(manifest.skills())) {
            if (takenSkillNames.contains(skill.name())) {
                conflicts.add("技能「" + skill.name() + "」");
            }
        }
        for (PackageManifest.McpServerSpec server : PackageManifest.nullSafe(manifest.mcpServers())) {
            if (takenServerNames.contains(server.name())) {
                conflicts.add("MCP 服务「" + server.name() + "」");
            }
            if (takenServerCodes.contains(server.code())) {
                // code 参与 LLM 可见的工具名（{code}__{toolName}），同应用内撞了会让
                // 全局工具名去重静默丢掉其中一个 server 的全部工具
                conflicts.add("MCP 服务标识「" + server.code() + "」");
            }
        }

        if (!conflicts.isEmpty()) {
            throw new BusinessException("以下名称在当前应用里已存在，请先重命名或删除已有的，再安装："
                    + String.join("、", conflicts));
        }
    }

    /**
     * 段一（事务内）：建 {@code AiTool} / {@code McpServer} / {@code AiSkill}。
     *
     * <p>⚠️ skill 的 {@code mcpToolIds} 这一步<b>留空</b>——安装那一刻 {@code mcp_tool} 表里
     * 一行都没有（spec §4.3），要等段二同步完才知道 id。
     *
     * <p>⚠️ 归属字段（{@code tenantId} / {@code appId}）<b>一个都不手工设</b>：由
     * {@code TenantEntityListener} 的 {@code @PrePersist} 从 {@code TenantContext} 回填。
     * {@code scope} 则必须<b>显式强制</b> {@code TENANT}——靠实体上的 {@code @Builder.Default}
     * 是「碰巧对」而不是「强制对」。
     */
    private Stage1 installStage1(PackageVariableApplier.AppliedPackage applied) {
        PackageManifest manifest = applied.manifest();

        Map<String, Long> toolIdByKey = new LinkedHashMap<>();
        List<String> toolNames = new ArrayList<>();
        for (PackageManifest.ToolSpec spec : PackageManifest.nullSafe(manifest.tools())) {
            AiTool tool = new AiTool();
            tool.setName(spec.name());
            tool.setDescription(spec.description());
            tool.setInputSchema(spec.inputSchema());
            tool.setHttpMethod(spec.httpMethod());
            tool.setUrlTemplate(spec.urlTemplate());
            tool.setAuthHeader(applied.toolAuthHeaders().get(spec.key()));
            tool.setTimeoutMs(spec.timeoutMs());
            tool.setMaxResponseChars(spec.maxResponseChars());
            tool.setEnabled(true);
            tool.setScope(OwnerScope.TENANT);
            AiTool saved = toolRepository.save(tool);
            toolIdByKey.put(spec.key(), saved.getId());
            toolNames.add(saved.getName());
        }

        Map<String, Long> serverIdByKey = new LinkedHashMap<>();
        List<String> serverNames = new ArrayList<>();
        for (PackageManifest.McpServerSpec spec : PackageManifest.nullSafe(manifest.mcpServers())) {
            McpServer server = new McpServer();
            server.setName(spec.name());
            server.setCode(spec.code());
            server.setDescription(spec.description());
            server.setTransport(spec.transport());
            server.setUrl(spec.url());
            server.setAuthHeader(applied.serverAuthHeaders().get(spec.key()));
            server.setTimeoutMs(spec.timeoutMs());
            server.setMaxResponseChars(spec.maxResponseChars());
            server.setEnabled(true);
            server.setScope(OwnerScope.TENANT);
            // 走 saveServer 而非裸 repository：复用它的 code 格式与同应用唯一性校验（纵深防御）
            McpServer saved = mcpServerService.saveServer(server);
            serverIdByKey.put(spec.key(), saved.getId());
            serverNames.add(saved.getName());
        }

        Map<String, Long> skillIdByKey = new LinkedHashMap<>();
        List<String> skillNames = new ArrayList<>();
        for (PackageManifest.SkillSpec spec : PackageManifest.nullSafe(manifest.skills())) {
            AiSkill skill = new AiSkill();
            skill.setName(spec.name());
            skill.setDescription(spec.description());
            skill.setContent(spec.content());
            Set<Long> toolIds = new LinkedHashSet<>();
            for (String toolKey : PackageManifest.nullSafe(spec.toolKeys())) {
                Long id = toolIdByKey.get(toolKey);
                if (id != null) {
                    toolIds.add(id);
                }
            }
            skill.setToolIds(toolIds);
            skill.setMcpToolIds(new LinkedHashSet<>());   // 段二回填
            skill.setEnabled(true);
            AiSkill saved = skillRepository.save(skill);
            skillIdByKey.put(spec.key(), saved.getId());
            skillNames.add(saved.getName());
        }

        return new Stage1(serverIdByKey, skillIdByKey, toolNames, serverNames, skillNames);
    }

    /**
     * 段二（事务外）：逐个同步 MCP 工具清单，成功则按 {@code mcpRefs.toolNames} 映射成新建的
     * {@code McpTool} id，再开一个短事务把绑定回填进 skill。
     *
     * <p>⚠️ <b>整段 best-effort，绝不把异常抛出去</b>（spec §4.3）：段一已经提交，工具与技能
     * 都建好了，它们跟 MCP 同步失败没有任何关系。抛出去会让客户端收到一个「安装失败」，而库里
     * 明明躺着一整套装好的行——租户会重装一次，然后撞上冲突检测。
     *
     * <p>⚠️ <b>catch 的是 {@code Exception} 而不只是 {@code BusinessException}</b>：
     * {@code sync()} 内部会抛 {@code DataAccessException}、网络层异常等各种东西。
     */
    private void syncMcpAndBindSkills(PackageManifest manifest, Stage1 stage1,
                                      List<String> warnings) {
        if (stage1.serverIdByKey().isEmpty()) {
            return;
        }

        // serverKey → (远端工具名 → 新建的 mcp_tool id)
        Map<String, Map<String, Long>> toolIdsByServerKey = new LinkedHashMap<>();
        for (PackageManifest.McpServerSpec spec : PackageManifest.nullSafe(manifest.mcpServers())) {
            Long serverId = stage1.serverIdByKey().get(spec.key());
            if (serverId == null) {
                continue;
            }
            Map<String, Long> byName = new LinkedHashMap<>();
            try {
                mcpServerService.sync(serverId);
                // ⚠️ 读取新建的工具行**必须在同一个 try 之内**：它紧跟一次成功的网络同步之后，
                // 一旦抛（连接抖动、超时、连接池耗尽）会不经任何拦截从 install() 冒出，把
                // 「部分成功」变成「整体失败」——而段一已经提交，客户端却收到「安装失败」，
                // 租户会重装一次然后撞上冲突检测。
                // ⚠️ 变异测试抓不到这条：摘掉已有的 catch 只能证明「这段保护在被执行」，
                // 证明不了「所有可能抛异常的调用都被保护」——它验不到本就没有保护的那条线。
                mcpToolRepository.findByMcpServerId(serverId)
                        .forEach(t -> byName.put(t.getName(), t.getId()));
            } catch (Exception e) {
                log.warn("能力包安装：MCP 服务 {} 同步失败", spec.code(), e);
                warnings.add("MCP 服务「" + spec.name() + "」的工具清单同步失败（" + e.getMessage()
                        + "）。服务本身已安装，请到 MCP 服务页手动同步一次，"
                        + "再到技能里勾选需要的工具");
                continue;
            }
            toolIdsByServerKey.put(spec.key(), byName);
        }

        for (PackageManifest.SkillSpec spec : PackageManifest.nullSafe(manifest.skills())) {
            Long skillId = stage1.skillIdByKey().get(spec.key());
            List<PackageManifest.McpRef> refs = PackageManifest.nullSafe(spec.mcpRefs());
            if (skillId == null || refs.isEmpty()) {
                continue;
            }
            Set<Long> mcpToolIds = new LinkedHashSet<>();
            for (PackageManifest.McpRef ref : refs) {
                Map<String, Long> byName = toolIdsByServerKey.get(ref.serverKey());
                if (byName == null) {
                    continue;   // 该 server 同步失败，上面已经记过 warning
                }
                for (String toolName : PackageManifest.nullSafe(ref.toolNames())) {
                    Long id = byName.get(toolName);
                    if (id == null) {
                        // 远端没有这个工具（改名/下线）。⚠️ 必须记 warning：不记的话技能会
                        // 静默少绑一个工具，而 resolve() 查不到 id 时同样是静默跳过
                        warnings.add("技能「" + spec.name() + "」需要的 MCP 工具「" + toolName
                                + "」在远端不存在，可能已改名或下线，该工具未绑定");
                        continue;
                    }
                    mcpToolIds.add(id);
                }
            }
            if (mcpToolIds.isEmpty()) {
                continue;
            }
            // ⚠️ 绑定完成 ≠ 「AI 能用」：McpServerService.sync() 新建的工具一律
            // readOnly=false（「未知一律当可写、不挂载」），而运行时
            // ChatToolService.resolveMcpCallbacks 走的是 findByIdInAndStatusAndReadOnlyTrue
            // —— 只取 readOnly=true 的。所以这些刚绑上的工具此刻挂不给 LLM。
            // ⚠️ 这里**绝不能自动帮租户标只读**：CLAUDE.md「MCP 接入」第 3 条明令「同步绝不
            // 覆盖 readOnly」——它是租户自己标注的安全判据，替他标就是安全降级且完全静默
            // （一个可写工具会被 LLM 调用）。所以只能明确告知、让他自己去标。
            // 不加这条 warning 的后果正是本特性真机验证时实拍到的：安装报告零 warning、
            // 界面显示技能绑着 N 个 MCP 工具，实际挂给 AI 的是 0 个。
            warnings.add("技能「" + spec.name() + "」已绑定 " + mcpToolIds.size()
                    + " 个 MCP 工具，但它们默认是「可写」状态、暂时不会挂给 AI。请到 MCP 服务页"
                    + "把需要的工具标为「只读」后才会生效——这一步必须由你确认，系统不会替你标"
                    + "（只读是安全判据）。");
            try {
                transactionTemplate.execute(status -> {
                    AiSkill skill = skillRepository.findById(skillId).orElse(null);
                    if (skill == null) {
                        return null;
                    }
                    Set<Long> merged = new LinkedHashSet<>(
                            skill.getMcpToolIds() == null ? Set.of() : skill.getMcpToolIds());
                    merged.addAll(mcpToolIds);
                    skill.setMcpToolIds(merged);
                    return skillRepository.save(skill);
                });
            } catch (Exception e) {
                log.warn("能力包安装：技能 {} 回填 MCP 工具绑定失败", spec.name(), e);
                warnings.add("技能「" + spec.name() + "」的 MCP 工具绑定没能自动完成，"
                        + "请到技能页手动勾选");
            }
        }
    }
}
