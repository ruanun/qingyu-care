package com.qingyu.care.service.pkg;

import com.qingyu.care.dto.pkg.PackageManifest;
import com.qingyu.care.entity.AiSkill;
import com.qingyu.care.entity.AiTool;
import com.qingyu.care.entity.McpServer;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 实体 → manifest 的转换与导出告警。<b>纯函数</b>：不碰数据库、不碰网络。
 *
 * <p>⚠️ <b>全程只读实体，绝不 setter 置空</b>（spec §4.1 / §6.2）。本项目未配
 * {@code spring.jpa.open-in-view}，即 Spring Boot 默认的 {@code true}，OSIV 让查出来的实体在
 * 整个请求期保持<b>受管</b>——为了「不导出明文凭据」而写一句 {@code tool.setAuthHeader(null)}
 * 会被脏检查刷回库，结果是<b>把平台管理员自己的凭据真的删掉</b>（{@code SharedCredentialMasker}
 * 那次就是靠 {@code em.detach} 才闭合的）。这里连 {@code detach} 都不需要：只读 getter 拼
 * manifest，从头不碰实体状态。⚠️ 直调本类的单测<b>证明不了</b>这条（实体本来就是分离的，断言
 * 会静默退化成永真），真正钉住它的是 {@code PackageExportIT} 那条走真实 MockMvc 的测试。
 *
 * <p><b>warning 一律不阻断</b>（spec §4.1）：它们是启发式，最终仍要平台管理员逐字看一遍
 * manifest 预览再发布——同噪声剔除，没法自动化。
 *
 * @author ruan
 */
@Component
public class PackageManifestBuilder {

    /** URL 里疑似嵌了密钥的启发式特征。命中只记 warning，不阻断。 */
    private static final Pattern SECRET_IN_URL =
            Pattern.compile("(?i)[?&](api_?key|key|token|secret|access_?token)=");

    /** 包内 key 的合法形状，与 MCP server 的 code 同口径。 */
    private static final Pattern KEY_SHAPE = Pattern.compile("[^a-z0-9_]");

    /** 导出输入。 */
    public record BuildInput(
            List<AiTool> tools,
            List<McpServer> mcpServers,
            List<AiSkill> skills,
            /** 全部可见工具的 id → name，仅用于把「集合外引用」的 id 翻译成人能看懂的名字 */
            Map<Long, String> toolNamesById,
            /** 全部可见 MCP 工具的 id → (所属 server id, 远端工具名)，用于把 mcpToolIds 转成 mcpRefs */
            Map<Long, McpToolRef> mcpToolsById) {
    }

    /** 一个 MCP 工具的身份：它属于哪个 server、远端叫什么名字。 */
    public record McpToolRef(Long serverId, String toolName) {
    }

    public record BuildResult(PackageManifest manifest, List<String> warnings) {
    }

    public BuildResult build(BuildInput input) {
        List<String> warnings = new ArrayList<>();
        List<PackageManifest.VariableSpec> variables = new ArrayList<>();
        Set<String> usedKeys = new LinkedHashSet<>();
        Set<String> usedVariableNames = new LinkedHashSet<>();

        // ===== 工具 =====
        Map<Long, String> toolKeyById = new LinkedHashMap<>();
        List<PackageManifest.ToolSpec> toolSpecs = new ArrayList<>();
        for (AiTool tool : PackageManifest.nullSafe(input.tools())) {
            String key = uniqueKey(slug(tool.getName(), "tool"), usedKeys);
            toolKeyById.put(tool.getId(), key);

            String authVar = promoteCredential(tool.getAuthHeader(), key, tool.getName(),
                    variables, usedVariableNames);

            if (tool.getUrlTemplate() != null && SECRET_IN_URL.matcher(tool.getUrlTemplate()).find()) {
                warnings.add("工具「" + tool.getName() + "」的地址里疑似直接写了密钥（?key= / ?token= 之类）"
                        + "，它会原样随包发给每个租户。请改成鉴权头，或把该段改成 ${变量名} 并在变量表里声明");
            }

            toolSpecs.add(new PackageManifest.ToolSpec(
                    key,
                    tool.getName(),
                    tool.getDescription(),
                    tool.getInputSchema(),
                    tool.getHttpMethod(),
                    tool.getUrlTemplate(),
                    tool.getTimeoutMs(),
                    tool.getMaxResponseChars(),
                    authVar));
        }

        // ===== MCP server =====
        Map<Long, String> serverKeyById = new LinkedHashMap<>();
        List<PackageManifest.McpServerSpec> serverSpecs = new ArrayList<>();
        for (McpServer server : PackageManifest.nullSafe(input.mcpServers())) {
            String key = uniqueKey(slug(server.getCode(), "mcp"), usedKeys);
            serverKeyById.put(server.getId(), key);

            String authVar = promoteCredential(server.getAuthHeader(), key, server.getName(),
                    variables, usedVariableNames);

            if (server.getUrl() != null && SECRET_IN_URL.matcher(server.getUrl()).find()) {
                warnings.add("MCP 服务「" + server.getName() + "」的地址里疑似直接写了密钥，"
                        + "它会原样随包发给每个租户");
            }

            serverSpecs.add(new PackageManifest.McpServerSpec(
                    key,
                    server.getName(),
                    server.getCode(),
                    server.getDescription(),
                    server.getTransport(),
                    server.getUrl(),
                    server.getTimeoutMs(),
                    server.getMaxResponseChars(),
                    authVar));
        }

        // ===== skill（引用转换必须在工具与 server 都编好 key 之后）=====
        List<PackageManifest.SkillSpec> skillSpecs = new ArrayList<>();
        for (AiSkill skill : PackageManifest.nullSafe(input.skills())) {
            String key = uniqueKey(slug(skill.getName(), "skill"), usedKeys);

            List<String> toolKeys = new ArrayList<>();
            for (Long toolId : nullSafeSet(skill.getToolIds())) {
                String toolKey = toolKeyById.get(toolId);
                if (toolKey == null) {
                    String name = input.toolNamesById() == null
                            ? null : input.toolNamesById().get(toolId);
                    warnings.add("技能「" + skill.getName() + "」绑定的工具「"
                            + (name != null ? name : "id=" + toolId)
                            + "」不在本次导出范围内，装出来的技能会少绑这个工具");
                    continue;
                }
                toolKeys.add(toolKey);
            }

            // 按 server 归并：一个 mcpRef 承载「某个 server 上的若干工具名」
            Map<String, List<String>> byServerKey = new LinkedHashMap<>();
            for (Long mcpToolId : nullSafeSet(skill.getMcpToolIds())) {
                McpToolRef ref = input.mcpToolsById() == null
                        ? null : input.mcpToolsById().get(mcpToolId);
                if (ref == null) {
                    warnings.add("技能「" + skill.getName() + "」绑定的 MCP 工具 id=" + mcpToolId
                            + " 已查不到（可能对应的服务已删除），装出来的技能会少绑它");
                    continue;
                }
                String serverKey = serverKeyById.get(ref.serverId());
                if (serverKey == null) {
                    warnings.add("技能「" + skill.getName() + "」绑定的 MCP 工具「" + ref.toolName()
                            + "」所属的服务不在本次导出范围内，装出来的技能会少绑它");
                    continue;
                }
                byServerKey.computeIfAbsent(serverKey, k -> new ArrayList<>()).add(ref.toolName());
            }
            List<PackageManifest.McpRef> mcpRefs = new ArrayList<>();
            byServerKey.forEach((serverKey, names) ->
                    mcpRefs.add(new PackageManifest.McpRef(serverKey, names)));

            skillSpecs.add(new PackageManifest.SkillSpec(
                    key,
                    skill.getName(),
                    skill.getDescription(),
                    skill.getContent(),
                    toolKeys,
                    mcpRefs));
        }

        PackageManifest manifest = new PackageManifest(
                PackageManifest.CURRENT_VERSION, toolSpecs, serverSpecs, skillSpecs, variables);
        return new BuildResult(manifest, warnings);
    }

