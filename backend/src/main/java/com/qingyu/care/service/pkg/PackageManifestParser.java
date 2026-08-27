package com.qingyu.care.service.pkg;

import com.qingyu.care.dto.pkg.PackageManifest;
import com.qingyu.care.enums.McpTransport;
import com.qingyu.care.exception.BusinessException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * manifest 的 JSON ↔ 对象转换与<b>全量结构校验</b>。纯函数，不碰数据库、不碰网络。
 *
 * <p><b>为什么校验要做到这么细。</b>manifest 有两条入口：导出时由
 * {@link PackageManifestBuilder} 生成（可信），以及平台管理员在界面上<b>手工编辑</b>后提交
 * （发布前要补变量的 label/help，spec §4.1）。后者意味着任何字段都可能被改坏，而安装是在
 * 租户名下批量建行——不在这里拦住，就会在 {@code save} 那一刻抛
 * {@code DataIntegrityViolationException}，被 {@code GlobalExceptionHandler} 接成 HTTP 500 +
 * 完整 INSERT 语句回给客户端（CLAUDE.md「MCP 接入」第 9 条那个坑，换个位置复发）。
 *
 * <p><b>长度上限直接对齐实体列宽</b>，每个常量都注明来源。⚠️ 改实体列宽时必须同步改这里，
 * 否则要么这里白拦（更严，用户被无谓挡住），要么拦不住（更松，500 复发）。
 *
 * @author ruan
 */
@Component
public class PackageManifestParser {

    /**
     * 包变量的行内引用语法。
     *
     * <p>⚠️ <b>Task 5/6 必须复用这一个常量，不得各写一份</b>——两处正则各自漂移的后果是
     * 「导出时写进去的引用，安装时认不出来」，而认不出来 = 变量没被替换 = 见下条。
     *
     * <p>⚠️ <b>它与 {@code UrlTemplateFiller.PLACEHOLDER}（{@code \{([^{}]+)}}）部分重叠</b>：
     * {@code ${VAR}} 里的 {@code {VAR}} 会被后者当成一个 LLM 参数占位符。所以变量必须在<b>安装
     * 落库前</b>被替换干净，残留的后果见 {@link PackageVariableApplier} 的类注释。
     */
    static final Pattern VARIABLE_REF = Pattern.compile("\\$\\{([A-Z][A-Z0-9_]*)}");

    /** 变量名格式。与 {@link #VARIABLE_REF} 的捕获组同口径。 */
    private static final Pattern VARIABLE_NAME = Pattern.compile("^[A-Z][A-Z0-9_]*$");

    /** MCP server 的 code 参与 LLM 可见的工具名，必须是合法标识符（同 McpServerService.CODE_PATTERN）。 */
    private static final Pattern MCP_CODE = Pattern.compile("^[a-z][a-z0-9_]*$");

    /** AiTool.name / AiSkill.name / McpServer.name 的列宽 */
    private static final int MAX_NAME = 100;
    /** AiTool.description / AiSkill.description / McpServer.description 的列宽 */
    private static final int MAX_DESCRIPTION = 500;
    /** AiTool.urlTemplate / McpServer.url 的列宽 */
    private static final int MAX_URL = 1000;
    /** McpServer.code 的列宽 */
    private static final int MAX_MCP_CODE = 50;
    /** 包内 key 不落库，给一个够用的上限只为挡住畸形输入 */
    private static final int MAX_KEY = 100;

    private final ObjectMapper objectMapper;

    public PackageManifestParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 反序列化 + 全量结构校验。任何问题都抛 {@link BusinessException}，消息面向平台管理员。 */
    public PackageManifest parse(String json) {
        if (json == null || json.isBlank()) {
            throw new BusinessException("manifest 为空");
        }
        PackageManifest manifest;
        try {
            manifest = objectMapper.readValue(json, PackageManifest.class);
        } catch (Exception e) {
            // ⚠️ 不把解析器原始异常抛出去：那是给开发看的（含行列号与内部类型名），
            // 平台管理员在文本框里编辑 manifest 时看不懂也修不了
            throw new BusinessException("manifest 不是合法的 JSON：" + rootMessage(e));
        }
        validate(manifest);
        // ⚠️ 归一化顶层数组字段：Jackson 对 JSON 里缺省的数组字段反序列化成 null（标准行为，
        // 不是 Jackson 3 特有），而下游（Task 5~8）会直接遍历 manifest.tools()/skills() 等，
        // 逐处判空既啰嗦又漏一处就是 NPE。parse() 是校验通过后的唯一出口，在这里一次性
        // 归一化，好过让每个调用方各自记得调 PackageManifest.nullSafe()。
        return new PackageManifest(
                manifest.manifestVersion(),
                PackageManifest.nullSafe(manifest.tools()),
                PackageManifest.nullSafe(manifest.mcpServers()),
                PackageManifest.nullSafe(manifest.skills()),
                PackageManifest.nullSafe(manifest.variables()));
    }

