package com.qingyu.care.it;

import cn.dev33.satoken.secure.BCrypt;
import tools.jackson.databind.ObjectMapper;
import com.qingyu.care.entity.AiTool;
import com.qingyu.care.entity.Application;
import com.qingyu.care.entity.User;
import com.qingyu.care.enums.OwnerScope;
import com.qingyu.care.enums.UserRole;
import com.qingyu.care.repository.AiToolRepository;
import com.qingyu.care.repository.UserRepository;
import com.qingyu.care.support.AbstractPostgresIT;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 平台共享工具的两条只有真实 HTTP 请求才测得到的约束。
 *
 * <p>⚠️ 必须走 MockMvc 而不是直调 service：
 * <ul>
 *   <li>Hibernate {@code @Filter} 只在请求流经 {@code TenantInterceptor} 时才启用；
 *   <li>OSIV 只在真实请求里才让实体保持受管——直调 service 时实体本来就是分离的，
 *       脏检查那条断言会<b>静默退化成永真</b>。
 * </ul>
 *
 * <p>⚠️ <b>只有 GET /v1/ai-tools 本身不足以复现脏检查刷库，且原因比想象的更刁钻</b>：
 * masking 发生在 {@code listTools()} 最后一步，之后控制器直接返回，本请求再没有任何
 * 后续动作——OSIV 的 EntityManager 在 {@code afterCompletion} 时直接 close，脏字段
 * 无 flush 触发点便被静默丢弃。亲测过两种直觉方案都不成立：
 * <ol>
 *   <li>另起一次独立的 POST /v1/ai-tools 请求——不行，OSIV 按请求分配 EntityManager，
 *       第二次请求是全新的持久化上下文，看不到第一次请求里未提交的脏状态；
 *   <li>在同一请求的 {@code postHandle}（早于 OSIV {@code afterCompletion}，此时
 *       EntityManager 仍受管）里对 {@code ai_tool} 表补一次<b>只读</b>查询（如
 *       {@code count()}）——同样不行。开了 {@code org.hibernate.SQL=DEBUG} 实测过：
 *       Spring 的 {@code JpaTransactionManager} 对 {@code readOnly=true} 的事务
 *       （Spring Data JPA 派生查询方法的类级默认值）会把 Hibernate 的 FlushMode 设为
 *       {@code MANUAL}，其提交<b>压根不 flush</b>——不是"没有可 flush 的东西"，是
 *       "读事务这条路径整个被跳过"。
 * </ol>
 * 真正有效的是一次<b>非只读的写事务</b>提交（如 {@code save()}，Spring Data JPA 对它
 * 单独标了非 {@code readOnly} 的 {@code @Transactional}，提交时会做一次完整脏检查）。
 *
 * <p>⚠️ <b>当前代码库里这条 bug 今天并不会实际触发</b>：{@code listTools()}/
 * {@code getAllModels()} 目前仅有的调用点都是纯 GET 端点，请求内没有任何后续写事务
 * （GET 端点没打 {@code @AuditLog}，而且 {@code AuditLogAspect} 是 {@code saveAsync()}
 * 异步落库、不共享本请求的持久化上下文）。{@code detach} 防的不是今天已经存在的暴露面，
 * 而是「以后任何人往这条请求链路里加一次写操作」这类回归——本类用 {@link FlushProbeConfig}
 * 这个测试专用拦截器人为构造出那个未来场景，好让这条不变量现在就有测试压着，而不改动
 * 任何生产代码。
 *
 * @author ruan
 */
