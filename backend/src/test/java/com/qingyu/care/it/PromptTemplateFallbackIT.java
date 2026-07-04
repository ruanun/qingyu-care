package com.qingyu.care.it;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.entity.PromptTemplate;
import com.qingyu.care.service.PromptTemplateService;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/** 某租户/应用无自有激活模板时，getActiveTemplate 应回退到平台默认(tenant_id=NULL) 而非 404。 */
class PromptTemplateFallbackIT extends AbstractPostgresIT {

    @Autowired
    private PromptTemplateService promptTemplateService;

    @AfterEach
    void clearCtx() {
        TenantContext.clear();
    }

    @Test
    void 租户无自有模板时回退平台默认() {
        // @PostConstruct 已建平台默认模板(tenant_id=NULL, is_active=true)
        TenantContext.setTenantId(90101L);   // 无自有模板的租户
        TenantContext.setAppId(90102L);

        PromptTemplate active = promptTemplateService.getActiveTemplate();

        assertThat(active).isNotNull();
        assertThat(active.getTenantId()).isNull();   // 命中平台默认
    }
}