    /**
     * 把一个明文 {@code authHeader} 升格成 {@code secret} 变量，返回变量名；{@code authHeader}
     * 为空时返回 null（该工具不需要鉴权）。
     *
     * <p>⚠️ <b>变量值是 authHeader 的完整值</b>（含 {@code "Authorization: "} 这样的头名前缀）
     * ——本项目 {@code AiTool.authHeader} 存的就是整行。所以 {@code help} 里必须把原来的头名
     * 带出来，否则租户只会填一个裸 token，工具静默 401（模型拿着错误响应继续编答案，
     * 全程无异常无日志）。
     *
     * <p>⚠️ <b>头名可以导出，值绝对不行</b>：头名不是密钥，且它正是租户填对格式所必需的信息。
     */
    private String promoteCredential(String authHeader, String key, String displayName,
                                     List<PackageManifest.VariableSpec> variables,
                                     Set<String> usedVariableNames) {
        if (authHeader == null || authHeader.isBlank()) {
            return null;
        }
        String varName = uniqueVariableName(key.toUpperCase(Locale.ROOT) + "_AUTH", usedVariableNames);
        int colon = authHeader.indexOf(':');
        String headerName = colon > 0 ? authHeader.substring(0, colon).trim() : null;
        String help = headerName != null
                ? "请填写完整的一行请求头，格式为「" + headerName + ": <你的密钥>」"
                : "请填写完整的一行请求头，格式为「头名: 值」";

        variables.add(new PackageManifest.VariableSpec(
                varName,
                displayName + " 的鉴权头",
                true,
                true,
                help));
        return varName;
    }

    /**
     * 名字 → 包内 key：小写、非法字符换成下划线、掐掉首尾下划线；产不出合法 key 时用 fallback。
     *
     * <p>skill 名常是中文（「客服技能」），规范化后会整个变空，此时回落到 {@code skill} 前缀 +
     * 序号（序号由 {@link #uniqueKey} 补）。
     */
    private String slug(String raw, String fallback) {
        if (raw == null) {
            return fallback;
        }
        String slug = KEY_SHAPE.matcher(raw.toLowerCase(Locale.ROOT)).replaceAll("_");
        slug = slug.replaceAll("^_+", "").replaceAll("_+$", "").replaceAll("_{2,}", "_");
        if (slug.isEmpty() || !Character.isLetter(slug.charAt(0))) {
            return fallback;
        }
        return slug.length() > 60 ? slug.substring(0, 60) : slug;
    }

    /**
     * key 去重：冲突时加 {@code _2}、{@code _3}……
     *
     * <p>⚠️ 两个对象共用一个 key 不是「不好看」而是<b>会指错对象</b>：skill 的 toolKeys 按 key
     * 查工具，重了就会绑到另一个工具上。
     */
    private String uniqueKey(String base, Set<String> used) {
        if (used.add(base)) {
            return base;
        }
        for (int i = 2; ; i++) {
            String candidate = base + "_" + i;
            if (used.add(candidate)) {
                return candidate;
            }
        }
    }

    private String uniqueVariableName(String base, Set<String> used) {
        if (used.add(base)) {
            return base;
        }
        for (int i = 2; ; i++) {
            String candidate = base + "_" + i;
            if (used.add(candidate)) {
                return candidate;
            }
        }
    }

    private Set<Long> nullSafeSet(Set<Long> set) {
        return set == null ? Set.of() : set;
    }
}
