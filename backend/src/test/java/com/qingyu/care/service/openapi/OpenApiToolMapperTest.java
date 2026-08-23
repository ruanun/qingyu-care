package com.qingyu.care.service.openapi;

import com.qingyu.care.dto.openapi.OpenApiToolCandidate;
import io.swagger.v3.oas.models.OpenAPI;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OpenAPI → 候选工具的映射规则。本特性绝大多数规则都在这里，故测试也最密。
 *
 * <p>{@link OpenApiToolMapper} 是纯函数：给定一份 {@code OpenAPI} 与一个 baseUrl，输出
 * 完全确定，不碰 DB 也不发请求。
 *
 * @author ruan
 */
class OpenApiToolMapperTest {

    private final OpenApiSchemaParser parser = new OpenApiSchemaParser();
    private final OpenApiToolMapper mapper = new OpenApiToolMapper(new tools.jackson.databind.ObjectMapper());

    private OpenApiToolMapper.MapResult map(String json, String baseUrl) {
        OpenAPI api = parser.parse(json);
        return mapper.map(api, baseUrl);
    }

    // ===== operation 筛选 =====

    @Test
    void 只导入GET与POST_其余方法跳过并记全局warning() {
        // RestClientHttpToolExecutor 把非 POST 一律降级成 GET 发出去，硬导 DELETE 会让
        // 一个删除端点被当成 GET 请求，语义错得无声无息，故只能跳过。
        String json = """
                {"openapi":"3.0.0","info":{"title":"t","version":"1"},
                 "paths":{"/items/{id}":{
                   "get":{"operationId":"getItem"},
                   "post":{"operationId":"createItem"},
                   "put":{"operationId":"updateItem"},
                   "delete":{"operationId":"deleteItem"},
                   "patch":{"operationId":"patchItem"}}}}""";

        OpenApiToolMapper.MapResult r = map(json, "https://api.example.com");

        assertThat(r.candidates()).extracting(OpenApiToolCandidate::getName)
                .containsExactlyInAnyOrder("getItem", "createItem");
        assertThat(r.globalWarnings()).anySatisfy(w ->
                assertThat(w).contains("PUT").contains("updateItem"));
        assertThat(r.globalWarnings()).anySatisfy(w -> assertThat(w).contains("deleteItem"));
        assertThat(r.globalWarnings()).anySatisfy(w -> assertThat(w).contains("patchItem"));
    }

    // ===== 名字 =====

    @Test
    void operationId缺失时按方法与路径生成名字() {
        String json = """
                {"openapi":"3.0.0","info":{"title":"t","version":"1"},
                 "paths":{"/orders/{orderId}/items":{"get":{"summary":"列条目"}}}}""";

        OpenApiToolMapper.MapResult r = map(json, "https://api.example.com");

        assertThat(r.candidates()).hasSize(1);
        assertThat(r.candidates().get(0).getName()).isEqualTo("get_orders_orderId_items");
    }

    @Test
    void 名字非法字符被清洗且首字符非字母时加前缀() {
        String json = """
                {"openapi":"3.0.0","info":{"title":"t","version":"1"},
                 "paths":{"/a":{"get":{"operationId":"9lives-of.cat"}}}}""";

        OpenApiToolMapper.MapResult r = map(json, "https://api.example.com");

        assertThat(r.candidates().get(0).getName()).isEqualTo("t_9lives_of_cat");
    }

    @Test
    void 名字截断到64而不是列宽100() {
        // 64 是 OpenAI 兼容端点对函数名的限制。按列宽 100 截断能存库能显示，但挂给 LLM 时
        // 被模型侧拒绝，症状是「工具配好了却从来不被调用」，无异常无日志。
        String longId = "a".repeat(120);
        String json = """
                {"openapi":"3.0.0","info":{"title":"t","version":"1"},
                 "paths":{"/a":{"get":{"operationId":"%s"}}}}""".formatted(longId);

        OpenApiToolMapper.MapResult r = map(json, "https://api.example.com");

        assertThat(r.candidates().get(0).getName()).hasSize(64);
    }

    @Test
    void 批内重名加后缀且仍不超过64() {
        String base = "b".repeat(64);
        String json = """
                {"openapi":"3.0.0","info":{"title":"t","version":"1"},
                 "paths":{"/a":{"get":{"operationId":"%s"}},
                          "/b":{"get":{"operationId":"%s"}}}}""".formatted(base, base);

        OpenApiToolMapper.MapResult r = map(json, "https://api.example.com");

        List<String> names = r.candidates().stream().map(OpenApiToolCandidate::getName).toList();
        assertThat(names).doesNotHaveDuplicates();
        assertThat(names).allSatisfy(n -> assertThat(n.length()).isLessThanOrEqualTo(64));
    }

