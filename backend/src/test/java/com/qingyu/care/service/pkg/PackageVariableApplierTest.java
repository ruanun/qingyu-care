package com.qingyu.care.service.pkg;

import com.qingyu.care.dto.pkg.PackageManifest;
import com.qingyu.care.enums.McpTransport;
import com.qingyu.care.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link PackageVariableApplier} 的规则测试。纯函数、无 Mockito。
 *
 * @author ruan
 */
class PackageVariableApplierTest {

    private final PackageVariableApplier applier = new PackageVariableApplier();

    private static PackageManifest manifestWith(PackageManifest.ToolSpec tool,
                                                PackageManifest.VariableSpec... variables) {
        return new PackageManifest(PackageManifest.CURRENT_VERSION,
                List.of(tool), List.of(), List.of(), List.of(variables));
    }

    private static PackageManifest.ToolSpec tool(String urlTemplate, String authHeaderVar) {
        return new PackageManifest.ToolSpec("weather_query", "weather_query", "查天气",
                "{\"type\":\"object\"}", "GET", urlTemplate, 5000, 4000, authHeaderVar);
    }

    private static PackageManifest.VariableSpec variable(String name, boolean required) {
        return new PackageManifest.VariableSpec(name, name + " 标签", required, false, "说明");
    }

    @Test
    void 行内变量被替换成租户填的值() {
        PackageManifest manifest = manifestWith(
                tool("https://${WEATHER_HOST}/v1/weather?city={city}", null),
                variable("WEATHER_HOST", true));

        PackageVariableApplier.AppliedPackage applied =
                applier.apply(manifest, Map.of("WEATHER_HOST", "api.qweather.com"));

        assertThat(applied.manifest().tools().get(0).urlTemplate())
                .isEqualTo("https://api.qweather.com/v1/weather?city={city}");
    }

    @Test
    @DisplayName("LLM 参数占位符 {city} 必须原样留着——它不是包变量")
    void LLM占位符不被当成变量() {
        PackageManifest manifest = manifestWith(
                tool("https://api.example.com/weather?city={city}&day={day}", null));

        PackageVariableApplier.AppliedPackage applied = applier.apply(manifest, Map.of());

        assertThat(applied.manifest().tools().get(0).urlTemplate())
                .isEqualTo("https://api.example.com/weather?city={city}&day={day}");
    }

    @Test
    void authHeaderVar的取值进authHeaders表而不进manifest() {
        PackageManifest manifest = manifestWith(
                tool("https://api.example.com/weather?city={city}", "WEATHER_AUTH"),
                variable("WEATHER_AUTH", true));

        PackageVariableApplier.AppliedPackage applied = applier.apply(manifest,
                Map.of("WEATHER_AUTH", "Authorization: Bearer sk-tenant-own"));

        assertThat(applied.toolAuthHeaders()).containsEntry("weather_query",
                "Authorization: Bearer sk-tenant-own");
        // manifest 本身不带凭据——它是要展示给人看的
        assertThat(applied.manifest().tools().get(0).authHeaderVar()).isEqualTo("WEATHER_AUTH");
    }

