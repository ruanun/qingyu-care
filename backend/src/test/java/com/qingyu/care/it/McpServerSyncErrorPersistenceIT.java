package com.qingyu.care.it;

import cn.dev33.satoken.secure.BCrypt;
import com.qingyu.care.entity.Application;
import com.qingyu.care.entity.McpServer;
import com.qingyu.care.entity.User;
import com.qingyu.care.enums.UserRole;
import com.qingyu.care.repository.McpServerRepository;
import com.qingyu.care.repository.UserRepository;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 复核终审 Finding 1：{@code McpServerService.sync()} 同步失败时必须把 {@code lastSyncError}
 * 真正落库。
 *
 * <p>⚠️ <b>单测在这里是无效的</b>——这正是本缺陷存活过 13 轮评审的根因。
 * {@code McpServerServiceSyncTest.sync_failure_leavesToolsUntouched} 用 Mockito 断言的是
 * 内存实体上的字段，没有真实事务，结构上不可能看到「方法带 {@code @Transactional} 时，
 * catch 块里的 {@code save()} 会在紧跟着的 {@code throw} 之后被默认回滚规则丢弃」这类问题
 * ——必须走真实 Postgres 事务、真实 HTTP 请求，重新从库里读出该行才能验证。
 *
 * <p>触发失败分支的手段：把 server 的 URL 指向 {@code 127.0.0.1}。{@link com.qingyu.care.config.SsrfGuard}
 * 对环回地址是同步、无网络往返的即时拒绝（{@code isLoopbackAddress()}），比真的指向一个网络层面
 * 不可达的公网地址更快更确定——两者殊途同归：都是 {@code RemoteMcpToolExecutor.listTools()}
 * 抛出一个未受检异常，被 {@code McpServerService.sync()} 的 {@code catch (Exception e)} 捕获。
 *
 * @author ruan
 */
class McpServerSyncErrorPersistenceIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private McpServerRepository serverRepository;
    @Autowired
    private UserRepository userRepository;

    private String login(String username, String password) throws Exception {
        MvcResult r = mockMvc.perform(post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return om.readTree(r.getResponse().getContentAsString()).path("data").path("token").asString();
    }

    @Test
    void 同步失败时lastSyncError真正落库_而不是被外层事务回滚丢弃() throws Exception {
        Application app = seedApp("MCP_SYNC_ERR", "http://localhost");
        userRepository.saveAndFlush(User.builder()
                .tenant(app.getTenant())
                .username("mcp_sync_err_admin").password(BCrypt.hashpw("pwd123456"))
                .role(UserRole.TENANT_ADMIN).enabled(true).build());
        String token = login("mcp_sync_err_admin", "pwd123456");

        // 指向环回地址：SsrfGuard 同步、即时拒绝，无需等待真实网络超时
        String body = "{\"name\":\"不可达服务\",\"code\":\"unreachable\","
                + "\"url\":\"http://127.0.0.1:9/mcp\",\"transport\":\"STREAMABLE_HTTP\"}";
        MvcResult created = mockMvc.perform(post("/v1/mcp/servers")
                        .header("satoken", token)
                        .header("X-App-Id", app.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        long serverId = om.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asLong();
        assertThat(serverId).as("必须真的建出了一行，后续断言才有意义").isPositive();

        // 同步前：lastSyncError 本就是 null，用它做「确实是同步这一步写入的」的对照基线
        McpServer beforeSync = serverRepository.findById(serverId).orElseThrow();
        assertThat(beforeSync.getLastSyncError()).isNull();

        // 同步：BusinessException 走「HTTP 200 + body.code=400」既定约定，不是 5xx
        mockMvc.perform(post("/v1/mcp/servers/" + serverId + "/sync")
                        .header("satoken", token)
                        .header("X-App-Id", app.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(containsString("已拒绝")));

        // 核心断言：重新从库里读出该行（不是复用上面持有的实体引用），证明写入真的落了库、
        // 没有被外层事务回滚丢弃。sync() 若仍带 @Transactional，这里会读到 null——
        // 因为 BusinessException extends RuntimeException，方法整体回滚，
        // catch 块里那次 save() 从未真正提交过。
        McpServer afterSync = serverRepository.findById(serverId).orElseThrow();
        assertThat(afterSync.getLastSyncError())
                .as("同步失败原因必须真正落库，否则前端警示条/失败图标全是死代码")
                .isNotBlank()
                .contains("已拒绝");
        assertThat(afterSync.getLastSyncedAt()).as("失败时不应更新成功同步时间").isNull();
    }
}
