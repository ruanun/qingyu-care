package com.qingyu.care.service.pkg;

import com.qingyu.care.dto.pkg.PackageManifest;
import com.qingyu.care.entity.AiSkill;
import com.qingyu.care.entity.AiTool;
import com.qingyu.care.entity.McpServer;
import com.qingyu.care.enums.McpTransport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PackageManifestBuilder} 的规则测试。纯函数、无 Mockito。
 *
 * @author ruan
 */
class PackageManifestBuilderTest {

    private final PackageManifestBuilder builder = new PackageManifestBuilder();

    private static AiTool tool(Long id, String name, String authHeader) {
        AiTool t = AiTool.builder()
                .name(name).description("描述 " + name)
                .inputSchema("{\"type\":\"object\"}")
                .httpMethod("GET")
                .urlTemplate("https://api.example.com/" + name + "?city={city}")
                .timeoutMs(5000).maxResponseChars(4000)
                .enabled(true)
                .build();
        t.setId(id);
        t.setAuthHeader(authHeader);
        return t;
    }

    private static AiSkill skill(Long id, String name, Set<Long> toolIds, Set<Long> mcpToolIds) {
        AiSkill s = AiSkill.builder()
                .name(name).description("技能描述").content("# " + name + "\n正文")
                .toolIds(toolIds).mcpToolIds(mcpToolIds).enabled(true)
                .build();
        s.setId(id);
        return s;
    }

    private static McpServer server(Long id, String code, String authHeader) {
        McpServer m = McpServer.builder()
                .name("服务 " + code).code(code).description("MCP 描述")
                .transport(McpTransport.STREAMABLE_HTTP)
                .url("https://mcp.example.com/" + code)
                .timeoutMs(10000).maxResponseChars(4000).enabled(true)
                .build();
        m.setId(id);
        m.setAuthHeader(authHeader);
        return m;
    }

    private static PackageManifestBuilder.BuildInput input(
            List<AiTool> tools, List<McpServer> servers, List<AiSkill> skills) {
        return new PackageManifestBuilder.BuildInput(tools, servers, skills, Map.of(), Map.of());
    }

    @Test
    void 工具字段被逐项抄进manifest() {
        PackageManifestBuilder.BuildResult result =
                builder.build(input(List.of(tool(1L, "weather_query", null)), List.of(), List.of()));

        PackageManifest.ToolSpec spec = result.manifest().tools().get(0);
        assertThat(spec.key()).isEqualTo("weather_query");
        assertThat(spec.name()).isEqualTo("weather_query");
        assertThat(spec.description()).isEqualTo("描述 weather_query");
        assertThat(spec.inputSchema()).isEqualTo("{\"type\":\"object\"}");
        assertThat(spec.httpMethod()).isEqualTo("GET");
        assertThat(spec.urlTemplate()).isEqualTo("https://api.example.com/weather_query?city={city}");
        assertThat(spec.timeoutMs()).isEqualTo(5000);
        assertThat(spec.maxResponseChars()).isEqualTo(4000);
        assertThat(spec.authHeaderVar()).isNull();
        assertThat(result.manifest().manifestVersion()).isEqualTo(PackageManifest.CURRENT_VERSION);
    }

    @Test
    @DisplayName("凭据升格成 secret 变量，manifest 里绝不出现明文")
    void 凭据升格成变量() {
        PackageManifestBuilder.BuildResult result = builder.build(
                input(List.of(tool(1L, "weather_query", "Authorization: Bearer sk-super-secret")),
                        List.of(), List.of()));

        PackageManifest.ToolSpec spec = result.manifest().tools().get(0);
        assertThat(spec.authHeaderVar()).isEqualTo("WEATHER_QUERY_AUTH");

        PackageManifest.VariableSpec variable = result.manifest().variables().get(0);
        assertThat(variable.name()).isEqualTo("WEATHER_QUERY_AUTH");
        assertThat(variable.secret()).isTrue();
        assertThat(variable.required()).isTrue();
        // 头名要带出来，否则租户不知道要填整行
        assertThat(variable.help()).contains("Authorization");

        // ⚠️ 核心：整份 manifest 的任何角落都不能出现明文密钥
        assertThat(result.manifest().toString()).doesNotContain("sk-super-secret");
        assertThat(variable.help()).doesNotContain("sk-super-secret");
    }

    @Test
    @DisplayName("导出全程只读实体：源工具的 authHeader 一个字节都不能被改动")
    void 导出不修改源实体() {
        AiTool source = tool(1L, "weather_query", "Authorization: Bearer sk-keep-me");

        builder.build(input(List.of(source), List.of(), List.of()));

        // OSIV 下实体是受管的，任何 setter 都会被脏检查刷回库——本意是防泄漏，
        // 结果是把平台管理员自己的凭据真的删掉（spec §4.1 / §6.2）
        assertThat(source.getAuthHeader()).isEqualTo("Authorization: Bearer sk-keep-me");
    }

    @Test
    void skill的工具绑定转成包内key引用() {
        AiTool t1 = tool(1L, "weather_query", null);
        AiTool t2 = tool(2L, "order_query", null);
        AiSkill s = skill(10L, "客服技能", Set.of(1L, 2L), Set.of());

        PackageManifest manifest = builder.build(input(List.of(t1, t2), List.of(), List.of(s))).manifest();

        assertThat(manifest.skills().get(0).toolKeys())
                .containsExactlyInAnyOrder("weather_query", "order_query");
    }