@Import(SharedToolIT.FlushProbeConfig.class)
class SharedToolIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private AiToolRepository toolRepository;
    @Autowired
    private UserRepository userRepository;

    /**
     * 测试专用探针：仅对 {@code /v1/ai-tools} 路径生效，在 {@code postHandle} 阶段
     * （早于 OSIV 的 {@code afterCompletion}，此时 EntityManager 仍受管）对
     * {@code ai_tool} 表补一次<b>写</b>操作（{@code save()}）。必须是写事务而非只读
     * 查询——见类注释，只读事务的 FlushMode 被 Spring 设为 MANUAL，提交时根本不 flush。
     */
    @TestConfiguration
    static class FlushProbeConfig implements WebMvcConfigurer {

        @Autowired
        private AiToolRepository probeRepository;

        @Bean
        HandlerInterceptor flushProbeInterceptor() {
            return new HandlerInterceptor() {
                @Override
                public void postHandle(HttpServletRequest request, HttpServletResponse response,
                                        Object handler, ModelAndView modelAndView) {
                    probeRepository.save(AiTool.builder()
                            .name("__flush_probe__").description("flush probe")
                            .urlTemplate("http://flush-probe.invalid")
                            .enabled(true).build());
                }
            };
        }

        @Override
        public void addInterceptors(InterceptorRegistry registry) {
            registry.addInterceptor(flushProbeInterceptor()).addPathPatterns("/v1/ai-tools");
        }
    }

    private String login(String username, String password) throws Exception {
        MvcResult r = mockMvc.perform(post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return om.readTree(r.getResponse().getContentAsString()).path("data").path("token").asString();
    }

    /** 建一个平台共享工具（tenant_id/app_id 均 null）。 */
    private AiTool seedSharedTool(String authHeader) {
        AiTool t = AiTool.builder()
                .name("shared_weather").description("查天气")
                .urlTemplate("http://example.com/weather?city={city}")
                .scope(OwnerScope.PLATFORM)
                .enabled(true).build();
        t.setAuthHeader(authHeader);
        t.setTenantId(null);
        t.setAppId(null);
        return toolRepository.saveAndFlush(t);
    }

    @Test
    void 租户列表能看到平台共享工具但读不到其凭据且库里凭据不被抹掉() throws Exception {
        AiTool shared = seedSharedTool("Bearer platform-secret-abc");
        Application app = seedApp("SHARED_TOOL", "http://localhost");
        userRepository.saveAndFlush(User.builder()
                .tenant(app.getTenant())
                .username("shared_tool_admin").password(BCrypt.hashpw("pwd123456"))
                .role(UserRole.TENANT_ADMIN).enabled(true).build());

        String token = login("shared_tool_admin", "pwd123456");

        MvcResult r = mockMvc.perform(get("/v1/ai-tools")
                        .header("satoken", token)
                        .header("X-App-Id", app.getId()))
                .andExpect(status().isOk()).andReturn();

        var body = om.readTree(r.getResponse().getContentAsString()).path("data");
        var sharedNode = java.util.stream.StreamSupport.stream(body.spliterator(), false)
                .filter(n -> n.path("id").asLong() == shared.getId())
                .findFirst().orElseThrow(() -> new AssertionError("共享工具未出现在租户列表里"));

        // 能看到工具本身
        assertThat(sharedNode.path("name").asString()).isEqualTo("shared_weather");
        // 但读不到凭据
        assertThat(sharedNode.path("authHeader").isNull()
                || sharedNode.path("authHeader").asString().isEmpty()).isTrue();

        // ⚠️ 核心：脱敏不能把库里的凭据也抹掉（OSIV 下实体受管，漏 detach 就会被刷回 null；
        // FlushProbeConfig 已在本请求内补了一次对 ai_tool 表的写事务以触发 flush，见类注释）
        AiTool reloaded = toolRepository.findById(shared.getId()).orElseThrow();
        assertThat(reloaded.getAuthHeader()).isEqualTo("Bearer platform-secret-abc");
    }

    @Test
    void 租户读不到其他租户的工具() throws Exception {
        Application appA = seedApp("TOOL_OWNER_A", "http://localhost");
        Application appB = seedApp("TOOL_PEEK_B", "http://localhost");

        AiTool aTool = AiTool.builder()
                .name("a_private_tool").description("A 租户私有")
                .urlTemplate("http://a.example.com/{q}")
                .scope(OwnerScope.TENANT).enabled(true).build();
        aTool.setAuthHeader("Bearer a-secret");
        aTool.setTenantId(appA.getTenant().getId());
        aTool.setAppId(appA.getId());
        AiTool savedA = toolRepository.saveAndFlush(aTool);

        userRepository.saveAndFlush(User.builder()
                .tenant(appB.getTenant())
                .username("tool_peek_b_admin").password(BCrypt.hashpw("pwd123456"))
                .role(UserRole.TENANT_ADMIN).enabled(true).build());

        String tokenB = login("tool_peek_b_admin", "pwd123456");

        MvcResult r = mockMvc.perform(get("/v1/ai-tools")
                        .header("satoken", tokenB)
                        .header("X-App-Id", appB.getId()))
                .andExpect(status().isOk()).andReturn();

        var body = om.readTree(r.getResponse().getContentAsString()).path("data");
        boolean leaked = java.util.stream.StreamSupport.stream(body.spliterator(), false)
                .anyMatch(n -> n.path("id").asLong() == savedA.getId());
        assertThat(leaked).as("B 租户不该看到 A 租户的私有工具").isFalse();
    }

    @Test
    void 库里存在scope为PLATFORM但带租户归属的畸形行时租户列表看不到它() throws Exception {
        // 模拟「租户自封平台共享」留下的畸形数据：scope=PLATFORM 但 tenant_id 非空。
        // 这类行曾可由租户在 POST 请求体里塞 scope=PLATFORM 造出（已在写入侧堵死），
        // 而共享查询的 AND tenant_id IS NULL 是让存量畸形数据失效的那一层——
        // 它一旦被「简化」掉，这条畸形工具就会作为平台共享项发给所有租户，
        // 别人绑定后其机器人会带着攻击者的 authHeader 去调攻击者的地址。
        Application attacker = seedApp("SELF_DECLARED", "http://localhost");
        AiTool selfDeclared = AiTool.builder()
                .name("self_declared_shared").description("自封共享")
                .urlTemplate("http://attacker.example.com/{q}")
                .scope(OwnerScope.PLATFORM)          // 自封
                .enabled(true).build();
        selfDeclared.setAuthHeader("Bearer attacker-secret");
        selfDeclared.setTenantId(attacker.getTenant().getId());   // 但带着自己的租户归属
        selfDeclared.setAppId(attacker.getId());
        AiTool saved = toolRepository.saveAndFlush(selfDeclared);

        // 另一个租户登录看列表
        Application victim = seedApp("SELF_DECLARED_VICTIM", "http://localhost");
        userRepository.saveAndFlush(User.builder()
                .tenant(victim.getTenant())
                .username("self_declared_victim").password(BCrypt.hashpw("pwd123456"))
                .role(UserRole.TENANT_ADMIN).enabled(true).build());
        String token = login("self_declared_victim", "pwd123456");

        MvcResult r = mockMvc.perform(get("/v1/ai-tools")
                        .header("satoken", token).header("X-App-Id", victim.getId()))
                .andExpect(status().isOk()).andReturn();

        var body = om.readTree(r.getResponse().getContentAsString()).path("data");
        boolean leaked = java.util.stream.StreamSupport.stream(body.spliterator(), false)
                .anyMatch(n -> n.path("id").asLong() == saved.getId());
        assertThat(leaked).as("自封 PLATFORM 但带租户归属的行不该出现在别的租户列表里").isFalse();
    }

    /**
     * 守 {@link AiToolRepository#findSharedByIdInAndEnabledTrue} 这条<b>运行时解析</b>查询。
     *
     * <p>⚠️ <b>比上面几条 list 侧的测试更要紧</b>：list 侧查询漏了守护，后果只是「租户在界面上
     * 多看见/少看见一条工具」；这条查询漏了守护，后果是 {@code HttpToolCallback} 真的带着
     * <b>查出来那一行的 authHeader</b> 对外发起真实 HTTP 请求——租户只要在自己的 skill 里填一个
     * 不属于自己的工具 id，就能让平台或别的租户的凭据被自己的对话链路盗用。已实证：把这条查询
     * 的 WHERE 简化成裸 {@code id IN (:ids)} 后，681 条单测 + 3 条既有 IT 全部照绿——它是这批
     * 代码里唯一一条完全没有真库测试压着的高危查询。
     *
     * <p>不需要 MockMvc：本查询是 native SQL，不依赖 Hibernate {@code @Filter}，直接
     * {@code @Autowired AiToolRepository} 调用即可复现，比走 HTTP 简单可靠。
     */
    @Test
    void 解析查询只返回真正的平台共享工具() {
        // 四行数据打四种情况——两个 WHERE 条件（scope='PLATFORM'、tenant_id IS NULL）
        // 必须各自配一行「只靠这一个条件才被挡住」的数据，否则删掉某个条件时另一个条件
        // 会顺带把泄漏挡住，变异验证测不出那个条件到底有没有在守。
        // 1. 真正的平台共享工具——scope=PLATFORM 且 tenant_id/app_id 均为 null，理应命中
        AiTool realShared = seedSharedTool("Bearer platform-secret-real");

        // 2. 别的租户的私有工具——scope=TENANT 且 tenant_id 为真实租户，两个条件都会把它挡住，
        //    留作「无论删哪个条件都不该泄漏」的基线对照
        Application otherTenant = seedApp("RESOLVE_OTHER_TENANT", "http://localhost");
        AiTool otherTenantPrivate = AiTool.builder()
                .name("other_tenant_private").description("别的租户私有工具")
                .urlTemplate("http://other.example.com/{q}")
                .scope(OwnerScope.TENANT).enabled(true).build();
        otherTenantPrivate.setAuthHeader("Bearer other-tenant-secret");
        otherTenantPrivate.setTenantId(otherTenant.getTenant().getId());
        otherTenantPrivate.setAppId(otherTenant.getId());
        AiTool savedOtherTenantPrivate = toolRepository.saveAndFlush(otherTenantPrivate);

        // 3. 「租户自封共享」留下的畸形行——scope=PLATFORM，但 tenant_id 非空。
        //    只靠 tenant_id IS NULL 这一个条件挡住；删掉它，本行立刻泄漏。
        Application selfDeclaredOwner = seedApp("RESOLVE_SELF_DECLARED", "http://localhost");
        AiTool selfDeclared = AiTool.builder()
                .name("resolve_self_declared").description("自封共享")
                .urlTemplate("http://attacker.example.com/{q}")
                .scope(OwnerScope.PLATFORM)
                .enabled(true).build();
        selfDeclared.setAuthHeader("Bearer attacker-secret");
        selfDeclared.setTenantId(selfDeclaredOwner.getTenant().getId());
        selfDeclared.setAppId(selfDeclaredOwner.getId());
        AiTool savedSelfDeclared = toolRepository.saveAndFlush(selfDeclared);

        // 4. 「TenantContext 未传递导致 tenant_id 静默落 null」这一 CLAUDE.md 反复点名的
        //    bug 类的模拟——scope 仍是 TENANT，但 tenant_id/app_id 因上下文丢失而为 null。
        //    只靠 scope='PLATFORM' 这一个条件挡住；删掉它，本行立刻泄漏。
        AiTool orphanedTenantScoped = AiTool.builder()
                .name("orphaned_tenant_scoped").description("上下文丢失后误落 null 租户的租户工具")
                .urlTemplate("http://orphan.example.com/{q}")
                .scope(OwnerScope.TENANT)
                .enabled(true).build();
        orphanedTenantScoped.setAuthHeader("Bearer orphaned-secret");
        orphanedTenantScoped.setTenantId(null);
        orphanedTenantScoped.setAppId(null);
        AiTool savedOrphaned = toolRepository.saveAndFlush(orphanedTenantScoped);

        List<AiTool> got = toolRepository.findSharedByIdInAndEnabledTrue(Set.of(
                realShared.getId(), savedOtherTenantPrivate.getId(),
                savedSelfDeclared.getId(), savedOrphaned.getId()));

        assertThat(got).extracting(AiTool::getId).containsExactly(realShared.getId());
    }
}
