package com.qingyu.care.it;

import cn.dev33.satoken.secure.BCrypt;
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
import org.junit.jupiter.api.DisplayName;
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
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 导出<b>不能吃掉平台管理员自己的凭据</b>（spec §4.1 / §6.2 / §7 的 IT ③）。
 *
 * <p>⚠️ <b>必须走真实 MockMvc</b>：本项目未配 {@code spring.jpa.open-in-view}（即 Boot 默认的
 * {@code true}），OSIV 只在真实请求里才让实体保持受管——直调 service 时实体本来就是分离的，
 * 断言会<b>静默退化成永真</b>。
 *
 * <p>⚠️ <b>光有导出请求本身还不足以触发脏检查刷库</b>：脏字段要有一次<b>非只读的写事务提交</b>
 * 才会被 flush（{@code SharedToolIT} 的类注释里记着两种不成立的直觉方案与实测过程）。导出路径
 * 本身有一次 {@code packageRepository.save(...)}，那就是那次写事务——所以本 IT 不需要
 * {@code SharedToolIT} 那样的 FlushProbe 拦截器，但仍保留一个 postHandle 探针作为纵深防御：
 * 万一将来 {@code export} 改成不落库（如「预览」端点），断言不会因此静默失去意义。
 *
 * @author ruan
 */
@Import(PackageExportIT.FlushProbeConfig.class)
class PackageExportIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AiToolRepository toolRepository;
    @Autowired
    private UserRepository userRepository;

    private final ObjectMapper om = new ObjectMapper();

    /** 在导出请求收尾前补一次对 ai_tool 的写事务，逼出脏检查 flush（同 SharedToolIT）。 */
    @TestConfiguration
    static class FlushProbeConfig implements WebMvcConfigurer {

        @Autowired
        private AiToolRepository probeRepository;

        @Bean
        HandlerInterceptor packageExportFlushProbe() {
            return new HandlerInterceptor() {
                @Override
                public void postHandle(HttpServletRequest request, HttpServletResponse response,
                                       Object handler, ModelAndView modelAndView) {
                    probeRepository.save(AiTool.builder()
                            .name("__pkg_export_probe__").description("flush probe")
                            .urlTemplate("http://flush-probe.invalid")
                            .enabled(true).build());
                }
            };
        }

        @Override
        public void addInterceptors(InterceptorRegistry registry) {
            registry.addInterceptor(packageExportFlushProbe())
                    .addPathPatterns("/v1/platform/packages/export");
        }
    }

    /**
     * 用内置的平台管理员账号登录。
     *
     * <p>⚠️ 不自己播种平台管理员：{@code User.tenant} 确实可空（null 即平台管理员），但账号是由
     * 启动时的数据初始化播种的，{@code LoginIT} 已经在用这一对 {@code admin}/{@code admin123}。
     * 自己再建一个只会多一份要维护的密码常量，且要处理 {@code username} 的唯一约束。
     */
    private String loginPlatformAdmin() throws Exception {
        MvcResult r = mockMvc.perform(post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk()).andReturn();
        return om.readTree(r.getResponse().getContentAsString()).path("data").path("token").asString();
    }

    @Test
    @DisplayName("导出之后，平台管理员那条工具的 authHeader 在库里仍然完好")
    void 导出不抹掉源工具的凭据() throws Exception {
        Application app = seedApp("PKG_EXPORT", "http://localhost");

        AiTool platformTool = AiTool.builder()
                .name("export_source_tool").description("导出源")
                .urlTemplate("https://api.example.com/x?city={city}")
                .httpMethod("GET").timeoutMs(5000).maxResponseChars(4000)
                .scope(OwnerScope.TENANT).enabled(true).build();
        platformTool.setAuthHeader("Authorization: Bearer platform-secret-keepme");
        platformTool.setTenantId(null);          // 平台管理员建的行
        platformTool.setAppId(app.getId());
        AiTool seeded = toolRepository.saveAndFlush(platformTool);

        String token = loginPlatformAdmin();

        MvcResult r = mockMvc.perform(post("/v1/platform/packages/export")
                        .header("satoken", token)
                        .header("X-App-Id", app.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"export_pack\",\"name\":\"导出测试包\","
                                + "\"toolIds\":[" + seeded.getId() + "]}"))
                .andExpect(status().isOk()).andReturn();

        var body = om.readTree(r.getResponse().getContentAsString());
        assertThat(body.path("code").asInt()).as("导出应当成功").isEqualTo(200);

        String manifest = body.path("data").path("toolPackage").path("manifest").asString();
        assertThat(manifest)
                .as("manifest 里绝不能出现明文凭据")
                .doesNotContain("platform-secret-keepme");
        assertThat(manifest).contains("EXPORT_SOURCE_TOOL_AUTH");

        // ⚠️ 核心：源实体的凭据不能被脏检查刷成 null
        AiTool reloaded = toolRepository.findById(seeded.getId()).orElseThrow();
        assertThat(reloaded.getAuthHeader())
                .as("导出全程只读实体；写一句 setAuthHeader(null) 会在 OSIV 下把平台管理员"
                        + "自己的凭据真的删掉")
                .isEqualTo("Authorization: Bearer platform-secret-keepme");
    }

    @Test
    @DisplayName("勾中属于某个租户的工具 → 整个导出被拒绝")
    void 拒绝把租户私有配置导出成平台包() throws Exception {
        Application app = seedApp("PKG_EXPORT_TENANT", "http://localhost");

        AiTool tenantTool = AiTool.builder()
                .name("tenant_private_tool").description("租户私有")
                .urlTemplate("https://api.example.com/y")
                .httpMethod("GET").timeoutMs(5000).maxResponseChars(4000)
                .scope(OwnerScope.TENANT).enabled(true).build();
        tenantTool.setTenantId(app.getTenant().getId());
        tenantTool.setAppId(app.getId());
        AiTool seeded = toolRepository.saveAndFlush(tenantTool);

        String token = loginPlatformAdmin();

        MvcResult r = mockMvc.perform(post("/v1/platform/packages/export")
                        .header("satoken", token)
                        .header("X-App-Id", app.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"leak_pack\",\"name\":\"越权导出\","
                                + "\"toolIds\":[" + seeded.getId() + "]}"))
                .andExpect(status().isOk()).andReturn();

        var body = om.readTree(r.getResponse().getContentAsString());
        assertThat(body.path("code").asInt()).isNotEqualTo(200);
        assertThat(body.path("message").asString()).contains("tenant_private_tool");
    }

    @Test
    void 非平台管理员访问平台包端点被拒() throws Exception {
        Application app = seedApp("PKG_EXPORT_DENY", "http://localhost");
        userRepository.saveAndFlush(User.builder()
                .tenant(app.getTenant())
                .username("pkg_export_tenant_admin").password(BCrypt.hashpw("pwd123456"))
                .role(UserRole.TENANT_ADMIN).enabled(true).build());
        MvcResult login = mockMvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"pkg_export_tenant_admin\",\"password\":\"pwd123456\"}"))
                .andExpect(status().isOk()).andReturn();
        String token = om.readTree(login.getResponse().getContentAsString())
                .path("data").path("token").asString();

        MvcResult r = mockMvc.perform(post("/v1/platform/packages/export")
                        .header("satoken", token).header("X-App-Id", app.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"denied_pack\",\"name\":\"越权\",\"toolIds\":[]}"))
                .andReturn();

        var body = om.readTree(r.getResponse().getContentAsString());
        assertThat(body.path("code").asInt())
                .as("租户管理员不该能操作平台包端点")
                .isEqualTo(403);
    }
}
