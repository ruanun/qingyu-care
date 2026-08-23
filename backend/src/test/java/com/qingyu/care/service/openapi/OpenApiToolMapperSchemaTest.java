package com.qingyu.care.service.openapi;

import com.qingyu.care.dto.openapi.OpenApiToolCandidate;
import io.swagger.v3.oas.models.OpenAPI;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * inputSchema 的构建：path / query 参数与 requestBody 三个来源**平铺合并到顶层**。
 *
 * <p>平铺是被运行时逼出来的：{@code HttpToolCallback} 在 POST 时把整个 toolInput 原样
 * 当 body 发出去，所以 body 字段必须在参数对象顶层，不能嵌在 {@code body: {...}} 里。
 *
 * @author ruan
 */
class OpenApiToolMapperSchemaTest {

    private final ObjectMapper om = new ObjectMapper();
    private final OpenApiSchemaParser parser = new OpenApiSchemaParser();
    private final OpenApiToolMapper mapper = new OpenApiToolMapper(om);

    private OpenApiToolMapper.MapResult map(String json) {
        OpenAPI api = parser.parse(json);
        return mapper.map(api, "https://api.example.com");
    }

    private JsonNode schemaOf(OpenApiToolCandidate c) {
        return om.readTree(c.getInputSchema());
    }

    @Test
    void path参数全部必填_query按各自required标记() {
        String json = """
                {"openapi":"3.0.0","info":{"title":"t","version":"1"},
                 "paths":{"/orders/{orderId}":{"get":{"operationId":"getOrder","parameters":[
                   {"name":"orderId","in":"path","required":true,"description":"订单号",
                    "schema":{"type":"string"}},
                   {"name":"q","in":"query","required":true,"schema":{"type":"string"}},
                   {"name":"limit","in":"query","schema":{"type":"integer"}}]}}}}""";

        JsonNode schema = schemaOf(map(json).candidates().get(0));

        assertThat(schema.path("type").asString()).isEqualTo("object");
        assertThat(schema.path("properties").propertyNames()).contains("orderId", "q", "limit");
        assertThat(schema.path("properties").path("orderId").path("description").asString())
                .isEqualTo("订单号");
        assertThat(schema.path("properties").path("limit").path("type").asString())
                .isEqualTo("integer");
        assertThat(schema.path("required").toString()).contains("orderId").contains("q")
                .doesNotContain("limit");
    }

    @Test
    void POST的requestBody字段被平铺合并到顶层() {
        // 必须平铺：HttpToolCallback 把整个 toolInput 当 body 发出去，嵌套的 body 字段
        // 在 LLM 那边永远填不对位置
        String json = """
                {"openapi":"3.0.0","info":{"title":"t","version":"1"},
                 "paths":{"/orders":{"post":{"operationId":"createOrder",
                   "requestBody":{"required":true,"content":{"application/json":{"schema":{
                     "type":"object",
                     "properties":{"sku":{"type":"string"},"qty":{"type":"integer"}},
                     "required":["sku"]}}}}}}}}""";

        JsonNode schema = schemaOf(map(json).candidates().get(0));

        assertThat(schema.path("properties").propertyNames()).contains("sku", "qty");
        assertThat(schema.path("required").toString()).contains("sku");
    }

    @Test
    void requestBody不是对象结构时整个operation被跳过() {
        // POST /items 收一个裸数组，在当前工具模型里根本表达不了——LLM 交出来的永远是 object
        String json = """
                {"openapi":"3.0.0","info":{"title":"t","version":"1"},
                 "paths":{"/items":{"post":{"operationId":"bulkCreate",
                   "requestBody":{"content":{"application/json":{"schema":{
                     "type":"array","items":{"type":"string"}}}}}}}}}""";

        OpenApiToolMapper.MapResult r = map(json);

        assertThat(r.candidates()).isEmpty();
        assertThat(r.globalWarnings()).anySatisfy(w -> assertThat(w).contains("/items"));
    }