    public String toJson(PackageManifest manifest) {
        try {
            return objectMapper.writeValueAsString(manifest);
        } catch (Exception e) {
            throw new BusinessException("manifest 序列化失败：" + rootMessage(e));
        }
    }

    /** 抽出一段文本里引用到的变量名。null / 无引用都返回空集合。 */
    static Set<String> referencedVariables(String text) {
        Set<String> names = new LinkedHashSet<>();
        if (text == null) {
            return names;
        }
        Matcher m = VARIABLE_REF.matcher(text);
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }

    private void validate(PackageManifest manifest) {
        Integer version = manifest.manifestVersion();
        if (version == null || version != PackageManifest.CURRENT_VERSION) {
            // 拒绝而不是「按当前版本猜着解析」：未知版本大概率有本代码不认识的字段，
            // 猜着装的后果是装出来的行少了配置且没人知道
            throw new BusinessException("不支持的 manifest 版本：" + version
                    + "，当前只支持 " + PackageManifest.CURRENT_VERSION);
        }

        List<PackageManifest.ToolSpec> tools = PackageManifest.nullSafe(manifest.tools());
        List<PackageManifest.McpServerSpec> servers = PackageManifest.nullSafe(manifest.mcpServers());
        List<PackageManifest.SkillSpec> skills = PackageManifest.nullSafe(manifest.skills());
        List<PackageManifest.VariableSpec> variables = PackageManifest.nullSafe(manifest.variables());

        if (tools.isEmpty() && servers.isEmpty() && skills.isEmpty()) {
            throw new BusinessException("包里至少要包含一个工具、MCP 服务或技能");
        }

        Set<String> variableNames = validateVariables(variables);
        Set<String> toolKeys = new LinkedHashSet<>();
        Set<String> serverKeys = new LinkedHashSet<>();

        for (PackageManifest.ToolSpec tool : tools) {
            requireKey(tool.key(), "工具", toolKeys);
            requireText(tool.name(), MAX_NAME, "工具「" + tool.key() + "」的名称");
            requireText(tool.description(), MAX_DESCRIPTION, "工具「" + tool.key() + "」的描述");
            requireText(tool.urlTemplate(), MAX_URL, "工具「" + tool.key() + "」的 urlTemplate");
            if (!"GET".equals(tool.httpMethod()) && !"POST".equals(tool.httpMethod())) {
                // 运行时 RestClientHttpToolExecutor 对非 POST 一律降级成 GET，
                // 放 PUT/DELETE 进来只会让语义错得无声无息（同 OpenAPI 导入的既有约束）
                throw new BusinessException("工具「" + tool.key() + "」的 httpMethod 只能是 GET 或 POST");
            }
            requireDeclared(tool.authHeaderVar(), variableNames,
                    "工具「" + tool.key() + "」的 authHeaderVar");
            requireInlineVarsDeclared(tool.urlTemplate(), variableNames,
                    "工具「" + tool.key() + "」的 urlTemplate");
        }

        for (PackageManifest.McpServerSpec server : servers) {
            requireKey(server.key(), "MCP 服务", serverKeys);
            requireText(server.name(), MAX_NAME, "MCP 服务「" + server.key() + "」的名称");
            requireText(server.code(), MAX_MCP_CODE, "MCP 服务「" + server.key() + "」的标识符");
            if (!MCP_CODE.matcher(server.code()).matches()) {
                throw new BusinessException("MCP 服务「" + server.key()
                        + "」的标识符只能是小写字母开头的字母、数字、下划线组合");
            }
            requireText(server.url(), MAX_URL, "MCP 服务「" + server.key() + "」的地址");
            if (server.transport() == McpTransport.SSE) {
                // McpConnectionFactory.create() 遇到 SSE 直接抛 UnsupportedOperationException，
                // 装进来的 server 只会在第一次调用时炸；在解析期拒绝好过让人装完才发现
                throw new BusinessException("MCP 服务「" + server.key()
                        + "」使用 SSE 传输，本平台暂不支持，请改用 STREAMABLE_HTTP");
            }
            requireDeclared(server.authHeaderVar(), variableNames,
                    "MCP 服务「" + server.key() + "」的 authHeaderVar");
            requireInlineVarsDeclared(server.url(), variableNames,
                    "MCP 服务「" + server.key() + "」的地址");
        }

        Set<String> skillKeys = new LinkedHashSet<>();
        for (PackageManifest.SkillSpec skill : skills) {
            requireKey(skill.key(), "技能", skillKeys);
            requireText(skill.name(), MAX_NAME, "技能「" + skill.key() + "」的名称");
            if (skill.description() != null && skill.description().length() > MAX_DESCRIPTION) {
                throw new BusinessException("技能「" + skill.key() + "」的描述超过 "
                        + MAX_DESCRIPTION + " 字");
            }
            if (skill.content() == null || skill.content().isBlank()) {
                // 空正文的 skill 装出来什么都不会发生：resolve() 只收集非空 content，
                // 而它绑的工具也就跟着挂不上（工具只经 skill 激活）
                throw new BusinessException("技能「" + skill.key() + "」的正文为空");
            }
            for (String toolKey : PackageManifest.nullSafe(skill.toolKeys())) {
                if (!toolKeys.contains(toolKey)) {
                    throw new BusinessException("技能「" + skill.key() + "」引用了不存在的工具 key「"
                            + toolKey + "」");
                }
            }
            for (PackageManifest.McpRef ref : PackageManifest.nullSafe(skill.mcpRefs())) {
                if (ref.serverKey() == null || !serverKeys.contains(ref.serverKey())) {
                    throw new BusinessException("技能「" + skill.key()
                            + "」引用了不存在的 MCP 服务 key「" + ref.serverKey() + "」");
                }
                if (PackageManifest.nullSafe(ref.toolNames()).isEmpty()) {
                    throw new BusinessException("技能「" + skill.key() + "」对 MCP 服务「"
                            + ref.serverKey() + "」的引用没有列出任何工具名");
                }
            }
        }
    }

