package com.qingyu.care.it;

import com.qingyu.care.entity.SysApiUsage;
import com.qingyu.care.entity.Tenant;
import com.qingyu.care.entity.User;
import com.qingyu.care.enums.OwnerScope;
import com.qingyu.care.enums.TenantPlan;
import com.qingyu.care.enums.TenantStatus;
import com.qingyu.care.enums.UserRole;
import com.qingyu.care.repository.SysApiUsageRepository;
import com.qingyu.care.repository.UserRepository;
import com.qingyu.care.service.ConfigService;
import com.qingyu.care.support.AbstractPostgresIT;
import cn.dev33.satoken.secure.BCrypt;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 租户查自身额度：plan/used/limit 正确。
 *
 * @author ruan
 */
class BillingQuotaViewIT extends AbstractPostgresIT {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired SysApiUsageRepository usageRepository;
    @Autowired ConfigService configService;
    private final ObjectMapper om = new ObjectMapper();

    private String login(String u, String p) throws Exception {
        MvcResult r = mockMvc.perform(post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + u + "\",\"password\":\"" + p + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return om.readTree(r.getResponse().getContentAsString()).path("data").path("token").asString();
    }

    @Test
    void 租户查额度返回plan与已用() throws Exception {
        Tenant t = tenantRepository.save(Tenant.builder().name("额度视图IT").code("QUOTA_VIEW")
                .status(TenantStatus.ACTIVE).plan(TenantPlan.FREE).build());
        User admin = userRepository.save(User.builder()
                .username("quota_view_admin").password(BCrypt.hashpw("pw123456"))
                .nickname("v").role(UserRole.TENANT_ADMIN).tenant(t).enabled(true).build());
        configService.set(OwnerScope.PLATFORM, null, "billing.quota.FREE.calls", "1000", "IT");
        usageRepository.save(SysApiUsage.builder().tenantId(t.getId()).appId(90090L)
                .usageDate(LocalDate.now()).callCount(12).messageCount(12).tokenCount(340).build());

        try {
            String token = login("quota_view_admin", "pw123456");
            mockMvc.perform(get("/v1/billing/quota").header("satoken", token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.plan").value("FREE"))
                    .andExpect(jsonPath("$.data.callsUsed").value(12))
                    .andExpect(jsonPath("$.data.callsLimit").value(1000));
        } finally {
            usageRepository.findAll().stream().filter(u -> t.getId().equals(u.getTenantId()))
                    .forEach(usageRepository::delete);
            userRepository.delete(admin);
            tenantRepository.delete(t);
            configService.set(OwnerScope.PLATFORM, null, "billing.quota.FREE.calls", "", null);
        }
    }
}