    @Test
    @DisplayName("skill 绑了不在导出集合内的工具 → 记 warning，不阻断（spec §4.1）")
    void 集合外的工具引用记warning() {
        AiTool t1 = tool(1L, "weather_query", null);
        AiSkill s = skill(10L, "客服技能", Set.of(1L, 99L), Set.of());

        PackageManifestBuilder.BuildResult result = builder.build(
                new PackageManifestBuilder.BuildInput(List.of(t1), List.of(), List.of(s),
                        Map.of(99L, "order_query"), Map.of()));

        assertThat(result.manifest().skills().get(0).toolKeys()).containsExactly("weather_query");
        assertThat(result.warnings()).anySatisfy(w ->
                assertThat(w).contains("order_query").contains("不在本次导出"));
    }

    @Test
    void MCP工具绑定转成serverKey加工具名() {
        McpServer m = server(5L, "ctx7", null);
        AiSkill s = skill(10L, "文档技能", Set.of(), Set.of(100L, 101L));

        PackageManifest manifest = builder.build(new PackageManifestBuilder.BuildInput(
                List.of(), List.of(m), List.of(s), Map.of(),
                Map.of(100L, new PackageManifestBuilder.McpToolRef(5L, "resolve-library-id"),
                        101L, new PackageManifestBuilder.McpToolRef(5L, "get-docs")))).manifest();

        assertThat(manifest.skills().get(0).mcpRefs()).hasSize(1);
        PackageManifest.McpRef ref = manifest.skills().get(0).mcpRefs().get(0);
        assertThat(ref.serverKey()).isEqualTo("ctx7");
        assertThat(ref.toolNames()).containsExactlyInAnyOrder("resolve-library-id", "get-docs");
    }

    @Test
    void 指向集合外MCP服务的工具引用记warning() {
        AiSkill s = skill(10L, "文档技能", Set.of(), Set.of(100L));

        PackageManifestBuilder.BuildResult result = builder.build(new PackageManifestBuilder.BuildInput(
                List.of(), List.of(), List.of(s), Map.of(),
                Map.of(100L, new PackageManifestBuilder.McpToolRef(5L, "resolve-library-id"))));

        assertThat(result.manifest().skills().get(0).mcpRefs()).isEmpty();
        assertThat(result.warnings()).anySatisfy(w -> assertThat(w).contains("不在本次导出"));
    }

    @Test
    @DisplayName("URL 里疑似嵌了密钥 → 记 warning（启发式，最终仍要人逐字看一遍）")
    void URL里疑似密钥记warning() {
        AiTool t = AiTool.builder()
                .name("weather_query").description("查天气").httpMethod("GET")
                .urlTemplate("https://api.example.com/weather?apikey=abcd1234&city={city}")
                .timeoutMs(5000).maxResponseChars(4000).enabled(true).build();
        t.setId(1L);

        PackageManifestBuilder.BuildResult result = builder.build(input(List.of(t), List.of(), List.of()));

        assertThat(result.warnings()).anySatisfy(w ->
                assertThat(w).contains("weather_query").contains("密钥"));
    }

    @Test
    @DisplayName("key 冲突时加序号，不能让两个工具共用一个 key（skill 引用会指错对象）")
    void key冲突时加序号() {
        AiTool t1 = tool(1L, "query", null);
        AiTool t2 = tool(2L, "query", null);

        PackageManifest manifest = builder.build(input(List.of(t1, t2), List.of(), List.of())).manifest();

        assertThat(manifest.tools()).extracting(PackageManifest.ToolSpec::key)
                .containsExactly("query", "query_2");
    }

    @Test
    @DisplayName("中文名的 skill 也要产出合法 key")
    void 中文名产出合法key() {
        AiSkill s = skill(10L, "客服技能", Set.of(), Set.of());

        PackageManifest manifest = builder.build(input(List.of(), List.of(), List.of(s))).manifest();

        assertThat(manifest.skills().get(0).key()).matches("[a-z][a-z0-9_]*");
        assertThat(manifest.skills().get(0).name()).isEqualTo("客服技能");
    }

    /**
     * ⚠️ 与「key 冲突时加序号」是不同的判据：key 只是包内符号，{@link #uniqueKey} 已经保证它在
     * 单次导出内唯一，两个工具即便同名也会各自拿到不同的 key（{@code query} / {@code query_2}）。
     * 但装出来的 {@code AiTool.name} 会是两行同名——{@code ChatToolService.resolve()} 遇到重名
     * 只 log.warn 跳过其一，实际挂给 LLM 的少一个。导出阶段只记 warning 不阻断（留给平台管理员
     * 在预览里决定改名与否），真正的硬拦截在 {@code PackageManifestParser.requireUniqueName}。
     */
    @Test
    @DisplayName("两个工具同名（key 因去重不同）→ 记 warning，提示装出来会静默丢失调用能力")
    void 导出同名工具记warning() {
        AiTool t1 = tool(1L, "query", null);
        AiTool t2 = tool(2L, "query", null);

        PackageManifestBuilder.BuildResult result = builder.build(input(List.of(t1, t2), List.of(), List.of()));

        // key 因去重各不相同——这条与「key 冲突时加序号」验的是同一批工具，但断言的是另一件事
        assertThat(result.manifest().tools()).extracting(PackageManifest.ToolSpec::key)
                .containsExactly("query", "query_2");
        assertThat(result.warnings()).anySatisfy(w ->
                assertThat(w).contains("query").contains("同名"));
    }

    @Test
    void 两个凭据变量名不冲突() {
        PackageManifest manifest = builder.build(input(
                List.of(tool(1L, "query", "Authorization: Bearer a"),
                        tool(2L, "query", "X-Api-Key: b")),
                List.of(), List.of())).manifest();

        assertThat(manifest.variables()).extracting(PackageManifest.VariableSpec::name)
                .containsExactly("QUERY_AUTH", "QUERY_2_AUTH");
    }
}