    /** 校验变量声明本身，返回全部变量名。 */
    private Set<String> validateVariables(List<PackageManifest.VariableSpec> variables) {
        Set<String> names = new LinkedHashSet<>();
        for (PackageManifest.VariableSpec variable : variables) {
            String name = variable.name();
            if (name == null || !VARIABLE_NAME.matcher(name).matches()) {
                throw new BusinessException("变量名「" + name
                        + "」非法：只能是大写字母开头的大写字母、数字、下划线组合");
            }
            if (!names.add(name)) {
                throw new BusinessException("变量名「" + name + "」重复声明");
            }
            if (variable.label() == null || variable.label().isBlank()) {
                // label 是安装表单上唯一的提示，空着等于让租户对着变量名猜要填什么
                throw new BusinessException("变量「" + name + "」缺少表单标签（label）");
            }
        }
        return names;
    }

    private void requireKey(String key, String what, Set<String> seen) {
        if (key == null || key.isBlank()) {
            throw new BusinessException(what + "缺少包内 key");
        }
        if (key.length() > MAX_KEY) {
            throw new BusinessException(what + "的 key「" + key + "」过长");
        }
        if (!seen.add(key)) {
            throw new BusinessException(what + "的 key「" + key + "」重复");
        }
    }

    private void requireText(String value, int max, String what) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(what + "为空");
        }
        if (value.length() > max) {
            throw new BusinessException(what + "超过 " + max + " 个字符");
        }
    }

    /** 字段级变量引用（{@code authHeaderVar}）必须指向一个已声明的变量；为 null 表示不需要鉴权。 */
    private void requireDeclared(String varName, Set<String> declared, String what) {
        if (varName == null || varName.isBlank()) {
            return;
        }
        if (!declared.contains(varName)) {
            throw new BusinessException(what + "引用了未声明的变量「" + varName + "」");
        }
    }

    /** 行内变量引用（{@code ${VAR}}）同样必须指向已声明的变量。 */
    private void requireInlineVarsDeclared(String text, Set<String> declared, String what) {
        for (String name : referencedVariables(text)) {
            if (!declared.contains(name)) {
                throw new BusinessException(what + "引用了未声明的变量「" + name + "」");
            }
        }
    }

    /** 取异常链最内层的消息，避免把一长串包装异常的 toString 丢给用户。 */
    private String rootMessage(Throwable e) {
        Throwable current = e;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null ? current.getClass().getSimpleName() : message;
    }
}
