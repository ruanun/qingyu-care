package com.qingyu.care.it;

import cn.dev33.satoken.secure.BCrypt;
import com.qingyu.care.entity.AiSkill;
import com.qingyu.care.entity.AiTool;
import com.qingyu.care.entity.Application;
import com.qingyu.care.entity.ToolPackage;
import com.qingyu.care.entity.User;
import com.qingyu.care.enums.OwnerScope;
import com.qingyu.care.enums.PackageStatus;
import com.qingyu.care.enums.UserRole;
import com.qingyu.care.repository.AiSkillRepository;
import com.qingyu.care.repository.AiToolRepository;
import com.qingyu.care.repository.ToolPackageRepository;
import com.qingyu.care.repository.UserRepository;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 安装路径上只有真实 HTTP 请求才测得到的三件事：落库归属、{@code scope} 强制、符号引用回填。
 *
 * <p>⚠️ 必须走 MockMvc 而不是直调 service：{@code TenantContext} 由 {@code TenantInterceptor}
 * 写入，Hibernate {@code @Filter} 也只在请求流经它时才启用——而本 IT 的核心正是「归属用的是
 * 上下文，不是 manifest」。
 *
 * @author ruan
 */
class PackageInstallIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ToolPackageRepository packageRepository;
    @Autowired
    private AiToolRepository toolRepository;
    @Autowired
    private AiSkillRepository skillRepository;
    @Autowired
    private UserRepository userRepository;

    private final ObjectMapper om = new ObjectMapper();

    private String login(String username, String password) throws Exception {
        MvcResult r = mockMvc.perform(post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return om.readTree(r.getResponse().getContentAsString()).path("data").path("token").asString();
    }

    private Application seedTenantAdmin(String suffix, String username) {
        Application app = seedApp(suffix, "http://localhost");
        userRepository.saveAndFlush(User.builder()
                .tenant(app.getTenant())
                .username(username).password(BCrypt.hashpw("pwd123456"))
                .role(UserRole.TENANT_ADMIN).enabled(true).build());
        return app;
    }

    /**
     * ⚠️ manifest 里塞了 {@code scope} 与 {@code tenantId}——它们在 record 上压根不存在，
     * 应当被反序列化直接忽略。断言看的是<b>落库归属</b>，不是响应状态码（同
     * {@code CreateTenantInjectionIT} 的口径：越权尝试不报错，行正常建进发起方自己的租户）。
     */
    private ToolPackage seedPublishedPackage(String code) {
        return packageRepository.saveAndFlush(ToolPackage.builder()
                .code(code).name("天气能力包").description("查天气")
                .status(PackageStatus.PUBLISHED)
                .manifest("""
                        {
                          "manifestVersion": 1,
                          "tools": [{
                            "key": "weather_query", "name": "pkg_weather_query",
                            "description": "查询天气",
                            "inputSchema": "{\\"type\\":\\"object\\"}",
                            "httpMethod": "GET",
                            "urlTemplate": "https://${WEATHER_HOST}/v1/weather?city={city}",
                            "timeoutMs": 5000, "maxResponseChars": 4000,
                            "authHeaderVar": "WEATHER_AUTH",
                            "scope": "PLATFORM",
                            "tenantId": 99999
                          }],
                          "mcpServers": [],
                          "skills": [{
                            "key": "weather_skill", "name": "包内天气助手",
                            "description": "回答天气问题",
                            "content": "用户问天气时调用 pkg_weather_query。",
                            "toolKeys": ["weather_query"], "mcpRefs": []
                          }],
                          "variables": [
                            {"name":"WEATHER_HOST","label":"服务地址","required":true,"secret":false,"help":"如 api.qweather.com"},
                            {"name":"WEATHER_AUTH","label":"鉴权头","required":true,"secret":true,"help":"Authorization: Bearer xxx"}
                          ]
                        }
                        """)
                .build());
    }

    private static final String INSTALL_BODY = """
            {"variables":{"WEATHER_HOST":"api.qweather.com",
             "WEATHER_AUTH":"Authorization: Bearer sk-tenant-own"}}""";

    @Test
    void 安装落库的归属与scope都由服务端决定() throws Exception {
        seedPublishedPackage("weather_pack");
        Application app = seedTenantAdmin("PKG_INSTALL", "pkg_install_admin");
        String token = login("pkg_install_admin", "pwd123456");

        mockMvc.perform(post("/v1/packages/weather_pack/install")
                        .header("satoken", token)
                        .header("X-App-Id", app.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(INSTALL_BODY))
                .andExpect(status().isOk());

        List<AiTool> tools = toolRepository.findAll().stream()
                .filter(t -> "pkg_weather_query".equals(t.getName())).toList();
        assertThat(tools).hasSize(1);
        AiTool tool = tools.get(0);

        assertThat(tool.getTenantId())
                .as("必须落进发起方租户，manifest 里那个 tenantId:99999 不能生效")
                .isEqualTo(app.getTenant().getId());
        assertThat(tool.getAppId())
                .as("appId 由 X-App-Id 头决定")
                .isEqualTo(app.getId());
        assertThat(tool.getScope())
                .as("scope 必须服务端强制 TENANT，manifest 里那个 PLATFORM 不能生效——"
                        + "否则租户的工具连同其鉴权头会出现在所有租户的共享列表里")
                .isEqualTo(OwnerScope.TENANT);
        assertThat(tool.getAuthHeader())
                .as("落库的是租户自己填的凭据")
                .isEqualTo("Authorization: Bearer sk-tenant-own");
        assertThat(tool.getUrlTemplate())
                .as("行内变量已替换，LLM 占位符保留")
                .isEqualTo("https://api.qweather.com/v1/weather?city={city}");

        List<AiSkill> skills = skillRepository.findAll().stream()
                .filter(s -> "包内天气助手".equals(s.getName())).toList();
        assertThat(skills).hasSize(1);
        assertThat(skills.get(0).getToolIds())
                .as("skill 绑的必须是新建那一行的 id——漏了这层映射是完全静默的")
                .containsExactly(tool.getId());
        assertThat(skills.get(0).getTenantId()).isEqualTo(app.getTenant().getId());
        assertThat(skills.get(0).getAppId()).isEqualTo(app.getId());
    }

    /**
     * spec §8 第 3 条的自动化版本：两个租户各装同一个包，第二个的归属仍正确。
     *
     * <p>⚠️ 单次安装永远是绿的——上下文泄漏只有连做两次才暴露（同站点爬取第 7 条）。
     * 真机那条手工验证仍然要做（它验的是真实浏览器会话下的连续操作），但这条能把最基本的
     * 「第二次装归属错了」挡在 CI 之前。
     */
    @Test
    void 两个租户先后安装同一个包归属都正确() throws Exception {
        seedPublishedPackage("shared_pack");

        Application appA = seedTenantAdmin("PKG_TENANT_A", "pkg_tenant_a_admin");
        Application appB = seedTenantAdmin("PKG_TENANT_B", "pkg_tenant_b_admin");

        mockMvc.perform(post("/v1/packages/shared_pack/install")
                        .header("satoken", login("pkg_tenant_a_admin", "pwd123456"))
                        .header("X-App-Id", appA.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(INSTALL_BODY))
                .andExpect(status().isOk());

        mockMvc.perform(post("/v1/packages/shared_pack/install")
                        .header("satoken", login("pkg_tenant_b_admin", "pwd123456"))
                        .header("X-App-Id", appB.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(INSTALL_BODY))
                .andExpect(status().isOk());

        List<AiTool> installed = toolRepository.findAll().stream()
                .filter(t -> "pkg_weather_query".equals(t.getName()))
                .filter(t -> appA.getId().equals(t.getAppId()) || appB.getId().equals(t.getAppId()))
                .toList();

        assertThat(installed).hasSize(2);
        assertThat(installed).anySatisfy(t -> {
            assertThat(t.getAppId()).isEqualTo(appA.getId());
            assertThat(t.getTenantId()).isEqualTo(appA.getTenant().getId());
        });
        assertThat(installed).anySatisfy(t -> {
            assertThat(t.getAppId()).isEqualTo(appB.getId());
            assertThat(t.getTenantId()).isEqualTo(appB.getTenant().getId());
        });
    }

    @Test
    void 重名时整体拒绝且一行都不建() throws Exception {
        seedPublishedPackage("conflict_pack");
        Application app = seedTenantAdmin("PKG_CONFLICT", "pkg_conflict_admin");
        String token = login("pkg_conflict_admin", "pwd123456");

        // 先装一次
        mockMvc.perform(post("/v1/packages/conflict_pack/install")
                        .header("satoken", token).header("X-App-Id", app.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(INSTALL_BODY))
                .andExpect(status().isOk());
        long skillCountAfterFirst = skillRepository.findAll().stream()
                .filter(s -> "包内天气助手".equals(s.getName())).count();

        // 再装一次：必须被冲突检测整体拒绝
        MvcResult r = mockMvc.perform(post("/v1/packages/conflict_pack/install")
                        .header("satoken", token).header("X-App-Id", app.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(INSTALL_BODY))
                .andExpect(status().isOk())   // 业务异常是 HTTP 200 + body.code
                .andReturn();

        var body = om.readTree(r.getResponse().getContentAsString());
        assertThat(body.path("code").asInt())
                .as("业务异常走 HTTP 200 + body.code，前端必须判 res.code").isNotEqualTo(200);
        assertThat(body.path("message").asString()).contains("pkg_weather_query");

        long skillCountAfterSecond = skillRepository.findAll().stream()
                .filter(s -> "包内天气助手".equals(s.getName())).count();
        assertThat(skillCountAfterSecond)
                .as("整体拒绝意味着一行都不该建出来")
                .isEqualTo(skillCountAfterFirst);
    }

    @Test
    void 草稿包对租户不可见也装不了() throws Exception {
        packageRepository.saveAndFlush(ToolPackage.builder()
                .code("draft_only").name("草稿包").status(PackageStatus.DRAFT)
                .manifest("{\"manifestVersion\":1,\"tools\":[],\"mcpServers\":[],\"skills\":[],\"variables\":[]}")
                .build());
        Application app = seedTenantAdmin("PKG_DRAFT", "pkg_draft_admin");
        String token = login("pkg_draft_admin", "pwd123456");

        MvcResult r = mockMvc.perform(post("/v1/packages/draft_only/install")
                        .header("satoken", token).header("X-App-Id", app.getId())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"variables\":{}}"))
                .andReturn();

        var body = om.readTree(r.getResponse().getContentAsString());
        assertThat(body.path("code").asInt()).isNotEqualTo(200);
    }
}