    @Test
    void 非json请求体的operation被跳过() {
        String json = """
                {"openapi":"3.0.0","info":{"title":"t","version":"1"},
                 "paths":{"/upload":{"post":{"operationId":"upload",
                   "requestBody":{"content":{"multipart/form-data":{"schema":{
                     "type":"object","properties":{"file":{"type":"string"}}}}}}}}}}""";

        OpenApiToolMapper.MapResult r = map(json);

        assertThat(r.candidates()).isEmpty();
        assertThat(r.globalWarnings()).anySatisfy(w -> assertThat(w).contains("/upload"));
    }

    @Test
    void 本地ref被解析成真实字段() {
        String json = """
                {"openapi":"3.0.0","info":{"title":"t","version":"1"},
                 "paths":{"/orders":{"post":{"operationId":"createOrder",
                   "requestBody":{"content":{"application/json":{"schema":
                     {"$ref":"#/components/schemas/OrderReq"}}}}}}},
                 "components":{"schemas":{"OrderReq":{"type":"object",
                   "properties":{"sku":{"type":"string","description":"商品编码"}},
                   "required":["sku"]}}}}""";

        JsonNode schema = schemaOf(map(json).candidates().get(0));

        assertThat(schema.path("properties").path("sku").path("description").asString())
                .isEqualTo("商品编码");
        assertThat(schema.path("required").toString()).contains("sku");
    }

    @Test
    void ref形式的path参数被解析且写入inputSchema并标记required() {
        // OpenAPI 允许参数写成 {"$ref": "#/components/parameters/X"} 引用共享定义——这是
        // GitHub REST、Kubernetes 官方 schema 几乎全用的标准写法，不是边角情况。swagger-core
        // 把它反序列化成 name/in 均为 null 的 Parameter，引用目标在 Components.getParameters()
        // 里。不解引用的后果：urlTemplate 里的 {orderId} 占位符仍在（直接来自 path 字符串），
        // 但 inputSchema 没有对应属性，LLM 不知道要填，运行时必然撞上 UrlTemplateFiller 的
        // 「缺少必填参数」——而工具调用是 fail-soft 的，全程无异常无日志。
        String json = """
                {"openapi":"3.0.0","info":{"title":"t","version":"1"},
                 "paths":{"/orders/{orderId}":{"get":{"operationId":"getOrder","parameters":[
                   {"$ref":"#/components/parameters/OrderId"}]}}},
                 "components":{"parameters":{"OrderId":{"name":"orderId","in":"path",
                   "required":true,"schema":{"type":"string"}}}}}""";

        OpenApiToolMapper.MapResult r = map(json);
        JsonNode schema = schemaOf(r.candidates().get(0));

        assertThat(schema.path("properties").propertyNames()).contains("orderId");
        assertThat(schema.path("required").toString()).contains("orderId");
    }

    @Test
    void ref形式的header参数被解析并记入候选级warning() {
        // header 参数在 AiTool 模型里无法表达，必须记 warning——CLAUDE.md 点名要防的
        // 「因为忽略了 header 认证参数所以永远返回 401」的失效模式，若 $ref 形式的 header
        // 参数不解引用，连这条 warning 都不会触发（因为 in 是 null，既不匹配 "header" 也
        // 不匹配其它任何分支），比明文 header 参数的失效还要更隐蔽。
        String json = """
                {"openapi":"3.0.0","info":{"title":"t","version":"1"},
                 "paths":{"/a":{"get":{"operationId":"a","parameters":[
                   {"$ref":"#/components/parameters/ApiKeyHeader"}]}}},
                 "components":{"parameters":{"ApiKeyHeader":{"name":"X-Api-Key","in":"header",
                   "schema":{"type":"string"}}}}}""";

        OpenApiToolMapper.MapResult r = map(json);

        assertThat(r.candidates().get(0).getWarnings()).anySatisfy(w ->
                assertThat(w).contains("X-Api-Key"));
    }

