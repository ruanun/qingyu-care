package com.qingyu.care.service;

import com.qingyu.care.config.PlatformSeeder;
import com.qingyu.care.config.TenantContext;
import com.qingyu.care.config.TenantOwnershipGuard;
import com.qingyu.care.entity.PromptTemplate;
import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.exception.ResourceNotFoundException;
import com.qingyu.care.repository.PromptTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class PromptTemplateService {

    private final PromptTemplateRepository repository;
    private final ConfigScopeResolver configScopeResolver;

    @Value("${qingyu.care.prompt.system-template}")
    private String defaultTemplate;

    /**
     * 初始化：如果数据库中没有模板，则创建默认模板
     */
    @PostConstruct
    @Transactional
    public void init() {
        PlatformSeeder.seedIfEmpty(repository.count(), "Prompt 模板", () -> {
            PromptTemplate template = PromptTemplate.builder()
                    .name("default")
                    .content(defaultTemplate)
                    .version(1)
                    .isActive(true)
                    .description("系统默认模板")
                    .build();
            repository.save(template);
        });
    }

    /**
     * 获取当前激活的模板
     * 显式按有效作用域收窄，避免平台管理员绕过 @Filter 时 NonUnique。
     * 1. 按 {@link ConfigScopeResolver} 解析出的有效 (tenantId, appId) 查自有激活模板
     * 2. 回退平台级默认模板（tenant_id IS NULL，原生查询绕过 Hibernate 过滤器）——闭 bug 的唯一关键
     */
    public PromptTemplate getActiveTemplate() {
        ConfigScopeResolver.Scope scope = configScopeResolver.resolve();
        return findScopedActive(scope)
                .or(repository::findPlatformDefaultActive)
                .orElseThrow(() -> new ResourceNotFoundException("没有激活的 Prompt 模板"));
    }

    /** 按有效作用域取租户自有激活模板；无有效租户则跳过、交由平台默认兜底。 */
    private java.util.Optional<PromptTemplate> findScopedActive(ConfigScopeResolver.Scope scope) {
        if (scope.tenantId() == null) {
            return java.util.Optional.empty();
        }
        return repository.findActiveScoped(scope.tenantId(), scope.appId()).stream().findFirst();
    }

    /**
     * 获取激活模板的内容
     */
    public String getActiveTemplateContent() {
        return getActiveTemplate().getContent();
    }

    /**
     * 获取所有版本（管理面）：租户自有(按 tenantId) ∪ 平台默认(tenant_id IS NULL)，按 id 去重。
     * TenantDataAspect 对 PLATFORM_ADMIN 整体不启用 @Filter，禁止裸 findAll；无 tenantId
     * （平台管理员）时自有部分为空，但平台默认模板仍须可见——保住多级回退可管理性。
     */
    public List<PromptTemplate> getAllVersions() {
        Long tenantId = TenantContext.getTenantId();
        List<PromptTemplate> own = tenantId == null ? List.of() : repository.findByTenantIdOrderByVersionDesc(tenantId);
        List<PromptTemplate> platformDefaults = repository.findAllPlatformDefaults();
        return mergeById(own, platformDefaults);
    }

    private List<PromptTemplate> mergeById(List<PromptTemplate> own, List<PromptTemplate> platformDefaults) {
        java.util.LinkedHashMap<Long, PromptTemplate> byId = new java.util.LinkedHashMap<>();
        for (PromptTemplate t : own) byId.putIfAbsent(t.getId(), t);
        for (PromptTemplate t : platformDefaults) byId.putIfAbsent(t.getId(), t);
        return new java.util.ArrayList<>(byId.values());
    }

    /**
     * 获取指定模板
     */
    public PromptTemplate getById(Long id) {
        PromptTemplate template = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Prompt 模板", id.toString()));
        // findById 不受 @Filter 保护，显式校验归属，不匹配按「不存在」同语义处理防枚举探测
        if (!TenantOwnershipGuard.belongsToCurrentContext(template.getTenantId(), template.getAppId())) {
            throw new ResourceNotFoundException("Prompt 模板", id.toString());
        }
        return template;
    }

    /**
     * 创建新版本
     */
    @Transactional
    public PromptTemplate createVersion(String name, String content, String description) {
        Integer maxVersion = repository.findMaxVersion();
        int newVersion = maxVersion + 1;

        PromptTemplate template = PromptTemplate.builder()
                .name(name)
                .content(content)
                .version(newVersion)
                .isActive(false)
                .description(description)
                .build();

        return repository.save(template);
    }

    /**
     * 激活指定版本
     */
    @Transactional
    public PromptTemplate activateVersion(Long id) {
        PromptTemplate template = getById(id);
        // 写路径守卫：平台默认模板（tenant_id IS NULL 种子行）仅平台管理员可操作
        if (!TenantOwnershipGuard.mutableInCurrentContext(template.getTenantId(), template.getAppId())) {
            throw new BusinessException(403, "平台默认模板仅平台管理员可操作");
        }

        // 先取消同范围内其他模板的激活位。范围锚定目标模板自身的租户/应用维度——
        // bulk UPDATE 不受 @Filter 保护，若不显式限定范围会清掉全库（含平台默认与其他租户）的激活位
        if (template.getTenantId() == null) {
            repository.deactivateAllPlatform();
        } else {
            repository.deactivateAllForTenant(template.getTenantId(), template.getAppId());
        }

        // 激活指定模板
        template.setIsActive(true);
        log.info("Activated prompt template version {} (id={})", template.getVersion(), id);

        return repository.save(template);
    }

    /**
     * 删除版本（不允许删除激活版本）
     */
    @Transactional
    public void deleteVersion(Long id) {
        PromptTemplate template = getById(id);
        // 写路径守卫：平台默认模板仅平台管理员可删（读放行是为保多级回退，删除必须收紧）
        if (!TenantOwnershipGuard.mutableInCurrentContext(template.getTenantId(), template.getAppId())) {
            throw new BusinessException(403, "平台默认模板仅平台管理员可删除");
        }
        if (template.getIsActive()) {
            throw new com.qingyu.care.exception.InvalidOperationException("不能删除激活中的模板");
        }
        repository.delete(template);
        log.info("Deleted prompt template version {} (id={})", template.getVersion(), id);
    }
}