    // ===== 描述 =====

    @Test
    void 描述优先summary_缺失时用description_再缺用方法与路径() {
        String json = """
                {"openapi":"3.0.0","info":{"title":"t","version":"1"},
                 "paths":{"/a":{"get":{"operationId":"a","summary":"取 A","description":"细节"}},
                          "/b":{"get":{"operationId":"b","description":"只有细节"}},
                          "/c":{"get":{"operationId":"c"}}}}""";

        OpenApiToolMapper.MapResult r = map(json, "https://api.example.com");

        assertThat(byName(r, "a").getDescription()).isEqualTo("取 A");
        assertThat(byName(r, "b").getDescription()).isEqualTo("只有细节");
        assertThat(byName(r, "c").getDescription()).isEqualTo("GET /c");
    }

    @Test
    void 超长描述被截断到500并记候选级warning() {
        String json = """
                {"openapi":"3.0.0","info":{"title":"t","version":"1"},
                 "paths":{"/a":{"get":{"operationId":"a","summary":"%s"}}}}"""
                .formatted("说".repeat(600));

        OpenApiToolMapper.MapResult r = map(json, "https://api.example.com");

        assertThat(r.candidates().get(0).getDescription()).hasSize(500);
        assertThat(r.candidates().get(0).getWarnings()).anySatisfy(w ->
                assertThat(w).contains("描述"));
    }

    // ===== 参数合并（路径级 + 操作级） =====

    @Test
    void 路径级query参数出现在urlTemplate里() {
        // OpenAPI 3.x 允许在路径级声明对该路径下所有 operation 都生效的共享参数，
        // 真实 schema 很常见（Petstore 就有）。漏合并会让路径级 query 参数完全消失。
        String json = """
                {"openapi":"3.0.0","info":{"title":"t","version":"1"},
                 "paths":{"/items":{
                   "parameters":[{"name":"filter","in":"query","schema":{"type":"string"}}],
                   "get":{"operationId":"listItems"}}}}""";

        OpenApiToolMapper.MapResult r = map(json, "https://api.example.com");

        assertThat(r.candidates().get(0).getUrlTemplate())
                .isEqualTo("https://api.example.com/items?filter={filter}");
    }

    @Test
    void 操作级同名参数覆盖路径级且不重复出现() {
        // 同名判据是 name 与 in 两者都相同：路径级与操作级都是 query 的 filter 是同一个
        // 参数，操作级的定义生效，合并结果只留一份。
        String json = """
                {"openapi":"3.0.0","info":{"title":"t","version":"1"},
                 "paths":{"/items":{
                   "parameters":[{"name":"filter","in":"query","schema":{"type":"string"}}],
                   "get":{"operationId":"listItems","parameters":[
                     {"name":"filter","in":"query","schema":{"type":"integer"}}]}}}}""";

        OpenApiToolMapper.MapResult r = map(json, "https://api.example.com");

        assertThat(r.candidates().get(0).getUrlTemplate())
                .isEqualTo("https://api.example.com/items?filter={filter}");
    }

    @Test
    void 同名但作用域不同的路径级与操作级参数都被保留() {
        // 判据必须是 name+in，不能只看 name：路径级 query 的 id 与操作级 header 的 id
        // 是两个不同参数，合并后两者都要生效——分别体现在 urlTemplate（query）与
        // warnings（header 被忽略但记了名字）里。若判据退化成只比 name，其中一个会被
        // 误当同一参数吞掉。
        String json = """
                {"openapi":"3.0.0","info":{"title":"t","version":"1"},
                 "paths":{"/items":{
                   "parameters":[{"name":"id","in":"query","schema":{"type":"string"}}],
                   "get":{"operationId":"getItem","parameters":[
                     {"name":"id","in":"header","schema":{"type":"string"}}]}}}}""";

        OpenApiToolMapper.MapResult r = map(json, "https://api.example.com");

        OpenApiToolCandidate c = r.candidates().get(0);
        assertThat(c.getUrlTemplate()).isEqualTo("https://api.example.com/items?id={id}");
        assertThat(c.getWarnings()).anySatisfy(w -> assertThat(w).contains("id"));
    }

    // ===== header/cookie 参数 warning =====