    @Test
    void ref形式的requestBody被解析成真实字段() {
        // requestBody 同参数一样可以写成 $ref。解不开时旧实现会把 mergeRequestBody 判成
        // 「非对象结构」而跳过整个 operation，且全局 warning 说的是「请求体不是对象结构」
        // ——这句话是错的，会把用户引向错误的排查方向（去改 schema 里的 body 定义，而不是
        // 去查引用指向哪）。这里验证的是能解开的情形：字段被正确合并进 inputSchema。
        String json = """
                {"openapi":"3.0.0","info":{"title":"t","version":"1"},
                 "paths":{"/orders":{"post":{"operationId":"createOrder",
                   "requestBody":{"$ref":"#/components/requestBodies/OrderBody"}}}},
                 "components":{
                   "requestBodies":{"OrderBody":{"content":{"application/json":{"schema":
                     {"type":"object","properties":{"sku":{"type":"string"}},
                      "required":["sku"]}}}}},
                   "schemas":{}}}""";

        OpenApiToolMapper.MapResult r = map(json);
        JsonNode schema = schemaOf(r.candidates().get(0));

        assertThat(schema.path("properties").propertyNames()).contains("sku");
        assertThat(schema.path("required").toString()).contains("sku");
    }

    @Test
    void requestBody的ref无法解析时全局warning措辞准确而非误导() {
        String json = """
                {"openapi":"3.0.0","info":{"title":"t","version":"1"},
                 "paths":{"/orders":{"post":{"operationId":"createOrder",
                   "requestBody":{"$ref":"#/components/requestBodies/Missing"}}}}}""";

        OpenApiToolMapper.MapResult r = map(json);

        assertThat(r.candidates()).isEmpty();
        assertThat(r.globalWarnings()).anySatisfy(w ->
                assertThat(w).contains("引用").doesNotContain("不是对象结构"));
    }

    @Test
    void 循环ref不栈溢出且降级为object并记warning() {
        // 自引用结构（评论树、组织架构、分类树）在真实 schema 里很常见，且是合法建模，
        // 不是畸形输入。不设防就是无限递归栈溢出。
        //
        // ⚠️ 名不副实，订正于终审：本条实际压中的是 MAX_REF_DEPTH 深度上限分支，不是
        // refStack 的循环检测分支。derefSchema 的 try/finally 只在单次解引用调用内把 ref
        // 钉在 refStack 里，返回后即在 finally 里移除；而 schemaToNode 对 properties 的递归
        // 遍历发生在 derefSchema 调用**之外**（拿到已解开的 resolved 之后），此时 refStack
        // 已经不含刚才那个 ref。所以像 Node.child 这种「单个 schema 直接引用自己」的写法，
        // 每次递归看到的 refStack 都是空的，真正拦住它的是 depth 单调递增撞上上限（5 层），
        // 不是 refStack 命中重复。我独立复核过终止性：只要 depth 沿递归严格递增，5 层必收敛，
        // 无栈溢出风险，行为仍然正确——只是这条测试的名字与它实际覆盖的分支对不上。
        // refStack 的循环检测分支需要 A→B→A 这种**跨多个 components/schemas 组件互引**
        // （A 引用 B、B 又引用回 A，而非同一 schema 直接自引用）才会真正触达，当前测试
        // 套件里没有一条覆盖它——将来若有人把循环检测那几行删掉，本条测试不会转红。
        String json = """
                {"openapi":"3.0.0","info":{"title":"t","version":"1"},
                 "paths":{"/tree":{"post":{"operationId":"createNode",
                   "requestBody":{"content":{"application/json":{"schema":
                     {"$ref":"#/components/schemas/Node"}}}}}}},
                 "components":{"schemas":{"Node":{"type":"object","properties":{
                   "name":{"type":"string"},
                   "child":{"$ref":"#/components/schemas/Node"}}}}}}""";

        OpenApiToolMapper.MapResult r = map(json);

        assertThat(r.candidates()).hasSize(1);
        OpenApiToolCandidate c = r.candidates().get(0);
        assertThat(schemaOf(c).path("properties").propertyNames()).contains("name", "child");
        assertThat(c.getWarnings()).anySatisfy(w -> assertThat(w).contains("引用"));
    }

    @Test
    void 指向不存在的ref降级为object并记warning() {
        String json = """
                {"openapi":"3.0.0","info":{"title":"t","version":"1"},
                 "paths":{"/a":{"post":{"operationId":"a",
                   "requestBody":{"content":{"application/json":{"schema":{"type":"object",
                     "properties":{"x":{"$ref":"#/components/schemas/Missing"}}}}}}}}}}""";

        OpenApiToolMapper.MapResult r = map(json);

        assertThat(r.candidates()).hasSize(1);
        assertThat(r.candidates().get(0).getWarnings()).anySatisfy(w ->
                assertThat(w).contains("引用"));
    }

