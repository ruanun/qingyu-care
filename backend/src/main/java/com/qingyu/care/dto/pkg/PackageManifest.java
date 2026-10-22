package com.qingyu.care.dto.pkg;

import com.qingyu.care.enums.McpTransport;

import java.util.List;

/**
 * 能力包 manifest 的类型化表示（spec §3.2）。序列化后存进 {@code tool_package.manifest}。
 *
 * <p><b>引用一律用包内 {@code key}，绝不用 id</b>（spec §3.3）：{@code AiSkill} 绑的是
 * {@code toolIds}（{@code Set<Long>}），而安装时工具是新建行、id 全新。安装期间维护一张
 * {@code key → 新 id} 映射表，建完工具再回填 skill。
 * ⚠️ 漏掉这层映射的后果是<b>完全静默</b>：{@code ChatToolService.resolve()} 的 filter-bound
 * 查询查不到那个 id 就自然跳过，不报错、不打日志，界面上 skill 仍显示绑着 N 个工具，实际
 * 挂给 LLM 的少了几个——与 docs/dev/子系统要点.md「MCP 接入」第 7 条同一个失效模式。
 *
 * <p><b>这里没有 {@code scope} / {@code tenantId} / {@code appId}</b>（spec §3.4 / §6.3）：
 * 安装时一律由服务端强制 {@code OwnerScope.TENANT} + 当前 {@code TenantContext}。
 * {@code scope} 是客户端可控字段这条已经真实栽过一次——租户 POST {@code /v1/ai-tools} 时在
 * 请求体里自封 {@code "scope":"PLATFORM"}，落库后带着自己的 {@code authHeader} 出现在所有
 * 租户的共享列表里（docs/dev/子系统要点.md「平台共享工具」第 1 条）。manifest 是另一条能把值送进实体的
 * 路径，<b>字段压根不存在比判空更可靠</b>。
 *
 * <p><b>凭据不进 manifest</b>：{@code authHeader} 在导出时升格成一个 {@code secret} 变量，
 * 字段位置只留变量名（{@code authHeaderVar}）。
 *
 * @author ruan
 */
public record PackageManifest(
        Integer manifestVersion,
        List<ToolSpec> tools,
        List<McpServerSpec> mcpServers,
        List<SkillSpec> skills,
        List<VariableSpec> variables) {

    /**
     * 当前 manifest 版本。
     *
     * <p>⚠️ 它与「包没有版本号」（spec §3.1）不是一回事：这是 <b>manifest 结构</b>的版本，
     * 由解析器强制校验、拒绝未知版本；那是<b>包内容</b>的版本，刻意不做。
     */
    public static final int CURRENT_VERSION = 1;

    /**
     * 一个 HTTP 工具。字段与 {@code AiTool} 一一对应，缺的那些由
     * {@code PackageManifestFieldCoverageTest} 的白名单逐条说明为什么不在这里。
     *
     * @param key           包内符号，仅用于 skill 引用，<b>不落库</b>
     * @param authHeaderVar 引用 {@link VariableSpec#name()}，可空（工具不需要鉴权时）。
     *                      ⚠️ 变量值就是 {@code authHeader} 的<b>完整值</b>（含
     *                      {@code "Authorization: "} 这样的头名前缀），导出时生成的
     *                      {@code help} 会把原头名带出来告诉租户要填整行
     */
    public record ToolSpec(
            String key,
            String name,
            String description,
            String inputSchema,
            String httpMethod,
            String urlTemplate,
            Integer timeoutMs,
            Integer maxResponseChars,
            String authHeaderVar) {
    }

    /**
     * 一个远端 MCP server。
     *
     * <p>⚠️ 安装那一刻 {@code mcp_tool} 表里<b>一行都还没有</b>（工具清单要连远端同步才有），
     * 所以 skill 对 MCP 工具的引用只能按<b>工具名</b>走 {@link McpRef}，不能按 id。
     */
    public record McpServerSpec(
            String key,
            String name,
            String code,
            String description,
            McpTransport transport,
            String url,
            Integer timeoutMs,
            Integer maxResponseChars,
            String authHeaderVar) {
    }

    /**
     * 一个 skill。
     *
     * @param toolKeys 引用 {@link ToolSpec#key()}
     * @param mcpRefs  引用 {@link McpServerSpec#key()} + 远端工具名
     */
    public record SkillSpec(
            String key,
            String name,
            String description,
            String content,
            List<String> toolKeys,
            List<McpRef> mcpRefs) {
    }

    /** skill 对某个 MCP server 上若干工具的引用（按工具名，见 {@link McpServerSpec} 的说明）。 */
    public record McpRef(String serverKey, List<String> toolNames) {
    }

    /**
     * 一个安装时要租户填的变量。
     *
     * @param name     变量名，格式 {@code [A-Z][A-Z0-9_]*}，在 {@code ${NAME}} 与
     *                 {@code authHeaderVar} 两处被引用
     * @param label    表单标签，导出时自动生成一个能用的，平台管理员发布前应改成人话
     * @param required 必填。缺失时安装直接拒绝；非必填缺失时其引用位置替换成空串
     * @param secret   前端用 password 输入框，且不回显
     * @param help     填写说明。对升格来的凭据变量，这里会带出原来的请求头名
     */
    public record VariableSpec(
            String name,
            String label,
            boolean required,
            boolean secret,
            String help) {
    }

    /**
     * 把可能为 null 的列表归一成空列表。
     *
     * <p>manifest 是 JSON 来的，任何一个数组字段都可能缺省；调用方遍历前一律过这一道，
     * 好过在五个地方各写一次判空（漏掉一处就是一个 NPE，而它只在「包里恰好没有 MCP server」
     * 这类偏门组合下才现形）。
     */
    public static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }
}
