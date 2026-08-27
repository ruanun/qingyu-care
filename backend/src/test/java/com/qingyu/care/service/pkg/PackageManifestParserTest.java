package com.qingyu.care.service.pkg;

import com.qingyu.care.dto.pkg.PackageManifest;
import com.qingyu.care.enums.McpTransport;
import com.qingyu.care.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link PackageManifestParser} 的规则测试。纯函数、无 Mockito。
 *
 * <p>⚠️ 每条「拒绝」都必须断言<b>错误消息内容</b>而不只是「抛了 BusinessException」：这些
 * 消息是平台管理员编辑 manifest 时唯一的反馈，说不清楚就等于没有校验（同 OpenAPI 导入那条
 * 「302 必须给出可读错误」的取舍）。
 *
 * @author ruan
 */
class PackageManifestParserTest {

    private final PackageManifestParser parser = new PackageManifestParser(new ObjectMapper());

    /** 一份最小可用 manifest：一个工具 + 一个 skill 引用它 + 一个凭据变量。 */
    private static final String VALID = """
            {
              "manifestVersion": 1,
              "tools": [{
                "key": "weather_query",
                "name": "weather_query",
                "description": "查询指定城市的天气",
                "inputSchema": "{\\"type\\":\\"object\\",\\"properties\\":{\\"city\\":{\\"type\\":\\"string\\"}}}",
                "httpMethod": "GET",
                "urlTemplate": "https://api.example.com/weather?city={city}",
                "timeoutMs": 5000,
                "maxResponseChars": 4000,
                "authHeaderVar": "WEATHER_API_KEY"
              }],
              "mcpServers": [],
              "skills": [{
                "key": "weather_skill",
                "name": "天气助手",
                "description": "回答天气问题",
                "content": "# 天气助手\\n用户问天气时调用 weather_query。",
                "toolKeys": ["weather_query"],
                "mcpRefs": []
              }],
              "variables": [{
                "name": "WEATHER_API_KEY",
                "label": "和风天气 API Key",
                "required": true,
                "secret": true,
                "help": "在控制台获取"
              }]
            }
            """;

    @Test
    void 合法manifest能解析出全部结构() {
        PackageManifest manifest = parser.parse(VALID);

        assertThat(manifest.manifestVersion()).isEqualTo(1);
        assertThat(manifest.tools()).hasSize(1);
        PackageManifest.ToolSpec tool = manifest.tools().get(0);
        assertThat(tool.key()).isEqualTo("weather_query");
        assertThat(tool.httpMethod()).isEqualTo("GET");
        assertThat(tool.urlTemplate()).isEqualTo("https://api.example.com/weather?city={city}");
        assertThat(tool.authHeaderVar()).isEqualTo("WEATHER_API_KEY");
        assertThat(manifest.skills().get(0).toolKeys()).containsExactly("weather_query");
        assertThat(manifest.variables().get(0).secret()).isTrue();
    }

    @Test
    void 序列化后能原样解析回来() {
        PackageManifest original = parser.parse(VALID);
        PackageManifest roundTripped = parser.parse(parser.toJson(original));
        assertThat(roundTripped).isEqualTo(original);
    }

