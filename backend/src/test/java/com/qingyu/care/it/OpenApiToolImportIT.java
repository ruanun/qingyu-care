package com.qingyu.care.it;

import cn.dev33.satoken.secure.BCrypt;
import com.qingyu.care.entity.Application;
import com.qingyu.care.entity.User;
import com.qingyu.care.enums.UserRole;
import com.qingyu.care.repository.AiToolRepository;
import com.qingyu.care.repository.UserRepository;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OpenAPI 导入的落库与隔离。
 *
 * <p>⚠️ 必须走真实 MockMvc 请求：多租户过滤器与 {@code TenantEntityListener} 的回填只在
 * 请求流经 {@code TenantInterceptor} 时生效，直调 service 断言会静默退化成永真。
 *
 * @author ruan
 */
class OpenApiToolImportIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AiToolRepository toolRepository;

    private String login(String username, String password) throws Exception {
        MvcResult r = mockMvc.perform(post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return om.readTree(r.getResponse().getContentAsString()).path("data").path("token").asString();
    }

    private String createAdminAndLogin(Application app, String username) throws Exception {
        userRepository.saveAndFlush(User.builder()
                .tenant(app.getTenant())
                .username(username).password(BCrypt.hashpw("pwd123456"))
                .role(UserRole.TENANT_ADMIN).enabled(true).build());
        return login(username, "pwd123456");
    }

    /**
     * 把资源文件里的 schema 包成 parse 请求体。用 Jackson 生成而不是手工拼接转义——schema
     * 要经「Java 字符串 → JSON 请求体」两层转义，手拼必错且没人看得懂。
     */
    private String parseBody() throws Exception {
        String schema = new String(getClass().getResourceAsStream("/openapi/sample.json")
                .readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        return om.writeValueAsString(java.util.Map.of("schemaText", schema));
    }

    @Test
    void parse不落任何行() throws Exception {
        Application app = seedApp("OAS_PARSE", "http://localhost");
        String token = createAdminAndLogin(app, "oas_parse_admin");
        long before = toolRepository.count();

        mockMvc.perform(post("/v1/ai-tools/openapi/parse")
                        .header("satoken", token).header("X-App-Id", app.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(parseBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.candidates[0].name").value("getOrder"))
                .andExpect(jsonPath("$.data.candidates[0].urlTemplate")
                        .value("https://api.example.com/orders/{orderId}"));

        // 用户会为调整配置反复解析同一份 schema，落库会留下大量垃圾
        assertThat(toolRepository.count()).as("parse 必须是只读的").isEqualTo(before);
    }

    @Test
    void import落库且租户与应用字段正确() throws Exception {
        Application app = seedApp("OAS_IMPORT", "http://localhost");
        String token = createAdminAndLogin(app, "oas_import_admin");

        String body = """
                {"candidates":[{"name":"getOrder","description":"查订单","httpMethod":"GET",
                  "urlTemplate":"https://api.example.com/orders/{orderId}",
                  "inputSchema":"{}","warnings":[],"nameConflict":false}],
                 "authHeader":"Authorization: Bearer x","timeoutMs":8000,
                 "maxResponseChars":2000,"enabled":true}""";

        MvcResult r = mockMvc.perform(post("/v1/ai-tools/openapi/import")
                        .header("satoken", token).header("X-App-Id", app.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        long id = om.readTree(r.getResponse().getContentAsString())
                .path("data").get(0).path("id").asLong();
        var saved = toolRepository.findById(id).orElseThrow();
        assertThat(saved.getTenantId()).as("@Async 之外的写入靠 TenantEntityListener 回填")
                .isEqualTo(app.getTenant().getId());
        assertThat(saved.getAppId()).isEqualTo(app.getId());
        assertThat(saved.getScope().name()).isEqualTo("TENANT");
        assertThat(saved.getAuthHeader()).isEqualTo("Authorization: Bearer x");
    }

    @Test
    void 同名工具在不同租户之间互不冲突() throws Exception {
        // 冲突检测是 filter-bound 的，只该看本租户；跨租户同名完全正常，拦下来是缺陷
        Application appA = seedApp("OAS_ISO_A", "http://localhost");
        Application appB = seedApp("OAS_ISO_B", "http://localhost");
        String tokenA = createAdminAndLogin(appA, "oas_iso_a_admin");
        String tokenB = createAdminAndLogin(appB, "oas_iso_b_admin");

        String body = """
                {"candidates":[{"name":"getOrder","description":"查订单","httpMethod":"GET",
                  "urlTemplate":"https://api.example.com/orders","inputSchema":"{}",
                  "warnings":[],"nameConflict":false}],"enabled":true}""";

        mockMvc.perform(post("/v1/ai-tools/openapi/import")
                        .header("satoken", tokenA).header("X-App-Id", appA.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(post("/v1/ai-tools/openapi/import")
                        .header("satoken", tokenB).header("X-App-Id", appB.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void 本租户重名时整批拒绝且一行都不落() throws Exception {
        Application app = seedApp("OAS_DUP", "http://localhost");
        String token = createAdminAndLogin(app, "oas_dup_admin");

        String body = """
                {"candidates":[
                  {"name":"dupTool","description":"a","httpMethod":"GET",
                   "urlTemplate":"https://api.example.com/a","inputSchema":"{}",
                   "warnings":[],"nameConflict":false},
                  {"name":"newTool","description":"b","httpMethod":"GET",
                   "urlTemplate":"https://api.example.com/b","inputSchema":"{}",
                   "warnings":[],"nameConflict":false}],"enabled":true}""";

        mockMvc.perform(post("/v1/ai-tools/openapi/import")
                        .header("satoken", token).header("X-App-Id", app.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(200));

        long after = toolRepository.count();
        // 再导一次：dupTool 冲突 → 整批回滚，newTool2 也不该落库
        String second = body.replace("\"newTool\"", "\"newTool2\"");
        mockMvc.perform(post("/v1/ai-tools/openapi/import")
                        .header("satoken", token).header("X-App-Id", app.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(second))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        assertThat(toolRepository.count()).as("整批一个事务，任一冲突则一行都不落")
                .isEqualTo(after);
    }
}
