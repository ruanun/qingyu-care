package com.qingyu.care.service.pkg;

import com.qingyu.care.dto.pkg.PackageManifest;
import com.qingyu.care.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;

/**
 * 把租户填的变量取值套进 manifest。<b>纯函数</b>：不碰数据库、不碰网络。
 *
 * <p><b>变量只允许出现在四个字段</b>（spec §3.5）：{@code AiTool.authHeader}、
 * {@code AiTool.urlTemplate}、{@code McpServer.authHeader}、{@code McpServer.url}。
 * <b>不做全文模板替换</b>——那意味着租户填的值能落进 manifest 的任意位置（包括 skill 的
 * markdown、{@code inputSchema}），既难审计又给注入留了口子。
 *
 * <p>⚠️⚠️ <b>替换后必须断言不残留任何 <code>${</code></b>，这是本类存在的首要理由：
 * {@code UrlTemplateFiller.PLACEHOLDER} 是 <code>\{([^{}]+)}</code>，它<b>会匹配
 * <code>${VAR}</code> 里的 <code>{VAR}</code></b>。一个没被替换掉的变量落库之后：
 * <ul>
 *   <li>残留在 <b>query 段</b> → 运行时该段被<b>整段静默删除</b>（{@code UrlTemplateFiller}
 *       对「段内仍有占位符」的处理就是丢弃整段）。鉴权参数就此消失 → 第三方返 401 →
 *       模型拿着错误响应继续编答案，<b>全程无异常无日志</b>；
 *   <li>残留在 <b>path 段</b> → 抛「缺少必填参数 VAR」，一个 LLM 永远填不出来的参数名，
 *       排查的人会一路去查 inputSchema 而不是想到包变量。
 * </ul>
 * 同理，<b>变量取值本身不得含 <code>{</code> 或 <code>}</code></b>（仅限被行内引用的变量）
 * ——租户填一个 {@code evil{city}host.com} 就等于凭空造出一个假的 LLM 占位符。
 *
 * @author ruan
 */
@Component
public class PackageVariableApplier {

    /**
     * 与 {@code AiTool.urlTemplate} / {@code McpServer.url} 的列宽一致。
     *
     * <p>⚠️ {@code PackageManifestParser} 也校验 {@code urlTemplate} 的长度，但它校的是<b>带
     * {@code ${VAR}} 占位符的模板</b>——替换成租户真值之后的长度不受它约束。一个长 JWT 或云厂商
     * 签名串轻松让替换后的地址超过 1000，不在这里补一道就会在 {@code save()} 那一刻抛
     * {@code DataIntegrityViolationException}，同 parser 类注释里点名的那个坑换个位置复发。
     */
    private static final int MAX_URL = 1000;

    /** 与 {@code AiTool.authHeader} / {@code McpServer.authHeader} 的列宽一致，理由同 {@link #MAX_URL}。 */
    private static final int MAX_AUTH_HEADER = 1000;

    /** 一次安装的产物：填充后的 manifest + 两张「包内 key → 最终 authHeader」表。 */
    public record AppliedPackage(
            PackageManifest manifest,
            Map<String, String> toolAuthHeaders,
            Map<String, String> serverAuthHeaders) {
    }

