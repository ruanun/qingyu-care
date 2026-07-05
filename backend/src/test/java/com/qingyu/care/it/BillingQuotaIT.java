package com.qingyu.care.it;

import com.qingyu.care.entity.Application;
import com.qingyu.care.entity.SysApiUsage;
import com.qingyu.care.entity.Tenant;
import com.qingyu.care.enums.OwnerScope;
import com.qingyu.care.enums.TenantPlan;
import com.qingyu.care.enums.TenantStatus;
import com.qingyu.care.repository.SysApiUsageRepository;
import com.qingyu.care.service.ConfigService;
import com.qingyu.care.service.ModelRouterService;
import com.qingyu.care.support.AbstractPostgresIT;
import com.qingyu.care.support.SpringAiResponses;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 开放 API 额度拦截：FREE 达额度 403 QUOTA_EXCEEDED；未达放行；ENTERPRISE(0) 不限放行。
 * 额度检查是开放 API 专属。每个用例独立 app，避开 uk_api_usage_app_date(app_id,usage_date) 唯一约束；
 * 放行用例用 awaitility 等异步计量落库后再清理，避免脏行污染其它 IT。
 *
 * @author ruan
 */
class BillingQuotaIT extends AbstractPostgresIT {

    @Autowired MockMvc mockMvc;
    @Autowired SysApiUsageRepository usageRepository;
    @Autowired ConfigService configService;

    @MockitoBean ModelRouterService modelRouterService;

    /** 登记所有测试用 app（含 seedApp 创建的），@AfterEach 统一清理 */
    private final List<Application> createdApps = new ArrayList<>();
    /** 登记额外手动创建的 tenant（如 ENTERPRISE 用例），@AfterEach 统一清理 */
    private final List<Tenant> createdTenants = new ArrayList<>();

    @AfterEach
    void cleanup() {
        // 外键顺序：先删 app 的 usage + app，再删 tenant
        for (Application a : createdApps) {
            // 先删该 app 的 usage 行，避免外键或残留行影响后续 IT
            usageRepository.findAll().stream()
                    .filter(u -> a.getId().equals(u.getAppId()))
                    .forEach(usageRepository::delete);
            applicationRepository.delete(a);
        }
        // 统一删所有已登记租户（seedTrackedApp 与 ENTERPRISE 用例均在 seed 当下登记了真实实例）
        createdTenants.forEach(tenantRepository::delete);
        // 恢复平台 FREE 额度默认，避免污染其它跑 FREE 对话的 IT
        configService.set(OwnerScope.PLATFORM, null, "billing.quota.FREE.calls", "", null);
    }

    /** 一行当月用量（当天）；同一 app 只调一次，避开唯一约束。 */
    private void seedUsage(Application app, long calls) {
        usageRepository.save(SysApiUsage.builder()
                .tenantId(app.getTenant().getId()).appId(app.getId())
                .usageDate(LocalDate.now()).callCount(calls).messageCount(calls).tokenCount(0).build());
    }

    private void stubModel() {
        Mockito.when(modelRouterService.callWithFallback(eq("default"), any()))
                .thenReturn(SpringAiResponses.of("OK", 1));
    }

    /** 播种一个 FREE 租户 + 应用并登记，供 @AfterEach 统一清理。 */
    private Application seedTrackedApp(String suffix) {
        Application app = seedApp(suffix, "http://it.example.com"); // 租户默认 FREE
        createdApps.add(app);
        // 此刻 app 为 seedApp 刚返回的内存装配对象，getTenant() 是真实实例（非懒代理），
        // 在此登记租户引用，避免 @AfterEach（无 Session）访问懒关联触发 LazyInitializationException。
        createdTenants.add(app.getTenant());
        return app;
    }

    @Test
    void FREE未达额度_放行() throws Exception {
        Application app = seedTrackedApp("quota_free_ok");
        configService.set(OwnerScope.PLATFORM, null, "billing.quota.FREE.calls", "3", "IT");
        stubModel();
        seedUsage(app, 2); // 当月已用 2 < 3

        mockMvc.perform(post("/open/v1/chat/completions")
                        .header("X-App-Id", app.getAppKey()).header("X-Api-Key", app.getAppSecret())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"hi\"}"))
                .andExpect(status().isOk());

        // 放行后异步计量会 UPDATE 该行（+1）；等它落库再由 @AfterEach 清理，避免脏行残留
        await().atMost(5, SECONDS).untilAsserted(() ->
                assertThat(usageRepository.findAll().stream()
                        .filter(u -> app.getId().equals(u.getAppId()))
                        .mapToLong(SysApiUsage::getCallCount).sum()).isEqualTo(3));
    }

    @Test
    void FREE达额度_返回403_QUOTA_EXCEEDED() throws Exception {
        Application app = seedTrackedApp("quota_free_over");
        configService.set(OwnerScope.PLATFORM, null, "billing.quota.FREE.calls", "3", "IT");
        stubModel();
        seedUsage(app, 3); // 当月已用 3 >= 3 → preHandle 拦截、返回 false，不进 controller、不触发异步计量

        mockMvc.perform(post("/open/v1/chat/completions")
                        .header("X-App-Id", app.getAppKey()).header("X-Api-Key", app.getAppSecret())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"hi\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.reason").value("QUOTA_EXCEEDED"));
    }

    @Test
    void ENTERPRISE额度0_不限_高用量仍放行() throws Exception {
        Tenant ent = tenantRepository.save(Tenant.builder()
                .name("企业IT").code("QUOTA_ENT")
                .status(TenantStatus.ACTIVE).plan(TenantPlan.ENTERPRISE).build());
        createdTenants.add(ent);
        Application app = new Application();
        app.setName("ent"); app.setTenant(ent);
        app.setAppKey("quota_ent_key"); app.setAppSecret("quota_ent_sec");
        app.setEnabled(true); app.setAllowedOrigins("http://it.example.com");
        app = applicationRepository.save(app);
        createdApps.add(app);
        final Application entApp = app;

        stubModel();
        seedUsage(entApp, 999999); // 用量很高，但 ENTERPRISE 额度=0（不限），快路径直接放行

        mockMvc.perform(post("/open/v1/chat/completions")
                        .header("X-App-Id", entApp.getAppKey()).header("X-Api-Key", entApp.getAppSecret())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"hi\"}"))
                .andExpect(status().isOk());

        // 等异步计量落库后再由 @AfterEach（createdApps/createdTenants）清理
        await().atMost(5, SECONDS).untilAsserted(() ->
                assertThat(usageRepository.findAll().stream()
                        .anyMatch(u -> entApp.getId().equals(u.getAppId()))).isTrue());
    }
}