    @Test
    void body字段与query参数同名时保留参数侧定义并记warning() {
        // URL 必须能填出来，所以参数侧优先
        String json = """
                {"openapi":"3.0.0","info":{"title":"t","version":"1"},
                 "paths":{"/a":{"post":{"operationId":"a",
                   "parameters":[{"name":"id","in":"query","schema":{"type":"string"}}],
                   "requestBody":{"content":{"application/json":{"schema":{"type":"object",
                     "properties":{"id":{"type":"integer"}}}}}}}}}}""";

        OpenApiToolMapper.MapResult r = map(json);
        OpenApiToolCandidate c = r.candidates().get(0);

        assertThat(schemaOf(c).path("properties").path("id").path("type").asString())
                .isEqualTo("string");
        assertThat(c.getWarnings()).anySatisfy(w -> assertThat(w).contains("同名"));
    }

    @Test
    void GET即使声明了requestBody也不合并它() {
        // 执行器只在 POST 时发 body，GET 合并进来只会让 LLM 填一堆到不了服务端的参数
        String json = """
                {"openapi":"3.0.0","info":{"title":"t","version":"1"},
                 "paths":{"/a":{"get":{"operationId":"a",
                   "requestBody":{"content":{"application/json":{"schema":{"type":"object",
                     "properties":{"ghost":{"type":"string"}}}}}}}}}}""";

        JsonNode schema = schemaOf(map(json).candidates().get(0));

        assertThat(schema.path("properties").propertyNames()).doesNotContain("ghost");
    }

    @Test
    void 无参数时产出空properties的合法object() {
        String json = """
                {"openapi":"3.0.0","info":{"title":"t","version":"1"},
                 "paths":{"/ping":{"get":{"operationId":"ping"}}}}""";

        JsonNode schema = schemaOf(map(json).candidates().get(0));

        assertThat(schema.path("type").asString()).isEqualTo("object");
        assertThat(schema.path("properties").isObject()).isTrue();
        assertThat(schema.path("properties").size()).isZero();
    }

    @Test
    void 两个字段引用同一个schema时都要被解析出来() {
        // ⚠️ 这条守的是 derefSchema 的 finally { refStack.remove(ref) }：把它删掉后，
        // 第二个引用同一 schema 的字段会被误判成循环引用、静默降级成空 object。
        // 两个字段共用一个 Address 之类的定义在真实 schema 里极其常见。
        //
        // ⚠️ 两个 $ref 字段必须嵌套在同一个父对象里（而非直接是 requestBody 的顶层属性）
        // 才能真正压中这条 finally：mergeRequestBody 给 requestBody 的每个顶层字段各开一个
        // 独立的 HashSet（buildInputSchema 对 path/query 参数也是同样的隔离设计），彼此本来
        // 就不共享 refStack 实例，无论 finally 是否清理都测不出区别。真正共享同一个 refStack
        // 实例的路径是 schemaToNode 处理某个 object 自身 properties 时的递归 forEach——它把
        // refStack 原样往下传，同一父对象下的兄弟字段因此共用一个 Set。本条测试最初直接照抄
        // 任务书把 from/to 放在 requestBody 顶层，变异验证（注释掉 finally 里的 remove）后
        // 仍然全绿，说明那个写法测不出这条 finally 的缺失，遂改成嵌套写法。
        String json = """
                {"openapi":"3.0.0","info":{"title":"t","version":"1"},
                 "paths":{"/a":{"post":{"operationId":"a",
                   "requestBody":{"content":{"application/json":{"schema":{"type":"object",
                     "properties":{"shipment":{"type":"object","properties":{
                       "from":{"$ref":"#/components/schemas/Addr"},
                       "to":{"$ref":"#/components/schemas/Addr"}}}}}}}}}}},
                 "components":{"schemas":{"Addr":{"type":"object",
                   "properties":{"city":{"type":"string"}}}}}}""";

        JsonNode shipment = schemaOf(map(json).candidates().get(0)).path("properties").path("shipment");

        assertThat(shipment.path("properties").path("from").path("properties").propertyNames())
                .contains("city");
        assertThat(shipment.path("properties").path("to").path("properties").propertyNames())
                .contains("city");
    }
}