    @Test
    void header与cookie参数被忽略并记入候选级warning() {
        // warnIgnoredParams 的 javadoc 强调「必须记 warning」（漏了会让工具永远 401 且
        // 静默失效），这条测试守住它真的被触发——过滤条件里 getIn() 判断打错字不会有任何
        // 其它测试转红。
        String json = """
                {"openapi":"3.0.0","info":{"title":"t","version":"1"},
                 "paths":{"/a":{"get":{"operationId":"a","parameters":[
                   {"name":"X-Token","in":"header","schema":{"type":"string"}},
                   {"name":"session","in":"cookie","schema":{"type":"string"}}]}}}}""";

        OpenApiToolMapper.MapResult r = map(json, "https://api.example.com");

        assertThat(r.candidates().get(0).getWarnings()).anySatisfy(w ->
                assertThat(w).contains("X-Token").contains("session"));
    }

    // ===== urlTemplate =====

    @Test
    void path占位符原样拼接_query参数按原始顺序追加() {
        // OpenAPI 的 {orderId} 与本项目 fillTemplate 的 {key} 语法完全一致，无需转换
        String json = """
                {"openapi":"3.0.0","info":{"title":"t","version":"1"},
                 "paths":{"/orders/{orderId}":{"get":{"operationId":"getOrder","parameters":[
                   {"name":"orderId","in":"path","required":true,"schema":{"type":"string"}},
                   {"name":"q","in":"query","required":true,"schema":{"type":"string"}},
                   {"name":"limit","in":"query","schema":{"type":"integer"}}]}}}}""";

        OpenApiToolMapper.MapResult r = map(json, "https://api.example.com");

        assertThat(r.candidates().get(0).getUrlTemplate())
                .isEqualTo("https://api.example.com/orders/{orderId}?q={q}&limit={limit}");
    }

    @Test
    void baseUrl带query时剥掉它并记全局warning() {
        // 拼接顺序是 base + path + ?query，base 自带 query 会拼出 .../api?v=1/search&q={q}
        // 这种 path 跑到 query 后面的坏 URL。OpenAPI 的 servers.url 本就不该带 query，
        // 故剥掉并告知，而不是硬拼一个必然请求失败的地址。
        String json = """
                {"openapi":"3.0.0","info":{"title":"t","version":"1"},
                 "paths":{"/search":{"get":{"operationId":"s","parameters":[
                   {"name":"q","in":"query","schema":{"type":"string"}}]}}}}""";

        OpenApiToolMapper.MapResult r = map(json, "https://api.example.com/api?v=1");

        assertThat(r.candidates().get(0).getUrlTemplate())
                .isEqualTo("https://api.example.com/api/search?q={q}");
        assertThat(r.globalWarnings()).anySatisfy(w -> assertThat(w).contains("查询参数"));
    }

    @Test
    void baseUrl末尾斜杠不会拼出双斜杠() {
        String json = """
                {"openapi":"3.0.0","info":{"title":"t","version":"1"},
                 "paths":{"/orders":{"get":{"operationId":"o"}}}}""";

        OpenApiToolMapper.MapResult r = map(json, "https://api.example.com/");

        assertThat(r.candidates().get(0).getUrlTemplate()).isEqualTo("https://api.example.com/orders");
    }

    @Test
    void baseUrl为空时记全局warning且候选仍产出等待用户填() {
        // servers 缺失或是相对路径（/v1）时会走到这里。⚠️ 绝不能猜成 schema URL 的 origin：
        // schema 托管地址（GitHub raw、文档站）与 API 实际地址不同是常态，猜测会静默生成
        // 一批指向错误主机的工具。
        String json = """
                {"openapi":"3.0.0","info":{"title":"t","version":"1"},
                 "paths":{"/orders":{"get":{"operationId":"o"}}}}""";

        OpenApiToolMapper.MapResult r = map(json, "");

        assertThat(r.globalWarnings()).anySatisfy(w -> assertThat(w).contains("服务器地址"));
        assertThat(r.candidates()).hasSize(1);
    }

    // ===== 上限 =====

    @Test
    void 超过200个候选时截断并置truncated标记() {
        StringBuilder paths = new StringBuilder();
        for (int i = 0; i < 250; i++) {
            paths.append("\"/p%d\":{\"get\":{\"operationId\":\"op%d\"}},".formatted(i, i));
        }
        paths.setLength(paths.length() - 1); // 去掉末尾多余的逗号
        String json = """
                {"openapi":"3.0.0","info":{"title":"t","version":"1"},"paths":{%s}}"""
                .formatted(paths);

        OpenApiToolMapper.MapResult r = map(json, "https://api.example.com");

        assertThat(r.candidates()).hasSize(200);
        assertThat(r.truncated()).isTrue();
        assertThat(r.globalWarnings()).anySatisfy(w -> assertThat(w).contains("200"));
    }

    private OpenApiToolCandidate byName(OpenApiToolMapper.MapResult r, String name) {
        return r.candidates().stream().filter(c -> c.getName().equals(name)).findFirst().orElseThrow();
    }
}
