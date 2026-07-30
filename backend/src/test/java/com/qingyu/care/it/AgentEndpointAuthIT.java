package com.qingyu.care.it;

import cn.dev33.satoken.secure.BCrypt;
import tools.jackson.databind.ObjectMapper;
import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.entity.Tenant;
import com.qingyu.care.entity.User;
import com.qingyu.care.enums.SessionStatus;
import com.qingyu.care.enums.TenantPlan;
import com.qingyu.care.enums.TenantStatus;
import com.qingyu.care.enums.UserRole;
import com.qingyu.care.repository.ChatMessageRepository;
import com.qingyu.care.repository.ChatSessionRepository;
import com.qingyu.care.repository.UserRepository;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 坐席端点角色门禁：未登录 401（Sa-Token 拦截，未过滤器链即挡下）；非坐席角色（平台管理员）
 * requireAgent 抛 BusinessException(403,...)。
 * 注：本仓库 GlobalExceptionHandler#handleBusinessException 未标 @ResponseStatus，
 * 故 BusinessException 走的是 HTTP 200 + 响应体 code=403 的"业务态 403"语义（与
 * requirePlatformAdmin/requireTenantAdmin 等既有守卫完全一致），并非真实 HTTP 403 状态码；
 * 这里按该既有约定断言，而非字面 HTTP 状态。
 * 平台管理员账号由 AuthService#init 在应用启动时兜底播种（admin/admin123），无需本类自行播种。
 *
 * 另覆盖身份收口行为（Task 4）：坐席角色登录后 accept/agentReply 均不再从请求参数取
 * agentId，而是取 StpUtil.getLoginIdAsLong()（登录态）——落库的 agentId/消息可见性均以
 * 登录用户为准。
 *
 * @author ruan
 */
class AgentEndpointAuthIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ChatSessionRepository sessionRepository;
    @Autowired
    private ChatMessageRepository messageRepository;

    private final ObjectMapper om = new ObjectMapper();

    private String login(String username, String password) throws Exception {
        MvcResult r = mockMvc.perform(post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return om.readTree(r.getResponse().getContentAsString()).path("data").path("token").asString();
    }

    @Test
    void 未登录访问坐席端点返回401() throws Exception {
        mockMvc.perform(get("/v1/chat/agents/online"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 平台管理员非坐席身份访问坐席端点返回业务态403() throws Exception {
        String token = login("admin", "admin123");

        mockMvc.perform(get("/v1/chat/agents/online").header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("无权限：该操作仅限本租户坐席"));
    }

    @Test
    void 坐席登录后accept与reply均取登录态agentId且落库可见() throws Exception {
        Tenant tenant = tenantRepository.save(Tenant.builder().name("坐席身份收口IT").code("AGENT_IDENTITY")
                .status(TenantStatus.ACTIVE).plan(TenantPlan.FREE).build());
        User agent = userRepository.save(User.builder()
                .username("agent_identity_it").password(BCrypt.hashpw("pw123456"))
                .nickname("坐席IT").role(UserRole.TENANT_MEMBER).tenant(tenant).enabled(true).build());
        String sessionId = "sess-agent-identity-it";
        ChatSession session = ChatSession.builder().sessionId(sessionId).status(SessionStatus.WAITING_AGENT).build();
        session.setTenantId(tenant.getId());
        session = sessionRepository.save(session);

        try {
            String token = login("agent_identity_it", "pw123456");

            // accept：不传 agentId（端点已删该参数），落库 agentId 须为登录用户 id
            mockMvc.perform(post("/v1/chat/session/" + sessionId + "/accept").header("satoken", token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.agentId").value(agent.getId()));

            // agentReply：请求体仅 sessionId+message（AgentReplyRequest 已删 agentId 字段）
            String body = "{\"sessionId\":\"" + sessionId + "\",\"message\":\"人工回复内容\"}";
            mockMvc.perform(post("/v1/chat/agent/reply").header("satoken", token)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            // getSessionMessages：落库的 ASSISTANT 消息可见（由登录坐席写入，非入参 agentId）
            mockMvc.perform(get("/v1/chat/session/" + sessionId + "/messages").header("satoken", token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].role").value("ASSISTANT"))
                    .andExpect(jsonPath("$.data[0].content").value("人工回复内容"));
        } finally {
            Long sid = session.getId();
            messageRepository.findBySessionIdOrderByCreatedAtAsc(sid).forEach(messageRepository::delete);
            sessionRepository.deleteById(sid);
            userRepository.delete(agent);
            tenantRepository.delete(tenant);
        }
    }
}