    public AppliedPackage apply(PackageManifest manifest, Map<String, String> values) {
        Map<String, String> given = values == null ? Map.of() : values;
        List<PackageManifest.VariableSpec> variables = PackageManifest.nullSafe(manifest.variables());

        // ① 必填校验：一次列全，别让人试一次填一个
        List<String> missing = new ArrayList<>();
        for (PackageManifest.VariableSpec variable : variables) {
            if (variable.required() && isBlank(given.get(variable.name()))) {
                missing.add(variable.name() + "（" + variable.label() + "）");
            }
        }
        if (!missing.isEmpty()) {
            throw new BusinessException("以下必填项没有填写：" + String.join("、", missing));
        }

        // ② 被行内引用的变量，其取值不得含大括号——会凭空造出假的 LLM 参数占位符
        Set<String> inlineReferenced = collectInlineReferenced(manifest);
        for (String name : inlineReferenced) {
            String value = given.get(name);
            if (value != null && (value.indexOf('{') >= 0 || value.indexOf('}') >= 0)) {
                throw new BusinessException("变量「" + name + "」的取值里不能包含大括号 { }："
                        + "它会被当成给 AI 填写的参数占位符，导致请求地址出错");
            }
        }

        // ③ 替换 + 结构校验
        // declared 是「manifest 里声明过的变量名」。⚠️ 它不能省：${GHOST} 这种「名字合法但没
        // 声明」的引用能被 VARIABLE_REF 匹配到，若只靠替换后查 ${ 那道兜底，它会被悄悄换成
        // 空串然后放行——鉴权参数就此凭空消失，正是本类要防的那个静默失效。
        Set<String> declared = new LinkedHashSet<>();
        variables.forEach(v -> declared.add(v.name()));

        Map<String, String> toolAuthHeaders = new LinkedHashMap<>();
        List<PackageManifest.ToolSpec> tools = new ArrayList<>();
        for (PackageManifest.ToolSpec tool : PackageManifest.nullSafe(manifest.tools())) {
            String url = substitute(tool.urlTemplate(), given, declared,
                    "工具「" + tool.name() + "」的地址");
            requireHttpUrl(url, "工具「" + tool.name() + "」的地址");
            authHeaderOf(tool.authHeaderVar(), given)
                    .ifPresent(header -> {
                        requireAuthHeaderLength(header, "工具「" + tool.name() + "」的鉴权头");
                        toolAuthHeaders.put(tool.key(), header);
                    });
            tools.add(new PackageManifest.ToolSpec(
                    tool.key(), tool.name(), tool.description(), tool.inputSchema(),
                    tool.httpMethod(), url, tool.timeoutMs(), tool.maxResponseChars(),
                    tool.authHeaderVar()));
        }

        Map<String, String> serverAuthHeaders = new LinkedHashMap<>();
        List<PackageManifest.McpServerSpec> servers = new ArrayList<>();
        for (PackageManifest.McpServerSpec server : PackageManifest.nullSafe(manifest.mcpServers())) {
            String url = substitute(server.url(), given, declared,
                    "MCP 服务「" + server.name() + "」的地址");
            requireHttpUrl(url, "MCP 服务「" + server.name() + "」的地址");
            authHeaderOf(server.authHeaderVar(), given)
                    .ifPresent(header -> {
                        requireAuthHeaderLength(header, "MCP 服务「" + server.name() + "」的鉴权头");
                        serverAuthHeaders.put(server.key(), header);
                    });
            servers.add(new PackageManifest.McpServerSpec(
                    server.key(), server.name(), server.code(), server.description(),
                    server.transport(), url, server.timeoutMs(), server.maxResponseChars(),
                    server.authHeaderVar()));
        }

        // skills / variables 原样带过——变量不进 skill 正文（见类注释）
        PackageManifest applied = new PackageManifest(
                manifest.manifestVersion(), tools, servers,
                PackageManifest.nullSafe(manifest.skills()), variables);
        return new AppliedPackage(applied, toolAuthHeaders, serverAuthHeaders);
    }

    /** {@code authHeaderVar} 指向的取值；变量没引用或取值为空时表示「这行不设鉴权头」。 */
    private java.util.Optional<String> authHeaderOf(String authHeaderVar, Map<String, String> given) {
        if (authHeaderVar == null || authHeaderVar.isBlank()) {
            return java.util.Optional.empty();
        }
        String value = given.get(authHeaderVar);
        return isBlank(value) ? java.util.Optional.empty() : java.util.Optional.of(value);
    }

    /** manifest 里全部被 {@code ${VAR}} 行内引用到的变量名。 */
    private Set<String> collectInlineReferenced(PackageManifest manifest) {
        Set<String> names = new LinkedHashSet<>();
        for (PackageManifest.ToolSpec tool : PackageManifest.nullSafe(manifest.tools())) {
            names.addAll(PackageManifestParser.referencedVariables(tool.urlTemplate()));
        }
        for (PackageManifest.McpServerSpec server : PackageManifest.nullSafe(manifest.mcpServers())) {
            names.addAll(PackageManifestParser.referencedVariables(server.url()));
        }
        return names;
    }

