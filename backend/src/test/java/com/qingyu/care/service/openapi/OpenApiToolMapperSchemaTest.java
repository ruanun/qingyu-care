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
    void 循环ref不栈溢出且降级为object并记warning() {
        // 自引用结构（评论树、组织架构、分类树）在真实 schema 里很常见，且是合法建模，
        // 不是畸形输入。不设防就是无限递归栈溢出。
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