    @Test
    @DisplayName("必填变量缺失 → 一次列全部缺的，别让人试一次填一个")
    void 必填变量缺失时一次列全() {
        PackageManifest manifest = new PackageManifest(PackageManifest.CURRENT_VERSION,
                List.of(tool("https://${HOST}/x?city={city}", "AUTH")), List.of(), List.of(),
                List.of(variable("HOST", true), variable("AUTH", true)));

        assertThatThrownBy(() -> applier.apply(manifest, Map.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("HOST")
                .hasMessageContaining("AUTH");
    }

    @Test
    void 全空格的必填变量等同于缺失() {
        PackageManifest manifest = manifestWith(
                tool("https://api.example.com/x?key=${K}&city={city}", null),
                variable("K", true));

        assertThatThrownBy(() -> applier.apply(manifest, Map.of("K", "   ")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("K");
    }

    @Test
    @DisplayName("非必填变量缺失 → 替换成空串，替换后仍须通过 URL 结构校验")
    void 非必填变量缺失替换成空串() {
        PackageManifest manifest = manifestWith(
                tool("https://api.example.com/x?region=${REGION}&city={city}", null),
                variable("REGION", false));

        PackageVariableApplier.AppliedPackage applied = applier.apply(manifest, Map.of());

        assertThat(applied.manifest().tools().get(0).urlTemplate())
                .isEqualTo("https://api.example.com/x?region=&city={city}");
    }

    /**
     * ⚠️ 本条与下一条共同守着整个特性最危险的一处：残留的 ${VAR} 会被 UrlTemplateFiller 的
     * \{([^{}]+)} 当成 LLM 参数占位符——在 query 段会被<b>整段静默删除</b>。
     *
     * <p>这一条走的是「变量名合法但没声明」：{@code ${GHOST}} 能被 VARIABLE_REF 匹配到，
     * 所以它<b>不会</b>留下 {@code ${}——若实现只有「替换后查 ${」那一道兜底，它会被悄悄换成
     * 空串然后放行。必须显式拿 variables 里声明的集合去卡。
     */
    @Test
    @DisplayName("引用了没声明的变量必须拒绝，不能悄悄替换成空串")
    void 未声明的变量引用被拒绝() {
        // 正常路径下 parser 会先拦下这种 manifest，这里直接构造对象绕过 parser，
        // 钉住 applier 自己也有这道兜底
        PackageManifest manifest = manifestWith(
                tool("https://api.example.com/x?token=${GHOST}&city={city}", null));

        assertThatThrownBy(() -> applier.apply(manifest, Map.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("GHOST");
    }

    /**
     * 这一条走的是另一半：<b>变量名写错了大小写</b>（或别的形状），VARIABLE_REF 根本匹配不到，
     * 替换整个跳过、{@code ${...}} 原样留在串里。两条走的是实现里两道不同的检查，缺一不可。
     */
    @Test
    @DisplayName("语法写错的变量引用（如小写名）会残留 ${...}，必须拒绝")
    void 语法写错的变量引用被拒绝() {
        PackageManifest manifest = manifestWith(
                tool("https://api.example.com/x?token=${lowercase}&city={city}", null));

        assertThatThrownBy(() -> applier.apply(manifest, Map.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("lowercase");
    }

    @Test
    @DisplayName("变量取值里含大括号会造出假的 LLM 占位符，必须拒绝")
    void 变量取值不得含大括号() {
        PackageManifest manifest = manifestWith(
                tool("https://${HOST}/x?city={city}", null),
                variable("HOST", true));

        assertThatThrownBy(() -> applier.apply(manifest, Map.of("HOST", "evil{city}host.com")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("HOST");
    }

    @Test
    @DisplayName("只被 authHeaderVar 引用的变量不受大括号限制——它不进 URL 模板")
    void 凭据变量允许含大括号() {
        PackageManifest manifest = manifestWith(
                tool("https://api.example.com/x?city={city}", "AUTH"),
                variable("AUTH", true));

        PackageVariableApplier.AppliedPackage applied =
                applier.apply(manifest, Map.of("AUTH", "X-Token: {abc}"));

        assertThat(applied.toolAuthHeaders()).containsEntry("weather_query", "X-Token: {abc}");
    }

    @Test
    @DisplayName("替换后不是 http/https 绝对地址必须拒绝")
    void 替换后URL结构非法被拒绝() {
        PackageManifest manifest = manifestWith(
                tool("${BASE}/weather?city={city}", null),
                variable("BASE", true));

        assertThatThrownBy(() -> applier.apply(manifest, Map.of("BASE", "ftp://evil.example.com")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("http");
    }

    @Test
    @DisplayName("替换后 host 段为空同样拒绝（非必填变量恰好构成整个 host 时会出现）")
    void 替换后host为空被拒绝() {
        PackageManifest manifest = manifestWith(
                tool("https://${HOST}/weather?city={city}", null),
                variable("HOST", false));

        assertThatThrownBy(() -> applier.apply(manifest, Map.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("地址");
    }

    @Test
    void MCP服务的url与凭据同样被处理() {
        PackageManifest manifest = new PackageManifest(PackageManifest.CURRENT_VERSION,
                List.of(), List.of(new PackageManifest.McpServerSpec("ctx7", "Context7", "ctx7",
                        "文档", McpTransport.STREAMABLE_HTTP, "https://${MCP_HOST}/mcp",
                        10000, 4000, "CTX7_TOKEN")),
                List.of(),
                List.of(variable("MCP_HOST", true), variable("CTX7_TOKEN", true)));

        PackageVariableApplier.AppliedPackage applied = applier.apply(manifest, Map.of(
                "MCP_HOST", "mcp.context7.com",
                "CTX7_TOKEN", "Authorization: Bearer ctx-key"));

        assertThat(applied.manifest().mcpServers().get(0).url()).isEqualTo("https://mcp.context7.com/mcp");
        assertThat(applied.serverAuthHeaders()).containsEntry("ctx7", "Authorization: Bearer ctx-key");
    }

    @Test
    @DisplayName("skill 正文与 inputSchema 不做任何替换——变量只允许出现在四个白名单字段")
    void 白名单外字段不做替换() {
        PackageManifest manifest = new PackageManifest(PackageManifest.CURRENT_VERSION,
                List.of(new PackageManifest.ToolSpec("t", "t", "d",
                        "{\"note\":\"${HOST}\"}", "GET", "https://api.example.com/x", 5000, 4000, null)),
                List.of(),
                List.of(new PackageManifest.SkillSpec("s", "技能", "描述",
                        "正文里写了 ${HOST} 但不该被替换", List.of("t"), List.of())),
                List.of(variable("HOST", true)));

        PackageVariableApplier.AppliedPackage applied =
                applier.apply(manifest, Map.of("HOST", "api.example.com"));

        assertThat(applied.manifest().tools().get(0).inputSchema()).contains("${HOST}");
        assertThat(applied.manifest().skills().get(0).content()).contains("${HOST}");
    }

    /**
     * ⚠️ {@code PackageManifestParser} 只校验了带 {@code ${VAR}} 占位符的<b>模板</b>长度——
     * 替换成租户真值之后的长度不受它约束。一个长 JWT 轻松超过 {@code AiTool.authHeader} 的
     * 列宽 1000，不在这里挡住会一路撞到 {@code save()} 抛 {@code DataIntegrityViolationException}。
     */
    @Test
    @DisplayName("替换后的鉴权头超长必须拒绝，不能留给 save 抛 DataIntegrityViolationException")
    void 替换后鉴权头超长被拒绝() {
        PackageManifest manifest = manifestWith(
                tool("https://api.example.com/x?city={city}", "AUTH"),
                variable("AUTH", true));

        String tooLong = "Authorization: Bearer " + "a".repeat(1000);
        assertThatThrownBy(() -> applier.apply(manifest, Map.of("AUTH", tooLong)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("1000");
    }

    @Test
    @DisplayName("替换后的 URL 超长必须拒绝，不能留给 save 抛 DataIntegrityViolationException")
    void 替换后URL超长被拒绝() {
        PackageManifest manifest = manifestWith(
                tool("https://${HOST}/weather?city={city}", null),
                variable("HOST", true));

        String tooLongHost = "a".repeat(1000) + ".example.com";
        assertThatThrownBy(() -> applier.apply(manifest, Map.of("HOST", tooLongHost)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("1000");
    }

    @Test
    void 多出来的取值被忽略而不是报错() {
        PackageManifest manifest = manifestWith(tool("https://api.example.com/x?city={city}", null));

        Map<String, String> values = new HashMap<>();
        values.put("UNUSED", "whatever");

        assertThat(applier.apply(manifest, values).manifest().tools()).hasSize(1);
    }
}