    /**
     * 行内替换：已声明且有取值的换成取值，已声明但没取值的换成空串（非必填变量走到这里）。
     *
     * <p><b>两道检查缺一不可，它们守的是两种不同的写错方式</b>：
     * <ul>
     *   <li><b>没声明</b>（{@code ${GHOST}}）→ 立刻抛。这种引用<b>能</b>被 VARIABLE_REF 匹配到，
     *       所以下面那道「查 {@code ${}」的兜底<b>看不见它</b>——不显式卡住的话它会被悄悄换成
     *       空串然后放行，鉴权参数凭空消失；
     *   <li><b>语法写错</b>（{@code ${lowercase}}）→ 匹配不到、替换整个跳过、{@code ${...}}
     *       原样留在串里，由下面那道兜底抓。
     * </ul>
     * 两种残留落库后的后果都写在类注释里那条「最危险」。
     */
    private String substitute(String template, Map<String, String> given,
                              Set<String> declared, String what) {
        if (template == null) {
            return null;
        }
        Matcher m = PackageManifestParser.VARIABLE_REF.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            if (!declared.contains(name)) {
                throw new BusinessException(PackageManifestParser.msg(what, "引用了未声明的变量「" + name
                        + "」：请先在包的变量表里声明它，否则安装时它会被替换成空值"));
            }
            String value = given.getOrDefault(name, "");
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        String result = sb.toString();

        int leftover = result.indexOf("${");
        if (leftover >= 0) {
            throw new BusinessException(PackageManifestParser.msg(what, "里还有没被替换的变量引用「"
                    + result.substring(leftover, Math.min(result.length(), leftover + 40))
                    + "」：变量名必须是大写字母开头的大写字母、数字、下划线组合，且已在变量表里声明"));
        }
        return result;
    }

    /**
     * 替换后的地址必须仍是 http/https 绝对地址且有非空 host。
     *
     * <p>⚠️ <b>不能用 {@code URI.create} 校验</b>：{@code urlTemplate} 里合法地留着
     * {@code {city}} 这样的 LLM 占位符，而 {@code {} 不是合法的 URI 字符，会直接抛异常。
     * 故这里只做前缀 + host 段的字符串校验。
     */
    private void requireHttpUrl(String url, String what) {
        if (url == null || url.isBlank()) {
            throw new BusinessException(PackageManifestParser.msg(what, "为空"));
        }
        String rest;
        if (url.startsWith("https://")) {
            rest = url.substring("https://".length());
        } else if (url.startsWith("http://")) {
            rest = url.substring("http://".length());
        } else {
            throw new BusinessException(PackageManifestParser.msg(what, "必须是 http:// 或 https:// 开头的完整地址，当前是：" + url));
        }
        int end = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                end = i;
                break;
            }
        }
        String host = rest.substring(0, end);
        if (host.isBlank() || host.indexOf(' ') >= 0) {
            throw new BusinessException(PackageManifestParser.msg(what, "的域名部分为空或含空格，替换后的结果是：" + url));
        }
        if (url.length() > MAX_URL) {
            // ⚠️ PackageManifestParser 只校验了带 ${VAR} 的模板长度，替换成租户真值之后可能
            // 更长（也可能更短，取决于取值）——这道必须在替换之后单独补，不能指望 parser 那道
            // 顶上：那是发布时校验平台管理员写的模板，这是安装时校验租户填的真实取值。
            throw new BusinessException(PackageManifestParser.msg(what, "替换变量后长度为 " + url.length()
                    + "，超过了 " + MAX_URL + " 个字符的上限，请检查你填的变量取值是否过长"));
        }
    }

    /**
     * 鉴权头取值不得超过实体列宽。
     *
     * <p>与 {@link #requireHttpUrl} 里的长度检查同一个理由：{@code PackageManifestParser} 没有
     * 校验这条——manifest 里存的是变量名（{@code authHeaderVar}），不是取值，租户在安装表单里
     * 填的一个长 JWT 或云厂商签名串轻松超过 1000，不在这里挡住就会在 {@code save()} 那一刻抛
     * {@code DataIntegrityViolationException} → 500 + 完整 INSERT 语句回给客户端。
     */
    private void requireAuthHeaderLength(String header, String what) {
        if (header.length() > MAX_AUTH_HEADER) {
            throw new BusinessException(PackageManifestParser.msg(what, "长度为 " + header.length()
                    + "，超过了 " + MAX_AUTH_HEADER + " 个字符的上限，请检查你填的取值是否过长"));
        }
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
