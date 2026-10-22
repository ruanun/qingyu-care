package com.qingyu.care.it;

import cn.dev33.satoken.secure.BCrypt;
import com.qingyu.care.config.ModelProviderBackfillRunner;
import com.qingyu.care.entity.Application;
import com.qingyu.care.entity.Tenant;
import com.qingyu.care.entity.User;
import com.qingyu.care.enums.TenantPlan;
import com.qingyu.care.enums.TenantStatus;
import com.qingyu.care.enums.UserRole;
import com.qingyu.care.repository.AiModelConfigRepository;
import com.qingyu.care.repository.UserRepository;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 供应商连接走真实 HTTP 与真实 PG：租户级连接 + 应用级模型的可见性、跨租户隔离、改凭据同步到模型、启动回填归组。
 *
 * <p>连接实体刻意不受租户过滤器管理（应用过滤器会把没有 app_id 的行滤掉），隔离全靠服务层显式收窄——
 * 正是这类「换了一种隔离方式」最容易漏，所以必须走真实请求链验证一遍。
 *
 * @author ruan
 */
class ModelProviderIT extends AbstractPostgresIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ModelProviderBackfillRunner backfillRunner;
    @Autowired private AiModelConfigRepository modelRepository;

    private static final String SUF = Long.toString(System.nanoTime(), 36);
    private static final String ADMIN_A = "prov_a_" + SUF;
    private static final String ADMIN_B = "prov_b_" + SUF;

    private Long tenantA;
    private Long appA1;
    private Long appA2;
    private Long appB;

    @BeforeEach
    void seed() {
        if (!userRepository.existsByUsernameAcrossTenants(ADMIN_A)) {
            Tenant a = tenant("A");
            Tenant b = tenant("B");
            userRepository.save(admin(a, ADMIN_A));
            userRepository.save(admin(b, ADMIN_B));
            app(a, "a1");
            app(a, "a2");
            app(b, "b1");
        }
        appA1 = applicationRepository.findByAppKey("app_prov_a1_" + SUF).orElseThrow().getId();
        appA2 = applicationRepository.findByAppKey("app_prov_a2_" + SUF).orElseThrow().getId();
        appB = applicationRepository.findByAppKey("app_prov_b1_" + SUF).orElseThrow().getId();
        tenantA = applicationRepository.findTenantIdById(appA1).orElseThrow();
    }

    private Tenant tenant(String tag) {
        return tenantRepository.save(Tenant.builder().name("IT连接租户-" + tag).code("IT_prov_" + tag + "_" + SUF)
                .status(TenantStatus.ACTIVE).plan(TenantPlan.FREE).build());
    }

    private static User admin(Tenant t, String username) {
        return User.builder().username(username).password(BCrypt.hashpw("pwd123456")).nickname(username)
                .role(UserRole.TENANT_ADMIN).tenant(t).enabled(true).build();
    }

    private void app(Tenant t, String tag) {
        Application app = new Application();
        app.setName("IT连接应用-" + tag);
        app.setTenant(t);
        app.setAppKey("app_prov_" + tag + "_" + SUF);
        app.setAppSecret("sec");
        app.setEnabled(true);
        applicationRepository.save(app);
    }

    @Test
    void 启动播种的平台模型都已挂到平台连接() {
        Integer orphans = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_model_config WHERE tenant_id IS NULL AND provider_id IS NULL", Integer.class);
        assertThat(orphans).isZero();
        // 播种的对话模型与向量模型同一套端点与 Key：归到同一个平台连接
        Integer providers = jdbcTemplate.queryForObject("SELECT COUNT(DISTINCT provider_id) FROM ai_model_config "
                + "WHERE tenant_id IS NULL AND remark LIKE '从 application.yml 初始化%'", Integer.class);
        assertThat(providers).isEqualTo(1);
        // 模型行的副本与连接一致（厂商按端点识别，播种的对话模型不再是 openai）
        Integer mismatched = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ai_model_config m JOIN model_provider p "
                + "ON p.id = m.provider_id WHERE m.provider <> p.provider_key OR m.endpoint IS DISTINCT FROM p.endpoint "
                + "OR m.api_key IS DISTINCT FROM p.api_key", Integer.class);
        assertThat(mismatched).isZero();
    }

    @Test
    void 租户级连接_模型按应用隔离_跨租户看不到也用不了() throws Exception {
        String tokenA = login(ADMIN_A);
        String tokenB = login(ADMIN_B);

        String created = mockMvc.perform(post("/v1/model-providers").header("satoken", tokenA).header("X-App-Id", appA1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"A的OpenAI\",\"providerKey\":\"openai\",\"endpoint\":\"https://8.8.8.8/v1\",\"apiKey\":\"sk-A\"}"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.apiKey").doesNotExist())          // Key 永不下发
                .andExpect(jsonPath("$.data.hasApiKey").value(true))
                .andReturn().getResponse().getContentAsString();
        long providerId = Long.parseLong(created.replaceAll("(?s).*\"id\":(\\d+).*", "$1"));

        mockMvc.perform(post("/v1/model-providers/" + providerId + "/models").header("satoken", tokenA).header("X-App-Id", appA1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"modelId\":\"gpt-it\",\"modelType\":\"CHAT\"}]"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].ok").value(true));

        // 应用 A1 能在连接下看到模型；同租户的应用 A2 看得到连接，但模型是 A1 的
        mockMvc.perform(get("/v1/model-providers").header("satoken", tokenA).header("X-App-Id", appA1))
                .andExpect(jsonPath("$.data[?(@.provider.id == " + providerId + ")].models[*].modelId").value(hasItem("gpt-it")));
        mockMvc.perform(get("/v1/model-providers").header("satoken", tokenA).header("X-App-Id", appA2))
                .andExpect(jsonPath("$.data[*].provider.id").value(hasItem((int) providerId)))
                .andExpect(jsonPath("$.data[?(@.provider.id == " + providerId + ")].models[*].modelId").value(not(hasItem("gpt-it"))));

        // 租户 B：列表里没有 A 的连接；按 id 往里加模型、改它、拿它建单个模型，一律当不存在
        mockMvc.perform(get("/v1/model-providers").header("satoken", tokenB).header("X-App-Id", appB))
                .andExpect(jsonPath("$.data[*].provider.id").value(not(hasItem((int) providerId))));
        mockMvc.perform(post("/v1/model-providers/" + providerId + "/models").header("satoken", tokenB).header("X-App-Id", appB)
                        .contentType(MediaType.APPLICATION_JSON).content("[{\"modelId\":\"steal\",\"modelType\":\"CHAT\"}]"))
                .andExpect(jsonPath("$.code").value(404));
        mockMvc.perform(put("/v1/model-providers/" + providerId).header("satoken", tokenB).header("X-App-Id", appB)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"apiKey\":\"sk-hijack\"}"))
                .andExpect(jsonPath("$.code").value(404));
        mockMvc.perform(post("/v1/ai-models").header("satoken", tokenB).header("X-App-Id", appB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"steal\",\"modelId\":\"x\",\"providerId\":" + providerId + "}"))
                .andExpect(jsonPath("$.code").value(404));
        Integer stolen = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ai_model_config WHERE app_id = ?", Integer.class, appB);
        assertThat(stolen).isZero();

        // 租户侧拿不到平台连接
        Long platformProvider = jdbcTemplate.queryForObject(
                "SELECT id FROM model_provider WHERE tenant_id IS NULL ORDER BY id LIMIT 1", Long.class);
        mockMvc.perform(post("/v1/model-providers/" + platformProvider + "/models").header("satoken", tokenA).header("X-App-Id", appA1)
                        .contentType(MediaType.APPLICATION_JSON).content("[{\"modelId\":\"gpt-5\",\"modelType\":\"CHAT\"}]"))
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void 改连接的Key_同步到其下全部模型并刷新时间戳_有模型时不能删() throws Exception {
        String tokenA = login(ADMIN_A);
        String created = mockMvc.perform(post("/v1/model-providers").header("satoken", tokenA).header("X-App-Id", appA1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"A的DeepSeek\",\"providerKey\":\"deepseek\",\"endpoint\":\"https://8.8.4.4/v1\",\"apiKey\":\"sk-OLD\"}"))
                .andReturn().getResponse().getContentAsString();
        long providerId = Long.parseLong(created.replaceAll("(?s).*\"id\":(\\d+).*", "$1"));
        for (Long app : List.of(appA1, appA2)) {
            mockMvc.perform(post("/v1/model-providers/" + providerId + "/models").header("satoken", tokenA).header("X-App-Id", app)
                            .contentType(MediaType.APPLICATION_JSON).content("[{\"modelId\":\"deepseek-chat\",\"modelType\":\"CHAT\"}]"))
                    .andExpect(jsonPath("$.data[0].ok").value(true));
        }
        Timestamp before = jdbcTemplate.queryForObject(
                "SELECT MAX(updated_at) FROM ai_model_config WHERE provider_id = ?", Timestamp.class, providerId);
        Thread.sleep(20);

        mockMvc.perform(put("/v1/model-providers/" + providerId).header("satoken", tokenA).header("X-App-Id", appA1)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"apiKey\":\"sk-NEW\"}"))
                .andExpect(jsonPath("$.code").value(200));

        // 两个应用的模型都换成新 Key：连接是租户级，改一次全生效
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT api_key, updated_at FROM ai_model_config WHERE provider_id = ?", providerId);
        assertThat(rows).hasSize(2).allSatisfy(r -> {
            assertThat(r.get("api_key")).isEqualTo("sk-NEW");
            assertThat((Timestamp) r.get("updated_at")).isAfter(before);   // 客户端缓存按 updatedAt 失效
        });

        mockMvc.perform(delete("/v1/model-providers/" + providerId).header("satoken", tokenA).header("X-App-Id", appA1))
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void 回填_同租户同凭据跨应用合并为一个连接_不同Key分开_可重复执行() {
        insertLegacy(appA1, "openai", "https://api.openai.com/v1", "sk-K1", "legacy-1");
        insertLegacy(appA2, "openai", "https://api.openai.com/v1", "sk-K1", "legacy-2");
        insertLegacy(appA1, "openai", "https://api.openai.com/v1", "sk-K2", "legacy-3");

        backfillRunner.run(null);
        backfillRunner.run(null);   // 第二次什么都不该做

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT m.model_id, m.provider_id, p.tenant_id, p.name FROM ai_model_config m "
                        + "JOIN model_provider p ON p.id = m.provider_id WHERE m.model_id LIKE 'legacy-%' AND m.app_id IN (?, ?) "
                        + "ORDER BY m.model_id", appA1, appA2);
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).get("provider_id")).isEqualTo(rows.get(1).get("provider_id"));
        assertThat(rows.get(2).get("provider_id")).isNotEqualTo(rows.get(0).get("provider_id"));
        assertThat(rows).allSatisfy(r -> assertThat(((Number) r.get("tenant_id")).longValue()).isEqualTo(tenantA));
        Integer providers = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM model_provider WHERE tenant_id = ? AND remark = '由已有模型自动归组生成'", Integer.class, tenantA);
        assertThat(providers).isEqualTo(2);
    }

    /**
     * 模型归属与所属连接对不上时（只能由绕过应用的直接改库造成），启动审计要把它报出来。
     * 不自动修：猜错归属会把流量发往错租户的端点。
     */
    @Test
    void 启动审计能认出归属与连接不一致的模型() {
        String modelId = "drift-" + SUF;
        jdbcTemplate.update("INSERT INTO ai_model_config(name, provider, model_id, endpoint, api_key, scenario, priority, "
                        + "enabled, scope, model_type, tenant_id, app_id, created_at, updated_at) "
                        + "VALUES (?, 'openai', ?, 'https://api.openai.com/v1', 'sk-D', 'default', 100, true, "
                        + "'PLATFORM', 'CHAT', NULL, NULL, now(), now())",
                modelId, modelId);
        Long driftId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_model_config WHERE model_id = ?", Long.class, modelId);
        // 挂到一个租户连接上：模型自称 PLATFORM，连接却是租户的。
        // 连接直接建而不是复用别的用例建的——用例执行顺序不保证
        String providerName = "drift-provider-" + SUF;
        jdbcTemplate.update("INSERT INTO model_provider(tenant_id, scope, name, provider_key, endpoint, api_key, "
                        + "created_at, updated_at) VALUES (?, 'TENANT', ?, 'openai', 'https://api.openai.com/v1', "
                        + "'sk-P', now(), now())", tenantA, providerName);
        Long tenantProvider = jdbcTemplate.queryForObject(
                "SELECT id FROM model_provider WHERE name = ?", Long.class, providerName);
        jdbcTemplate.update("UPDATE ai_model_config SET provider_id = ? WHERE id = ?", tenantProvider, driftId);

        assertThat(modelRepository.findProviderScopeDrift())
                .extracting(m -> m.getId())
                .contains(driftId);

        // 写路径建出来的行一律一致，报完上面那条后库里不该再有剩下的
        jdbcTemplate.update("DELETE FROM ai_model_config WHERE id = ?", driftId);
        jdbcTemplate.update("DELETE FROM model_provider WHERE id = ?", tenantProvider);
        assertThat(modelRepository.findProviderScopeDrift()).isEmpty();
    }

    private void insertLegacy(Long appId, String provider, String endpoint, String apiKey, String modelId) {
        jdbcTemplate.update("INSERT INTO ai_model_config(name, provider, model_id, endpoint, api_key, scenario, priority, "
                        + "enabled, scope, model_type, tenant_id, app_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'default', 100, true, 'TENANT', 'CHAT', ?, ?, now(), now())",
                modelId, provider, modelId, endpoint, apiKey, tenantA, appId);
    }

    private String login(String username) throws Exception {
        String body = mockMvc.perform(post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"pwd123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();
        int at = body.indexOf("\"token\":\"") + 9;
        return body.substring(at, body.indexOf('"', at));
    }
}
