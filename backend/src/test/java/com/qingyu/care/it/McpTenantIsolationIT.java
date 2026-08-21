package com.qingyu.care.it;

import cn.dev33.satoken.secure.BCrypt;
import com.qingyu.care.entity.Application;
import com.qingyu.care.entity.McpServer;
import com.qingyu.care.entity.McpTool;
import com.qingyu.care.entity.User;
import com.qingyu.care.enums.McpTransport;
import com.qingyu.care.enums.UserRole;
import com.qingyu.care.repository.McpServerRepository;
import com.qingyu.care.repository.McpToolRepository;
import com.qingyu.care.repository.UserRepository;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MCP 的跨租户隔离。
 *
 * <p>⚠️ 必须走真实 MockMvc 请求：多租户过滤器只在请求流经 {@code TenantInterceptor} 时生效，
 * 直调 service 断言会静默退化成永真——这是本仓库反复记载的教训（见 {@code SharedToolIT}、
 * {@code CreateTenantInjectionIT}）。
 *
 * <p>每条隔离断言都配一次<b>正向对照</b>：先证明发起方自己能拿到/改动自己的数据（种子与
 * 请求路径本身是对的），再证明另一租户拿不到/改不动——没有正向对照的隔离断言等于没测。
 *
 * @author ruan
 */
class McpTenantIsolationIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private McpServerRepository serverRepository;
    @Autowired
    private McpToolRepository toolRepository;
    @Autowired
    private UserRepository userRepository;

    private String login(String username, String password) throws Exception {
        MvcResult r = mockMvc.perform(post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return om.readTree(r.getResponse().getContentAsString()).path("data").path("token").asString();
    }

    /** 建一个租户管理员账号并登录，返回其 token。 */
    private String createAdminAndLogin(Application app, String username) throws Exception {
        userRepository.saveAndFlush(User.builder()
                .tenant(app.getTenant())
                .username(username).password(BCrypt.hashpw("pwd123456"))
                .role(UserRole.TENANT_ADMIN).enabled(true).build());
        return login(username, "pwd123456");
    }

    @Test
    void A租户建的MCP服务_B租户按id取不到工具清单_按不存在处理而非403() throws Exception {
        Application appA = seedApp("MCP_ISO_A", "http://localhost");
        Application appB = seedApp("MCP_ISO_B", "http://localhost");
        String tokenA = createAdminAndLogin(appA, "mcp_iso_a_admin");
        String tokenB = createAdminAndLogin(appB, "mcp_iso_b_admin");

        String bodyA = "{\"name\":\"订单系统\",\"code\":\"order\",\"url\":\"https://mcp.example.com/mcp\","
                + "\"transport\":\"STREAMABLE_HTTP\",\"authHeader\":\"Authorization: Bearer secret\"}";

        MvcResult created = mockMvc.perform(post("/v1/mcp/servers")
                        .header("satoken", tokenA)
                        .header("X-App-Id", appA.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        long serverId = om.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asLong();
        assertThat(serverId).as("必须真的建出了一行，后续断言才有意义").isPositive();

        // 正向对照：A 自己能拿到该 server 的工具清单（证明种子与请求路径本身是对的；
        // 尚未同步远端工具，清单为空数组，走的是 200 而不是 404）
        mockMvc.perform(get("/v1/mcp/servers/" + serverId + "/tools")
                        .header("satoken", tokenA)
                        .header("X-App-Id", appA.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());

        // B 租户拿着同一个 id 取——必须是「不存在」而不是 403，避免枚举探测。
        // ResourceNotFoundException 经 GlobalExceptionHandler 写真实 HTTP 404
        // （不同于 BusinessException 的「HTTP 200 + body.code」约定），同目录既有 IT
        // （ChatAttachmentIT/ChatFeedbackIT/OpenInboxOwnershipIT）一律断言 status().isNotFound()。
        mockMvc.perform(get("/v1/mcp/servers/" + serverId + "/tools")
                        .header("satoken", tokenB)
                        .header("X-App-Id", appB.getId()))
                .andExpect(status().isNotFound());
    }

    @Test
    void B租户批量标注只读时_A租户的工具不受影响_更新条数为0() throws Exception {
        Application appA = seedApp("MCP_ISO_RO_A", "http://localhost");
        Application appB = seedApp("MCP_ISO_RO_B", "http://localhost");
        String tokenA = createAdminAndLogin(appA, "mcp_iso_ro_a_admin");
        String tokenB = createAdminAndLogin(appB, "mcp_iso_ro_b_admin");

        // 直接种一个属于 A 租户的 server + tool（跳过同步远端这一步，聚焦隔离本身）
        McpServer serverA = McpServer.builder()
                .name("A的订单系统").code("order")
                .url("https://mcp.example.com/mcp")
                .transport(McpTransport.STREAMABLE_HTTP)
                .build();
        serverA.setTenantId(appA.getTenant().getId());
        serverA.setAppId(appA.getId());
        McpServer savedServerA = serverRepository.saveAndFlush(serverA);

        McpTool toolA = McpTool.builder()
                .mcpServerId(savedServerA.getId())
                .name("create_order")
                .readOnly(false)
                .build();
        toolA.setTenantId(appA.getTenant().getId());
        toolA.setAppId(appA.getId());
        McpTool savedToolA = toolRepository.saveAndFlush(toolA);

        // B 租户尝试把 A 的工具标注为只读
        mockMvc.perform(put("/v1/mcp/tools/read-only")
                        .header("satoken", tokenB)
                        .header("X-App-Id", appB.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toolIds\":[" + savedToolA.getId() + "],\"readOnly\":true}"))
                .andExpect(status().isOk())
                // markReadOnly 逐 id 过 TenantOwnershipGuard，越权那条被跳过，实际更新 0 条
                .andExpect(jsonPath("$.data").value(0));

        McpTool afterCrossTenantAttempt = toolRepository.findById(savedToolA.getId()).orElseThrow();
        assertThat(afterCrossTenantAttempt.getReadOnly())
                .as("B 的越权标注不该对 A 的工具生效").isFalse();

        // 正向对照：A 标注自己的工具，必须真的生效——证明上面的「0」是守卫拦下的，
        // 不是 markReadOnly 这条链路本身就不工作
        mockMvc.perform(put("/v1/mcp/tools/read-only")
                        .header("satoken", tokenA)
                        .header("X-App-Id", appA.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toolIds\":[" + savedToolA.getId() + "],\"readOnly\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(1));

        McpTool afterOwnAttempt = toolRepository.findById(savedToolA.getId()).orElseThrow();
        assertThat(afterOwnAttempt.getReadOnly()).as("A 标注自己的工具必须真的生效").isTrue();
    }
}