    @Test
    @DisplayName("未知 manifestVersion 直接拒绝，不按当前版本猜着解析")
    void 未知版本被拒绝() {
        assertThatThrownBy(() -> parser.parse(VALID.replace("\"manifestVersion\": 1", "\"manifestVersion\": 2")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("manifest 版本");
    }

    @Test
    void 非法JSON给出可读错误而不是抛解析器原始异常() {
        assertThatThrownBy(() -> parser.parse("{ 这不是 JSON"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("manifest 不是合法的 JSON");
    }

    @Test
    void 空包被拒绝() {
        assertThatThrownBy(() -> parser.parse("""
                {"manifestVersion":1,"tools":[],"mcpServers":[],"skills":[],"variables":[]}"""))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("至少要包含一个");
    }

    @Test
    @DisplayName("skill 引用了不存在的工具 key —— 悬空引用必须拒绝，装出来是个绑不上工具的空壳")
    void skill引用不存在的工具key被拒绝() {
        assertThatThrownBy(() -> parser.parse(VALID.replace("\"toolKeys\": [\"weather_query\"]",
                "\"toolKeys\": [\"not_exists\"]")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not_exists");
    }

    @Test
    void mcpRef引用不存在的serverKey被拒绝() {
        String json = VALID.replace("\"mcpRefs\": []",
                "\"mcpRefs\": [{\"serverKey\":\"ghost\",\"toolNames\":[\"search\"]}]");
        assertThatThrownBy(() -> parser.parse(json))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ghost");
    }

    @Test
    void authHeaderVar引用不存在的变量被拒绝() {
        assertThatThrownBy(() -> parser.parse(VALID.replace("\"authHeaderVar\": \"WEATHER_API_KEY\"",
                "\"authHeaderVar\": \"NOT_DECLARED\"")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("NOT_DECLARED");
    }

    @Test
    @DisplayName("urlTemplate 里的 ${VAR} 也必须在 variables 里声明过")
    void 行内变量引用未声明被拒绝() {
        String json = VALID.replace("https://api.example.com/weather?city={city}",
                "https://${UNDECLARED_HOST}/weather?city={city}");
        assertThatThrownBy(() -> parser.parse(json))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("UNDECLARED_HOST");
    }

    @Test
    void 重复的工具key被拒绝() {
        String twoTools = VALID.replace("\"mcpServers\": []", "\"mcpServers\": []")
                .replace("}],\n  \"mcpServers\"", """
                        }, {
                          "key": "weather_query",
                          "name": "weather_query_2",
                          "description": "重复 key",
                          "httpMethod": "GET",
                          "urlTemplate": "https://api.example.com/x",
                          "timeoutMs": 5000,
                          "maxResponseChars": 4000
                        }],
                          "mcpServers\"""");
        assertThatThrownBy(() -> parser.parse(twoTools))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("重复");
    }

    @Test
    @DisplayName("httpMethod 只收 GET/POST —— 非 POST 在运行时一律降级成 GET，导进来只会语义错得无声无息")
    void 非GET_POST被拒绝() {
        assertThatThrownBy(() -> parser.parse(VALID.replace("\"httpMethod\": \"GET\"",
                "\"httpMethod\": \"DELETE\"")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("GET");
    }

    @Test
    @DisplayName("超出实体列宽的值必须在这里被拒绝，不能留给 save 抛 DataIntegrityViolationException")
    void 超长字段被拒绝() {
        String longName = "n".repeat(101);   // AiTool.name 列宽 100
        assertThatThrownBy(() -> parser.parse(VALID.replace("\"name\": \"weather_query\"",
                "\"name\": \"" + longName + "\"")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("100");
    }

    @Test
    @DisplayName("MCP 服务描述超列宽必须在这里被拒绝——它是本类注释自称覆盖、却最容易漏掉的一个")
    void MCP服务描述超长被拒绝() {
        String longDescription = "d".repeat(501);   // McpServer.description 列宽 500
        String json = """
                {
                  "manifestVersion": 1, "tools": [],
                  "mcpServers": [{
                    "key": "ctx7", "name": "Context7", "code": "ctx7",
                    "description": "%s",
                    "transport": "STREAMABLE_HTTP", "url": "https://mcp.example.com/mcp",
                    "timeoutMs": 10000, "maxResponseChars": 4000
                  }],
                  "skills": [], "variables": []
                }
                """.formatted(longDescription);
        assertThatThrownBy(() -> parser.parse(json))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("500");
    }

    @Test
    @DisplayName("timeoutMs 缺失必须拒绝——安装时是直接 setter 搬运，null 会一路撞到 NOT NULL 列")
    void 缺少timeoutMs被拒绝() {
        String json = """
                {"manifestVersion":1,"tools":[{
                  "key":"t","name":"t","description":"d","httpMethod":"GET",
                  "urlTemplate":"https://example.com/x","maxResponseChars":4000}],
                 "mcpServers":[],"skills":[],"variables":[]}
                """;
        assertThatThrownBy(() -> parser.parse(json))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("timeoutMs");
    }

    @Test
    void 非正数的maxResponseChars被拒绝() {
        String json = """
                {"manifestVersion":1,"tools":[{
                  "key":"t","name":"t","description":"d","httpMethod":"GET",
                  "urlTemplate":"https://example.com/x","timeoutMs":5000,"maxResponseChars":0}],
                 "mcpServers":[],"skills":[],"variables":[]}
                """;
        assertThatThrownBy(() -> parser.parse(json))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("正整数");
    }

    @Test
    void 变量名格式非法被拒绝() {
        String json = VALID.replace("\"name\": \"WEATHER_API_KEY\"", "\"name\": \"weather-key\"")
                .replace("\"authHeaderVar\": \"WEATHER_API_KEY\"", "\"authHeaderVar\": \"weather-key\"");
        assertThatThrownBy(() -> parser.parse(json))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("变量名");
    }

    @Test
    void mcpServer的code必须是合法标识符() {
        String json = """
                {
                  "manifestVersion": 1,
                  "tools": [],
                  "mcpServers": [{
                    "key": "ctx7", "name": "Context7", "code": "Ctx-7",
                    "transport": "STREAMABLE_HTTP", "url": "https://mcp.example.com/mcp",
                    "timeoutMs": 10000, "maxResponseChars": 4000
                  }],
                  "skills": [], "variables": []
                }
                """;
        assertThatThrownBy(() -> parser.parse(json))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("标识符");
    }

    @Test
    void 合法的MCP包能解析() {
        String json = """
                {
                  "manifestVersion": 1,
                  "tools": [],
                  "mcpServers": [{
                    "key": "ctx7", "name": "Context7", "code": "ctx7",
                    "description": "文档检索",
                    "transport": "STREAMABLE_HTTP", "url": "https://mcp.example.com/mcp",
                    "timeoutMs": 10000, "maxResponseChars": 4000,
                    "authHeaderVar": "CTX7_TOKEN"
                  }],
                  "skills": [{
                    "key": "doc_skill", "name": "文档助手", "content": "查文档时用 ctx7",
                    "toolKeys": [],
                    "mcpRefs": [{"serverKey": "ctx7", "toolNames": ["resolve-library-id"]}]
                  }],
                  "variables": [{
                    "name": "CTX7_TOKEN", "label": "Context7 令牌",
                    "required": false, "secret": true, "help": "可留空"
                  }]
                }
                """;
        PackageManifest manifest = parser.parse(json);
        assertThat(manifest.mcpServers()).hasSize(1);
        assertThat(manifest.mcpServers().get(0).transport()).isEqualTo(McpTransport.STREAMABLE_HTTP);
        assertThat(manifest.skills().get(0).mcpRefs())
                .extracting(PackageManifest.McpRef::serverKey).containsExactly("ctx7");
    }

    @Test
    @DisplayName("缺省的数组字段按空列表处理，不是 NPE")
    void 缺省数组不抛NPE() {
        PackageManifest manifest = parser.parse("""
                {"manifestVersion":1,"tools":[{
                  "key":"t","name":"t","description":"d","httpMethod":"GET",
                  "urlTemplate":"https://example.com/x","timeoutMs":5000,"maxResponseChars":4000}]}
                """);
        assertThat(manifest.skills()).isEmpty();
        assertThat(manifest.mcpServers()).isEmpty();
        assertThat(manifest.variables()).isEmpty();
    }

    @Test
    void referencedVariables抽得出行内引用() {
        assertThat(PackageManifestParser.referencedVariables(
                "https://${HOST}/v1/${PATH_SEG}?city={city}"))
                .containsExactlyInAnyOrder("HOST", "PATH_SEG");
        assertThat(PackageManifestParser.referencedVariables("https://a.example.com/{city}")).isEmpty();
        assertThat(PackageManifestParser.referencedVariables(null)).isEmpty();
    }

    @Test
    @DisplayName("SSE 传输在本仓库连不上，包里出现它必须在解析期就拒绝")
    void SSE传输被拒绝() {
        String json = """
                {
                  "manifestVersion": 1, "tools": [],
                  "mcpServers": [{
                    "key": "s", "name": "S", "code": "s",
                    "transport": "SSE", "url": "https://mcp.example.com/sse",
                    "timeoutMs": 10000, "maxResponseChars": 4000
                  }],
                  "skills": [], "variables": []
                }
                """;
        assertThatThrownBy(() -> parser.parse(json))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("SSE");
    }
}
